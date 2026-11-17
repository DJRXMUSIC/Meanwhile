package app.meanwhile.bridge.core;

/**
 * The options that apply to every glucose alert, with xDrip+'s keys and defaults
 * (its "Alerts and Notifications" settings).
 */
public final class AlertSettings {

    /** xDrip+ {@code bg_alert_profile} values. */
    public static final String HIGH = "High";
    public static final String ASCENDING = "ascending";
    public static final String MEDIUM = "medium";
    public static final String VIBRATE_ONLY = "vibrate only";
    public static final String SILENT = "Silent";

    /** {@code bg_alert_profile}, default ascending: vibrate 2 min, then louder each minute. */
    public final String profile;
    /** {@code smart_snoozing}: don't repeat while the reading is already heading back. */
    public final boolean smartSnoozing;
    /** {@code smart_alerting}: don't start while the reading is already heading back. */
    public final boolean smartAlerting;
    /** {@code disable_alerts_stale_data} and {@code _minutes}. */
    public final boolean disableStale;
    public final int staleMinutes;
    /** {@code start_snoozed}. */
    public final boolean startSnoozed;
    /** {@code delay_ascending_3min}: ascending starts with vibration only. */
    public final boolean delayAscending3min;
    /** {@code ascending_volume_to_medium}. */
    public final boolean ascendingToMedium;
    /** {@code no_alarms_during_calls}. */
    public final boolean noAlarmsDuringCalls;
    /** Snoozes from the alert screen ({@code alerts_disabled_until} etc.), ms; 0 = none. */
    public final long allDisabledUntil;
    public final long lowDisabledUntil;
    public final long highDisabledUntil;

    public AlertSettings(String profile, boolean smartSnoozing, boolean smartAlerting, boolean disableStale,
                         int staleMinutes, boolean startSnoozed, boolean delayAscending3min, boolean ascendingToMedium,
                         boolean noAlarmsDuringCalls, long allDisabledUntil, long lowDisabledUntil, long highDisabledUntil) {
        this.profile = normalizeProfile(profile);
        this.smartSnoozing = smartSnoozing;
        this.smartAlerting = smartAlerting;
        this.disableStale = disableStale;
        this.staleMinutes = staleMinutes;
        this.startSnoozed = startSnoozed;
        this.delayAscending3min = delayAscending3min;
        this.ascendingToMedium = ascendingToMedium;
        this.noAlarmsDuringCalls = noAlarmsDuringCalls;
        this.allDisabledUntil = allDisabledUntil;
        this.lowDisabledUntil = lowDisabledUntil;
        this.highDisabledUntil = highDisabledUntil;
    }

    public static AlertSettings defaults() {
        return new AlertSettings(ASCENDING, true, true, false, 15, false, true, false, false, 0, 0, 0);
    }

    /** xDrip+ {@code getAlertProfile}: anything unknown is ascending. */
    static String normalizeProfile(String p) {
        if (HIGH.equals(p) || MEDIUM.equals(p) || VIBRATE_ONLY.equals(p) || SILENT.equals(p)) return p;
        return ASCENDING;
    }

    public AlertSettings withDisabledUntil(long all, long low, long high) {
        return new AlertSettings(profile, smartSnoozing, smartAlerting, disableStale, staleMinutes, startSnoozed,
                delayAscending3min, ascendingToMedium, noAlarmsDuringCalls, all, low, high);
    }

    public AlertSettings withProfile(String p) {
        return new AlertSettings(p, smartSnoozing, smartAlerting, disableStale, staleMinutes, startSnoozed,
                delayAscending3min, ascendingToMedium, noAlarmsDuringCalls, allDisabledUntil, lowDisabledUntil, highDisabledUntil);
    }

    /** The same options with this app's alert snoozes kept (an import never brings those). */
    public AlertSettings keepingDisabledUntilOf(AlertSettings current) {
        return withDisabledUntil(current.allDisabledUntil, current.lowDisabledUntil, current.highDisabledUntil);
    }
}
