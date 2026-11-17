package app.meanwhile.bridge.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decides, after each reading and at each scheduled check, whether a glucose alert starts,
 * repeats, stays quiet or stops. A port of xDrip+'s {@code Notifications.FileBasedNotifications},
 * {@code AlertType.get_highest_active_alert}, {@code AlertPlayer.startAlert/ClockTick/Snooze}
 * and {@code BgReading.trendingToAlertEnd}, step for step, so EverBridge alerts the way xDrip+
 * did with the same settings. Pure: the caller stores the returned state and makes the noise.
 */
public final class AlertEngine {

    /** xDrip+ {@code AlertPlayer}: vibrate only for this long when ascending, then ramp up… */
    static final int MAX_VIBRATING_MINUTES = 2;
    /** …to full volume here. */
    static final int MAX_ASCENDING_MINUTES = 5;
    static final float NODELAY_ASCENDING_INTERCEPT = 0.3333f;
    static final float NODELAY_ASCENDING_SLOPE = 0.166675f;
    static final float MEDIUM_VOLUME = 0.7f;

    private AlertEngine() {
    }

    public enum Action {
        /** Nothing changes. */
        NONE,
        /** Sound the alert now (it starts, or it repeats). */
        PLAY,
        /** Silence any alert sound and remove its notification. */
        STOP
    }

    public static final class Result {
        public final Action action;
        /** The alert state to store; null when no alert is going on. */
        public final ActiveAlert active;
        /** The alert that plays (PLAY only). */
        public final AlertRule rule;
        /** Minutes since it started sounding, for the ascending volume (PLAY only). */
        public final int minutesSinceStart;
        /** Why, for the log. */
        public final String why;

        Result(Action action, ActiveAlert active, AlertRule rule, int minutesSinceStart, String why) {
            this.action = action;
            this.active = active;
            this.rule = rule;
            this.minutesSinceStart = minutesSinceStart;
            this.why = why;
        }

        @Override
        public String toString() {
            return action + (rule != null ? " " + rule : "") + " (" + why + ")";
        }
    }

    /**
     * @param rules       every alert, the urgent low at 55 included
     * @param newestFirst the latest readings (three are enough), newest first
     * @param minuteOfDay local time, minutes after midnight
     */
    public static Result evaluate(List<AlertRule> rules, AlertSettings s, ActiveAlert active,
                                  List<Reading> newestFirst, long now, int minuteOfDay) {
        if (newestFirst.isEmpty()) return new Result(Action.STOP, null, null, 0, "no readings");
        final Reading last = newestFirst.get(0);
        final double bg = last.mgdl;

        final AlertRule newAlert = highestActive(rules, s, bg, last.timestamp, now, minuteOfDay);
        if (newAlert == null) {
            // stopAlert(clearData=false, clearIfSnoozeFinished=true): a snooze outlives the alert
            final ActiveAlert keep = active != null && !active.ready(now) ? active : null;
            return new Result(Action.STOP, keep, null, 0, "no alert applies");
        }

        final AlertRule current = active == null ? null : find(rules, active.uuid);
        if (current == null) {
            final boolean trending = s.smartAlerting && trendingToAlertEnd(newestFirst, newAlert.above, now);
            return start(newAlert, s, now, trending, Action.NONE);
        }

        if (current.uuid.equals(newAlert.uuid)) {
            if (s.disableStale && now - 60_000L * (s.staleMinutes + 2) - last.timestamp > 0) {
                return new Result(Action.NONE, active, null, 0, "reading too old to repeat");
            }
            final boolean trending = s.smartSnoozing && trendingToAlertEnd(newestFirst, newAlert.above, now);
            return clockTick(newAlert, s, active, now, trending);
        }

        if (!active.ready(now)) {
            // snoozed: a lower low (or higher high) breaks through, a milder one doesn't
            if (current.above == newAlert.above && higher(current, newAlert) == current) {
                return new Result(Action.NONE, active, null, 0, "snoozed; " + newAlert + " is milder");
            }
        }
        final boolean trending = s.smartAlerting && trendingToAlertEnd(newestFirst, newAlert.above, now);
        // the old one is stopped and cleared whether or not the new one starts
        return start(newAlert, s, now, trending, Action.STOP);
    }

    /** User snooze (the notification's button, a swipe, or the alert screen). */
    public static ActiveAlert snooze(ActiveAlert active, int minutes, long now) {
        return active == null ? null : active.snoozedFor(minutes, now);
    }

    /** When to check again: at the repeat or end of snooze, but never more than 6 minutes away. */
    public static long nextCheck(ActiveAlert active, long now) {
        long t = Long.MAX_VALUE;
        if (active != null) t = active.nextAlertAt < now ? now + 60_000L : active.nextAlertAt;
        if (t >= now + 6 * 60_000L) t = now + 6 * 60_000L;
        if (t <= now) t = now + 1000;
        return t;
    }

    // ------------------------------------------------------------------ the steps

    private static Result start(AlertRule rule, AlertSettings s, long now, boolean trending, Action otherwise) {
        if (trending) return new Result(otherwise, null, null, 0, "heading back already: " + rule + " not started");
        final ActiveAlert a = new ActiveAlert(rule.uuid, s.startSnoozed, nextAlertTime(rule, s, now), now);
        if (s.startSnoozed) return new Result(Action.STOP, a, null, 0, rule + " starts snoozed");
        return new Result(Action.PLAY, a, rule, 0, rule + " starts");
    }

    private static Result clockTick(AlertRule rule, AlertSettings s, ActiveAlert active, long now, boolean trending) {
        if (trending) return new Result(Action.NONE, active, null, 0, "heading back already: no repeat");
        if (!active.ready(now)) return new Result(Action.NONE, active, null, 0, "waiting to repeat");
        // getAndUpdateAlertingMinutes: the ascending ramp restarts after a snooze
        final long startedAt = active.snoozed ? now : active.startedAt;
        final int minutes = (int) Math.round(((now - startedAt) / 1000L) / 60.0);
        final ActiveAlert next = new ActiveAlert(active.uuid, false, nextAlertTime(rule, s, now), startedAt);
        return new Result(Action.PLAY, next, rule, minutes, rule + " repeats");
    }

    /** xDrip+ {@code getNextAlertTime}: the alert's repeat interval, or every minute when ascending. */
    static long nextAlertTime(AlertRule rule, AlertSettings s, long now) {
        int minutes = rule.minutesBetween;
        if (minutes < 1 || AlertSettings.ASCENDING.equals(s.profile)) minutes = 1;
        return now + minutes * 60_000L;
    }

    /**
     * xDrip+ {@code get_highest_active_alert}: the lowest low that applies, else the highest
     * high; none while alerts are snoozed from the alert screen or for "special" values ≤ 14.
     */
    static AlertRule highestActive(List<AlertRule> rules, AlertSettings s, double bg, long lastTs, long now, int minuteOfDay) {
        if (s.allDisabledUntil > now) return null;
        if (bg <= 14) return null;
        if (s.lowDisabledUntil <= now) {
            final List<AlertRule> lows = new ArrayList<>();
            for (AlertRule r : rules) if (!r.above && r.threshold >= bg) lows.add(r);
            lows.sort(Comparator.comparingDouble(r -> r.threshold));
            for (AlertRule r : lows) if (r.shouldAlarm(bg, minuteOfDay)) return filterStale(r, s, lastTs, now);
        }
        if (s.highDisabledUntil <= now) {
            final List<AlertRule> highs = new ArrayList<>();
            for (AlertRule r : rules) if (r.above && r.threshold <= bg) highs.add(r);
            highs.sort(Comparator.comparingDouble((AlertRule r) -> r.threshold).reversed());
            for (AlertRule r : highs) if (r.shouldAlarm(bg, minuteOfDay)) return filterStale(r, s, lastTs, now);
        }
        return null;
    }

    private static AlertRule filterStale(AlertRule r, AlertSettings s, long lastTs, long now) {
        if (!s.disableStale) return r;
        final int staleMinutes = Math.max(6, s.staleMinutes + 2);
        return lastTs > now - staleMinutes * 60_000L ? r : null;
    }

    /** xDrip+ {@code AlertType.HigherAlert}: of two alerts, the more urgent. */
    static AlertRule higher(AlertRule a, AlertRule b) {
        if (a.above && !b.above) return b;
        if (!a.above && b.above) return a;
        if (a.above) return a.threshold > b.threshold ? a : b;
        return a.threshold < b.threshold ? a : b;
    }

    /**
     * xDrip+ {@code BgReading.trendingToAlertEnd}: from a low, up more than 4 in one reading
     * or 10 over two; from a high, down more than 1 or 2. Needs three readings, the oldest
     * within 21 minutes.
     */
    static boolean trendingToAlertEnd(List<Reading> newestFirst, boolean above, long now) {
        if (newestFirst.size() < 3) return false;
        final Reading r0 = newestFirst.get(0), r1 = newestFirst.get(1), r2 = newestFirst.get(2);
        if (now - r2.timestamp > (3 * 5 + 6) * 60_000L) return false;
        if (!above) return r0.mgdl - r1.mgdl > 4 || r0.mgdl - r2.mgdl > 10;
        return r1.mgdl - r0.mgdl > 1 || r2.mgdl - r0.mgdl > 2;
    }

    static AlertRule find(List<AlertRule> rules, String uuid) {
        for (AlertRule r : rules) if (r.uuid.equals(uuid)) return r;
        return null;
    }

    // ------------------------------------------------------------------ the noise

    /** How an alert sounds this time (xDrip+ {@code VibrateNotifyMakeNoise}). */
    public static final class Sound {
        /** Fraction of the stream's maximum volume; 0 means no sound this time. */
        public final float volume;
        public final boolean vibrate;

        Sound(float volume, boolean vibrate) {
            this.volume = volume;
            this.vibrate = vibrate;
        }

        @Override
        public String toString() {
            return "volume " + Math.round(volume * 100) + "%" + (vibrate ? " + vibrate" : "");
        }
    }

    public static Sound sound(AlertRule rule, AlertSettings s, int minutesSinceStart) {
        String profile = s.profile;
        // the urgent low is never vibrate-only
        if (AlertRule.LOW_ALERT_55.equals(rule.uuid) && AlertSettings.VIBRATE_ONLY.equals(profile)) {
            profile = AlertSettings.ASCENDING;
        }
        final boolean ascending = AlertSettings.ASCENDING.equals(profile);
        final int minutes = ascending ? minutesSinceStart : MAX_ASCENDING_MINUTES;
        float volume = 0;
        if (!AlertSettings.VIBRATE_ONLY.equals(profile) && !AlertSettings.SILENT.equals(profile)) {
            volume = (float) (minutes - MAX_VIBRATING_MINUTES) / (MAX_ASCENDING_MINUTES - MAX_VIBRATING_MINUTES);
            if (!s.delayAscending3min) volume = minutes * NODELAY_ASCENDING_SLOPE + NODELAY_ASCENDING_INTERCEPT;
            volume = Math.max(0, Math.min(1, volume));
            if (s.ascendingToMedium && ascending) volume = Math.min(volume, MEDIUM_VOLUME);
            if (AlertSettings.MEDIUM.equals(profile)) volume = MEDIUM_VOLUME;
        }
        final boolean vibrate = !AlertSettings.SILENT.equals(profile) && rule.vibrate;
        return new Sound(volume, vibrate);
    }
}
