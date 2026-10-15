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
import android.webkit.CookieManager;
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
    static final String VERSION = "1.3";
    static final String HOME = "https://brauhauspos.lockandkeyhome.com/";
    static final String HOST = "brauhauspos.lockandkeyhome.com";
    private static final int REQ_SCAN = 4711;

    static final String OFFLINE =
        "<html><body style='background:#0d0f14;color:#e2e8f0;font-family:sans-serif;text-align:center;padding-top:30%'>"
        + "<h2 style='color:#e8b87a'>BRAUHAUS POS</h2><p>No connection to the server.</p>"
        + "<button style='padding:14px 28px;font-size:16px;background:#e8b87a;border:0;border-radius:8px' "
        + "onclick=\"location.href='" + HOME + "'\">Try again</button></body></html>";

    private WebView web;
    private PrinterBridge printer;
    private NfcAdapter nfc;
    private boolean nfcWanted = false;
    private boolean resumed = false;

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
        s.setUserAgentString(s.getUserAgentString() + " BrauhausPOS-iMin/" + VERSION + " BrauhausPOS/" + VERSION);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        askPermissions();

        printer = new PrinterBridge(getApplicationContext());
        web.addJavascriptInterface(printer, "IminPrintInstance");
        web.addJavascriptInterface(new ScanBridge(this), "BrauhausScan");
        nfc = NfcAdapter.getDefaultAdapter(this);

        new Thread(() -> {
            final String info = printer.bridgeInfo();
            runOnUiThread(() -> Toast.makeText(this, "Printer: " + info, Toast.LENGTH_LONG).show());
        }).start();

        web.setWebChromeClient(new WebChromeClient() {
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

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) v.loadDataWithBaseURL(null, OFFLINE, "text/html", "utf-8", null);
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
        try {
            ScanOptions o = new ScanOptions();
            o.setDesiredBarcodeFormats(ScanOptions.QR_CODE, ScanOptions.CODE_128, ScanOptions.DATA_MATRIX, ScanOptions.EAN_13);
            o.setPrompt("Scan the guest's card");
            o.setBeepEnabled(true);
            o.setOrientationLocked(false);
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
