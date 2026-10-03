package app.meanwhile.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Intent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.shadows.ShadowNotificationManager;

import app.meanwhile.bridge.core.Reading;
import app.meanwhile.bridge.core.TimestampSource;

@RunWith(RobolectricTestRunner.class)
public class BridgeServiceTest {

    private Application app;
    private ServiceController<BridgeService> controller;
    private ShadowNotificationManager nm;

    @Before
    public void setUp() {
        Bridge.resetForTests();
        app = RuntimeEnvironment.getApplication();
        nm = shadowOf(app.getSystemService(NotificationManager.class));
        controller = Robolectric.buildService(BridgeService.class).create().startCommand(0, 1);
    }

    @After
    public void tearDown() {
        controller.destroy();
        assertFalse(BridgeService.isRunning());
        Bridge.resetForTests();
    }

    private void storeReading(long ts, int mgdl) {
        Bridge.get(app).store.insert(new Reading(ts, mgdl, TimestampSource.POST_TIME, 0, ts, ts, "p", ""));
    }

    @Test
    public void runsInForegroundWithStatusNotification() {
        assertTrue(BridgeService.isRunning());
        final Notification fg = shadowOf(controller.get()).getLastForegroundNotification();
        assertNotNull(fg);
        assertEquals("Waiting for first Eversense reading",
                fg.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        assertTrue((fg.flags & Notification.FLAG_ONGOING_EVENT) != 0);
        assertNotNull(app.getSystemService(NotificationManager.class).getNotificationChannel(BridgeService.CHANNEL_STATUS));
        assertNotNull(app.getSystemService(NotificationManager.class).getNotificationChannel(BridgeService.CHANNEL_STALE));
        assertTrue(Bridge.get(app).server.isRunning());
    }

    @Test
    public void newReadingUpdatesStatusNotification() {
        storeReading(System.currentTimeMillis(), 142);
        BridgeService.refresh(app);
        final Notification n = nm.getNotification(BridgeService.STATUS_ID);
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_TITLE).toString().startsWith("142 mg/dL at "));
    }

    @Test
    public void staleDataRaisesOneAlertThenClearsWhenFresh() {
        final long now = System.currentTimeMillis();
        storeReading(now - 25 * 60_000L, 100);
        controller.get().tick();
        final Notification alert = nm.getNotification(BridgeService.STALE_ID);
        assertNotNull(alert);
        assertTrue(alert.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
                .startsWith("No Eversense reading for 25 min"));

        app.getSystemService(NotificationManager.class).cancelAll();
        controller.get().tick(); // rate limited: no second alert straight away
        assertNull(nm.getNotification(BridgeService.STALE_ID));

        storeReading(now, 110);
        controller.get().tick();
        assertNull(nm.getNotification(BridgeService.STALE_ID));
        assertNotNull(nm.getNotification(BridgeService.STATUS_ID));
    }

    @Test
    public void noAlertBeforeFirstReadingOrWhenDisabled() {
        controller.get().tick();
        assertNull(nm.getNotification(BridgeService.STALE_ID));

        final Bridge b = Bridge.get(app);
        b.settings.save(Settings.DEFAULT_PORT, false, "", "auto", "", false, 20);
        storeReading(System.currentTimeMillis() - 60 * 60_000L, 100);
        controller.get().tick();
        assertNull(nm.getNotification(BridgeService.STALE_ID));
    }

    @Test
    public void watchdogRestartsADeadEndpoint() {
        final Bridge b = Bridge.get(app);
        b.server.stop();
        assertFalse(b.server.isRunning());
        controller.get().tick();
        assertTrue(b.server.isRunning());
    }

    @Test
    public void startCommandIsSticky() {
        assertEquals(android.app.Service.START_STICKY,
                controller.get().onStartCommand(new Intent(app, BridgeService.class), 0, 2));
    }
}
