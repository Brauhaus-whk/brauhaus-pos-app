package com.lockandkeyhome.brauhauspos;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;
import android.webkit.JavascriptInterface;

import com.sunmi.peripheral.printer.InnerPrinterCallback;
import com.sunmi.peripheral.printer.InnerPrinterManager;
import com.sunmi.peripheral.printer.SunmiPrinterService;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Printer bridge, exposed to the web POS as window.IminPrintInstance
 * (same method names printer.js already calls).
 *
 * Every call is turned into ESC/POS bytes and sent as one job through the first
 * route that works on this device:
 *   sunmi = Sunmi print service (woyou.aidlservice.jiuiv5) via com.sunmi:printerlibrary
 *   imin  = iMin print service via PrinterHelper (iMin SDK 2.0)
 *   bt    = built-in virtual Bluetooth printer ("InnerPrinter" / "BluetoothPrinter")
 * A route can be pinned from printer-test.html (saved on the device).
 */
public class PrinterBridge {
    static final String VERSION = MainActivity.VERSION;
    private static final String TAG = "BrauhausPrint";
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String SUNMI_PKG = "woyou.aidlservice.jiuiv5";
    private static final String[] ROUTES = { "sunmi", "imin", "bt" };

    private final Context ctx;
    private final SharedPreferences prefs;
    private final ScheduledExecutorService q = Executors.newSingleThreadScheduledExecutor();
    private final ByteArrayOutputStream job = new ByteArrayOutputStream();
    private ScheduledFuture<?> pendingFlush;

    private String route;
    private volatile String lastError = "";

    private volatile Object sunmi;
    private Object imin;
    private BluetoothSocket socket;
    private OutputStream out;
    private String btName = "";

    PrinterBridge(Context c) {
        ctx = c;
        prefs = c.getSharedPreferences("printer", Context.MODE_PRIVATE);
    }

    /* -- JS API (names match printer.js) -- */

    @JavascriptInterface public void initPrinter(String type)   { q.execute(() -> { if (job.size() == 0) esc(0x1B, 0x40); }); }
    @JavascriptInterface public void setPageFormat(int f)        { }
    @JavascriptInterface public void setTextWidth(int w)         { }
    @JavascriptInterface public void setTextTypeface(int t)      { }
    @JavascriptInterface public void setTextLineSpacing(float s) { }
    @JavascriptInterface public void setAlignment(int a)         { add(0x1B, 0x61, clamp(a, 0, 2)); }
    @JavascriptInterface public void setTextStyle(int s)         { add(0x1B, 0x45, s == 1 ? 1 : 0); }
    @JavascriptInterface public void setTextSize(int s)          { add(0x1D, 0x21, s >= 36 ? 0x11 : s >= 30 ? 0x01 : 0x00); }
    @JavascriptInterface public void printAndLineFeed()          { add(0x0A); }
    @JavascriptInterface public void printAndFeedPaper(int dots) { add(0x1B, 0x4A, clamp(dots, 0, 255)); }
    @JavascriptInterface public void partialCut()                { endJob(); }
    @JavascriptInterface public void fullCut()                   { endJob(); }
    @JavascriptInterface public void openCashBox()               { q.execute(() -> { esc(0x1B, 0x70, 0x00, 0x19, 0xFA); flushNow(); }); }

    @JavascriptInterface public void printText(final String text) {
        final String t = text == null ? "\n" : text;
        q.execute(() -> { byte[] b = t.getBytes(enc()); job.write(b, 0, b.length); later(); });
    }

    @JavascriptInterface public String bridgeInfo() {
        try { return q.submit(this::describe).get(15, TimeUnit.SECONDS); }
        catch (Throwable t) { return "app=BrauhausPOS/" + VERSION + "; busy"; }
    }

    @JavascriptInterface public int getStatus() {
        try { return q.submit(() -> { pick(); return route != null ? 0 : -1; }).get(15, TimeUnit.SECONDS); }
        catch (Throwable t) { return -1; }
    }

    /** Prints one labelled slip through EVERY route, so you can see which one really prints. */
    @JavascriptInterface public String selfTest() {
        try {
            return q.submit(() -> {
                StringBuilder r = new StringBuilder();
                for (String rt : ROUTES) {
                    byte[] slip = slip(rt);
                    boolean ok = ready(rt) && send(rt, slip);
                    r.append(rt).append('=').append(ok ? "sent" : "failed: " + lastError).append('\n');
                }
                return r.toString().trim();
            }).get(60, TimeUnit.SECONDS);
        } catch (Throwable t) { return "selfTest error: " + t; }
    }

    @JavascriptInterface public String setRoute(final String r) {
        final String v = r == null ? "auto" : r.trim().toLowerCase();
        prefs.edit().putString("route", v).apply();
        q.execute(() -> { route = null; });
        return "route set to " + v;
    }

    @JavascriptInterface public String packages() { return printerPackages(); }

    /* -- job handling -- */

    private void add(final int... b) { q.execute(() -> { esc(b); later(); }); }
    private void endJob() { q.execute(() -> { esc(0x1B, 0x4A, 0x60); flushNow(); }); }
    private void esc(int... b) { for (int x : b) job.write(x & 0xFF); }

    private void later() {
        if (pendingFlush != null) pendingFlush.cancel(false);
        pendingFlush = q.schedule(this::flushNow, 600, TimeUnit.MILLISECONDS);
    }

    private void flushNow() {
        if (pendingFlush != null) { pendingFlush.cancel(false); pendingFlush = null; }
        if (job.size() == 0) return;
        byte[] data = job.toByteArray();
        job.reset();
        pick();
        if (route != null && send(route, data)) return;
        route = null;
        pick();
        if (route != null) send(route, data);
    }

    /* -- route selection -- */

    private void pick() {
        if (route != null) return;
        String pinned = prefs.getString("route", "auto");
        if (!"auto".equals(pinned)) { if (ready(pinned)) route = pinned; return; }
        for (String rt : ROUTES) if (ready(rt)) { route = rt; return; }
    }

    private boolean ready(String rt) {
        switch (rt) {
            case "sunmi": return sunmiReady();
            case "imin":  return iminReady();
            case "bt":    return out != null || btConnect();
            default: lastError = "unknown route " + rt; return false;
        }
    }

    private boolean send(String rt, byte[] data) {
        try {
            switch (rt) {
                case "sunmi": rawCall(sunmi, data); return true;
                case "imin":  rawCall(imin, data);  return true;
                case "bt":    return btSend(data);
                default: return false;
            }
        } catch (Throwable t) {
            lastError = rt + " send: " + root(t);
            Log.w(TAG, lastError, t);
            if ("sunmi".equals(rt)) sunmi = null;
            return false;
        }
    }

    /* -- Sunmi print service -- */

    private boolean sunmiReady() {
        if (sunmi != null) return true;
        if (!installed(SUNMI_PKG)) { lastError = "Sunmi print service not on this device"; return false; }
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            boolean ok = InnerPrinterManager.getInstance().bindService(ctx, new InnerPrinterCallback() {
                @Override protected void onConnected(SunmiPrinterService s) { sunmi = s; latch.countDown(); }
                @Override protected void onDisconnected() { sunmi = null; }
            });
            if (!ok) { lastError = "Sunmi service refused to connect"; return false; }
            latch.await(4, TimeUnit.SECONDS);
        } catch (Throwable t) {
            lastError = "Sunmi: " + root(t);
            return false;
        }
        if (sunmi == null) lastError = "Sunmi service did not answer";
        return sunmi != null;
    }

    /* -- iMin print service (SDK 2.0) -- */

    private boolean iminReady() {
        if (imin != null) return true;
        if (!hasIminService()) { lastError = "iMin print service not on this device"; return false; }
        try {
            Class<?> k = Class.forName("com.imin.printer.PrinterHelper");
            Object h = k.getMethod("getInstance").invoke(null);
            invokeAny(h, "initPrinterService", ctx);
            Thread.sleep(1500);
            invokeAny(h, "initPrinter", ctx.getPackageName(), null);
            imin = h;
            return true;
        } catch (Throwable t) {
            lastError = "iMin: " + root(t);
            return false;
        }
    }

    private boolean hasIminService() {
        for (String p : printerPackages().split(",")) {
            String l = p.trim().toLowerCase();
            if (l.contains("imin") && l.contains("print")) return true;
        }
        return false;
    }

    /* -- virtual Bluetooth printer -- */

    @SuppressWarnings("MissingPermission")
    private boolean btConnect() {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null) { lastError = "no Bluetooth on device"; return false; }
            if (!a.isEnabled()) { lastError = "Bluetooth is off (Settings > Bluetooth ON)"; return false; }
            BluetoothDevice pickDev = null;
            Set<BluetoothDevice> bonded = a.getBondedDevices();
            for (BluetoothDevice d : bonded) {
                String l = d.getName() == null ? "" : d.getName().toLowerCase();
                if (l.contains("innerprinter") || l.contains("bluetoothprinter")) { pickDev = d; break; }
                if (pickDev == null && l.contains("printer")) pickDev = d;
            }
            if (pickDev == null) { lastError = "no paired InnerPrinter / BluetoothPrinter"; return false; }
            btName = pickDev.getName();
            a.cancelDiscovery();
            try {
                socket = pickDev.createRfcommSocketToServiceRecord(SPP);
                socket.connect();
            } catch (Throwable first) {
                closeBt();
                socket = pickDev.createInsecureRfcommSocketToServiceRecord(SPP);
                socket.connect();
            }
            out = socket.getOutputStream();
            return true;
        } catch (SecurityException se) {
            lastError = "Bluetooth permission refused";
        } catch (Throwable t) {
            lastError = "Bluetooth: " + root(t);
        }
        closeBt();
        return false;
    }

    private boolean btSend(byte[] data) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (out == null && !btConnect()) return false;
                for (int i = 0; i < data.length; i += 2048) out.write(data, i, Math.min(2048, data.length - i));
                out.flush();
                return true;
            } catch (Throwable t) {
                lastError = "Bluetooth send: " + root(t);
                closeBt();
            }
        }
        return false;
    }

    private void closeBt() {
        try { if (socket != null) socket.close(); } catch (Throwable ignored) { }
        socket = null;
        out = null;
    }

    /* -- diagnostics -- */

    String describe() {
        pick();
        String s = "app=BrauhausPOS/" + VERSION
            + "; device=" + Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")"
            + "; pinned=" + prefs.getString("route", "auto")
            + "; route=" + (route == null ? "none" : route);
        if ("bt".equals(route)) s += "; printer=" + btName;
        if (route == null && !lastError.isEmpty()) s += "; lastError=" + lastError;
        return s;
    }

    private byte[] slip(String rt) {
        String label = "sunmi".equals(rt) ? "ROUTE: SUNMI SERVICE" : "imin".equals(rt) ? "ROUTE: IMIN SERVICE" : "ROUTE: BLUETOOTH";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int[] head = { 0x1B, 0x40, 0x1B, 0x61, 0x01, 0x1B, 0x45, 0x01, 0x1D, 0x21, 0x11 };
        for (int x : head) b.write(x);
        byte[] t1 = "BRAUHAUS\n".getBytes(enc()); b.write(t1, 0, t1.length);
        int[] mid = { 0x1D, 0x21, 0x00, 0x1B, 0x45, 0x00 };
        for (int x : mid) b.write(x);
        byte[] t2 = (label + "\nPrinter test OK\n" + Build.MANUFACTURER + " " + Build.MODEL + "\n--------------------------------\n").getBytes(enc());
        b.write(t2, 0, t2.length);
        int[] end = { 0x1B, 0x4A, 0x78 };
        for (int x : end) b.write(x);
        return b.toByteArray();
    }

    private boolean installed(String pkg) {
        try { ctx.getPackageManager().getPackageInfo(pkg, 0); return true; }
        catch (Throwable t) { return false; }
    }

    private String printerPackages() {
        StringBuilder sb = new StringBuilder();
        try {
            List<PackageInfo> all = ctx.getPackageManager().getInstalledPackages(0);
            for (PackageInfo p : all) {
                String n = p.packageName.toLowerCase();
                if (n.contains("print") || n.contains("woyou") || n.contains("imin") || n.contains("sunmi")) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(p.packageName);
                }
            }
        } catch (Throwable t) { sb.append("(cannot list: ").append(root(t)).append(')'); }
        return sb.toString();
    }

    /* -- reflection helpers -- */

    private static void rawCall(Object target, byte[] data) throws Throwable {
        if (target == null) throw new IllegalStateException("not connected");
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals("sendRAWData")) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 0 || p[0] != byte[].class) continue;
            Object[] args = new Object[p.length];
            args[0] = data;
            try { m.invoke(target, args); return; }
            catch (InvocationTargetException e) { throw e.getCause() != null ? e.getCause() : e; }
        }
        throw new NoSuchMethodException("sendRAWData");
    }

    private static Object invokeAny(Object target, String name, Object... args) throws Throwable {
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterTypes().length != args.length) continue;
            try { return m.invoke(target, args); }
            catch (IllegalArgumentException wrongTypes) { /* try next overload */ }
            catch (InvocationTargetException e) { throw e.getCause() != null ? e.getCause() : e; }
        }
        throw new NoSuchMethodException(name + "/" + args.length);
    }

    private static String root(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    private static Charset enc() {
        try { return Charset.forName("GB18030"); } catch (Throwable t) { return Charset.forName("ISO-8859-1"); }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
