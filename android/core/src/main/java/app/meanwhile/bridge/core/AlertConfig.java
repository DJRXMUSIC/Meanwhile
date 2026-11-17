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

    /** Nothing set up yet: no alerts until the xDrip+ settings are imported. */
    public static AlertConfig empty() {
        return new AlertConfig(Collections.emptyList(), AlertSettings.defaults(), "mgdl");
    }

    public AlertConfig withRules(List<AlertRule> r) {
        return new AlertConfig(r, settings, units);
    }

    public AlertConfig withSettings(AlertSettings s) {
        return new AlertConfig(rules, s, units);
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

    /** Never throws: damaged or missing data gives {@link #empty()}. */
    @SuppressWarnings("unchecked")
    public static AlertConfig fromJson(String text) {
        if (text == null || text.isEmpty()) return empty();
        try {
            final Map<String, Object> m = (Map<String, Object>) JsonReader.parse(text);
            final Map<String, Object> s = m.get("settings") instanceof Map ? (Map<String, Object>) m.get("settings") : Collections.emptyMap();
            final AlertSettings d = AlertSettings.defaults();
            final AlertSettings settings = new AlertSettings(
                    s.get("bg_alert_profile") instanceof String ? (String) s.get("bg_alert_profile") : d.profile,
                    bool(s, "smart_snoozing", d.smartSnoozing), bool(s, "smart_alerting", d.smartAlerting),
                    bool(s, "disable_alerts_stale_data", d.disableStale),
                    (int) num(s, "disable_alerts_stale_data_minutes", d.staleMinutes),
                    bool(s, "start_snoozed", d.startSnoozed), bool(s, "delay_ascending_3min", d.delayAscending3min),
                    bool(s, "ascending_volume_to_medium", d.ascendingToMedium),
                    bool(s, "no_alarms_during_calls", d.noAlarmsDuringCalls),
                    num(s, "alerts_disabled_until", 0), num(s, "low_alerts_disabled_until", 0),
                    num(s, "high_alerts_disabled_until", 0));
            final List<AlertRule> rules = new ArrayList<>();
            if (m.get("alerts") instanceof List) {
                for (Object o : (List<Object>) m.get("alerts")) {
                    if (!(o instanceof Map)) continue;
                    final AlertRule r = AlertRule.fromMap((Map<String, Object>) o);
                    if (r != null && AlertEngine.find(rules, r.uuid) == null) rules.add(r);
                }
            }
            return new AlertConfig(rules, settings, m.get("units") instanceof String ? (String) m.get("units") : "mgdl");
        } catch (RuntimeException e) {
            return empty();
        }
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
