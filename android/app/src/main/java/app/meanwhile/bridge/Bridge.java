package app.meanwhile.bridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.provider.Settings.Secure;
import android.text.TextUtils;
import android.util.Log;

import java.util.List;

import app.meanwhile.bridge.core.BridgeRoutes;
import app.meanwhile.bridge.core.CaptureEngine;
import app.meanwhile.bridge.core.CaptureLog;
import app.meanwhile.bridge.core.HttpServer;
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
    final HttpServer server;

    private volatile boolean listenerConnected;
    private volatile long listenerChangedAt;
    private volatile long lastMonitoredPostAt;
    private String appliedServerConfig;

    private Bridge(Context app) {
        this.app = app;
        this.settings = new Settings(app);
        this.store = new SqliteReadingStore(app);
        this.engine = new CaptureEngine(store, new PrefsKeyValueStore(app), new CaptureLog(200));
        this.server = new HttpServer(new BridgeRoutes(store, new BridgeRoutes.Status() {
            @Override
            public String statusJson() {
                return Bridge.this.statusJson();
            }

            @Override
            public String unitsHint() {
                return settings.units() == NotificationParser.Units.MMOL ? "mmol" : "mgdl";
            }
        }));
        engine.setListener(reading -> BridgeService.refresh(app));
        applySettings();
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
        if (!ok) Log.e(TAG, "HTTP server: " + server.lastError());
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
                .field("app", "meanwhile-eversense-bridge")
                .field("version", versionName())
                .field("now", now)
                .field("listener_connected", listenerConnected)
                .field("notification_access", hasNotificationAccess())
                .field("battery_unrestricted", isIgnoringBatteryOptimizations())
                .field("eversense_installed", installedEversensePackage())
                .field("last_eversense_post", lastMonitoredPostAt);
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
