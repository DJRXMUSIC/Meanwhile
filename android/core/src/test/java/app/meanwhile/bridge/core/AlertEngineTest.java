package app.meanwhile.bridge.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import app.meanwhile.bridge.core.AlertEngine.Action;
import app.meanwhile.bridge.core.AlertEngine.Result;

public class AlertEngineTest {

    private static final long T = 1_790_000_000_000L;
    private static final long MIN = 60_000L;
    private static final int NOON = 12 * 60;

    private static Reading r(long ts, int mgdl) {
        return new Reading(ts, mgdl, TimestampSource.NOTIFICATION_WHEN, ts, ts, ts, "p", String.valueOf(mgdl));
    }

    /** Newest first, five minutes apart, the newest at {@code at}. */
    private static List<Reading> readings(long at, int... newestFirst) {
        final List<Reading> out = new ArrayList<>();
        for (int i = 0; i < newestFirst.length; i++) out.add(r(at - i * 5 * MIN, newestFirst[i]));
        return out;
    }

    private static AlertRule low(String uuid, double t, int repeat, int snooze) {
        return new AlertRule(uuid, "low " + uuid, true, false, t, true, 0, 0, repeat, snooze, true, true, true, null);
    }

    private static AlertRule high(String uuid, double t, int repeat, int snooze) {
        return new AlertRule(uuid, "high " + uuid, true, true, t, true, 0, 0, repeat, snooze, true, true, true, null);
    }

    /** The user's own xDrip+ settings: High profile, smart snoozing and alerting off. */
    private static AlertSettings highProfile() {
        return new AlertSettings(AlertSettings.HIGH, false, false, false, 15, false, true, false, true, 0, 0, 0);
    }

    private static final AlertRule LOW90 = low("L90", 90, 1, 60);
    private static final AlertRule HIGH155 = high("H155", 155, 3, 90);
    private static final List<AlertRule> RULES = Arrays.asList(HIGH155, LOW90);

    @Test
    public void nothingHappensInRange() {
        final Result res = AlertEngine.evaluate(RULES, highProfile(), null, readings(T, 120, 118, 117), T, NOON);
        assertEquals(Action.STOP, res.action);
        assertNull(res.active);
    }

    @Test
    public void noReadingsStopsAndClears() {
        final ActiveAlert a = new ActiveAlert("L90", false, T + MIN, T);
        final Result res = AlertEngine.evaluate(RULES, highProfile(), a, Collections.emptyList(), T, NOON);
        assertEquals(Action.STOP, res.action);
        assertNull(res.active);
    }

    @Test
    public void lowStartsAtTheThresholdAndRepeatsEveryMinute() {
        final AlertSettings s = highProfile();
        Result res = AlertEngine.evaluate(RULES, s, null, readings(T, 90, 95, 100), T, NOON);
        assertEquals(Action.PLAY, res.action);
        assertSame(LOW90, res.rule);
        assertEquals(0, res.minutesSinceStart);
        assertEquals(T + MIN, res.active.nextAlertAt);

        // the next check, 30 s on: not yet
        ActiveAlert a = res.active;
        res = AlertEngine.evaluate(RULES, s, a, readings(T, 90, 95, 100), T + 30_000, NOON);
        assertEquals(Action.NONE, res.action);
        assertSame(a, res.active);

        // a minute on (strictly after): again
        res = AlertEngine.evaluate(RULES, s, a, readings(T, 90, 95, 100), T + MIN + 1000, NOON);
        assertEquals(Action.PLAY, res.action);
        assertEquals(1, res.minutesSinceStart);
        assertEquals(T + 2 * MIN + 1000, res.active.nextAlertAt);
        assertEquals(T, res.active.startedAt);
    }

    @Test
    public void highRepeatsAtItsOwnIntervalUnlessAscending() {
        Result res = AlertEngine.evaluate(RULES, highProfile(), null, readings(T, 160, 150, 140), T, NOON);
        assertEquals(Action.PLAY, res.action);
        assertSame(HIGH155, res.rule);
        assertEquals(T + 3 * MIN, res.active.nextAlertAt);

        // ascending repeats every minute, whatever the alert says (xDrip+ getNextAlertTime)
        res = AlertEngine.evaluate(RULES, AlertSettings.defaults(), null, readings(T, 160, 150, 140), T, NOON);
        assertEquals(T + MIN, res.active.nextAlertAt);
    }

    @Test
    public void stopsWhenBackInRange() {
        final ActiveAlert a = new ActiveAlert("L90", false, T + MIN, T);
        final Result res = AlertEngine.evaluate(RULES, highProfile(), a, readings(T + 5 * MIN, 95, 85), T + 5 * MIN, NOON);
        assertEquals(Action.STOP, res.action);
        assertNull(res.active);
    }

    @Test
    public void snoozeHoldsEvenAcrossLeavingAndReturning() {
        final AlertSettings s = highProfile();
        final ActiveAlert playing = AlertEngine.evaluate(RULES, s, null, readings(T, 85), T, NOON).active;
        final ActiveAlert snoozed = AlertEngine.snooze(playing, LOW90.snoozeMinutes(), T + 10_000);
        assertTrue(snoozed.snoozed);
        assertEquals(T + 10_000 + 60 * MIN, snoozed.nextAlertAt);

        // still low: quiet
        Result res = AlertEngine.evaluate(RULES, s, snoozed, readings(T + 5 * MIN, 84, 85), T + 5 * MIN, NOON);
        assertEquals(Action.NONE, res.action);
        // back up: the sound stops but the snooze is kept…
        res = AlertEngine.evaluate(RULES, s, snoozed, readings(T + 10 * MIN, 95, 84), T + 10 * MIN, NOON);
        assertEquals(Action.STOP, res.action);
        assertSame(snoozed, res.active);
        // …so dipping again inside the hour stays quiet
        res = AlertEngine.evaluate(RULES, s, res.active, readings(T + 15 * MIN, 88, 95), T + 15 * MIN, NOON);
        assertEquals(Action.NONE, res.action);
        // and after it, it sounds again, the ramp counted from now
        final long after = T + 10_000 + 60 * MIN + 1000;
        res = AlertEngine.evaluate(RULES, s, res.active, readings(after, 88, 95), after, NOON);
        assertEquals(Action.PLAY, res.action);
        assertEquals(0, res.minutesSinceStart);
        assertFalse(res.active.snoozed);
        assertEquals(after, res.active.startedAt);
    }

    @Test
    public void snoozeOverWhileInRangeClearsIt() {
        final ActiveAlert snoozed = new ActiveAlert("L90", true, T, T - 60 * MIN);
        final Result res = AlertEngine.evaluate(RULES, highProfile(), snoozed, readings(T + MIN, 100), T + MIN, NOON);
        assertEquals(Action.STOP, res.action);
        assertNull(res.active);
    }

    @Test
    public void aLowerLowBreaksThroughASnoozeButAMilderOneDoesNot() {
        final AlertRule low70 = low("L70", 70, 1, 20);
        final List<AlertRule> rules = Arrays.asList(LOW90, low70);
        final AlertSettings s = highProfile();

        final ActiveAlert snoozed90 = new ActiveAlert("L90", true, T + 30 * MIN, T - MIN);
        Result res = AlertEngine.evaluate(rules, s, snoozed90, readings(T, 65, 75), T, NOON);
        assertEquals(Action.PLAY, res.action);
        assertSame(low70, res.rule);
        assertEquals("L70", res.active.uuid);

        final ActiveAlert snoozed70 = new ActiveAlert("L70", true, T + 30 * MIN, T - MIN);
        res = AlertEngine.evaluate(rules, s, snoozed70, readings(T, 80, 68), T, NOON);
        assertEquals(Action.NONE, res.action);
        assertSame(snoozed70, res.active);
    }

    @Test
    public void lowestApplicableLowWins() {
        final AlertRule low70 = low("L70", 70, 1, 20);
        final AlertRule low55 = low(AlertRule.LOW_ALERT_55, 55, 1, 20);
        final Result res = AlertEngine.evaluate(Arrays.asList(LOW90, low70, low55), highProfile(), null,
                readings(T, 54), T, NOON);
        assertSame(low55, res.rule);
    }

    @Test
    public void highestApplicableHighWins() {
        final AlertRule high250 = high("H250", 250, 5, 0);
        final Result res = AlertEngine.evaluate(Arrays.asList(HIGH155, high250), highProfile(), null,
                readings(T, 260), T, NOON);
        assertSame(high250, res.rule);
    }

    @Test
    public void offAlertsAndSpecialValuesNeverSound() {
        final List<AlertRule> rules = Collections.singletonList(LOW90.withActive(false));
        assertEquals(Action.STOP, AlertEngine.evaluate(rules, highProfile(), null, readings(T, 60), T, NOON).action);
        // xDrip+ treats ≤ 14 as a sensor code, not a glucose value
        assertEquals(Action.STOP, AlertEngine.evaluate(RULES, highProfile(), null, readings(T, 12), T, NOON).action);
    }

    @Test
    public void timeWindowIncludingOneThatCrossesMidnight() {
        final AlertRule night = new AlertRule("N", "night", true, false, 100, false, 22 * 60, 7 * 60, 1, 0, true, true, true, null);
        final List<AlertRule> rules = Collections.singletonList(night);
        assertEquals(Action.PLAY, AlertEngine.evaluate(rules, highProfile(), null, readings(T, 95), T, 23 * 60).action);
        assertEquals(Action.PLAY, AlertEngine.evaluate(rules, highProfile(), null, readings(T, 95), T, 3 * 60).action);
        assertEquals(Action.PLAY, AlertEngine.evaluate(rules, highProfile(), null, readings(T, 95), T, 7 * 60).action);
        assertEquals(Action.STOP, AlertEngine.evaluate(rules, highProfile(), null, readings(T, 95), T, NOON).action);

        final AlertRule day = new AlertRule("D", "day", true, true, 150, false, 8 * 60, 20 * 60, 1, 0, true, true, true, null);
        assertTrue(day.inTimeFrame(8 * 60));
        assertTrue(day.inTimeFrame(20 * 60));
        assertFalse(day.inTimeFrame(21 * 60));
    }

    @Test
    public void disabledFromTheAlertScreen() {
        final AlertSettings s = highProfile();
        assertEquals(Action.STOP, AlertEngine.evaluate(RULES, s.withDisabledUntil(T + MIN, 0, 0), null, readings(T, 60), T, NOON).action);
        assertEquals(Action.STOP, AlertEngine.evaluate(RULES, s.withDisabledUntil(0, T + MIN, 0), null, readings(T, 60), T, NOON).action);
        assertEquals(Action.PLAY, AlertEngine.evaluate(RULES, s.withDisabledUntil(0, 0, T + MIN), null, readings(T, 60), T, NOON).action);
        assertEquals(Action.STOP, AlertEngine.evaluate(RULES, s.withDisabledUntil(0, 0, T + MIN), null, readings(T, 200), T, NOON).action);
        // once over, it works again
        assertEquals(Action.PLAY, AlertEngine.evaluate(RULES, s.withDisabledUntil(T, T, T), null, readings(T, 60), T, NOON).action);
    }

    @Test
    public void staleReadingsOnlyMatterWhenThatOptionIsOn() {
        final List<Reading> old = readings(T - 30 * MIN, 60);
        assertEquals(Action.PLAY, AlertEngine.evaluate(RULES, highProfile(), null, old, T, NOON).action);
        final AlertSettings stale = new AlertSettings(AlertSettings.HIGH, false, false, true, 15, false, true, false, false, 0, 0, 0);
        assertEquals(Action.STOP, AlertEngine.evaluate(RULES, stale, null, old, T, NOON).action);
        assertEquals(Action.PLAY, AlertEngine.evaluate(RULES, stale, null, readings(T - 10 * MIN, 60), T, NOON).action);
    }

    @Test
    public void smartAlertingSkipsALowAlreadyRisingFast() {
        final AlertSettings smart = AlertSettings.defaults();
        // up 5 in the last reading: heading back already
        Result res = AlertEngine.evaluate(RULES, smart, null, readings(T, 85, 80, 78), T, NOON);
        assertEquals(Action.NONE, res.action);
        assertNull(res.active);
        // up 4 only: still alerts
        res = AlertEngine.evaluate(RULES, smart, null, readings(T, 85, 81, 80), T, NOON);
        assertEquals(Action.PLAY, res.action);
        // with smart alerting off, it alerts regardless
        res = AlertEngine.evaluate(RULES, highProfile(), null, readings(T, 85, 80, 78), T, NOON);
        assertEquals(Action.PLAY, res.action);
    }

    @Test
    public void smartSnoozingHoldsTheRepeatOfAHighAlreadyFalling() {
        final AlertSettings smart = AlertSettings.defaults();
        final ActiveAlert a = new ActiveAlert("H155", false, T - 1, T - 10 * MIN);
        final Result res = AlertEngine.evaluate(RULES, smart, a, readings(T, 170, 172, 175), T, NOON);
        assertEquals(Action.NONE, res.action);
        final Result flat = AlertEngine.evaluate(RULES, smart, a, readings(T, 170, 170, 171), T, NOON);
        assertEquals(Action.PLAY, flat.action);
        assertEquals(10, flat.minutesSinceStart);
    }

    @Test
    public void trendNeedsThreeRecentReadings() {
        assertFalse(AlertEngine.trendingToAlertEnd(readings(T, 85, 75), false, T));
        assertTrue(AlertEngine.trendingToAlertEnd(readings(T, 85, 75, 70), false, T));
        // the oldest more than 21 minutes back: no trend
        assertFalse(AlertEngine.trendingToAlertEnd(readings(T - 15 * MIN, 85, 75, 70), false, T));
    }

    @Test
    public void startSnoozedStoresTheAlertWithoutSound() {
        final AlertSettings s = new AlertSettings(AlertSettings.HIGH, false, false, false, 15, true, true, false, false, 0, 0, 0);
        final Result res = AlertEngine.evaluate(RULES, s, null, readings(T, 80), T, NOON);
        assertEquals(Action.STOP, res.action);
        assertTrue(res.active.snoozed);
    }

    @Test
    public void switchingFromHighToLowStartsTheLow() {
        final ActiveAlert high = new ActiveAlert("H155", false, T + 2 * MIN, T - MIN);
        final Result res = AlertEngine.evaluate(RULES, highProfile(), high, readings(T, 80), T, NOON);
        assertEquals(Action.PLAY, res.action);
        assertSame(LOW90, res.rule);
    }

    @Test
    public void anAlertDeletedWhilePlayingIsReplaced() {
        final ActiveAlert gone = new ActiveAlert("deleted", false, T + MIN, T - MIN);
        final Result res = AlertEngine.evaluate(RULES, highProfile(), gone, readings(T, 80), T, NOON);
        assertEquals(Action.PLAY, res.action);
        assertEquals("L90", res.active.uuid);
    }

    @Test
    public void nextCheckIsTheRepeatButNeverMoreThanSixMinutesAway() {
        assertEquals(T + 6 * MIN, AlertEngine.nextCheck(null, T));
        assertEquals(T + MIN, AlertEngine.nextCheck(new ActiveAlert("x", false, T + MIN, T), T));
        assertEquals(T + 6 * MIN, AlertEngine.nextCheck(new ActiveAlert("x", true, T + 60 * MIN, T), T));
        // overdue: a minute from now
        assertEquals(T + MIN, AlertEngine.nextCheck(new ActiveAlert("x", false, T - 5000, T), T));
        assertEquals(T + 1000, AlertEngine.nextCheck(new ActiveAlert("x", false, T, T), T));
    }

    @Test
    public void snoozeLengths() {
        assertEquals(60, LOW90.snoozeMinutes());
        assertEquals(35, low("x", 70, 1, 0).snoozeMinutes());
        assertEquals(120, high("y", 200, 1, 0).snoozeMinutes());
    }

    // ------------------------------------------------------------------ sound

    @Test
    public void highProfileIsFullVolumeFromTheStart() {
        final AlertEngine.Sound s = AlertEngine.sound(LOW90, highProfile(), 0);
        assertEquals(1f, s.volume, 1e-6);
        assertTrue(s.vibrate);
        final AlertRule noVibe = new AlertRule("v", "n", true, false, 90, true, 0, 0, 1, 0, false, true, true, null);
        assertFalse(AlertEngine.sound(noVibe, highProfile(), 0).vibrate);
    }

    @Test
    public void ascendingVibratesFirstThenRampsUp() {
        final AlertSettings s = AlertSettings.defaults();
        assertEquals(0f, AlertEngine.sound(LOW90, s, 0).volume, 1e-6);
        assertEquals(0f, AlertEngine.sound(LOW90, s, 2).volume, 1e-6);
        assertEquals(1f / 3, AlertEngine.sound(LOW90, s, 3).volume, 1e-6);
        assertEquals(2f / 3, AlertEngine.sound(LOW90, s, 4).volume, 1e-6);
        assertEquals(1f, AlertEngine.sound(LOW90, s, 5).volume, 1e-6);
        assertEquals(1f, AlertEngine.sound(LOW90, s, 30).volume, 1e-6);
        assertTrue(AlertEngine.sound(LOW90, s, 0).vibrate);
    }

    @Test
    public void aSoundlessAlarmSaysWhy() {
        assertNull(AlertEngine.sound(LOW90, highProfile(), 0).quietBecause);
        assertEquals("alarm volume is Vibrate only",
                AlertEngine.sound(LOW90, highProfile().withProfile(AlertSettings.VIBRATE_ONLY), 0).quietBecause);
        assertEquals("alarm volume is Silent",
                AlertEngine.sound(LOW90, highProfile().withProfile(AlertSettings.SILENT), 0).quietBecause);
        // ascending with the delay: silent for the alarm's first minutes, then says nothing
        assertEquals("Ascending: vibration only for the first 3 minutes",
                AlertEngine.sound(LOW90, AlertSettings.defaults(), 2).quietBecause);
        assertNull(AlertEngine.sound(LOW90, AlertSettings.defaults(), 3).quietBecause);
        assertTrue(AlertEngine.sound(LOW90, highProfile().withProfile(AlertSettings.VIBRATE_ONLY), 0).toString()
                .contains("(alarm volume is Vibrate only)"));
    }

    @Test
    public void ascendingWithoutTheDelayAndCappedAtMedium() {
        final AlertSettings noDelay = new AlertSettings(AlertSettings.ASCENDING, true, true, false, 15, false, false, false, false, 0, 0, 0);
        assertEquals(0.3333f, AlertEngine.sound(LOW90, noDelay, 0).volume, 1e-4);
        assertEquals(0.3333f + 2 * 0.166675f, AlertEngine.sound(LOW90, noDelay, 2).volume, 1e-4);
        assertEquals(1f, AlertEngine.sound(LOW90, noDelay, 5).volume, 1e-6);
        final AlertSettings toMedium = new AlertSettings(AlertSettings.ASCENDING, true, true, false, 15, false, true, true, false, 0, 0, 0);
        assertEquals(0.7f, AlertEngine.sound(LOW90, toMedium, 10).volume, 1e-6);
    }

    @Test
    public void eachAlertsVolumeScalesEveryProfile() {
        // High at 40% plays at 40%; Medium at 70% of that; Ascending climbs to it
        final AlertRule low40 = LOW90.withVolume(40);
        assertEquals(0.40f, AlertEngine.sound(low40, highProfile(), 0).volume, 1e-6);
        assertEquals(0.28f, AlertEngine.sound(low40, highProfile().withProfile(AlertSettings.MEDIUM), 0).volume, 1e-6);
        final AlertSettings asc = AlertSettings.defaults();
        assertEquals(0f, AlertEngine.sound(low40, asc, 2).volume, 1e-6);
        assertEquals(0.40f / 3, AlertEngine.sound(low40, asc, 3).volume, 1e-6);
        assertEquals(0.40f, AlertEngine.sound(low40, asc, 9).volume, 1e-6);
        // vibration isn't affected
        assertEquals(0f, AlertEngine.sound(low40, asc.withProfile(AlertSettings.VIBRATE_ONLY), 9).volume, 1e-6);
        assertEquals(0.4f, AlertEngine.testVolume(low40), 1e-6);
        // another alert keeps its own
        assertEquals(1f, AlertEngine.sound(HIGH155, highProfile(), 0).volume, 1e-6);
        assertEquals(0.07f, AlertEngine.sound(LOW90.withVolume(7), highProfile(), 0).volume, 1e-6);
    }

    @Test
    public void volumeIsOneToHundredAndCopiesKeepIt() {
        assertEquals(100, LOW90.volumePercent);
        assertEquals(1, LOW90.withVolume(0).volumePercent);
        assertEquals(100, LOW90.withVolume(250).volumePercent);
        assertEquals(33, LOW90.withVolume(33).volumePercent);
        assertEquals(33, LOW90.withVolume(33).withActive(false).withSound("x").withSettings(80, 2, 30, true, true).volumePercent);
    }

    @Test
    public void otherProfiles() {
        assertEquals(0.7f, AlertEngine.sound(LOW90, AlertSettings.defaults().withProfile(AlertSettings.MEDIUM), 0).volume, 1e-6);
        final AlertEngine.Sound vib = AlertEngine.sound(LOW90, AlertSettings.defaults().withProfile(AlertSettings.VIBRATE_ONLY), 9);
        assertEquals(0f, vib.volume, 1e-6);
        assertTrue(vib.vibrate);
        final AlertEngine.Sound silent = AlertEngine.sound(LOW90, AlertSettings.defaults().withProfile(AlertSettings.SILENT), 9);
        assertEquals(0f, silent.volume, 1e-6);
        assertFalse(silent.vibrate);
        // the urgent low at 55 is never only a vibration
        final AlertRule low55 = low(AlertRule.LOW_ALERT_55, 55, 1, 20);
        assertEquals(1f, AlertEngine.sound(low55, AlertSettings.defaults().withProfile(AlertSettings.VIBRATE_ONLY), 5).volume, 1e-6);
        // an unknown profile is ascending, as in xDrip+
        assertEquals(AlertSettings.ASCENDING, AlertSettings.defaults().withProfile("loud").profile);
    }
}
