package com.lockandkeyhome.brauhauspos;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * Brauhaus POS for iMin handhelds.
 * Opens the live POS and exposes the built-in printer to it as
 * window.IminPrintInstance, so the app's existing printer.js prints
 * receipts straight to the thermal roll with no dialog.
 */
public class MainActivity extends Activity {
    static final String HOME = "https://brauhauspos.lockandkeyhome.com/";
    static final String HOST = "brauhauspos.lockandkeyhome.com";

    static final String OFFLINE =
        "<html><body style='background:#0d0f14;color:#e2e8f0;font-family:sans-serif;text-align:center;padding-top:30%'>"
        + "<h2 style='color:#e8b87a'>BRAUHAUS POS</h2><p>No connection to the server.</p>"
        + "<button style='padding:14px 28px;font-size:16px;background:#e8b87a;border:0;border-radius:8px' "
        + "onclick=\"location.href='" + HOME + "'\">Try again</button></body></html>";

    private WebView web;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(s.getUserAgentString() + " BrauhausPOS-iMin/1.0");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new IminBridge(getApplicationContext()), "IminPrintInstance");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String scheme = u.getScheme();
                boolean web = "https".equals(scheme) || "http".equals(scheme);
                if (web && HOST.equals(u.getHost())) return false;   // stay inside the POS
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;                                          // WhatsApp, tel:, maps open outside
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) v.loadDataWithBaseURL(null, OFFLINE, "text/html", "utf-8", null);
            }
        });

        if (state != null) web.restoreState(state); else web.loadUrl(HOME);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override
    protected void onPause() { super.onPause(); CookieManager.getInstance().flush(); }
}
