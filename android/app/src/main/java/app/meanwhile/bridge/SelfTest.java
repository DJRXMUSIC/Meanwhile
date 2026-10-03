package app.meanwhile.bridge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import app.meanwhile.bridge.core.CaptureEngine;
import app.meanwhile.bridge.core.NotificationParser;
import app.meanwhile.bridge.core.Reading;

/**
 * One-tap check of every link in the chain except the Eversense app itself:
 * data endpoint answers, a test notification reaches the listener and parses,
 * storage works, keep-alive service is running. Nothing is stored.
 */
final class SelfTest {

    static final String NOTIFICATION_TAG = "bridge-selftest";
    static final int NOTIFICATION_ID = 77;
    static final String CHANNEL = "selftest";
    static final String TEST_TEXT = "123 mg/dL";
    static final long LISTENER_TIMEOUT_MS = 5_000;

    private static volatile CountDownLatch pending;
    private static volatile String listenerResult;

    private SelfTest() {
    }

    /** Runs off the main thread; {@code done} is called on the main thread with a report. */
    static void run(Context context, Consumer<String> done) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            final String report = runBlocking(app);
            EventLog.log(app, "SELFTEST", report.replace("\n", " | "));
            new Handler(Looper.getMainLooper()).post(() -> done.accept(report));
        }, "bridge-selftest").start();
    }

    static String runBlocking(Context app) {
        final Bridge bridge = Bridge.get(app);
        final StringBuilder r = new StringBuilder();
        boolean allOk = true;

        // 1. endpoint
        final long t0 = System.nanoTime();
        try {
            final HttpURLConnection c = (HttpURLConnection) new URL(
                    "http://127.0.0.1:" + bridge.settings.port() + "/status.json").openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            final int code = c.getResponseCode();
            try (InputStream in = c.getInputStream()) {
                while (in.read() != -1) {
                    // drain
                }
            }
            final long ms = (System.nanoTime() - t0) / 1_000_000;
            if (code == 200) {
                line(r, true, "Data endpoint answered in " + ms + " ms (port " + bridge.settings.port() + ")");
            } else {
                allOk = false;
                line(r, false, "Data endpoint returned HTTP " + code);
            }
        } catch (Exception e) {
            allOk = false;
            line(r, false, "Data endpoint unreachable: " + e.getMessage()
                    + (bridge.server.lastError() != null ? " (" + bridge.server.lastError() + ")" : ""));
        }

        // 2. listener round trip
        if (!bridge.hasNotificationAccess()) {
            allOk = false;
            line(r, false, "Notification test skipped: notification access is off");
        } else if (Build.VERSION.SDK_INT >= 33
                && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            allOk = false;
            line(r, false, "Notification test skipped: this app may not post notifications");
        } else {
            final String result = listenerRoundTrip(app);
            final boolean ok = result.startsWith("ok");
            allOk &= ok;
            line(r, ok, ok ? "Listener received a test notification and " + result.substring(3)
                    : "Listener test failed: " + result);
        }

        // 3. storage
        try {
            final int n = bridge.store.count();
            final Reading last = bridge.latest();
            line(r, true, "Storage OK: " + n + " readings"
                    + (last != null ? ", newest " + ((System.currentTimeMillis() - last.timestamp) / 60_000) + " min old" : ""));
        } catch (RuntimeException e) {
            allOk = false;
            line(r, false, "Storage error: " + e);
        }

        // 4. keep-alive
        final boolean svc = BridgeService.isRunning();
        allOk &= svc;
        line(r, svc, svc ? "Keep-alive service running" : "Keep-alive service NOT running");

        return (allOk ? "PASS" : "PROBLEMS FOUND") + "\n" + r;
    }

    private static String listenerRoundTrip(Context app) {
        final NotificationManager nm = app.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Self-test", NotificationManager.IMPORTANCE_LOW));
        final CountDownLatch latch = new CountDownLatch(1);
        listenerResult = null;
        pending = latch;
        final long posted = System.currentTimeMillis();
        nm.notify(NOTIFICATION_TAG, NOTIFICATION_ID, new Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_bridge)
                .setContentTitle(TEST_TEXT)
                .setContentText("Eversense Bridge self-test (removed automatically)")
                .setTimeoutAfter(LISTENER_TIMEOUT_MS * 2)
                .build());
        try {
            if (!latch.await(LISTENER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return "no callback within " + LISTENER_TIMEOUT_MS / 1000 + " s (listener not bound?)";
            }
            return listenerResult + " after " + (System.currentTimeMillis() - posted) + " ms";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } finally {
            pending = null;
            nm.cancel(NOTIFICATION_TAG, NOTIFICATION_ID);
        }
    }

    /** Called by the listener for our own test notification. */
    static void onReceived(CaptureEngine.Input in) {
        final CountDownLatch latch = pending;
        if (latch == null) return;
        final NotificationParser.Result p = NotificationParser.parse(in.viewTexts, in.extrasTexts, NotificationParser.Units.AUTO);
        listenerResult = p.ok() && p.mgdl == 123 ? "ok parsed " + p.mgdl + " mg/dL"
                : "parsed wrong value: " + (p.ok() ? p.mgdl : p.failure) + " from " + in.describeTexts();
        latch.countDown();
    }

    private static void line(StringBuilder sb, boolean ok, String text) {
        sb.append(ok ? "✓ " : "✗ ").append(text).append('\n');
    }
}
