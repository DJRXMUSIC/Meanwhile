package app.meanwhile.bridge.core;

import java.util.Map;

/**
 * One low or high glucose alert, as xDrip+ defines it ({@code models/AlertType}): a
 * threshold, above or below, an optional time-of-day window, how often to repeat while
 * it goes on, and how long a snooze lasts. Thresholds are mg/dL, as xDrip+ stores them.
 */
public final class AlertRule {

    /** xDrip+'s fixed urgent low at 55 mg/dL ({@code AlertType.LOW_ALERT_55}), if it is in the export. */
    public static final String LOW_ALERT_55 = "c5f1999c-4ec5-449e-adad-3980b172b920";

    public final String uuid;
    public final String name;
    public final boolean active;
    /** True for a high alert, false for a low one. */
    public final boolean above;
    public final double threshold;
    public final boolean allDay;
    /** Minutes from midnight; used when not {@link #allDay}. */
    public final int startMinutes;
    public final int endMinutes;
    /** Repeat interval while the alert goes on, minutes. */
    public final int minutesBetween;
    /** Snooze length, minutes; 0 means xDrip+'s default (35 low, 120 high). */
    public final int defaultSnooze;
    public final boolean vibrate;
    public final boolean overrideSilent;
    /** Play on the alarm stream (through the speaker) rather than the media stream. */
    public final boolean forceSpeaker;
    /** Sound to play (a content:// or file URI from xDrip+), or null for the default alarm. */
    public final String soundUri;

    public AlertRule(String uuid, String name, boolean active, boolean above, double threshold,
                     boolean allDay, int startMinutes, int endMinutes, int minutesBetween, int defaultSnooze,
                     boolean vibrate, boolean overrideSilent, boolean forceSpeaker, String soundUri) {
        this.uuid = uuid;
        this.name = name == null ? "" : name;
        this.active = active;
        this.above = above;
        this.threshold = threshold;
        this.allDay = allDay;
        this.startMinutes = startMinutes;
        this.endMinutes = endMinutes;
        this.minutesBetween = minutesBetween;
        this.defaultSnooze = defaultSnooze;
        this.vibrate = vibrate;
        this.overrideSilent = overrideSilent;
        this.forceSpeaker = forceSpeaker;
        this.soundUri = soundUri;
    }

    public AlertRule withActive(boolean on) {
        return new AlertRule(uuid, name, on, above, threshold, allDay, startMinutes, endMinutes,
                minutesBetween, defaultSnooze, vibrate, overrideSilent, forceSpeaker, soundUri);
    }

    public AlertRule withSettings(double newThreshold, int newMinutesBetween, int newDefaultSnooze,
                                  boolean newVibrate, boolean newOverrideSilent) {
        return new AlertRule(uuid, name, active, above, newThreshold, allDay, startMinutes, endMinutes,
                newMinutesBetween, newDefaultSnooze, newVibrate, newOverrideSilent, forceSpeaker, soundUri);
    }

    /** @param uri a sound the app can open, or null for the phone's alarm sound */
    public AlertRule withSound(String uri) {
        return new AlertRule(uuid, name, active, above, threshold, allDay, startMinutes, endMinutes,
                minutesBetween, defaultSnooze, vibrate, overrideSilent, forceSpeaker, uri == null || uri.isEmpty() ? null : uri);
    }

    /** xDrip+ {@code s_in_time_frame}: inclusive, and a window may run past midnight. */
    public boolean inTimeFrame(int minuteOfDay) {
        if (allDay) return true;
        if (startMinutes < endMinutes) return minuteOfDay >= startMinutes && minuteOfDay <= endMinutes;
        return minuteOfDay >= startMinutes || minuteOfDay <= endMinutes;
    }

    /** xDrip+ {@code should_alarm}. ("Predictive" there tests the same condition, so it adds nothing.) */
    public boolean shouldAlarm(double bg, int minuteOfDay) {
        return inTimeFrame(minuteOfDay) && active && (above ? bg >= threshold : bg <= threshold);
    }

    /** The snooze a swipe or the notification's Snooze button gives (xDrip+ {@code snoozeBgAlert}). */
    public int snoozeMinutes() {
        if (defaultSnooze != 0) return defaultSnooze;
        return above ? 120 : 35;
    }

    // ------------------------------------------------------------------ storage

    void writeTo(Json j) {
        j.beginObject()
                .field("uuid", uuid)
                .field("name", name)
                .field("active", active)
                .field("above", above)
                .field("threshold", threshold)
                .field("all_day", allDay)
                .field("start_time_minutes", startMinutes)
                .field("end_time_minutes", endMinutes)
                .field("minutes_between", minutesBetween)
                .field("default_snooze", defaultSnooze)
                .field("vibrate", vibrate)
                .field("override_silent_mode", overrideSilent)
                .field("force_speaker", forceSpeaker);
        if (soundUri != null) j.field("mp3_file", soundUri);
        j.endObject();
    }

    /**
     * Reads one alert in xDrip+'s own JSON field names (its {@code saved_alerts} export and
     * this app's storage use the same ones). Returns null if it has no threshold or uuid.
     */
    static AlertRule fromMap(Map<String, Object> m) {
        final Object uuid = m.get("uuid");
        final Object threshold = m.get("threshold");
        if (!(uuid instanceof String) || ((String) uuid).isEmpty() || !(threshold instanceof Number)) return null;
        final double t = ((Number) threshold).doubleValue();
        if (Double.isNaN(t) || Double.isInfinite(t)) return null;
        final Object sound = m.get("mp3_file");
        return new AlertRule((String) uuid, str(m.get("name")), bool(m.get("active"), true), bool(m.get("above"), false), t,
                bool(m.get("all_day"), true), num(m.get("start_time_minutes"), 0), num(m.get("end_time_minutes"), 0),
                num(m.get("minutes_between"), 1), num(m.get("default_snooze"), 0), bool(m.get("vibrate"), true),
                bool(m.get("override_silent_mode"), false), bool(m.get("force_speaker"), false),
                sound instanceof String && !((String) sound).isEmpty() ? (String) sound : null);
    }

    private static String str(Object o) {
        return o instanceof String ? (String) o : "";
    }

    private static boolean bool(Object o, boolean def) {
        return o instanceof Boolean ? (Boolean) o : def;
    }

    private static int num(Object o, int def) {
        return o instanceof Number ? (int) Math.round(((Number) o).doubleValue()) : def;
    }

    @Override
    public String toString() {
        return (above ? "high" : "low") + " " + name.trim() + " " + (above ? "≥" : "≤") + Math.round(threshold) + (active ? "" : " (off)");
    }
}
