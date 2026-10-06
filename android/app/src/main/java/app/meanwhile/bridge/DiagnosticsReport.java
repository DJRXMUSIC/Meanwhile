package app.meanwhile.bridge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings.Secure;
import android.service.notification.StatusBarNotification;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.TreeSet;

import app.meanwhile.bridge.core.CaptureEngine;
import app.meanwhile.bridge.core.NotificationParser;
import app.meanwhile.bridge.core.Reading;

/**
 * Everything needed to debug the bridge remotely, as one plain-text file: device and app
 * state, health checks, settings, a raw dump of the Eversense notification currently
 * showing, readings, the capture log, the persistent event log and this app's logcat.
 */
final class DiagnosticsReport {

    static final int READINGS = 60;
    static final int LOGCAT_LINES = 3000;
    private static final int MAX_VALUE_CHARS = 400;

    private DiagnosticsReport() {
    }

    static String build(Context context) {
        final Context app = context.getApplicationContext();
        final Bridge bridge = Bridge.get(app);
        final StringBuilder sb = new StringBuilder(64 * 1024);
        final long now = System.currentTimeMillis();

        sb.append("===== Eversense Bridge diagnostics =====\n");
        kv(sb, "generated", full(now) + " (" + TimeZone.getDefault().getID() + ")");
        kv(sb, "app", appVersion(app, app.getPackageName()));
        kv(sb, "device", Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")");
        kv(sb, "android", Build.VERSION.RELEASE + " / SDK " + Build.VERSION.SDK_INT
                + " / patch " + Build.VERSION.SECURITY_PATCH);
        kv(sb, "process", "pid " + Process.myPid() + ", up "
                + (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000 + " s");

        section(sb, "Health");
        kv(sb, "notification access", bridge.hasNotificationAccess());
        kv(sb, "enabled listeners", Secure.getString(app.getContentResolver(), "enabled_notification_listeners"));
        kv(sb, "listener connected", bridge.isListenerConnected()
                + " (changed " + ago(now, bridge.listenerChangedAt()) + ")");
        kv(sb, "keep-alive service", BridgeService.isRunning());
        kv(sb, "battery unrestricted", bridge.isIgnoringBatteryOptimizations());
        final PowerManager pm = app.getSystemService(PowerManager.class);
        if (pm != null) {
            kv(sb, "power save mode", pm.isPowerSaveMode());
            kv(sb, "device idle (doze)", pm.isDeviceIdleMode());
        }
        if (Build.VERSION.SDK_INT >= 28) {
            final UsageStatsManager usm = app.getSystemService(UsageStatsManager.class);
            if (usm != null) kv(sb, "app standby bucket", standbyBucket(usm.getAppStandbyBucket()));
        }
        final NotificationManager nm = app.getSystemService(NotificationManager.class);
        kv(sb, "may post notifications", nm.areNotificationsEnabled() + (Build.VERSION.SDK_INT >= 33
                ? " (POST_NOTIFICATIONS " + (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED ? "granted" : "denied") + ")" : ""));
        kv(sb, "endpoint", bridge.server.isRunning()
                ? "running on port " + bridge.server.port() + (bridge.server.isLan() ? " (LAN)" : " (loopback)")
                + ", " + bridge.server.requestsServed() + " requests served"
                : "DOWN: " + bridge.server.lastError());
        kv(sb, "last Eversense notification", ago(now, bridge.lastMonitoredPostAt()));
        kv(sb, "when unreliable (per package)", whenUnreliable(bridge));

        section(sb, "Settings");
        final Settings s = bridge.settings;
        kv(sb, "port", s.port());
        kv(sb, "lan", s.lan());
        kv(sb, "api secret", s.apiSecret().isEmpty() ? "(none)" : "(set, " + s.apiSecret().length() + " chars)");
        kv(sb, "units", s.unitsRaw());
        kv(sb, "packages", s.packagesCsv());
        kv(sb, "stale alert", s.staleAlert() + " after " + s.staleMinutes() + " min");

        section(sb, "Eversense app");
        for (String pkg : bridge.engine.getPackages()) kv(sb, pkg, appVersion(app, pkg));

        section(sb, "Active notifications (raw)");
        appendActiveNotifications(sb, app, bridge);

        section(sb, "Readings (newest " + READINGS + ")");
        sb.append("time                     mg/dL  ts_source          when           post_time      received       raw\n");
        for (Reading r : bridge.store.latest(READINGS)) {
            sb.append(full(r.timestamp)).append("  ").append(String.format(Locale.ROOT, "%5d", r.mgdl)).append("  ")
                    .append(String.format(Locale.ROOT, "%-18s", r.timestampSource)).append(' ')
                    .append(r.notificationWhen).append("  ").append(r.postTime).append("  ").append(r.receivedAt)
                    .append("  ").append(r.rawText).append('\n');
        }
        kv(sb, "total stored", bridge.store.count());

        section(sb, "Capture log (since process start, newest first)");
        for (CaptureEngine.Result r : bridge.engine.log().recent()) {
            sb.append(full(r.input.receivedAt)).append(' ').append(r.outcome)
                    .append(r.reading != null ? " " + r.reading.mgdl + " @" + r.reading.timestamp : "")
                    .append(" when=").append(r.input.when).append(" post=").append(r.input.postTime)
                    .append(" ongoing=").append(r.input.ongoing).append(' ').append(r.input.describeTexts())
                    .append(r.detail != null ? " :: " + r.detail : "").append('\n');
        }

        section(sb, "status.json");
        sb.append(bridge.statusJson()).append('\n');

        section(sb, "Event log (persistent)");
        sb.append(EventLog.readAll(app));

        section(sb, "Logcat (this app, last " + LOGCAT_LINES + " lines)");
        sb.append(logcat());

        sb.append("\n===== end =====\n");
        return sb.toString();
    }

    private static void appendActiveNotifications(StringBuilder sb, Context app, Bridge bridge) {
        final EversenseListenerService listener = EversenseListenerService.connected();
        if (listener == null) {
            sb.append("(listener not connected, cannot read active notifications)\n");
            return;
        }
        final StatusBarNotification[] active;
        try {
            active = listener.getActiveNotifications();
        } catch (RuntimeException e) {
            sb.append("(getActiveNotifications failed: ").append(e).append(")\n");
            return;
        }
        if (active == null) {
            sb.append("(none)\n");
            return;
        }
        // Package names only for everything (spots a renamed Eversense package) ...
        final TreeSet<String> packages = new TreeSet<>();
        for (StatusBarNotification sbn : active) packages.add(sbn.getPackageName());
        kv(sb, "packages with notifications", packages.toString());
        // ... full detail for monitored packages and anything from Senseonics.
        int dumped = 0;
        for (StatusBarNotification sbn : active) {
            final String pkg = sbn.getPackageName();
            if (!bridge.engine.isMonitored(pkg) && !pkg.startsWith("com.senseonics")) continue;
            dumped++;
            dumpNotification(sb, app, sbn);
        }
        if (dumped == 0) sb.append("No notification from an Eversense package is showing right now.\n");
    }

    @SuppressWarnings("deprecation")
    static void dumpNotification(StringBuilder sb, Context app, StatusBarNotification sbn) {
        final Notification n = sbn.getNotification();
        sb.append("--- ").append(sbn.getKey()).append('\n');
        kv(sb, "  package/id/tag", sbn.getPackageName() + " / " + sbn.getId() + " / " + sbn.getTag());
        kv(sb, "  postTime", sbn.getPostTime() + " (" + full(sbn.getPostTime()) + ")");
        if (n == null) return;
        kv(sb, "  when", n.when + " (" + full(n.when) + ")");
        kv(sb, "  ongoing/flags", sbn.isOngoing() + " / 0x" + Integer.toHexString(n.flags));
        kv(sb, "  channel/category", n.getChannelId() + " / " + n.category);
        final Bundle extras = n.extras;
        if (extras != null) {
            for (String key : new TreeSet<>(extras.keySet())) {
                Object v;
                try {
                    v = extras.get(key);
                } catch (RuntimeException e) {
                    v = "<" + e + ">";
                }
                String text = v == null ? "null" : v.getClass().getSimpleName() + ": " + v;
                if (text.length() > MAX_VALUE_CHARS) text = text.substring(0, MAX_VALUE_CHARS) + "…";
                kv(sb, "  extra " + key, text);
            }
        }
        final CaptureEngine.Input in = NotificationTexts.toInput(app, sbn, System.currentTimeMillis());
        kv(sb, "  contentView", n.contentView == null ? "none" : "present");
        kv(sb, "  texts read", in.describeTexts());
        kv(sb, "  bigContentView", n.bigContentView == null ? "none" : "present");
        final NotificationParser.Result p = NotificationParser.parse(in.viewTexts, in.extrasTexts,
                Bridge.get(app).settings.units());
        kv(sb, "  parse result", p.ok() ? p.mgdl + " mg/dL from \"" + p.matchedText + "\""
                : p.failure + (p.rangeMarker != null ? " (" + p.rangeMarker + ")" : ""));
    }

    static String logcat() {
        final String pid = String.valueOf(Process.myPid());
        try {
            final java.lang.Process p = new ProcessBuilder("logcat", "-d", "-v", "threadtime",
                    "-t", String.valueOf(LOGCAT_LINES), "--pid=" + pid).redirectErrorStream(true).start();
            final StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            p.waitFor();
            return out.length() == 0 ? "(empty)\n" : out.toString();
        } catch (Exception e) {
            return "(logcat unavailable: " + e + ")\n";
        }
    }

    private static String whenUnreliable(Bridge bridge) {
        final StringBuilder sb = new StringBuilder();
        for (String pkg : bridge.engine.getPackages()) {
            if (bridge.engine.timestamps().isWhenUnreliable(pkg)) sb.append(pkg).append(' ');
        }
        return sb.length() == 0 ? "none" : sb.toString().trim();
    }

    private static String appVersion(Context app, String pkg) {
        try {
            final PackageInfo pi = app.getPackageManager().getPackageInfo(pkg, 0);
            final long code = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : legacyVersionCode(pi);
            return pkg + " " + pi.versionName + " (" + code + "), updated " + full(pi.lastUpdateTime);
        } catch (PackageManager.NameNotFoundException e) {
            return "not installed";
        }
    }

    @SuppressWarnings("deprecation")
    private static long legacyVersionCode(PackageInfo pi) {
        return pi.versionCode;
    }

    private static String standbyBucket(int b) {
        switch (b) {
            case UsageStatsManager.STANDBY_BUCKET_ACTIVE: return "ACTIVE";
            case UsageStatsManager.STANDBY_BUCKET_WORKING_SET: return "WORKING_SET";
            case UsageStatsManager.STANDBY_BUCKET_FREQUENT: return "FREQUENT";
            case UsageStatsManager.STANDBY_BUCKET_RARE: return "RARE";
            case 45: return "RESTRICTED";
            default: return String.valueOf(b);
        }
    }

    private static void section(StringBuilder sb, String title) {
        sb.append("\n===== ").append(title).append(" =====\n");
    }

    private static void kv(StringBuilder sb, String k, Object v) {
        sb.append(k).append(": ").append(v).append('\n');
    }

    static String full(long ms) {
        return ms <= 0 ? "-" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date(ms));
    }

    private static String ago(long now, long then) {
        return then <= 0 ? "never (since app start)" : ((now - then) / 1000) + " s ago (" + full(then) + ")";
    }
}
