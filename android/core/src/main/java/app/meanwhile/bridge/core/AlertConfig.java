package app.meanwhile.bridge.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** The alerts, their shared options and the alert going on now, as stored by the app (JSON). */
public final class AlertConfig {

    public final List<AlertRule> rules;
    public final AlertSettings settings;
    /** "mgdl" or "mmol": how thresholds are shown. */
    public final String units;

    public AlertConfig(List<AlertRule> rules, AlertSettings settings, String units) {
        this.rules = Collections.unmodifiableList(new ArrayList<>(rules));
        this.settings = settings;
        this.units = "mmol".equals(units) ? "mmol" : "mgdl";
    }

    /** The app's built-in sounds (res/raw). */
    public static final String SOUND_BEEP = "android.resource://app.meanwhile.bridge/raw/alert_beep";
    public static final String SOUND_VIBRATION = "android.resource://app.meanwhile.bridge/raw/alert_vibration";

    /** No alerts at all. */
    public static AlertConfig empty() {
        return new AlertConfig(Collections.emptyList(), AlertSettings.defaults(), "mgdl");
    }

    /**
     * What the app starts with: the owner's alerts (from their xDrip+ setup), with the app's own
     * beep and vibration sounds, at 50%. Both sound on silent and through Do Not Disturb.
     */
    public static AlertConfig defaults() {
        final List<AlertRule> rules = new ArrayList<>();
        rules.add(new AlertRule("570e659e-467b-4990-94f7-b92425a11a3f", "LOW WARNING", true, false, 90,
                true, 0, 0, 1, 60, false, true, true, SOUND_BEEP, AlertRule.DEFAULT_VOLUME));
        rules.add(new AlertRule("33de0538-00bb-4057-9e4c-cbd61bbbea4c", "Elevated Alert", true, true, 155,
                true, 0, 0, 3, 90, true, true, true, SOUND_VIBRATION, AlertRule.DEFAULT_VOLUME));
        final AlertSettings s = new AlertSettings(AlertSettings.HIGH, false, false, false, 15, false, true, false, true,
                0, 0, 0);
        return new AlertConfig(rules, s, "mgdl");
    }

    public AlertConfig withRules(List<AlertRule> r) {
        return new AlertConfig(r, settings, units);
    }

    public AlertConfig withSettings(AlertSettings s) {
        return new AlertConfig(rules, s, units);
    }

    /** Adds an alert, or replaces the one with its uuid. */
    public AlertConfig withSaved(AlertRule rule) {
        if (rule(rule.uuid) != null) return withRule(rule);
        final List<AlertRule> r = new ArrayList<>(rules);
        r.add(rule);
        return withRules(r);
    }

    public AlertConfig without(String uuid) {
        final List<AlertRule> r = new ArrayList<>();
        for (AlertRule x : rules) if (!x.uuid.equals(uuid)) r.add(x);
        return withRules(r);
    }

    /** Replaces one alert (by uuid). */
    public AlertConfig withRule(AlertRule changed) {
        final List<AlertRule> r = new ArrayList<>(rules);
        for (int i = 0; i < r.size(); i++) if (r.get(i).uuid.equals(changed.uuid)) r.set(i, changed);
        return withRules(r);
    }

    public AlertRule rule(String uuid) {
        return uuid == null ? null : AlertEngine.find(rules, uuid);
    }

    // ------------------------------------------------------------------ JSON

    public String toJson() {
        final Json j = new Json().beginObject();
        j.field("units", units);
        j.name("settings").beginObject()
                .field("bg_alert_profile", settings.profile)
                .field("smart_snoozing", settings.smartSnoozing)
                .field("smart_alerting", settings.smartAlerting)
                .field("disable_alerts_stale_data", settings.disableStale)
                .field("disable_alerts_stale_data_minutes", settings.staleMinutes)
                .field("start_snoozed", settings.startSnoozed)
                .field("delay_ascending_3min", settings.delayAscending3min)
                .field("ascending_volume_to_medium", settings.ascendingToMedium)
                .field("no_alarms_during_calls", settings.noAlarmsDuringCalls)
                .field("alerts_disabled_until", settings.allDisabledUntil)
                .field("low_alerts_disabled_until", settings.lowDisabledUntil)
                .field("high_alerts_disabled_until", settings.highDisabledUntil)
                .endObject();
        j.name("alerts").beginArray();
        for (AlertRule r : rules) r.writeTo(j);
        j.endArray();
        return j.endObject().toString();
    }

    /** Never throws: missing or damaged data gives {@link #defaults()}, so alerts always work. */
    @SuppressWarnings("unchecked")
    public static AlertConfig fromJson(String text) {
        if (text == null || text.isEmpty()) return defaults();
        try {
            final Map<String, Object> m = (Map<String, Object>) JsonReader.parse(text);
            final Map<String, Object> s = m.get("settings") instanceof Map ? (Map<String, Object>) m.get("settings") : Collections.emptyMap();
            final AlertSettings settings = settingsFrom(s, AlertSettings.defaults());
            // stored before alerts had their own volume: the one level then set for all
            final int legacyVolume = (int) num(s, "alert_volume_percent", AlertRule.DEFAULT_VOLUME);
            final List<AlertRule> rules = new ArrayList<>();
            if (m.get("alerts") instanceof List) {
                for (Object o : (List<Object>) m.get("alerts")) {
                    if (!(o instanceof Map)) continue;
                    final AlertRule r = AlertRule.fromMap((Map<String, Object>) o, legacyVolume);
                    if (r != null && AlertEngine.find(rules, r.uuid) == null) rules.add(r);
                }
            }
            return new AlertConfig(rules, settings, m.get("units") instanceof String ? (String) m.get("units") : "mgdl");
        } catch (RuntimeException e) {
            return defaults();
        }
    }

    private static AlertSettings settingsFrom(Map<String, Object> s, AlertSettings d) {
        return new AlertSettings(
                s.get("bg_alert_profile") instanceof String ? (String) s.get("bg_alert_profile") : d.profile,
                bool(s, "smart_snoozing", d.smartSnoozing), bool(s, "smart_alerting", d.smartAlerting),
                bool(s, "disable_alerts_stale_data", d.disableStale),
                (int) num(s, "disable_alerts_stale_data_minutes", d.staleMinutes),
                bool(s, "start_snoozed", d.startSnoozed), bool(s, "delay_ascending_3min", d.delayAscending3min),
                bool(s, "ascending_volume_to_medium", d.ascendingToMedium),
                bool(s, "no_alarms_during_calls", d.noAlarmsDuringCalls),
                num(s, "alerts_disabled_until", d.allDisabledUntil), num(s, "low_alerts_disabled_until", d.lowDisabledUntil),
                num(s, "high_alerts_disabled_until", d.highDisabledUntil));
    }

    /**
     * The options for all alerts as the Alerts tab sends them (stored field names); what it
     * leaves out, the "off until" times included, stays as {@code current} has it.
     * @throws IllegalArgumentException if it isn't a JSON object
     */
    @SuppressWarnings("unchecked")
    public static AlertSettings settingsFromJson(String json, AlertSettings current) {
        final Object o = JsonReader.parse(json);
        if (!(o instanceof Map)) throw new IllegalArgumentException("not an object");
        final Map<String, Object> s = new java.util.HashMap<>((Map<String, Object>) o);
        s.remove("alerts_disabled_until");
        s.remove("low_alerts_disabled_until");
        s.remove("high_alerts_disabled_until");
        final AlertSettings parsed = settingsFrom(s, current);
        int stale = parsed.staleMinutes;
        if (stale < 10) stale = 10;
        if (stale > 1440) stale = 1440;
        return parsed.withOptions(parsed.smartSnoozing, parsed.smartAlerting, parsed.disableStale, stale, parsed.startSnoozed,
                parsed.delayAscending3min, parsed.ascendingToMedium, parsed.noAlarmsDuringCalls);
    }

    /**
     * One alert as the Alerts tab sends it (stored field names, threshold in mg/dL).
     * @throws IllegalArgumentException if it isn't a usable alert
     */
    @SuppressWarnings("unchecked")
    public static AlertRule ruleFromJson(String json) {
        final Object o = JsonReader.parse(json);
        if (!(o instanceof Map)) throw new IllegalArgumentException("not an object");
        final AlertRule r = AlertRule.fromMap((Map<String, Object>) o);
        if (r == null) throw new IllegalArgumentException("an alert needs an id and a threshold");
        if (r.threshold < 40 || r.threshold > 400) throw new IllegalArgumentException("threshold must be 40-400 mg/dL");
        if (r.minutesBetween < 1 || r.minutesBetween > 120) throw new IllegalArgumentException("repeat must be 1-120 minutes");
        if (r.defaultSnooze < 0 || r.defaultSnooze > 1440) throw new IllegalArgumentException("snooze must be up to 1440 minutes");
        return r;
    }

    public static String activeToJson(ActiveAlert a) {
        if (a == null) return "";
        return new Json().beginObject().field("uuid", a.uuid).field("snoozed", a.snoozed)
                .field("next_alert_at", a.nextAlertAt).field("started_at", a.startedAt).endObject().toString();
    }

    @SuppressWarnings("unchecked")
    public static ActiveAlert activeFromJson(String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            final Map<String, Object> m = (Map<String, Object>) JsonReader.parse(text);
            if (!(m.get("uuid") instanceof String)) return null;
            return new ActiveAlert((String) m.get("uuid"), bool(m, "snoozed", false),
                    num(m, "next_alert_at", 0), num(m, "started_at", 0));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean bool(Map<String, Object> m, String k, boolean def) {
        return m.get(k) instanceof Boolean ? (Boolean) m.get(k) : def;
    }

    private static long num(Map<String, Object> m, String k, long def) {
        return m.get(k) instanceof Number ? (long) ((Number) m.get(k)).doubleValue() : def;
    }
}
