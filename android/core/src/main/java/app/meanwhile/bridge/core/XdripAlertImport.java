package app.meanwhile.bridge.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads the glucose alert setup out of xDrip+'s settings export: the preferences file its
 * Import/Export → "Save settings" writes, {@code xDrip-export/shared_prefs/
 * com.eveningoutpost.dexdrip_preferences.xml}. xDrip+ puts its alert list in that file as
 * JSON under {@code saved_alerts} (its {@code AlertType.toSettings}), next to the options
 * that apply to all alerts.
 *
 * The "alerts disabled until" times are deliberately not taken: if xDrip+'s alerts were
 * silenced before exporting, they must not silence EverBridge's.
 */
public final class XdripAlertImport {

    public static final class Result {
        public final List<AlertRule> rules;
        public final AlertSettings settings;
        /** The display units xDrip+ was using ("mgdl" or "mmol"), to show thresholds the same way. */
        public final String units;

        Result(List<AlertRule> rules, AlertSettings settings, String units) {
            this.rules = rules;
            this.settings = settings;
            this.units = units;
        }
    }

    private XdripAlertImport() {
    }

    /**
     * The file as picked: the preferences XML itself, or a zip with it inside.
     * @throws IllegalArgumentException with a message for the user if the file isn't usable
     */
    public static Result parse(byte[] file) {
        if (file != null && file.length > 4 && file[0] == 'P' && file[1] == 'K') {
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(file))) {
                ZipEntry e;
                while ((e = zip.getNextEntry()) != null) {
                    if (!e.isDirectory() && e.getName().endsWith("_preferences.xml")) {
                        final ByteArrayOutputStream out = new ByteArrayOutputStream();
                        final byte[] buf = new byte[8192];
                        int n;
                        while ((n = zip.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            if (out.size() > MAX_BYTES) throw new IllegalArgumentException("The settings file is too big");
                        }
                        return parse(new String(out.toByteArray(), StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("The zip file can't be read (" + e.getMessage() + ")");
            }
            throw new IllegalArgumentException("The zip file has no xDrip+ settings in it (no ..._preferences.xml).");
        }
        return parse(file == null ? null : new String(file, StandardCharsets.UTF_8));
    }

    /** Larger than any real preferences file. */
    public static final int MAX_BYTES = 8 * 1024 * 1024;

    /** @throws IllegalArgumentException with a message for the user if the file isn't usable */
    public static Result parse(String xml) {
        if (xml == null || !xml.contains("<map")) {
            throw new IllegalArgumentException("This isn't an xDrip+ settings file. In xDrip+ use Import/Export → "
                    + "Save settings, then pick com.eveningoutpost.dexdrip_preferences.xml from Downloads/xDrip-export.");
        }
        final Map<String, String> p = prefs(xml);
        final String saved = p.get("saved_alerts");
        if (saved == null || saved.trim().isEmpty()) {
            throw new IllegalArgumentException("The file has no alert list in it. Export again from xDrip+ "
                    + "(Import/Export → Save settings), which writes the alerts into the file.");
        }
        final Object json;
        try {
            json = JsonReader.parse(saved);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The alert list in the file is damaged (" + e.getMessage() + ")");
        }
        if (!(json instanceof List)) throw new IllegalArgumentException("The alert list in the file is damaged");
        // exactly the alerts xDrip+ has; none is added (xDrip+ itself no longer adds the one at 55)
        final List<AlertRule> rules = new ArrayList<>();
        for (Object o : (List<?>) json) {
            if (!(o instanceof Map)) continue;
            @SuppressWarnings("unchecked") final AlertRule r = AlertRule.fromMap((Map<String, Object>) o);
            if (r != null && AlertEngine.find(rules, r.uuid) == null) rules.add(r);
        }

        final AlertSettings settings = new AlertSettings(
                p.getOrDefault("bg_alert_profile", AlertSettings.ASCENDING),
                bool(p, "smart_snoozing", true), bool(p, "smart_alerting", true),
                bool(p, "disable_alerts_stale_data", false), num(p, "disable_alerts_stale_data_minutes", 15),
                bool(p, "start_snoozed", false), bool(p, "delay_ascending_3min", true),
                bool(p, "ascending_volume_to_medium", false), bool(p, "no_alarms_during_calls", false),
                0, 0, 0);
        if (rules.isEmpty()) throw new IllegalArgumentException("xDrip+ has no low or high alerts set up in this file.");
        return new Result(rules, settings, "mmol".equals(p.get("units")) ? "mmol" : "mgdl");
    }

    // ------------------------------------------------------------------ the preferences file

    private static final Pattern STRING = Pattern.compile("<string\\s+name=\"([^\"]*)\"\\s*>(.*?)</string>", Pattern.DOTALL);
    private static final Pattern EMPTY_STRING = Pattern.compile("<string\\s+name=\"([^\"]*)\"\\s*/>");
    private static final Pattern VALUE = Pattern.compile("<(?:boolean|int|long|float)\\s+name=\"([^\"]*)\"\\s+value=\"([^\"]*)\"\\s*/>");

    /** Android's SharedPreferences XML: every value as text, by key. */
    static Map<String, String> prefs(String xml) {
        final Map<String, String> out = new LinkedHashMap<>();
        Matcher m = STRING.matcher(xml);
        while (m.find()) out.put(unescape(m.group(1)), unescape(m.group(2)));
        m = EMPTY_STRING.matcher(xml);
        while (m.find()) out.put(unescape(m.group(1)), "");
        m = VALUE.matcher(xml);
        while (m.find()) out.put(unescape(m.group(1)), unescape(m.group(2)));
        return out;
    }

    static String unescape(String s) {
        if (s.indexOf('&') < 0) return s;
        final StringBuilder b = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            final char c = s.charAt(i);
            final int semi = c == '&' ? s.indexOf(';', i) : -1;
            if (semi < 0 || semi - i > 10) {
                b.append(c);
                i++;
                continue;
            }
            final String ent = s.substring(i + 1, semi);
            String rep = null;
            switch (ent) {
                case "lt": rep = "<"; break;
                case "gt": rep = ">"; break;
                case "amp": rep = "&"; break;
                case "quot": rep = "\""; break;
                case "apos": rep = "'"; break;
                default:
                    try {
                        if (ent.startsWith("#x") || ent.startsWith("#X")) rep = new String(Character.toChars(Integer.parseInt(ent.substring(2), 16)));
                        else if (ent.startsWith("#")) rep = new String(Character.toChars(Integer.parseInt(ent.substring(1))));
                    } catch (IllegalArgumentException ignored) {
                        rep = null;
                    }
            }
            if (rep == null) {
                b.append(c);
                i++;
            } else {
                b.append(rep);
                i = semi + 1;
            }
        }
        return b.toString();
    }

    private static boolean bool(Map<String, String> p, String key, boolean def) {
        final String v = p.get(key);
        return v == null ? def : "true".equalsIgnoreCase(v.trim());
    }

    private static int num(Map<String, String> p, String key, int def) {
        try {
            return Integer.parseInt(p.get(key).trim());
        } catch (RuntimeException e) {
            return def;
        }
    }
}
