package app.meanwhile.bridge;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.Set;

import app.meanwhile.bridge.core.CaptureEngine;
import app.meanwhile.bridge.core.NotificationParser;

/** User settings. */
final class Settings {

    static final String PREFS = "bridge_settings";
    /**
     * Not xDrip+'s 17580, so xDrip+ can keep its own web service on while the bridge
     * runs. Meanwhile, its watch face and anything else of ours read this port.
     */
    static final int DEFAULT_PORT = 17590;
    /** The default before 17590: xDrip+'s port. */
    static final int OLD_DEFAULT_PORT = 17580;
    static final int DEFAULT_STALE_MINUTES = 20;

    private static final String KEY_PORT = "port";
    private static final String KEY_LAN = "lan";
    private static final String KEY_SECRET = "api_secret";
    private static final String KEY_UNITS = "units";
    private static final String KEY_PACKAGES = "packages";
    private static final String KEY_STALE_ALERT = "stale_alert";
    private static final String KEY_STALE_MINUTES = "stale_minutes";
    static final String KEY_PORT_MOVED = "port_moved_off_xdrip";

    private final SharedPreferences p;

    Settings(Context context) {
        p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        movePortOffXdrip();
    }

    /**
     * Once: a port saved as the old default (xDrip+'s 17580) moves to 17590. A port
     * chosen after this runs is left alone.
     */
    private void movePortOffXdrip() {
        if (p.getBoolean(KEY_PORT_MOVED, false)) return;
        final SharedPreferences.Editor e = p.edit().putBoolean(KEY_PORT_MOVED, true);
        if (p.getInt(KEY_PORT, DEFAULT_PORT) == OLD_DEFAULT_PORT) e.putInt(KEY_PORT, DEFAULT_PORT);
        e.commit();
    }

    int port() {
        final int port = p.getInt(KEY_PORT, DEFAULT_PORT);
        return port >= 1024 && port <= 65535 ? port : DEFAULT_PORT;
    }

    boolean lan() {
        return p.getBoolean(KEY_LAN, false);
    }

    String apiSecret() {
        return p.getString(KEY_SECRET, "");
    }

    NotificationParser.Units units() {
        return NotificationParser.Units.parse(p.getString(KEY_UNITS, "auto"));
    }

    String unitsRaw() {
        return p.getString(KEY_UNITS, "auto");
    }

    Set<String> packages() {
        return parsePackages(p.getString(KEY_PACKAGES, null));
    }

    String packagesCsv() {
        return String.join(", ", packages());
    }

    boolean staleAlert() {
        return p.getBoolean(KEY_STALE_ALERT, true);
    }

    int staleMinutes() {
        final int m = p.getInt(KEY_STALE_MINUTES, DEFAULT_STALE_MINUTES);
        return m >= 6 && m <= 240 ? m : DEFAULT_STALE_MINUTES;
    }

    void save(int port, boolean lan, String secret, String units, String packagesCsv, boolean staleAlert, int staleMinutes) {
        p.edit()
                .putInt(KEY_PORT, port)
                .putBoolean(KEY_LAN, lan)
                .putString(KEY_SECRET, secret == null ? "" : secret.trim())
                .putString(KEY_UNITS, units)
                .putString(KEY_PACKAGES, String.join(",", parsePackages(packagesCsv)))
                .putBoolean(KEY_STALE_ALERT, staleAlert)
                .putInt(KEY_STALE_MINUTES, staleMinutes)
                .commit();
    }

    static Set<String> parsePackages(String csv) {
        final Set<String> out = new LinkedHashSet<>();
        if (csv != null) {
            for (String s : csv.split("[,\\s]+")) {
                if (s.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) out.add(s);
            }
        }
        if (out.isEmpty()) out.addAll(CaptureEngine.DEFAULT_PACKAGES);
        return out;
    }
}
