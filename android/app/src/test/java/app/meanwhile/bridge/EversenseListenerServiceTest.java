package app.meanwhile.bridge;

import static app.meanwhile.bridge.TestNotifications.EV365;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.app.Notification;
import android.content.Context;
import android.widget.RemoteViews;

import org.robolectric.RuntimeEnvironment;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;

import java.util.List;

import app.meanwhile.bridge.core.CaptureEngine;
import app.meanwhile.bridge.core.Reading;
import app.meanwhile.bridge.core.TimestampSource;

@RunWith(RobolectricTestRunner.class)
public class EversenseListenerServiceTest {

    private Context ctx;
    private ServiceController<EversenseListenerService> controller;
    private EversenseListenerService service;
    private Bridge bridge;
    private long now;

    @Before
    public void setUp() {
        Bridge.resetForTests();
        ctx = RuntimeEnvironment.getApplication();
        bridge = Bridge.get(ctx);
        controller = Robolectric.buildService(EversenseListenerService.class).create();
        service = controller.get();
        now = System.currentTimeMillis();
    }

    @After
    public void tearDown() {
        controller.destroy();
        Bridge.resetForTests();
    }

    @Test
    public void customViewNotificationIsStoredWithReadingTime() {
        final long when = now - 1_400;
        service.onNotificationPosted(TestNotifications.sbn(EV365,
                TestNotifications.customView(ctx, "123", "mg/dL", when), now));

        final List<Reading> r = bridge.store.latest(10);
        assertEquals(1, r.size());
        assertEquals(123, r.get(0).mgdl);
        assertEquals(when, r.get(0).timestamp);
        assertEquals(TimestampSource.NOTIFICATION_WHEN, r.get(0).timestampSource);
        assertEquals(now, r.get(0).postTime);
        assertEquals(EV365, r.get(0).sourcePackage);
        assertEquals(CaptureEngine.Outcome.STORED, bridge.engine.log().last().outcome);
    }

    @Test
    public void viewTextsAreReadFromTheInflatedRemoteViews() {
        final Notification n = TestNotifications.customView(ctx, "7,4", "mmol/L", now);
        final List<String> texts = NotificationTexts.viewTexts(ctx, n);
        assertEquals(java.util.Arrays.asList("7,4", "mmol/L"), texts);
    }

    @Test
    public void styledTitleOnlyNotificationIsStored() {
        service.onNotificationPosted(TestNotifications.sbn(EV365,
                TestNotifications.extrasOnly(ctx, "118 mg/dL", now - 900), now));
        assertEquals(118, bridge.store.latest(1).get(0).mgdl);
    }

    @Test
    public void ongoingRefreshOfSameReadingIsNotDuplicated() {
        final Notification n = TestNotifications.customView(ctx, "140", "mg/dL", now - 1_000);
        service.onNotificationPosted(TestNotifications.sbn(EV365, n, now));
        service.onNotificationPosted(TestNotifications.sbn(EV365, n, now + 60_000));
        assertEquals(1, bridge.store.count());
        assertEquals(CaptureEngine.Outcome.REPOST, bridge.engine.log().last().outcome);
    }

    @Test
    public void otherAppsAreIgnoredEvenIfTheyShowNumbers() {
        service.onNotificationPosted(TestNotifications.sbn("com.whatsapp",
                TestNotifications.extrasOnly(ctx, "123", now), now));
        assertEquals(0, bridge.store.count());
        assertNull(bridge.engine.log().last());
        assertEquals(0, bridge.lastMonitoredPostAt());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void brokenNotificationsNeverCrashTheListener() {
        // a custom view whose layout cannot be inflated
        final Notification broken = TestNotifications.extrasOnly(ctx, "99 mg/dL", now);
        broken.contentView = new RemoteViews(ctx.getPackageName(), 0x7f0fffff);
        service.onNotificationPosted(TestNotifications.sbn(EV365, broken, now));
        // the title is still used when the custom view fails, and the failure is logged
        assertEquals(99, bridge.store.latest(1).get(0).mgdl);
        org.junit.Assert.assertNotNull(bridge.engine.log().last().input.viewError);

        final Notification empty = new Notification();
        empty.extras = null;
        service.onNotificationPosted(TestNotifications.sbn(EV365, empty, now + 60_000));
        service.onNotificationPosted(null);
        assertEquals(1, bridge.store.count());
    }

    @Test
    public void connectAndDisconnectAreSurvivable() {
        service.onListenerConnected();
        assertEquals(true, bridge.isListenerConnected());
        service.onListenerDisconnected();
        assertEquals(false, bridge.isListenerConnected());
    }
}
