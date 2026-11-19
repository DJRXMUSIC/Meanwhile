package app.meanwhile.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlarmManager;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowNotificationManager;

import app.meanwhile.bridge.core.ActiveAlert;
import app.meanwhile.bridge.core.AlertConfig;
import app.meanwhile.bridge.core.AlertEngine;
import app.meanwhile.bridge.core.AlertRule;
import app.meanwhile.bridge.core.AlertSettings;
import app.meanwhile.bridge.core.Reading;
import app.meanwhile.bridge.core.TimestampSource;

@RunWith(RobolectricTestRunner.class)
public class AlertsTest {

    /** Records what would be heard. */
    static final class FakeNoise implements Alerts.Noise {
        final List<String> calls = new ArrayList<>();

        @Override
        public void alert(android.content.Context c, AlertRule rule, AlertSettings settings, AlertEngine.Sound sound) {
            calls.add("alert " + rule.uuid + " " + sound);
        }

        @Override
        public void test(android.content.Context c, AlertRule rule, float volume) {
            calls.add("test " + rule.uuid + " " + Math.round(volume * 100) + "%");
        }

        @Override
        public void stop(android.content.Context c) {
            calls.add("stop");
        }
    }

    private Application app;
    private FakeNoise noise;
    private ShadowNotificationManager nm;
    private ShadowAlarmManager alarms;

    @Before
    public void setUp() {
        Bridge.resetForTests();
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("alerts", 0).edit().clear().commit();
        noise = new FakeNoise();
        Alerts.noise = noise;
        nm = shadowOf(app.getSystemService(NotificationManager.class));
        alarms = shadowOf(app.getSystemService(AlarmManager.class));
        ShadowAlarmManager.setCanScheduleExactAlarms(true);
    }

    @After
    public void tearDown() {
        Bridge.resetForTests();
    }

    /** A low at 90 (snooze 60, sounds on silent) and a high at 180 (no override), both at 50%. */
    private void importExport() {
        final AlertSettings s = new AlertSettings(AlertSettings.HIGH, false, false, false, 15, false, true, false, false, 0, 0, 0);
        Alerts.update(app, new AlertConfig(Arrays.asList(
                new AlertRule("low-90", "LOW WARNING", true, false, 90, true, 0, 0, 1, 60, false, true, true, null, 50),
                new AlertRule("high-180", "Elevated", true, true, 180, true, 0, 0, 3, 90, true, false, true, null, 50)),
                s, "mgdl"), "test setup");
    }

    private void storeReading(long ts, int mgdl) {
        Bridge.get(app).store.insert(new Reading(ts, mgdl, TimestampSource.POST_TIME, 0, ts, ts, "p", ""));
    }

    private void idleAlertThread() {
        shadowOf(Alerts.handler().getLooper()).idle();
    }

    @Test
    public void builtInAlertsWorkWithoutAnyImport() {
        storeReading(System.currentTimeMillis(), 85);
        assertEquals(AlertEngine.Action.PLAY, Alerts.checkNow(app, "test").action);
        // High profile at the default Alert volume of 50%, not xDrip+'s 100%
        assertEquals("alert 570e659e-467b-4990-94f7-b92425a11a3f volume 50%", noise.calls.get(noise.calls.size() - 1));
        final Notification n = nm.getNotification(Alerts.NOTIFICATION_ID);
        assertEquals("85 LOW WARNING", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        assertEquals("Snooze 60 min", n.actions[0].title.toString());
        assertNotNull(n.fullScreenIntent);
        assertEquals("Beep", SoundNames.name(app, AlertConfig.SOUND_BEEP));
        assertEquals("Vibration sound", SoundNames.name(app, AlertConfig.SOUND_VIBRATION));
        assertEquals("Phone alarm", SoundNames.name(app, null));
    }

    @Test
    public void builtInHighIsTheVibrationSoundAt155() {
        storeReading(System.currentTimeMillis(), 155);
        assertEquals(AlertEngine.Action.PLAY, Alerts.checkNow(app, "test").action);
        final AlertRule rule = Alerts.config(app).rule(Alerts.active(app).uuid);
        assertEquals(AlertConfig.SOUND_VIBRATION, rule.soundUri);
        assertEquals("155 Elevated Alert", nm.getNotification(Alerts.NOTIFICATION_ID).extras
                .getCharSequence(Notification.EXTRA_TITLE).toString());
    }

    @Test
    public void noAlertsAtAllMeansNoTimer() {
        Alerts.update(app, AlertConfig.empty(), "none");
        storeReading(System.currentTimeMillis(), 50);
        assertNull(Alerts.checkNow(app, "test"));
        assertNull(nm.getNotification(Alerts.NOTIFICATION_ID));
        assertNull(alarms.peekNextScheduledAlarm());
    }

    /** As if the alert's repeat time has come. */
    private void makeRepeatDue() {
        final ActiveAlert a = Alerts.active(app);
        app.getSharedPreferences("alerts", 0).edit().putString("active", AlertConfig.activeToJson(
                new ActiveAlert(a.uuid, a.snoozed, System.currentTimeMillis() - 1000, a.startedAt))).commit();
    }

    @Test
    public void lowReadingSoundsShowsTheAlertAndWakesForTheRepeat() {
        importExport();
        final long now = System.currentTimeMillis();
        storeReading(now, 70);
        final AlertEngine.Result r = Alerts.checkNow(app, "test");
        assertEquals(AlertEngine.Action.PLAY, r.action);
        assertEquals("alert low-90 volume 50%", noise.calls.get(noise.calls.size() - 1));

        final Notification n = nm.getNotification(Alerts.NOTIFICATION_ID);
        assertNotNull(n);
        assertEquals(Alerts.CHANNEL, n.getChannelId());
        assertEquals("70 LOW WARNING", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_TEXT).toString().startsWith("LOW alert: 70 at "));
        assertEquals("Snooze 60 min", n.actions[0].title.toString());
        assertNotNull(n.deleteIntent);
        // overrides silent mode: an alarm that may show over the lock screen
        assertEquals(Notification.CATEGORY_ALARM, n.category);
        assertNotNull(n.fullScreenIntent);
        assertNotNull(app.getSystemService(NotificationManager.class).getNotificationChannel(Alerts.CHANNEL));
        assertNull(app.getSystemService(NotificationManager.class).getNotificationChannel(Alerts.CHANNEL).getSound());

        final ShadowAlarmManager.ScheduledAlarm alarm = alarms.peekNextScheduledAlarm();
        assertNotNull(alarm);
        assertNotNull(alarm.getAlarmClockInfo());
        final long at = alarm.getAlarmClockInfo().getTriggerTime();
        assertTrue(at - now >= 59_000 && at - now <= 62_000);

        // the timer fires a minute later: it sounds again
        final ActiveAlert a = Alerts.active(app);
        assertEquals("low-90", a.uuid);
        assertFalse(a.snoozed);
    }

    @Test
    public void backInRangeStopsTheAlert() {
        importExport();
        final long now = System.currentTimeMillis();
        storeReading(now - 5 * 60_000L, 70);
        Alerts.checkNow(app, "test");
        storeReading(now, 110);
        assertEquals(AlertEngine.Action.STOP, Alerts.checkNow(app, "test").action);
        assertNull(nm.getNotification(Alerts.NOTIFICATION_ID));
        assertEquals("stop", noise.calls.get(noise.calls.size() - 1));
        // as in xDrip+, the alert's record stays until its repeat time, then goes
        assertNotNull(Alerts.active(app));
        makeRepeatDue();
        assertEquals(AlertEngine.Action.STOP, Alerts.checkNow(app, "test").action);
        assertNull(Alerts.active(app));
        // nothing going on: the next check is a plain wake-up, not an alarm clock
        assertNull(alarms.peekNextScheduledAlarm().getAlarmClockInfo());
    }

    @Test
    public void snoozeButtonSilencesForTheAlertsSnooze() {
        importExport();
        storeReading(System.currentTimeMillis(), 70);
        Alerts.checkNow(app, "test");
        final long before = System.currentTimeMillis();
        new AlertReceiver().onReceive(app, new Intent(Alerts.ACTION_SNOOZE));
        idleAlertThread();
        final ActiveAlert a = Alerts.active(app);
        assertTrue(a.snoozed);
        assertTrue(a.nextAlertAt >= before + 60 * 60_000L);
        assertNull(nm.getNotification(Alerts.NOTIFICATION_ID));
        assertEquals("stop", noise.calls.get(noise.calls.size() - 1));

        // still low on the next reading: quiet
        storeReading(System.currentTimeMillis() + 1000, 72);
        assertEquals(AlertEngine.Action.NONE, Alerts.checkNow(app, "test").action);
        assertNull(nm.getNotification(Alerts.NOTIFICATION_ID));
    }

    @Test
    public void swipingAwaySnoozesButNotRightAfterPosting() {
        importExport();
        storeReading(System.currentTimeMillis(), 70);
        Alerts.checkNow(app, "test");
        Alerts.dismissed(app, System.currentTimeMillis());
        assertFalse(Alerts.active(app).snoozed);
        Alerts.dismissed(app, System.currentTimeMillis() - 10_000);
        assertTrue(Alerts.active(app).snoozed);
    }

    @Test
    public void turningLowsOffForAWhileAndBackOn() {
        importExport();
        storeReading(System.currentTimeMillis(), 70);
        Alerts.checkNow(app, "test");
        Alerts.disable(app, "low", 30);
        assertTrue(Alerts.config(app).settings.lowDisabledUntil > System.currentTimeMillis());
        assertNull(nm.getNotification(Alerts.NOTIFICATION_ID));
        assertEquals("stop", noise.calls.get(noise.calls.size() - 1));
        Alerts.disable(app, "low", 0);
        assertEquals(0, Alerts.config(app).settings.lowDisabledUntil);
        makeRepeatDue();
        assertEquals(AlertEngine.Action.PLAY, Alerts.checkNow(app, "test").action);
        assertNotNull(nm.getNotification(Alerts.NOTIFICATION_ID));
    }

    @Test
    public void turningHighsOffUntilTurnedBackOn() {
        importExport();
        final long before = System.currentTimeMillis();
        Alerts.disable(app, "high", Alerts.OFF_UNTIL_TURNED_ON);
        assertEquals(Alerts.OFF_FOREVER_UNTIL, Alerts.config(app).settings.highDisabledUntil);
        // counts as a snooze for going back to Now
        assertTrue(Alerts.snoozedAt(app) >= before);
        storeReading(System.currentTimeMillis(), 250);
        assertNotEquals(AlertEngine.Action.PLAY, Alerts.checkNow(app, "test").action);
        final int heard = noise.calls.size();
        Alerts.disable(app, "high", 0);
        assertEquals(0, Alerts.config(app).settings.highDisabledUntil);
        // turned back on: it sounds straight away
        assertEquals("high-180", Alerts.active(app).uuid);
        final List<String> since = new ArrayList<>(noise.calls.subList(heard, noise.calls.size()));
        assertTrue(since.toString(), since.stream().anyMatch(x -> x.startsWith("alert high-180")));
    }

    @Test
    public void everyAlertCanOpenTheAlarmScreen() {
        importExport();
        storeReading(System.currentTimeMillis(), 200);
        assertEquals(AlertEngine.Action.PLAY, Alerts.checkNow(app, "test").action);
        final Notification n = nm.getNotification(Alerts.NOTIFICATION_ID);
        assertEquals("200 Elevated", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        assertEquals("Snooze 90 min", n.actions[0].title.toString());
        // even one that doesn't sound on silent: speed matters, the alarm screen is a tap away
        assertNotNull(n.fullScreenIntent);
        assertEquals(Notification.CATEGORY_ALARM, n.category);
        assertEquals(AlertScreenActivity.class.getName(),
                shadowOf(n.contentIntent).getSavedIntent().getComponent().getClassName());
    }

    @Test
    public void checkRunsOnTheAlertThread() {
        importExport();
        storeReading(System.currentTimeMillis(), 65);
        Alerts.check(app, "reading");
        idleAlertThread();
        assertNotNull(nm.getNotification(Alerts.NOTIFICATION_ID));
    }

    @Test
    public void mmolDisplay() {
        assertEquals("3.9", Alerts.bgText(70, "mmol").replace(',', '.'));
        assertEquals("70", Alerts.bgText(70, "mgdl"));
    }

    @Test
    public void snoozeAlertBeforeItFires() {
        final String low = "570e659e-467b-4990-94f7-b92425a11a3f";
        storeReading(System.currentTimeMillis() - 5 * 60_000L, 120);
        Alerts.preSnooze(app, low, 30);
        assertTrue(Alerts.active(app).snoozed);
        storeReading(System.currentTimeMillis(), 80);
        assertEquals(AlertEngine.Action.NONE, Alerts.checkNow(app, "test").action);
        assertNull(nm.getNotification(Alerts.NOTIFICATION_ID));
    }

    @Test
    public void createdAndRemovedAlertsTakeEffect() {
        final AlertRule urgent = new AlertRule("urgent", "Urgent low", true, false, 60, true, 0, 0, 1, 20, true, true, true,
                AlertConfig.SOUND_BEEP);
        Alerts.update(app, Alerts.config(app).withSaved(urgent), "add");
        storeReading(System.currentTimeMillis(), 55);
        Alerts.checkNow(app, "test");
        assertEquals("urgent", Alerts.active(app).uuid);
        Alerts.update(app, Alerts.config(app).without("urgent"), "remove");
        assertEquals("570e659e-467b-4990-94f7-b92425a11a3f", Alerts.active(app).uuid);
    }

    @Test
    public void playerGainMakesFiveStepsFine() {
        // a 7-step alarm stream: 50% is 3.5 steps -> step 4, gain 0.875; 55% -> step 4, gain 0.9625
        assertEquals(4, AlertSound.gainStep(3.5f));
        assertEquals(0.875f, AlertSound.gain(3.5f, 4), 1e-6);
        assertEquals(4, AlertSound.gainStep(7 * 0.55f));
        assertEquals(0.9625f, AlertSound.gain(7 * 0.55f, 4), 1e-4);
        // full volume: the top step, no reduction
        assertEquals(7, AlertSound.gainStep(7f));
        assertEquals(1f, AlertSound.gain(7f, 7), 1e-6);
        // 1% of 7 steps: step 1, turned well down
        assertEquals(1, AlertSound.gainStep(0.07f));
        assertEquals(0.07f, AlertSound.gain(0.07f, 1), 1e-6);
        // 5% of 7 steps: step 1, turned down
        assertEquals(1, AlertSound.gainStep(0.35f));
        assertEquals(0.35f, AlertSound.gain(0.35f, 1), 1e-6);
        // stream left at a lower step (e.g. Do Not Disturb refused the change): no boost
        assertEquals(1f, AlertSound.gain(5f, 3), 1e-6);
    }

    @Test
    public void eachAlertPlaysAndTestsAtItsOwnVolume() {
        importExport();
        Alerts.update(app, Alerts.config(app).withRule(Alerts.config(app).rule("low-90").withVolume(35)), "volume");
        storeReading(System.currentTimeMillis(), 80);
        assertEquals(AlertEngine.Action.PLAY, Alerts.checkNow(app, "test").action);
        assertEquals("alert low-90 volume 35%", noise.calls.get(noise.calls.size() - 1));
        Alerts.test(app, Alerts.config(app).rule("high-180").withVolume(70));
        idleAlertThread();
        assertEquals("test high-180 70%", noise.calls.get(noise.calls.size() - 1));
    }

    // ---------------------------------------------------------------- the alarm screen

    @Test
    public void alarmOverlayCoversTheScreenOnlyWhenAllowedOverOtherApps() {
        importExport();
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false);
        storeReading(System.currentTimeMillis(), 80);
        Alerts.checkNow(app, "test");
        shadowOf(android.os.Looper.getMainLooper()).idle();
        assertFalse(AlarmOverlay.isShowing());

        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true);
        makeRepeatDue();
        Alerts.checkNow(app, "test");
        shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue(AlarmOverlay.isShowing());
        final String all = texts(AlarmOverlay.panelForTests());
        assertTrue(all, all.contains("LOW WARNING"));
        assertTrue(all, all.contains("90 min"));

        // its big button snoozes, and it goes
        findButton(AlarmOverlay.panelForTests(), "90 min").performClick();
        shadowOf(android.os.Looper.getMainLooper()).idle();
        assertFalse(AlarmOverlay.isShowing());
        idleAlertThread();
        assertTrue(Alerts.active(app).snoozed);
        assertNull(nm.getNotification(Alerts.NOTIFICATION_ID));
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false);
    }

    @Test
    public void alarmOverlaySnoozesWithAVolumeButtonAndGoesWhenTheAlertStops() {
        importExport();
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true);
        storeReading(System.currentTimeMillis(), 80);
        Alerts.checkNow(app, "test");
        shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue(AlarmOverlay.isShowing());
        AlarmOverlay.panelForTests().dispatchKeyEvent(
                new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_UP));
        shadowOf(android.os.Looper.getMainLooper()).idle();
        assertFalse(AlarmOverlay.isShowing());
        idleAlertThread();
        assertTrue(Alerts.active(app).snoozed);

        // up again at the next repeat; back in range takes it away
        makeRepeatDue();
        Alerts.checkNow(app, "test");
        shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue(AlarmOverlay.isShowing());
        storeReading(System.currentTimeMillis() + 1000, 120);
        Alerts.checkNow(app, "test");
        shadowOf(android.os.Looper.getMainLooper()).idle();
        assertFalse(AlarmOverlay.isShowing());
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false);
    }

    @Test
    public void alarmScreenSnoozesWithItsBigButton() {
        importExport();
        storeReading(System.currentTimeMillis(), 80);
        Alerts.checkNow(app, "test");
        final ActivityController<AlertScreenActivity> c = Robolectric.buildActivity(AlertScreenActivity.class).setup();
        final String all = texts(c.get().getWindow().getDecorView());
        assertTrue(all, all.contains("LOW WARNING"));
        assertTrue(all, all.contains("80"));
        assertTrue(all, all.contains("90 min"));
        final android.widget.Button big = findButton(c.get().getWindow().getDecorView(), "90 min");
        big.performClick();
        assertTrue(c.get().isFinishing());
        idleAlertThread();
        assertTrue(Alerts.active(app).snoozed);
        assertTrue(Alerts.active(app).nextAlertAt > System.currentTimeMillis() + 89 * 60_000L);
        assertNull(nm.getNotification(Alerts.NOTIFICATION_ID));
    }

    @Test
    public void alarmScreenSnoozesWithAVolumeKeyOrAQuickChoice() {
        importExport();
        storeReading(System.currentTimeMillis(), 80);
        Alerts.checkNow(app, "test");
        ActivityController<AlertScreenActivity> c = Robolectric.buildActivity(AlertScreenActivity.class).setup();
        assertTrue(c.get().onKeyDown(android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
                new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_DOWN)));
        assertTrue(c.get().isFinishing());
        idleAlertThread();
        assertTrue(Alerts.active(app).snoozed);

        makeRepeatDue();
        Alerts.checkNow(app, "test");
        c = Robolectric.buildActivity(AlertScreenActivity.class).setup();
        findButton(c.get().getWindow().getDecorView(), "30 min").performClick();
        idleAlertThread();
        final long left = Alerts.active(app).nextAlertAt - System.currentTimeMillis();
        assertTrue(left > 29 * 60_000L && left <= 30 * 60_000L);
    }

    @Test
    public void alarmSnoozeChoicesTopToBottomWithNinetyAsTheBigOne() {
        importExport();
        storeReading(System.currentTimeMillis(), 80);
        Alerts.checkNow(app, "test");
        final ActivityController<AlertScreenActivity> c = Robolectric.buildActivity(AlertScreenActivity.class).setup();
        final String all = texts(c.get().getWindow().getDecorView());
        int at = -1;
        for (String l : new String[]{"30 min", "60 min", "90 min", "2 h", "2.5 h", "3 h"}) {
            final int i = all.indexOf(l, at + 1);
            assertTrue(l + " in order: " + all, i > at);
            at = i;
        }
        final android.widget.Button big = findButton(c.get().getWindow().getDecorView(), "90 min");
        final android.widget.Button small = findButton(c.get().getWindow().getDecorView(), "60 min");
        assertTrue(big.getTextSize() > small.getTextSize());

        // a volume key gives the big one too
        assertTrue(c.get().onKeyDown(android.view.KeyEvent.KEYCODE_VOLUME_UP,
                new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_UP)));
        idleAlertThread();
        final long left = Alerts.active(app).nextAlertAt - System.currentTimeMillis();
        assertTrue(String.valueOf(left), left > 89 * 60_000L && left <= 90 * 60_000L);
    }

    @Test
    public void untilWakeIsTheNextSixThirty() {
        final java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(2026, 10, 18, 23, 0, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        assertEquals(7 * 60 + 30, AlarmPanel.minutesUntil(AlarmPanel.WAKE_MINUTE_OF_DAY, cal.getTimeInMillis()));
        cal.set(java.util.Calendar.HOUR_OF_DAY, 5);
        assertEquals(90, AlarmPanel.minutesUntil(AlarmPanel.WAKE_MINUTE_OF_DAY, cal.getTimeInMillis()));
        cal.set(java.util.Calendar.HOUR_OF_DAY, 7);
        assertEquals(23 * 60 + 30, AlarmPanel.minutesUntil(AlarmPanel.WAKE_MINUTE_OF_DAY, cal.getTimeInMillis()));
        assertEquals("90 min", AlarmPanel.label(90));
        assertEquals("2.5 h", AlarmPanel.label(150));
        assertEquals("3 h", AlarmPanel.label(180));
    }

    @Test
    public void alarmScreenClosesWhenNoAlertIsGoingOn() {
        importExport();
        final ActivityController<AlertScreenActivity> c = Robolectric.buildActivity(AlertScreenActivity.class).setup();
        shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue(c.get().isFinishing());
    }

    // ---------------------------------------------------------------- the Alerts tab's bridge

    @Test
    public void alertsTabReadsEverything() throws Exception {
        importExport();
        storeReading(System.currentTimeMillis(), 80);
        Alerts.checkNow(app, "test");
        final org.json.JSONObject st = new org.json.JSONObject(AlertsBridge.state(app));
        assertEquals(2, st.getJSONObject("config").getJSONArray("alerts").length());
        assertEquals("low-90", st.getJSONObject("active").getString("uuid"));
        assertEquals(80, st.getJSONObject("last").getInt("mgdl"));
        assertEquals(30, st.getJSONArray("snooze_choices").getInt(0));
        assertEquals(300, st.getJSONArray("snooze_choices").getInt(st.getJSONArray("snooze_choices").length() - 1));
        assertEquals("Phone alarm", st.getJSONObject("sounds").getJSONObject("low-90").getString("name"));
        assertTrue(st.getJSONObject("perms").has("overlay"));
        assertTrue(st.getJSONObject("perms").has("dnd"));
    }

    @Test
    public void alertsTabAddsEditsAndRemovesAlerts() {
        importExport();
        final boolean[] done = {false};
        assertNull(AlertsBridge.saveRule(app, "{\"uuid\":\"\",\"name\":\"Urgent\",\"active\":true,\"above\":false,"
                + "\"threshold\":60,\"all_day\":true,\"minutes_between\":1,\"default_snooze\":20,\"vibrate\":true,"
                + "\"override_silent_mode\":true,\"force_speaker\":true,\"volume_percent\":80}", () -> done[0] = true));
        idleAlertThread();
        assertTrue(done[0]);
        AlertRule urgent = null;
        for (AlertRule r : Alerts.config(app).rules) if ("Urgent".equals(r.name)) urgent = r;
        assertNotNull(urgent);
        assertFalse(urgent.uuid.isEmpty());
        assertEquals(80, urgent.volumePercent);
        assertEquals(3, Alerts.config(app).rules.size());

        // what can't be saved says why, and nothing changes
        assertNotNull(AlertsBridge.saveRule(app, "{\"uuid\":\"x\",\"threshold\":20}", null));
        assertNotNull(AlertsBridge.saveRule(app, "not json", null));

        AlertsBridge.removeRule(app, urgent.uuid, null);
        idleAlertThread();
        assertEquals(2, Alerts.config(app).rules.size());
    }

    @Test
    public void alertsTabChangesOptionsSnoozesAndTurnsOff() {
        importExport();
        assertNull(AlertsBridge.saveSettings(app, "{\"bg_alert_profile\":\"ascending\",\"smart_snoozing\":true}", null));
        idleAlertThread();
        assertEquals(AlertSettings.ASCENDING, Alerts.config(app).settings.profile);
        assertTrue(Alerts.config(app).settings.smartSnoozing);

        storeReading(System.currentTimeMillis(), 80);
        Alerts.checkNow(app, "test");
        AlertsBridge.snooze(app, 0, null);
        idleAlertThread();
        assertTrue(Alerts.active(app).snoozed);

        AlertsBridge.disable(app, "high", 30, null);
        idleAlertThread();
        assertTrue(Alerts.config(app).settings.highDisabledUntil > System.currentTimeMillis());
        AlertsBridge.disable(app, "nonsense", 30, null);
        idleAlertThread();
        assertEquals(0, Alerts.config(app).settings.lowDisabledUntil);
    }

    @Test
    public void unsnoozeSoundsAgainIfStillOutOfRange() {
        importExport();
        storeReading(System.currentTimeMillis(), 80);
        Alerts.checkNow(app, "test");
        AlertsBridge.snooze(app, 0, null);
        idleAlertThread();
        assertTrue(Alerts.active(app).snoozed);
        assertNull(nm.getNotification(Alerts.NOTIFICATION_ID));

        // when it was snoozed reaches the tab (it goes back to Now 2 minutes later if left alone)
        final long at = Alerts.snoozedAt(app);
        assertTrue(Math.abs(System.currentTimeMillis() - at) < 10_000);
        assertTrue(AlertsBridge.state(app).contains("\"snoozed_at\":" + at));

        AlertsBridge.unsnooze(app, null);
        idleAlertThread();
        assertFalse(Alerts.active(app).snoozed);
        assertNotNull(nm.getNotification(Alerts.NOTIFICATION_ID));
        assertEquals("alert low-90 volume 50%", noise.calls.get(noise.calls.size() - 1));

        // a snooze set before it fired can be cancelled the same way
        AlertsBridge.preSnooze(app, "high-180", 30, null);
        idleAlertThread();
        AlertsBridge.unsnooze(app, null);
        idleAlertThread();
        assertFalse(Alerts.active(app).snoozed);
    }

    @Test
    public void eachAlertTurnsOffAndBackOnFromTheList() {
        importExport();
        AlertsBridge.setActive(app, "low-90", false, null);
        idleAlertThread();
        assertFalse(Alerts.config(app).rule("low-90").active);
        storeReading(System.currentTimeMillis(), 80);
        assertEquals(AlertEngine.Action.STOP, Alerts.checkNow(app, "test").action);
        AlertsBridge.setActive(app, "low-90", true, null);
        idleAlertThread();
        assertTrue(Alerts.config(app).rule("low-90").active);
        assertNotNull(nm.getNotification(Alerts.NOTIFICATION_ID));
    }

    // ---------------------------------------------------------------- adb only

    @Test
    public void adbControlsNeedTheTokenAdbLeft() throws Exception {
        importExport();
        storeReading(System.currentTimeMillis(), 80);
        Alerts.checkNow(app, "test");
        final java.io.File dir = app.getExternalFilesDir(null);
        dir.mkdirs();
        java.nio.file.Files.write(new java.io.File(dir, Automation.TOKEN_FILE).toPath(),
                "0123456789abcdef0123\n".getBytes(StandardCharsets.UTF_8));
        final AutomationReceiver r = new AutomationReceiver();
        r.onReceive(app, new Intent().putExtra("action", "alerts_snooze").putExtra("token", "0123456789abcdef9999"));
        idleAlertThread();
        assertFalse(Alerts.active(app).snoozed);
        r.onReceive(app, new Intent().putExtra("action", "alerts_snooze").putExtra("token", "0123456789abcdef0123"));
        idleAlertThread();
        assertTrue(Alerts.active(app).snoozed);

        java.nio.file.Files.write(new java.io.File(dir, "alerts.json").toPath(), AlertConfig.empty().toJson()
                .getBytes(StandardCharsets.UTF_8));
        r.onReceive(app, new Intent().putExtra("action", "alerts_config").putExtra("file", "alerts.json")
                .putExtra("token", "0123456789abcdef0123"));
        idleAlertThread();
        assertEquals(0, Alerts.config(app).rules.size());
        // only a plain file name in the app's own folder
        r.onReceive(app, new Intent().putExtra("action", "alerts_config").putExtra("file", "../x.json")
                .putExtra("token", "0123456789abcdef0123"));
        idleAlertThread();
    }

    @Test
    public void backFromTheEversenseScreenLandsOnMeanwhile() {
        final ActivityController<StatusActivity> c = Robolectric.buildActivity(StatusActivity.class).setup();
        c.get().onBackPressed();
        assertTrue(c.get().isFinishing());
        final Intent next = shadowOf(c.get()).getNextStartedActivity();
        if (c.get().isTaskRoot() && next != null && next.getComponent() != null
                && !next.getComponent().getClassName().equals(BridgeService.class.getName())) {
            assertEquals(MainActivity.class.getName(), next.getComponent().getClassName());
        }
        c.pause().stop().destroy();
    }

    private static android.widget.Button findButton(View v, String text) {
        if (v == null) return null;
        if (v instanceof android.widget.Button && text.contentEquals(((android.widget.Button) v).getText())) {
            return (android.widget.Button) v;
        }
        if (v instanceof ViewGroup) {
            final ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                final android.widget.Button b = findButton(g.getChildAt(i), text);
                if (b != null) return b;
            }
        }
        return null;
    }

    private static String texts(View v) {
        final StringBuilder sb = new StringBuilder();
        if (v instanceof TextView) sb.append(((TextView) v).getText()).append('\n');
        if (v instanceof ViewGroup) {
            final ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) sb.append(texts(g.getChildAt(i)));
        }
        return sb.toString();
    }
}
