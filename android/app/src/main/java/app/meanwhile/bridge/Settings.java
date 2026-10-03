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
    /** Same port as xDrip+'s local web service, so Meanwhile needs no changes. */
    static final int DEFAULT_PORT = 17580;
    static final int DEFAULT_STALE_MINUTES = 20;

    private static final String KEY_PORT = "port";
    private static final String KEY_LAN = "lan";
    private static final String KEY_SECRET = "api_secret";
    private static final String KEY_UNITS = "units";
    private static final String KEY_PACKAGES = "packages";
    private static final String KEY_STALE_ALERT = "stale_alert";
    private static final String KEY_STALE_MINUTES = "stale_minutes";

    private final SharedPreferences p;

    Settings(Context context) {
        p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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
