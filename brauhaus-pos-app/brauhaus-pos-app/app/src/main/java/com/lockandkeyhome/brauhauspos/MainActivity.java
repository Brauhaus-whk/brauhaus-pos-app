package com.lockandkeyhome.brauhauspos;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Build;
import android.os.Bundle;
import android.graphics.Color;
import android.net.http.SslError;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceResponse;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.google.zxing.client.android.Intents;
import com.journeyapps.barcodescanner.ScanOptions;
import com.journeyapps.barcodescanner.CaptureActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Brauhaus POS for iMin and Sunmi handhelds and Android phones.
 * Opens the live POS and gives it:
 *   window.IminPrintInstance  the built-in receipt printer (PrinterBridge)
 *   window.BrauhausScan       QR scanner + NFC reader for cashless cards (ScanBridge)
 */
public class MainActivity extends Activity {
    static final String VERSION = "1.3.9";
    static final String HOME = "https://brauhauspos.lockandkeyhome.com/";
    static final String HOST = "brauhauspos.lockandkeyhome.com";
    /* Chrome < 85 cannot parse ??= and friends. */
    static boolean OLD_WEBVIEW = true;
    private static final int REQ_SCAN = 4711;

    /* A visible screen for every way loading can fail, so the device never
       just shows black. The message says exactly what went wrong. */
    static String screen(String title, String detail) {
        return "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
            + "<body style='background:#0d0f14;color:#e2e8f0;font-family:sans-serif;text-align:center;padding:22% 18px 0'>"
            + "<h2 style='color:#e8b87a;margin:0 0 12px'>BRAUHAUS POS</h2><p style='font-size:17px'>" + title + "</p>"
            + "<p style='font-size:13px;color:#8b9ab0;word-break:break-word'>" + detail + "</p>"
            + "<button style='margin-top:18px;padding:14px 28px;font-size:16px;background:#e8b87a;border:0;border-radius:8px' "
            + "onclick=\"location.href='" + HOME + "'\">Try again</button>"
            + "<p style='font-size:12px;color:#55606f;margin-top:28px'>App " + VERSION + " &middot; " + Build.MANUFACTURER + " " + Build.MODEL
            + " &middot; Android " + Build.VERSION.RELEASE + "</p></body></html>";
    }

    private WebView web;
    private PrinterBridge printer;
    private NfcAdapter nfc;
    private boolean nfcWanted = false;
    private boolean resumed = false;
    /* First script errors from the page, shown if it stays empty. */
    private final StringBuilder jsErrors = new StringBuilder();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0d0f14"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(s.getUserAgentString() + " BrauhausPOS-iMin/" + VERSION + " BrauhausPOS/" + VERSION);

        try {
            android.content.pm.PackageInfo wp = WebView.getCurrentWebViewPackage();
            int major = wp == null ? 0 : Integer.parseInt(wp.versionName.split("\\.")[0]);
            OLD_WEBVIEW = major > 0 && major < 90;
        } catch (Throwable ignored) { OLD_WEBVIEW = true; }
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        /* Crash reporter: if the app ever closes on its own, the reason is
           shown on the next start so it can be photographed and fixed. */
        final android.content.SharedPreferences crashPrefs = getSharedPreferences("crash", MODE_PRIVATE);
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((th, ex) -> {
            try {
                String trace = android.util.Log.getStackTraceString(ex);
                crashPrefs.edit().putString("last", "App " + VERSION + " crashed:\n" + trace.substring(0, Math.min(1800, trace.length()))).commit();
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(th, ex);
        });
        String lastCrash = crashPrefs.getString("last", null);
        if (lastCrash != null) {
            crashPrefs.edit().remove("last").apply();
            new android.app.AlertDialog.Builder(this).setTitle("The app closed last time")
                .setMessage("Please send a photo of this to support.\n\n" + lastCrash)
                .setPositiveButton("OK", null).show();
        }

        askPermissions();

        printer = new PrinterBridge(getApplicationContext());
        web.addJavascriptInterface(printer, "IminPrintInstance");
        web.addJavascriptInterface(new ScanBridge(this), "BrauhausScan");
        /* WebView ignores window.print(). This hands the page to Android's
           print system, the same one Chrome uses (iMin print plugin). */
        web.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface public void printPage(final String title) { printSized(title, 0, 0); }

            /* Paper exactly as long as the receipt, so the roll stops right after the last line. */
            @android.webkit.JavascriptInterface public void printSized(final String title, final double wMm, final double hMm) {
                runOnUiThread(() -> {
                    try {
                        android.print.PrintManager pm = (android.print.PrintManager) getSystemService(PRINT_SERVICE);
                        String name = title == null || title.isEmpty() ? "Brauhaus receipt" : title;
                        android.print.PrintAttributes.Builder b = new android.print.PrintAttributes.Builder()
                            .setColorMode(android.print.PrintAttributes.COLOR_MODE_MONOCHROME)
                            .setMinMargins(android.print.PrintAttributes.Margins.NO_MARGINS);
                        if (wMm > 20 && hMm > 20) {
                            int w = (int) Math.round(wMm / 25.4 * 1000), h = (int) Math.round(hMm / 25.4 * 1000);
                            b.setMediaSize(new android.print.PrintAttributes.MediaSize("brauhaus_receipt", "Receipt " + Math.round(wMm) + " mm", w, h).asPortrait());
                        }
                        pm.print(name, web.createPrintDocumentAdapter(name), b.build());
                    } catch (Throwable t) {
                        Toast.makeText(MainActivity.this, "Print: " + t.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "BrauhausApp");
        nfc = NfcAdapter.getDefaultAdapter(this);

        new Thread(() -> {
            final String info = printer.bridgeInfo();
            runOnUiThread(() -> Toast.makeText(this, "Printer: " + info, Toast.LENGTH_LONG).show());
        }).start();

        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage m) {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR && jsErrors.length() < 600) {
                    String src = m.sourceId() == null ? "" : m.sourceId().replaceAll(".*/", "");
                    jsErrors.append(m.message()).append(" (").append(src).append(':').append(m.lineNumber()).append(")\n");
                }
                return super.onConsoleMessage(m);
            }
            /* Lets the POS use the camera inside the app (only our own site). */
            @Override public void onPermissionRequest(final PermissionRequest r) {
                Uri o = r.getOrigin();
                if (o != null && HOST.equals(o.getHost())) runOnUiThread(() -> r.grant(r.getResources()));
                else runOnUiThread(r::deny);
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String scheme = u.getScheme();
                boolean isWeb = "https".equals(scheme) || "http".equals(scheme);
                if (isWeb && HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            /* Old WebView (iMin): serve the POS page with new JS syntax rewritten. */
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                if (OLD_WEBVIEW && OldWebViewFix.wants(r, HOST)) {
                    WebResourceResponse fixed = OldWebViewFix.fetch(r);
                    if (fixed != null) return fixed;
                }
                return super.shouldInterceptRequest(v, r);
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) show(v, "No connection to the server.", "Error " + e.getErrorCode() + ": " + e.getDescription());
            }

            @Override
            public void onReceivedHttpError(WebView v, WebResourceRequest r, WebResourceResponse resp) {
                if (r.isForMainFrame() && resp.getStatusCode() >= 500)
                    show(v, "The server answered with an error.", "HTTP " + resp.getStatusCode() + " " + resp.getReasonPhrase());
            }

            /* Most common cause of a blank screen on handhelds: the device clock is
               wrong, so the website's security certificate looks invalid. */
            @Override
            public void onReceivedSslError(WebView v, SslErrorHandler h, SslError err) {
                h.cancel();
                show(v, "Secure connection failed.", "Check the date and time on this device (Settings > Date & time > automatic). Code " + err.getPrimaryError());
            }

            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                recreate();
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
                if (url != null && url.startsWith(HOME)) jsErrors.setLength(0);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                /* Slow handhelds can need a while to draw a big page. Only call it
                   empty after 25 s, and then show the script errors and the WebView
                   version so the cause is on screen. */
                if (url == null || !url.startsWith(HOME)) return;
                v.evaluateJavascript("window.print=function(){try{var z=window.__bhPrintSize||{};BrauhausApp.printSized(document.title,z.w||0,z.h||0)}catch(e){}};", null);
                v.postDelayed(() -> v.evaluateJavascript(
                    "(function(){var b=document.body;return b&&(b.innerText.trim().length>0||b.querySelector('canvas,img,input,button'))?'ok':'blank'})()",
                    res -> {
                        if (!"\"blank\"".equals(res) || !url.equals(v.getUrl())) return;
                        String wv = "unknown";
                        try { android.content.pm.PackageInfo p = WebView.getCurrentWebViewPackage(); if (p != null) wv = p.packageName + " " + p.versionName; } catch (Throwable ignored) { }
                        String errs = jsErrors.length() > 0 ? jsErrors.toString() : "no script errors reported";
                        show(v, "The page loaded but stayed empty.", "WebView: " + wv + "<br>" + errs.replace("\n", "<br>"));
                    }), 25000);
            }
        });

        if (state != null) web.restoreState(state); else web.loadUrl(HOME);
    }

    private void askPermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), 1);
    }

    /* -- QR scanner (ZXing) -- */

    void startQrScan() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.CAMERA }, 2);
            Toast.makeText(this, "Allow the camera, then tap QR scan again", Toast.LENGTH_LONG).show();
            js("window.__bhScanCancel && window.__bhScanCancel()");
            return;
        }
        try {
            ScanOptions o = new ScanOptions();
            o.setDesiredBarcodeFormats(ScanOptions.QR_CODE, ScanOptions.CODE_128, ScanOptions.DATA_MATRIX, ScanOptions.EAN_13);
            o.setPrompt("Scan the guest's card");
            o.setBeepEnabled(true);
            o.setOrientationLocked(true);
            Intent i = o.createScanIntent(this);
            i.setClass(this, CaptureActivity.class);
            startActivityForResult(i, REQ_SCAN);
        } catch (Throwable t) {
            Toast.makeText(this, "Scanner failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int code, Intent data) {
        super.onActivityResult(req, code, data);
        if (req != REQ_SCAN) return;
        String text = (code == RESULT_OK && data != null) ? data.getStringExtra(Intents.Scan.RESULT) : null;
        if (text != null) js("window.__bhScan && window.__bhScan(" + quote(text) + ")");
        else js("window.__bhScanCancel && window.__bhScanCancel()");
    }

    /* -- NFC reader (chip serial number = card id) -- */

    void setNfcListening(boolean on) {
        nfcWanted = on;
        applyNfc();
    }

    private void applyNfc() {
        if (nfc == null) return;
        try {
            if (nfcWanted && resumed && nfc.isEnabled()) {
                int flags = NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B | NfcAdapter.FLAG_READER_NFC_F
                    | NfcAdapter.FLAG_READER_NFC_V | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK;
                nfc.enableReaderMode(this, this::onTag, flags, null);
            } else {
                nfc.disableReaderMode(this);
            }
        } catch (Throwable ignored) { }
    }

    private void onTag(Tag tag) {
        byte[] id = tag.getId();
        if (id == null || id.length == 0) return;
        StringBuilder hex = new StringBuilder();
        for (byte b : id) hex.append(String.format("%02X", b));
        runOnUiThread(() -> js("window.__bhNfc && window.__bhNfc('" + hex + "')"));
    }

    /* -- helpers -- */

    private void show(WebView v, String title, String detail) {
        v.loadDataWithBaseURL(null, screen(title, detail.replace("<br>", "\u0001").replace("<", "&lt;").replace("\u0001", "<br>")), "text/html", "utf-8", null);
    }

    private void js(String code) { if (web != null) web.evaluateJavascript(code, null); }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c < 0x20 || c == 0x2028 || c == 0x2029) b.append(String.format("\\u%04x", (int) c));
            else b.append(c);
        }
        return b.append('"').toString();
    }

    @Override protected void onResume() { super.onResume(); resumed = true; applyNfc(); }
    @Override protected void onPause() { resumed = false; applyNfc(); super.onPause(); CookieManager.getInstance().flush(); }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
