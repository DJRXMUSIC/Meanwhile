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
    /**
     * How loud "full volume" is, percent of the stream's maximum, in 5% steps (this app's
     * addition; xDrip+ always uses the maximum). High plays at it, Medium at 70% of it,
     * Ascending climbs to it.
     */
    public final int volumePercent;

    public static final int MIN_VOLUME = 5;

    /** Full volume (100%), as xDrip+ has it. */
    public AlertSettings(String profile, boolean smartSnoozing, boolean smartAlerting, boolean disableStale,
                         int staleMinutes, boolean startSnoozed, boolean delayAscending3min, boolean ascendingToMedium,
                         boolean noAlarmsDuringCalls, long allDisabledUntil, long lowDisabledUntil, long highDisabledUntil) {
        this(profile, smartSnoozing, smartAlerting, disableStale, staleMinutes, startSnoozed, delayAscending3min,
                ascendingToMedium, noAlarmsDuringCalls, allDisabledUntil, lowDisabledUntil, highDisabledUntil, 100);
    }

    public AlertSettings(String profile, boolean smartSnoozing, boolean smartAlerting, boolean disableStale,
                         int staleMinutes, boolean startSnoozed, boolean delayAscending3min, boolean ascendingToMedium,
                         boolean noAlarmsDuringCalls, long allDisabledUntil, long lowDisabledUntil, long highDisabledUntil,
                         int volumePercent) {
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
        this.volumePercent = normalizeVolume(volumePercent);
    }

    /** 5–100, rounded to a 5% step. */
    static int normalizeVolume(int v) {
        final int r = Math.round(v / 5f) * 5;
        return Math.max(MIN_VOLUME, Math.min(100, r));
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
                delayAscending3min, ascendingToMedium, noAlarmsDuringCalls, all, low, high, volumePercent);
    }

    public AlertSettings withProfile(String p) {
        return new AlertSettings(p, smartSnoozing, smartAlerting, disableStale, staleMinutes, startSnoozed,
                delayAscending3min, ascendingToMedium, noAlarmsDuringCalls, allDisabledUntil, lowDisabledUntil,
                highDisabledUntil, volumePercent);
    }

    public AlertSettings withVolume(int percent) {
        return new AlertSettings(profile, smartSnoozing, smartAlerting, disableStale, staleMinutes, startSnoozed,
                delayAscending3min, ascendingToMedium, noAlarmsDuringCalls, allDisabledUntil, lowDisabledUntil,
                highDisabledUntil, percent);
    }

    /** The general options, by xDrip+ key. */
    public AlertSettings withOptions(boolean smartSnoozing, boolean smartAlerting, boolean disableStale, int staleMinutes,
                                     boolean startSnoozed, boolean delayAscending3min, boolean ascendingToMedium,
                                     boolean noAlarmsDuringCalls) {
        return new AlertSettings(profile, smartSnoozing, smartAlerting, disableStale, staleMinutes, startSnoozed,
                delayAscending3min, ascendingToMedium, noAlarmsDuringCalls, allDisabledUntil, lowDisabledUntil,
                highDisabledUntil, volumePercent);
    }

    /**
     * Imported options with what only this app has kept: its alert snoozes (an import never
     * brings those) and its volume level (xDrip+ has none).
     */
    public AlertSettings keepingLocalOf(AlertSettings current) {
        return new AlertSettings(profile, smartSnoozing, smartAlerting, disableStale, staleMinutes, startSnoozed,
                delayAscending3min, ascendingToMedium, noAlarmsDuringCalls, current.allDisabledUntil,
                current.lowDisabledUntil, current.highDisabledUntil, current.volumePercent);
    }
}
