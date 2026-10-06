package app.meanwhile.bridge;

import static app.meanwhile.bridge.TestNotifications.EV365;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.provider.Settings.Secure;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;

import java.io.File;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicReference;

/** Event log, diagnostics report, log export and self-test. */
@RunWith(RobolectricTestRunner.class)
public class DiagnosticsTest {

    private Application app;
    private ServiceController<EversenseListenerService> listener;

    @Before
    public void setUp() throws Exception {
        Bridge.resetForTests();
        app = RuntimeEnvironment.getApplication();
        EventLog.clear(app);
        final int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        new Settings(app).save(port, false, "hunter2-secret", "auto", "", true, 20);
        assertTrue(Bridge.get(app).ensureServer());
        listener = Robolectric.buildService(EversenseListenerService.class).create();
    }

    @After
    public void tearDown() {
        listener.destroy();
        Bridge.resetForTests();
    }

    @Test
    public void eventLogPersistsAndRotates() {
        EventLog.log(app, "T", "first line");
        assertTrue(EventLog.readAll(app).contains("[T] first line"));
        final StringBuilder big = new StringBuilder();
        for (int i = 0; i < 1000; i++) big.append('x');
        for (int i = 0; i < 2300; i++) EventLog.log(app, "FILL", big.toString());
        EventLog.log(app, "T", "last line");
        final File dir = new File(app.getFilesDir(), "logs");
        assertTrue(new File(dir, "events.1.log").exists());
        assertTrue(new File(dir, "events.log").length() < EventLog.MAX_BYTES);
        final String all = EventLog.readAll(app);
        assertTrue(all.endsWith("[T] last line\n"));
        assertFalse("oldest generation dropped", all.contains("first line"));
        EventLog.clear(app);
        assertEquals("", EventLog.readAll(app));
    }

    @Test
    public void captureEventsAreLoggedWithRawTexts() {
        final long now = System.currentTimeMillis();
        listener.get().onNotificationPosted(TestNotifications.sbn(EV365,
                TestNotifications.customView(app, "123", "mg/dL", now - 900), now));
        final String log = EventLog.readAll(app);
        assertTrue(log, log.contains("[CAPTURE] STORED 123 mg/dL"));
        assertTrue(log, log.contains("views=[123, mg/dL]"));
        assertTrue(log, log.contains("(notification_when)"));
    }

    @Test
    public void reportHasEverySectionAndNeverTheSecret() {
        final long now = System.currentTimeMillis();
        listener.get().onListenerConnected();
        listener.get().onNotificationPosted(TestNotifications.sbn(EV365,
                TestNotifications.customView(app, "142", "mg/dL", now - 700), now));
        final String r = DiagnosticsReport.build(app);
        for (String section : new String[]{"===== Eversense Bridge diagnostics", "===== Health", "===== Settings",
                "===== Eversense app", "===== Active notifications (raw)", "===== Readings", "===== Capture log",
                "===== status.json", "===== Event log (persistent)", "===== Logcat", "===== end"}) {
            assertTrue("missing " + section, r.contains(section));
        }
        assertTrue(r, r.contains("  142  notification_when"));
        assertTrue(r, r.contains("[LISTENER] connected"));
        assertTrue(r, r.contains("api secret: (set, 14 chars)"));
        assertFalse("secret leaked", r.contains("hunter2"));
    }

    @Test
    public void rawNotificationDumpShowsExtrasTextsAndParse() {
        final long now = System.currentTimeMillis();
        final StringBuilder sb = new StringBuilder();
        DiagnosticsReport.dumpNotification(sb, app, TestNotifications.sbn(EV365,
                TestNotifications.customView(app, "123", "mg/dL", now - 500), now));
        final String d = sb.toString();
        assertTrue(d, d.contains("package/id/tag: " + EV365));
        assertTrue(d, d.contains("when: " + (now - 500)));
        assertTrue(d, d.contains("contentView: present"));
        assertTrue(d, d.contains("texts read: views=[123, mg/dL]"));
        assertTrue(d, d.contains("parse result: 123 mg/dL"));
        assertTrue(d, d.contains("extra android."));
    }

    @Test
    public void logExportWritesTheReport() throws Exception {
        final LogExport.Saved saved = LogExport.save(app);
        assertNotNull(saved.where);
        assertTrue(saved.bytes > 1000);
        assertTrue(saved.where.contains("eversense-bridge-log-"));
        assertTrue(EventLog.readAll(app).contains("[EXPORT] saved"));
        assertNotNull(LogExport.shareIntent(app, saved));
    }

    @Test
    public void selfTestReportsEndpointAndMissingAccess() {
        final String report = SelfTest.runBlocking(app);
        assertTrue(report, report.startsWith("PROBLEMS FOUND"));
        assertTrue(report, report.contains("✓ Data endpoint answered"));
        assertTrue(report, report.contains("✗ Notification test skipped: notification access is off"));
        assertTrue(report, report.contains("✓ Storage OK"));
    }

    @Test
    public void selfTestListenerRoundTrip() throws Exception {
        Secure.putString(app.getContentResolver(), "enabled_notification_listeners",
                new ComponentName(app, EversenseListenerService.class).flattenToString());
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        final AtomicReference<String> report = new AtomicReference<>();
        final Thread t = new Thread(() -> report.set(SelfTest.runBlocking(app)));
        t.start();
        // play the system's part: hand the posted test notification to the listener
        final NotificationManager nm = app.getSystemService(NotificationManager.class);
        Notification posted = null;
        final long deadline = System.currentTimeMillis() + 4_000;
        while (posted == null && System.currentTimeMillis() < deadline) {
            posted = shadowOf(nm).getNotification(SelfTest.NOTIFICATION_TAG, SelfTest.NOTIFICATION_ID);
            if (posted == null) Thread.sleep(20);
        }
        assertNotNull("self-test notification never posted", posted);
        listener.get().onNotificationPosted(TestNotifications.sbn(app.getPackageName(), SelfTest.NOTIFICATION_TAG,
                SelfTest.NOTIFICATION_ID, posted, System.currentTimeMillis()));
        t.join(10_000);
        assertTrue(report.get(), report.get().contains("✓ Listener received a test notification and parsed 123 mg/dL"));
        assertEquals("self-test must not store a reading", 0, Bridge.get(app).store.count());
        assertEquals(null, shadowOf(nm).getNotification(SelfTest.NOTIFICATION_TAG, SelfTest.NOTIFICATION_ID));
    }
}
