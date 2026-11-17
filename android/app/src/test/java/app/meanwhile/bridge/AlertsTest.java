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
        public void test(android.content.Context c, AlertRule rule) {
            calls.add("test " + rule.uuid);
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
    public void nothingSetUpMeansNoAlertAndNoTimer() {
        storeReading(System.currentTimeMillis(), 50);
        assertNull(Alerts.checkNow(app, "test"));
        assertNull(nm.getNotification(Alerts.NOTIFICATION_ID));
        assertNull(alarms.peekNextScheduledAlarm());
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
        assertEquals("alert low-90 volume 100%", noise.calls.get(noise.calls.size() - 1));

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
        assertNull(Alerts.active(app));
        assertEquals("stop", noise.calls.get(noise.calls.size() - 1));
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
        assertNull(Alerts.active(app));
        Alerts.disable(app, "low", 0);
        assertEquals(0, Alerts.config(app).settings.lowDisabledUntil);
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
        assertTrue(all, all.contains("LOW WARNING: 70 mg/dL"));
        assertTrue(all, all.contains("Snooze 60 min"));
        assertTrue(all, all.contains("Low: at or below 90 mg/dL · all day"));
        assertTrue(all, all.contains("Repeats every 1 min · snooze 60 min"));
        assertTrue(all, all.contains("High: at or above 180 mg/dL"));
        assertTrue(all, all.contains("Sound: the phone's alarm sound"));
        assertTrue(all, all.contains("High: full volume"));
        c.pause().stop().destroy();
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
