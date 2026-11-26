package app.meanwhile.bridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.provider.Settings.Secure;
import android.text.TextUtils;

import java.util.List;

import app.meanwhile.bridge.core.BridgeRoutes;
import app.meanwhile.bridge.core.CaptureEngine;
import app.meanwhile.bridge.core.CaptureLog;
import app.meanwhile.bridge.core.HttpServer;
import app.meanwhile.bridge.core.InsulinOnBoard;
import app.meanwhile.bridge.core.Json;
import app.meanwhile.bridge.core.NotificationParser;
import app.meanwhile.bridge.core.Reading;

/** Process-wide wiring: storage, capture engine and the local HTTP endpoint. */
public final class Bridge {

    static final String TAG = "EversenseBridge";

    private static volatile Bridge instance;

    public static Bridge get(Context context) {
        Bridge b = instance;
        if (b == null) {
            synchronized (Bridge.class) {
                b = instance;
                if (b == null) {
                    b = new Bridge(context.getApplicationContext());
                    instance = b;
                }
            }
        }
        return b;
    }

    /** Tests only: drop the singleton, its socket and its database handle. */
    static void resetForTests() {
        Alerts.resetForTests();
        AlarmOverlay.resetForTests();
        synchronized (Bridge.class) {
            if (instance != null) {
                instance.server.stop();
                instance.store.close();
                instance = null;
            }
        }
    }

    final Context app;
    final Settings settings;
    final SqliteReadingStore store;
    final CaptureEngine engine;
    final BridgeRoutes routes;
    final HttpServer server;

    private volatile boolean listenerConnected;
    private volatile long listenerChangedAt;
    private volatile long lastMonitoredPostAt;
    private String appliedServerConfig;
    /** Doses and curve as Meanwhile's page last reported them; null if it never has. */
    private volatile InsulinOnBoard insulin;

    private Bridge(Context app) {
        this.app = app;
        this.settings = new Settings(app);
        this.store = new SqliteReadingStore(app);
        this.engine = new CaptureEngine(store, new PrefsKeyValueStore(app), new CaptureLog(200));
        this.routes = new BridgeRoutes(store, new BridgeRoutes.Status() {
            @Override
            public String statusJson() {
                return Bridge.this.statusJson();
            }

            @Override
            public String unitsHint() {
                return Bridge.this.unitsHint();
            }

            @Override
            public Double iob(long nowMs) {
                return Bridge.this.iob(nowMs);
            }

            @Override
            public void pebbleServed(Reading newest, long nowMs) {
                EventLog.log(app, "GARMIN", "watch asked for the newest reading: "
                        + (newest == null ? "none yet" : newest.mgdl + ", taken " + (nowMs - newest.timestamp) / 1000 + " s ago"));
            }
        });
        this.server = new HttpServer(routes);
        engine.setListener(reading -> {
            // A repeat of the last value waits a moment before it is announced: at a new
            // reading the Eversense app's first posts can still show the old value (up to 4
            // posts, the new value under a second after them), and the new value then
            // replaces the repeat. Announced at once, the watch showed the old value at "0m"
            // for a moment. A changed value goes out straight away.
            if (isRepeat(reading)) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    final List<Reading> newest = store.latest(1);
                    if (!newest.isEmpty() && newest.get(0).timestamp == reading.timestamp) onNewReading(reading);
                }, REPEAT_HOLD_MS);
            } else {
                onNewReading(reading);
            }
        });
        insulin = loadInsulin();
        applySettings();
    }

    String unitsHint() {
        return settings.units() == NotificationParser.Units.MMOL ? "mmol" : "mgdl";
    }

    // ------------------------------------------------------------------ insulin on board

    static final String INSULIN_PREFS = "insulin";
    private static final String KEY_INSULIN_REPORT = "report";
    private static final String KEY_INSULIN_AT = "reported_at";

    /** IOB now (units), as Meanwhile's IOB tile would show it; null if never reported. */
    Double iob(long nowMs) {
        final InsulinOnBoard i = insulin;
        return i == null ? null : i.total(nowMs);
    }

    InsulinOnBoard insulin() {
        return insulin;
    }

    /**
     * Meanwhile's page reports its recent doses and insulin curve whenever they change, so
     * IOB keeps decaying for the watch when the page is closed. Returns null when stored,
     * else what was wrong.
     */
    String setInsulin(String json) {
        final long now = System.currentTimeMillis();
        final InsulinOnBoard parsed;
        try {
            parsed = InsulinOnBoard.parse(json, now);
        } catch (RuntimeException e) {
            return "bad insulin report: " + e.getMessage();
        }
        final InsulinOnBoard before = insulin;
        insulin = parsed;
        app.getSharedPreferences(INSULIN_PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_INSULIN_REPORT, parsed.toJson()).putLong(KEY_INSULIN_AT, now).apply();
        if (before == null || !before.toJson().equals(parsed.toJson())) {
            EventLog.log(app, "IOB", parsed.doses.size() + " doses reported; IOB now " + parsed.total(now) + " U");
            pushToWatch("insulin changed");
        }
        return null;
    }

    private InsulinOnBoard loadInsulin() {
        final SharedPreferences p = app.getSharedPreferences(INSULIN_PREFS, Context.MODE_PRIVATE);
        final String json = p.getString(KEY_INSULIN_REPORT, null);
        if (json == null) return null;
        try {
            return InsulinOnBoard.parse(json, p.getLong(KEY_INSULIN_AT, 0));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Sends the newest reading to the Garmin watch face, if Garmin Connect is there. */
    /** How long a repeat of the last value waits before it is announced (see the listener). */
    static final long REPEAT_HOLD_MS = 2_000L;

    private boolean isRepeat(Reading reading) {
        final List<Reading> last = store.latest(2);
        return last.size() == 2 && last.get(0).timestamp == reading.timestamp && last.get(1).mgdl == reading.mgdl;
    }

    private void onNewReading(Reading reading) {
        BridgeService.refresh(app);
        Alerts.check(app, "reading " + reading.mgdl);
        pushToWatch("new reading");
    }

    void pushToWatch(String why) {
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        main.post(() -> {
            try {
                GarminPush.get(app).push(why);
            } catch (RuntimeException e) {
                EventLog.log(app, "GARMIN", "push failed: " + e);
            }
        });
    }

    void applySettings() {
        engine.setPackages(settings.packages());
        engine.setUnits(settings.units());
    }

    /** Starts the endpoint, or restarts it if it died or its settings changed. Idempotent. */
    synchronized boolean ensureServer() {
        final String config = settings.port() + "|" + settings.lan() + "|" + settings.apiSecret();
        if (server.isRunning() && config.equals(appliedServerConfig)) return true;
        final boolean ok = server.start(settings.port(), settings.lan(), settings.apiSecret());
        appliedServerConfig = ok ? config : null;
        EventLog.log(app, "SERVER", ok ? "listening on " + (settings.lan() ? "all interfaces" : "127.0.0.1")
                + ":" + server.port() : "FAILED: " + server.lastError());
        return ok;
    }

    void setListenerConnected(boolean connected) {
        listenerConnected = connected;
        listenerChangedAt = System.currentTimeMillis();
    }

    boolean isListenerConnected() {
        return listenerConnected;
    }

    long listenerChangedAt() {
        return listenerChangedAt;
    }

    void noteMonitoredPost() {
        lastMonitoredPostAt = System.currentTimeMillis();
    }

    long lastMonitoredPostAt() {
        return lastMonitoredPostAt;
    }

    Reading latest() {
        final List<Reading> r = store.latest(1);
        return r.isEmpty() ? null : r.get(0);
    }

    boolean hasNotificationAccess() {
        final String flat = Secure.getString(app.getContentResolver(), "enabled_notification_listeners");
        if (TextUtils.isEmpty(flat)) return false;
        final ComponentName me = new ComponentName(app, EversenseListenerService.class);
        for (String name : flat.split(":")) {
            if (me.equals(ComponentName.unflattenFromString(name))) return true;
        }
        return false;
    }

    boolean isIgnoringBatteryOptimizations() {
        final PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(app.getPackageName());
    }

    /** First configured Eversense package that is installed, or null. */
    String installedEversensePackage() {
        final PackageManager pm = app.getPackageManager();
        for (String pkg : engine.getPackages()) {
            try {
                pm.getPackageInfo(pkg, 0);
                return pkg;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return null;
    }

    String versionName() {
        try {
            final PackageInfo pi = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
            return pi.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    long staleThresholdMs() {
        return settings.staleMinutes() * 60_000L;
    }

    String statusJson() {
        final long now = System.currentTimeMillis();
        final Reading last = latest();
        final CaptureEngine.Result lastCapture = engine.log().last();
        final boolean fresh = last != null && now - last.timestamp < staleThresholdMs();
        final Json j = new Json().beginObject()
                .field("ok", fresh && listenerConnected)
                .field("app", "everbridge")
                .field("version", versionName())
                .field("now", now)
                .field("listener_connected", listenerConnected)
                .field("notification_access", hasNotificationAccess())
                .field("battery_unrestricted", isIgnoringBatteryOptimizations())
                .field("eversense_installed", installedEversensePackage())
                .field("last_eversense_post", lastMonitoredPostAt)
                .field("iob", iob(now) == null ? Double.NaN : iob(now));
        j.name("last_reading");
        if (last == null) {
            j.value((String) null);
        } else {
            j.beginObject()
                    .field("date", last.timestamp)
                    .field("dateString", Json.isoUtc(last.timestamp))
                    .field("sgv", last.mgdl)
                    .field("ts_source", last.timestampSource)
                    .field("notification_when", last.notificationWhen)
                    .field("post_time", last.postTime)
                    .field("received_at", last.receivedAt)
                    .field("age_seconds", (now - last.timestamp) / 1000)
                    .endObject();
        }
        j.name("last_capture");
        if (lastCapture == null) {
            j.value((String) null);
        } else {
            j.beginObject()
                    .field("outcome", lastCapture.outcome.name())
                    .field("received_at", lastCapture.input.receivedAt)
                    .field("detail", lastCapture.detail)
                    .endObject();
        }
        j.name("server").beginObject()
                .field("port", server.port())
                .field("lan", server.isLan())
                .field("requests", server.requestsServed())
                .field("error", server.lastError())
                .endObject();
        j.name("packages").beginArray();
        for (String p : engine.getPackages()) j.value(p);
        j.endArray();
        return j.endObject().toString();
    }
}
