package com.lockandkeyhome.brauhauspos;

import android.nfc.NfcAdapter;
import android.webkit.JavascriptInterface;

/**
 * window.BrauhausScan for the web POS (cashless event cards).
 *   scanQr()        opens the camera scanner; result arrives in window.__bhScan(text)
 *   hasNfc()        true when this device has NFC switched on
 *   nfcListen(on)   start/stop reading NFC tags; each tap calls window.__bhNfc(hexSerial)
 */
public class ScanBridge {
    private final MainActivity act;
    ScanBridge(MainActivity a) { act = a; }

    @JavascriptInterface public void scanQr() { act.runOnUiThread(act::startQrScan); }

    @JavascriptInterface public boolean hasNfc() {
        NfcAdapter n = NfcAdapter.getDefaultAdapter(act);
        return n != null && n.isEnabled();
    }

    @JavascriptInterface public void nfcListen(boolean on) { act.runOnUiThread(() -> act.setNfcListening(on)); }

    @JavascriptInterface public String version() { return MainActivity.VERSION; }
}
