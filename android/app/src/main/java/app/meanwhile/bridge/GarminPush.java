package app.meanwhile.bridge;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.meanwhile.bridge.core.PebbleJson;
import app.meanwhile.bridge.core.Reading;

/**
 * Pushes each new reading (with IOB) to the EverBridge watch face the moment it is stored,
 * through Garmin's Connect IQ Mobile SDK and the Garmin Connect app. The watch face
 * otherwise only asks every 5 minutes (Connect IQ's limit for a watch face), which keeps
 * running as the backup.
 *
 * Newest wins: a send, and every retry, is built from the store at that moment, so a
 * watch that was away gets the latest reading, never a backlog. "Sent" from the SDK only
 * means Garmin Connect took it; a failed status is retried, and a watch that reconnects is
 * sent the current reading again.
 */
final class GarminPush {

    /** The watch face's application id (garmin/manifest.xml). */
    static final String WATCH_FACE_ID = "36c44dc8535c48068c9ec56ca8b05fce";
    static final String GARMIN_CONNECT = "com.garmin.android.apps.connectmobile";
    static final long RETRY_MS = 20_000L;
    static final int MAX_RETRIES = 6;
    /**
     * Insulin on board decays between readings; a watch that already has the newest reading
     * is sent the new IOB by the once-a-minute check at most this often (a logged dose is
     * sent at once). Each send wakes Garmin Connect, so not for every 0.1 U.
     */
    static final long IOB_REFRESH_MS = 150_000L;
    static final String WATCHDOG = "watchdog";
    /**
     * A fresh copy of each reading follows this long after it is delivered. The Instinct E's
     * watch face gets a phone message only when the next one arrives (each push showed the
     * reading before it); the copy releases the reading, and the face then also asks the
     * phone for its newest reading. With this the watch was current within seconds.
     */
    static final long AGAIN_MS = 5_000L;
    static final String AGAIN = "again";

    /** The Connect IQ SDK, or a fake in tests. All calls and callbacks on the main thread. */
    interface Transport {
        interface Events {
            void onReady();

            void onUnavailable(String why);

            /** A watch connected (or reconnected). */
            void onDeviceConnected(String name);
        }

        interface Result {
            void done(boolean ok, String status);
        }

        void start(Context context, Events events);

        /** Watches connected right now: opaque handles. */
        List<Object> connectedDevices();

        String name(Object device);

        void send(Object device, Map<String, Object> message, Result result);

        void stop(Context context);
    }

    private static GarminPush instance;

    /** The process's pusher; started on first use. Main thread only. */
    static synchronized GarminPush get(Context context) {
        if (instance == null) {
            instance = new GarminPush(context.getApplicationContext(), new ConnectIqTransport());
            instance.start();
        }
        return instance;
    }

    /** Tests only. */
    static synchronized void setForTests(GarminPush push) {
        if (instance != null && instance != push) instance.stop();
        instance = push;
    }

    private final Context app;
    private final Transport transport;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /** Per watch: the key of the last reading it accepted. */
    private final Map<String, String> delivered = new HashMap<>();
    /** Per watch: the reading time it last accepted, and when it was sent. */
    private final Map<String, Long> deliveredTs = new HashMap<>();
    private final Map<String, Long> lastSentAt = new HashMap<>();
    /** The phone's clock; tests set their own. */
    java.util.function.LongSupplier clock = System::currentTimeMillis;
    private boolean ready;
    private String state = "not started";
    private int retries;
    private long lastSendAt;
    private String lastStatus = "-";
    private int sent;
    private int failed;
    /** Garmin Connect reported no watch connected at the last push; logged once per drop. */
    private boolean noWatch;
    private long noWatchSince;

    GarminPush(Context app, Transport transport) {
        this.app = app;
        this.transport = transport;
    }

    void start() {
        if (!garminConnectInstalled()) {
            state = "Garmin Connect is not installed";
            EventLog.log(app, "GARMIN", state + "; the watch face will only ask every 5 minutes");
            return;
        }
        state = "starting";
        try {
            transport.start(app, new Transport.Events() {
                @Override
                public void onReady() {
                    ready = true;
                    state = "ready";
                    EventLog.log(app, "GARMIN", "Connect IQ ready");
                    push("ready");
                }

                @Override
                public void onUnavailable(String why) {
                    ready = false;
                    state = "unavailable: " + why;
                    EventLog.log(app, "GARMIN", "Connect IQ unavailable: " + why);
                }

                @Override
                public void onDeviceConnected(String name) {
                    delivered.remove(name);
                    deliveredTs.remove(name);
                    push(name + " connected");
                }
            });
        } catch (RuntimeException e) {
            state = "failed to start: " + e;
            EventLog.log(app, "GARMIN", state);
        }
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);
        try {
            transport.stop(app);
        } catch (RuntimeException ignored) {
            // already stopped
        }
        ready = false;
    }

    private boolean garminConnectInstalled() {
        try {
            app.getPackageManager().getPackageInfo(GARMIN_CONNECT, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Sends the newest reading to every connected watch that doesn't have it yet. Safe to
     * call often: a watch that already accepted this reading and IOB isn't sent it again.
     */
    void push(String why) {
        push(why, false);
    }

    /** force: send even to a watch that already has this reading (the follow-up copy). */
    private void push(String why, boolean force) {
        if (!ready) return;
        final Bridge bridge = Bridge.get(app);
        final List<Reading> latest = bridge.store.latest(2);
        final long now = clock.getAsLong();
        final Double iob = bridge.iob(now);
        final Map<String, Object> message = PebbleJson.message(latest, bridge.unitsHint(), now, iob);
        if (message == null) return;
        // IOB to 0.1 U, as the watch shows it: a change that small isn't worth a send
        final String key = latest.get(0).timestamp + "|" + (iob == null ? "-" : Math.round(iob * 10));
        final List<Object> devices;
        try {
            devices = transport.connectedDevices();
        } catch (RuntimeException e) {
            state = "can't list watches: " + e;
            return;
        }
        if (devices.isEmpty()) {
            if (!noWatch) {
                noWatch = true;
                noWatchSince = now;
                EventLog.log(app, "GARMIN", "no watch connected to Garmin Connect (" + why + "); "
                        + summary(message) + " waits until it reconnects");
            }
            return;
        }
        if (noWatch) {
            noWatch = false;
            EventLog.log(app, "GARMIN", "watch connected again after " + (now - noWatchSince) / 1000 + " s");
        }
        for (Object device : devices) {
            final String name = transport.name(device);
            if (!force && key.equals(delivered.get(name))) continue;
            // the same reading, only IOB moved on: not more often than IOB_REFRESH_MS
            final Long hasTs = deliveredTs.get(name);
            final Long sentAt = lastSentAt.get(name);
            if (WATCHDOG.equals(why) && hasTs != null && hasTs == latest.get(0).timestamp
                    && sentAt != null && now - sentAt < IOB_REFRESH_MS) continue;
            send(device, name, message, key, latest.get(0).timestamp, why);
        }
    }

    private void send(Object device, String name, Map<String, Object> message, String key, long readingTs, String why) {
        lastSendAt = clock.getAsLong();
        try {
            transport.send(device, message, (ok, status) -> {
                lastStatus = status;
                if (ok) {
                    sent++;
                    retries = 0;
                    delivered.put(name, key);
                    deliveredTs.put(name, readingTs);
                    lastSentAt.put(name, clock.getAsLong());
                    EventLog.log(app, "GARMIN", "sent to " + name + " (" + why + "): " + summary(message));
                    if (!AGAIN.equals(why)) {
                        handler.removeCallbacks(again);
                        handler.postDelayed(again, AGAIN_MS);
                    }
                } else {
                    failed++;
                    EventLog.log(app, "GARMIN", "send to " + name + " failed: " + status);
                    retryLater();
                }
            });
        } catch (RuntimeException e) {
            failed++;
            lastStatus = e.getClass().getSimpleName();
            EventLog.log(app, "GARMIN", "send to " + name + " failed: " + e);
            retryLater();
        }
    }

    private final Runnable again = () -> push(AGAIN, true);

    private void retryLater() {
        if (retries >= MAX_RETRIES) return; // the next reading, reconnect or watchdog tries again
        retries++;
        handler.postDelayed(() -> push("retry " + retries), RETRY_MS);
    }

    /** Called once a minute by the keep-alive service: re-send anything not delivered. */
    void tick() {
        retries = 0;
        push(WATCHDOG);
    }

    @SuppressWarnings("unchecked")
    private static String summary(Map<String, Object> message) {
        final Map<String, Object> bg = ((List<Map<String, Object>>) message.get("bgs")).get(0);
        return bg.get("sgv") + " delta " + bg.get("bgdelta") + " iob " + bg.get("iob");
    }

    /** For the status screen and diagnostics. */
    String status() {
        return state + (noWatch ? " (no watch connected since " + DiagnosticsReport.full(noWatchSince) + ")" : "")
                + "; sent " + sent + ", failed " + failed + ", last status " + lastStatus
                + (lastSendAt > 0 ? " at " + DiagnosticsReport.full(lastSendAt) : "");
    }
}
