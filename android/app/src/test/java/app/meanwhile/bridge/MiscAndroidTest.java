package app.meanwhile.bridge;

import static app.meanwhile.bridge.TestNotifications.EV365;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.os.Looper;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;

import java.util.Arrays;
import java.util.LinkedHashSet;

import app.meanwhile.bridge.core.CaptureEngine;
import app.meanwhile.bridge.core.NotificationParser;
import app.meanwhile.bridge.core.Reading;
import app.meanwhile.bridge.core.TimestampSource;

@RunWith(RobolectricTestRunner.class)
public class MiscAndroidTest {

    private Application app;

    @Before
    public void setUp() {
        Bridge.resetForTests();
        app = RuntimeEnvironment.getApplication();
    }

    @After
    public void tearDown() {
        Bridge.resetForTests();
    }

    @Test
    public void bootAndUpdateStartTheKeepAliveService() {
        for (String action : Arrays.asList(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            new BootReceiver().onReceive(app, new Intent(action));
            final Intent started = shadowOf(app).getNextStartedService();
            assertNotNull(action, started);
            assertEquals(BridgeService.class.getName(), started.getComponent().getClassName());
        }
        new BootReceiver().onReceive(app, new Intent(Intent.ACTION_SCREEN_ON));
        assertEquals(null, shadowOf(app).getNextStartedService());
    }

    @Test
    public void settingsDefaultsAndValidation() {
        final Settings s = new Settings(app);
        assertEquals(17580, s.port());
        assertEquals(false, s.lan());
        assertEquals(NotificationParser.Units.AUTO, s.units());
        assertEquals(new LinkedHashSet<>(CaptureEngine.DEFAULT_PACKAGES), s.packages());
        assertEquals(20, s.staleMinutes());

        s.save(80, true, " pw ", "mmol", "com.senseonics.eversense365.eu, not a package,, com.x.y", false, 1);
        assertEquals(17580, s.port()); // privileged port rejected
        assertEquals(true, s.lan());
        assertEquals("pw", s.apiSecret());
        assertEquals(NotificationParser.Units.MMOL, s.units());
        assertEquals(new LinkedHashSet<>(Arrays.asList("com.senseonics.eversense365.eu", "com.x.y")), s.packages());
        assertEquals(20, s.staleMinutes());

        assertEquals(new LinkedHashSet<>(CaptureEngine.DEFAULT_PACKAGES), Settings.parsePackages("  "));
    }

    @Test
    public void appliedSettingsReachTheEngine() {
        new Settings(app).save(17580, false, "", "mgdl", "com.senseonics.eversense365.eu", true, 20);
        final Bridge b = Bridge.get(app);
        assertTrue(b.engine.isMonitored("com.senseonics.eversense365.eu"));
        assertEquals(false, b.engine.isMonitored(EV365));
    }

    @Test
    public void stateSurvivesProcessRestart() {
        final PrefsKeyValueStore a = new PrefsKeyValueStore(app);
        a.putLong("k", 42);
        assertEquals(42, new PrefsKeyValueStore(app).getLong("k", 0));
    }

    @Test
    public void mainActivityShowsLastReadingStatusAndSetupSteps() {
        final long now = System.currentTimeMillis();
        Bridge.get(app).store.insert(new Reading(now - 300_000, 124, TimestampSource.POST_TIME, 0, now, now, EV365, ""));
        Bridge.get(app).store.insert(new Reading(now - 1_000, 131, TimestampSource.NOTIFICATION_WHEN, now - 1_000, now, now, EV365, ""));

        final ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        shadowOf(Looper.getMainLooper()).idle();
        final String all = allText(c.get().findViewById(android.R.id.content));
        assertTrue(all, all.contains("LAST READING"));
        assertTrue(all, all.contains("131 ↗"));
        assertTrue(all, all.contains("time from Eversense"));
        assertTrue(all, all.contains("Notification access"));
        assertTrue(all, all.contains("Setup: "));
        assertTrue(all, all.contains("Turn on: Notification access"));
        assertTrue(all, all.contains("Stored: 2 readings"));
        assertTrue(all, all.contains("Run self-test"));
        assertTrue(all, all.contains("Save log file"));
        c.pause().stop().destroy();
    }

    @Test
    public void mainActivityWithNoReadingsSaysWaiting() {
        final ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        shadowOf(Looper.getMainLooper()).idle();
        final String all = allText(c.get().findViewById(android.R.id.content));
        assertTrue(all, all.contains("Waiting for the first reading"));
        assertTrue(all, all.contains("--"));
        c.pause().stop().destroy();
    }

    @Test
    public void exportLogAutomationIntentSavesAFile() throws Exception {
        final Intent i = new Intent(app, MainActivity.class).putExtra(MainActivity.EXTRA_ACTION, "export_log");
        final ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class, i).setup();
        final long deadline = System.currentTimeMillis() + 10_000;
        while (!EventLog.readAll(app).contains("[EXPORT] saved") && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(EventLog.readAll(app), EventLog.readAll(app).contains("[EXPORT] saved"));
        c.pause().stop().destroy();
    }

    private static String allText(android.view.View v) {
        if (v instanceof TextView) return ((TextView) v).getText() + "\n";
        final StringBuilder sb = new StringBuilder();
        if (v instanceof android.view.ViewGroup) {
            final android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) sb.append(allText(g.getChildAt(i)));
        }
        return sb.toString();
    }
}
