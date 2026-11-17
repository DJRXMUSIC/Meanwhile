package app.meanwhile.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import app.meanwhile.bridge.core.XdripAlertImport;

@RunWith(RobolectricTestRunner.class)
public class AlertsTest {

    /** An xDrip+ export with a low at 90 (snooze 60, silent-mode override) and a high at 180. */
    static final String EXPORT = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
            + "<string name=\"saved_alerts\">[{&quot;above&quot;:false,&quot;active&quot;:true,&quot;all_day&quot;:true,"
            + "&quot;default_snooze&quot;:60,&quot;force_speaker&quot;:true,&quot;minutes_between&quot;:1,"
            + "&quot;name&quot;:&quot;LOW WARNING&quot;,&quot;override_silent_mode&quot;:true,&quot;threshold&quot;:90.0,"
            + "&quot;uuid&quot;:&quot;low-90&quot;,&quot;vibrate&quot;:false},"
            + "{&quot;above&quot;:true,&quot;active&quot;:true,&quot;all_day&quot;:true,&quot;default_snooze&quot;:90,"
            + "&quot;force_speaker&quot;:true,&quot;minutes_between&quot;:3,&quot;name&quot;:&quot;Elevated&quot;,"
            + "&quot;override_silent_mode&quot;:false,&quot;threshold&quot;:180.0,&quot;uuid&quot;:&quot;high-180&quot;,"
            + "&quot;vibrate&quot;:true}]</string>\n"
            + "<string name=\"bg_alert_profile\">High</string>\n"
            + "<boolean name=\"smart_snoozing\" value=\"false\" />\n"
            + "<boolean name=\"smart_alerting\" value=\"false\" />\n"
            + "<long name=\"alerts_disabled_until\" value=\"99999999999999\" />\n"
            + "</map>\n";

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

    private void importExport() {
        Alerts.importFrom(app, XdripAlertImport.parse(EXPORT.getBytes(StandardCharsets.UTF_8)));
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
        assertEquals("Beep (built in)", AlertsActivity.soundName(app, AlertConfig.SOUND_BEEP));
        assertEquals("Vibration sound (built in)", AlertsActivity.soundName(app, AlertConfig.SOUND_VIBRATION));
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
    public void importTakesXdripsAlertsButNotItsSnoozes() {
        importExport();
        assertEquals(2, Alerts.config(app).rules.size());
        assertEquals(AlertSettings.HIGH, Alerts.config(app).settings.profile);
        assertEquals(0, Alerts.config(app).settings.allDisabledUntil);
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
    public void highAlertThatDoesNotOverrideSilentIsAPlainNotification() {
        importExport();
        storeReading(System.currentTimeMillis(), 200);
        assertEquals(AlertEngine.Action.PLAY, Alerts.checkNow(app, "test").action);
        final Notification n = nm.getNotification(Alerts.NOTIFICATION_ID);
        assertEquals("200 Elevated", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        assertEquals("Snooze 90 min", n.actions[0].title.toString());
        assertNull(n.fullScreenIntent);
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
        assertEquals(72, AlertsActivity.parseThreshold("4.0", "mmol"), 0);
        assertEquals(90, AlertsActivity.parseThreshold("90", "mgdl"), 0);
        assertEquals(-1, AlertsActivity.parseThreshold("x", "mgdl"), 0);
    }

    @Test
    public void alertsScreenShowsTheImportedAlerts() {
        importExport();
        storeReading(System.currentTimeMillis(), 70);
        Alerts.checkNow(app, "test");
        final ActivityController<AlertsActivity> c = Robolectric.buildActivity(AlertsActivity.class).setup();
        final String all = texts(c.get().getWindow().getDecorView());
        // laid out like xDrip+: Snooze, Glucose Level Alerts List, Glucose Alerts Settings
        for (String s : new String[]{"Snooze", "Active alert: LOW WARNING · 70 mg/dL", "for 1 hour ▾",
                "Disable Low alerts", "Disable High alerts", "Disable All alerts",
                "Glucose Level Alerts List", "Create low alert", "Create high alert", "LOW ALERTS", "HIGH ALERTS",
                "≤ 90 mg/dL · all day", "Re-raise every 1 min · Default Snooze 60 min", "≥ 180 mg/dL",
                "Alert Tone: the phone's alarm sound", "Glucose Alerts Settings", "Alert Volume Profile",
                "Alert volume: 50%", "Smart Snoozing", "Smart Alerting", "Don't alarm during phone calls",
                "Start Snoozed", "Suppress Alerts if missed readings", "Delay Ascending Volume", "Ascend to medium"}) {
            assertTrue(s + " in:\n" + all, all.contains(s));
        }
        c.pause().stop().destroy();
    }

    @Test
    public void volumeLevelSetsHowLoudAlertsAndTestsAre() {
        Alerts.update(app, Alerts.config(app).withSettings(Alerts.config(app).settings.withVolume(35)), "volume");
        storeReading(System.currentTimeMillis(), 80);
        assertEquals(AlertEngine.Action.PLAY, Alerts.checkNow(app, "test").action);
        assertEquals("alert 570e659e-467b-4990-94f7-b92425a11a3f volume 35%", noise.calls.get(noise.calls.size() - 1));
        Alerts.test(app, Alerts.config(app).rules.get(0));
        idleAlertThread();
        assertEquals("test 570e659e-467b-4990-94f7-b92425a11a3f 35%", noise.calls.get(noise.calls.size() - 1));
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
    public void automationIsIgnoredWhenNotFromAdb() {
        importExport();
        storeReading(System.currentTimeMillis(), 70);
        Alerts.checkNow(app, "test");
        final ActivityController<AlertsActivity> c = Robolectric.buildActivity(AlertsActivity.class,
                new Intent(app, AlertsActivity.class).putExtra("action", "snooze")).setup();
        idleAlertThread();
        assertFalse(Alerts.active(app).snoozed);
        c.pause().stop().destroy();
    }

    @Test
    public void automationWorksWithTheTokenAdbLeft() throws Exception {
        importExport();
        storeReading(System.currentTimeMillis(), 70);
        Alerts.checkNow(app, "test");
        final java.io.File dir = app.getExternalFilesDir(null);
        dir.mkdirs();
        java.nio.file.Files.write(new java.io.File(dir, AlertsActivity.AUTOMATION_TOKEN).toPath(),
                "0123456789abcdef0123\n".getBytes(StandardCharsets.UTF_8));
        final ActivityController<AlertsActivity> wrong = Robolectric.buildActivity(AlertsActivity.class,
                new Intent(app, AlertsActivity.class).putExtra("action", "snooze").putExtra("token", "0123456789abcdef9999")).setup();
        idleAlertThread();
        assertFalse(Alerts.active(app).snoozed);
        wrong.pause().stop().destroy();
        final ActivityController<AlertsActivity> right = Robolectric.buildActivity(AlertsActivity.class,
                new Intent(app, AlertsActivity.class).putExtra("action", "snooze").putExtra("token", "0123456789abcdef0123")).setup();
        idleAlertThread();
        assertTrue(Alerts.active(app).snoozed);
        right.pause().stop().destroy();
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
