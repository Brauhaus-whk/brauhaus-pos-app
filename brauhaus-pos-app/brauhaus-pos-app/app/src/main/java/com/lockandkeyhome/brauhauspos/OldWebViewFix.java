package com.lockandkeyhome.brauhauspos;

import android.net.Uri;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Old Android WebViews (Chrome 80 on the iMin Swift 1) cannot read
 *   a ??= b   a ||= b   a &&= b   and CSS  inset:
 * The app fetches the POS page itself, rewrites those into old-style code
 * (a ?? (a = b)) and hands the result to the WebView. So any app.html works,
 * converted or not.
 */
final class OldWebViewFix {
    private OldWebViewFix() { }

    static boolean wants(WebResourceRequest r, String host) {
        if (!r.isForMainFrame() || !"GET".equalsIgnoreCase(r.getMethod())) return false;
        Uri u = r.getUrl();
        if (!host.equals(u.getHost()) || !"https".equals(u.getScheme())) return false;
        String p = u.getPath() == null ? "" : u.getPath();
        return p.isEmpty() || p.equals("/") || p.endsWith("/index.php") || p.endsWith("/app.html");
    }

    /** Returns the rewritten page, or null to let the WebView load it normally. */
    static WebResourceResponse fetch(WebResourceRequest r) {
        HttpURLConnection c = null;
        try {
            String url = r.getUrl().toString();
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            for (Map.Entry<String, String> h : r.getRequestHeaders().entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            c.setRequestProperty("Accept-Encoding", "identity");
            CookieManager cm = CookieManager.getInstance();
            String ck = cm.getCookie(url);
            if (ck != null) c.setRequestProperty("Cookie", ck);
            int code = c.getResponseCode();
            List<String> set = c.getHeaderFields().get("Set-Cookie");
            if (set != null) { for (String s : set) cm.setCookie(url, s); cm.flush(); }
            String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase();
            if (code != 200 || !type.contains("text/html")) return null; // redirects, errors: WebView handles them
            InputStream in = c.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            String html = new String(b.toByteArray(), StandardCharsets.UTF_8);
            byte[] out = css(js(html)).getBytes(StandardCharsets.UTF_8);
            Map<String, String> hdr = new HashMap<>();
            hdr.put("Cache-Control", "no-store");
            return new WebResourceResponse("text/html", "utf-8", 200, "OK", hdr, new ByteArrayInputStream(out));
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static final Pattern LOGICAL = Pattern.compile("([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)(\\?\\?|\\|\\||&&)=(?!=)");

    static String js(String s) {
        StringBuilder out = new StringBuilder(s.length() + 256);
        int pos = 0;
        Matcher m = LOGICAL.matcher(s);
        while (m.find(pos)) {
            String lhs = m.group(1), op = m.group(2);
            int end = rhsEnd(s, m.end());
            out.append(s, pos, m.start()).append(lhs).append(op).append('(').append(lhs).append('=').append(s, m.end(), end).append(')');
            pos = end;
        }
        return out.append(s.substring(pos)).toString();
    }

    private static int rhsEnd(String s, int i) {
        int depth = 0; char q = 0;
        for (int j = i; j < s.length(); j++) {
            char c = s.charAt(j);
            if (q != 0) { if (c == '\\') { j++; continue; } if (c == q) q = 0; }
            else if (c == '"' || c == '\'' || c == '`') q = c;
            else if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') { if (depth == 0) return j; depth--; }
            else if ((c == ',' || c == ';') && depth == 0) return j;
        }
        return s.length();
    }

    private static final Pattern INSET = Pattern.compile("([;{\\s])inset:\\s*([^;}!]+?)\\s*(!important)?(?=[;}])");

    static String css(String s) {
        Matcher m = INSET.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String[] v = m.group(2).trim().split("\\s+");
            String rep;
            if (v.length == 0 || v.length > 4) rep = m.group(0);
            else {
                String t = v[0], r = v.length > 1 ? v[1] : v[0], b = v.length > 2 ? v[2] : v[0], l = v.length > 3 ? v[3] : r;
                String imp = m.group(3) != null ? " !important" : "";
                rep = m.group(1) + "top:" + t + imp + ";right:" + r + imp + ";bottom:" + b + imp + ";left:" + l + imp;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
