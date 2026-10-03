package com.lockandkeyhome.brauhauspos;

import android.content.Context;
import android.graphics.Typeface;
import android.util.Log;
import android.webkit.JavascriptInterface;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * window.IminPrintInstance for the web POS.
 * Mirrors the method names printer.js already calls. Every call is queued on
 * one thread so a receipt prints in order and the screen never freezes.
 * The iMin SDK is reached by reflection: a renamed method logs a warning
 * instead of breaking the build.
 */
public class IminBridge {
    private static final String TAG = "BrauhausPrint";
    private static final String[] SDK_CLASSES = { "com.imin.printerlib.IminPrintUtils" };

    private final Context ctx;
    private final ExecutorService queue = Executors.newSingleThreadExecutor();
    private Object printer;
    private boolean initDone;
    private volatile String lastError = "";

    IminBridge(Context c) { ctx = c; }

    /* ── JS API (names match printer.js) ───────────────────── */

    @JavascriptInterface public void initPrinter(String type) {
        // printer.js tries USB then SPI. The Swift 1 is USB, so the first call wins.
        final String t = (type == null || type.isEmpty() || "undefined".equals(type)) ? "USB" : type.toUpperCase();
        queue.execute(() -> init(t));
    }
    @JavascriptInterface public void setPageFormat(int f)        { run("setPageFormat", f); }
    @JavascriptInterface public void setTextWidth(int w)         { run("setTextWidth", w); }
    @JavascriptInterface public void setTextTypeface(int t)      { run("setTextTypeface", t); }
    @JavascriptInterface public void setTextLineSpacing(float s) { run("setTextLineSpacing", s); }
    @JavascriptInterface public void setAlignment(int a)         { run("setAlignment", a); }
    @JavascriptInterface public void setTextStyle(int s)         { run("setTextStyle", s); }
    @JavascriptInterface public void setTextSize(int s)          { run("setTextSize", s); }
    @JavascriptInterface public void printText(String text)      { run("printText", text == null ? "\n" : text); }
    @JavascriptInterface public void printAndLineFeed()          { run("printAndLineFeed"); }
    @JavascriptInterface public void printAndFeedPaper(int dots) { run("printAndFeedPaper", dots); }
    @JavascriptInterface public void partialCut()                { run("partialCut"); }
    @JavascriptInterface public void fullCut()                   { run("fullCut"); }
    @JavascriptInterface public void openCashBox()               { run("openCashBox"); }

    /** Diagnostics for printer-test.html: SDK found, init state, last error. */
    @JavascriptInterface public String bridgeInfo() {
        return "app=BrauhausPOS-iMin/1.0; sdk=" + (sdk() != null) + "; init=" + initDone + "; lastError=" + lastError;
    }

    /** Printer status code (0 = ready, -1 = unknown). Synchronous, 3 s timeout. */
    @JavascriptInterface public int getStatus() {
        try {
            Future<Object> f = queue.submit(() -> { ensureInit(); return invoke("getPrinterStatus", "USB"); });
            Object r = f.get(3, TimeUnit.SECONDS);
            return r instanceof Number ? ((Number) r).intValue() : -1;
        } catch (Throwable t) { return -1; }
    }

    /* ── internals ─────────────────────────────────────────── */

    private void run(final String name, final Object... args) {
        queue.execute(() -> { ensureInit(); invoke(name, args); });
    }

    private void ensureInit() { if (!initDone) init("USB"); }

    private void init(String type) {
        if (initDone) return;
        initDone = true;
        invoke("initPrinter", type);
    }

    private Object sdk() {
        if (printer != null) return printer;
        for (String cn : SDK_CLASSES) {
            try {
                Class<?> k = Class.forName(cn);
                printer = k.getMethod("getInstance", Context.class).invoke(null, ctx);
                return printer;
            } catch (Throwable t) { lastError = "SDK missing: " + t; }
        }
        return null;
    }

    private Object invoke(String name, Object... args) {
        Object p = sdk();
        if (p == null) return null;
        for (Method m : p.getClass().getMethods()) {
            Class<?>[] types = m.getParameterTypes();
            if (!m.getName().equals(name) || types.length != args.length) continue;
            try {
                Object[] conv = new Object[args.length];
                for (int i = 0; i < args.length; i++) conv[i] = convert(types[i], args[i]);
                return m.invoke(p, conv);
            } catch (Throwable t) {
                lastError = name + ": " + t;
                Log.w(TAG, lastError, t);
            }
        }
        return null;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Object convert(Class<?> t, Object v) {
        if (t.isEnum()) return Enum.valueOf((Class<Enum>) t, String.valueOf(v));
        if (t == Typeface.class) {
            int n = toInt(v);
            return n == 1 ? Typeface.MONOSPACE : n == 2 ? Typeface.SERIF : n == 3 ? Typeface.SANS_SERIF : Typeface.DEFAULT;
        }
        if (t == int.class || t == Integer.class) return toInt(v);
        if (t == float.class || t == Float.class) return (float) toDouble(v);
        if (t == double.class || t == Double.class) return toDouble(v);
        if (t == boolean.class || t == Boolean.class) return Boolean.valueOf(String.valueOf(v));
        if (t == String.class) return String.valueOf(v);
        throw new IllegalArgumentException("unsupported type " + t.getName());
    }

    private static int toInt(Object v) { return v instanceof Number ? ((Number) v).intValue() : Integer.parseInt(String.valueOf(v)); }
    private static double toDouble(Object v) { return v instanceof Number ? ((Number) v).doubleValue() : Double.parseDouble(String.valueOf(v)); }
}
