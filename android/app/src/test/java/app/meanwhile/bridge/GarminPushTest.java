package app.meanwhile.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Looper;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import app.meanwhile.bridge.core.Reading;
import app.meanwhile.bridge.core.TimestampSource;

/** Readings pushed to the watch face, with IOB, through a fake Garmin Connect. */
@RunWith(RobolectricTestRunner.class)
public class GarminPushTest {

    private static final long M = 60_000L;

    /** Stands in for the Connect IQ SDK: one watch, sends recorded, answers on demand. */
    static final class FakeTransport implements GarminPush.Transport {
        final List<Map<String, Object>> sent = new ArrayList<>();
        final List<Result> pending = new ArrayList<>();
        Events events;
        boolean connected = true;
        boolean autoOk = true;
        boolean started;

        @Override
        public void start(Context context, Events events) {
            started = true;
            this.events = events;
            events.onReady();
        }

        @Override
        public List<Object> connectedDevices() {
            return connected ? Collections.singletonList((Object) "Instinct E") : Collections.emptyList();
        }

        @Override
        public String name(Object device) {
            return (String) device;
        }

        @Override
        public void send(Object device, Map<String, Object> message, Result result) {
            sent.add(message);
            if (autoOk) result.done(true, "SUCCESS");
            else pending.add(result);
        }

        @Override
        public void stop(Context context) {
        }
    }

    private Application app;
    private Bridge bridge;
    private FakeTransport fake;
    private GarminPush push;

    @Before
    public void setUp() {
        Bridge.resetForTests();
        app = RuntimeEnvironment.getApplication();
        final PackageInfo gc = new PackageInfo();
        gc.packageName = GarminPush.GARMIN_CONNECT;
        shadowOf(app.getPackageManager()).installPackage(gc);
        bridge = Bridge.get(app);
        fake = new FakeTransport();
        push = new GarminPush(app, fake);
        GarminPush.setForTests(push);
    }

    @After
    public void tearDown() {
        GarminPush.setForTests(null);
        Bridge.resetForTests();
    }

    private void reading(long ts, int mgdl) {
        bridge.store.insert(new Reading(ts, mgdl, TimestampSource.NOTIFICATION_WHEN, ts, ts + 900, ts + 950,
                TestNotifications.EV365, String.valueOf(mgdl)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bg(Map<String, Object> message) {
        return ((List<Map<String, Object>>) message.get("bgs")).get(0);
    }

    @Test
    public void sendsTheNewestReadingWithDeltaAndIobOnce() {
        final long now = System.currentTimeMillis();
        reading(now - 5 * M - 2_000, 138);
        reading(now - 2_000, 142);
        assertEquals(null, bridge.setInsulin("{\"dia\":5.5,\"peak\":64,\"delay\":15,\"doses\":[{\"ts\":"
                + (now - 30 * M) + ",\"units\":3,\"kind\":\"bolus\"}]}"));

        push.start();
        assertTrue(fake.started);
        assertEquals(1, fake.sent.size());
        final Map<String, Object> b = bg(fake.sent.get(0));
        assertEquals("142", b.get("sgv"));
        assertEquals(4, b.get("bgdelta"));
        assertEquals(2.92f, (Float) b.get("iob"), 0.02f); // 3 U taken 30 min ago

        // nothing new: not sent again
        push.push("again");
        push.tick();
        assertEquals(1, fake.sent.size());

        // a new reading is sent straight away
        reading(now + 5 * M, 147);
        push.push("new reading");
        assertEquals(2, fake.sent.size());
        assertEquals("147", bg(fake.sent.get(1)).get("sgv"));
        assertEquals(5, bg(fake.sent.get(1)).get("bgdelta"));
    }

    @Test
    public void aFreshCopyFollowsEachDeliveredReadingOnce() {
        final long now = System.currentTimeMillis();
        reading(now - 1_000, 124);
        push.start();
        assertEquals(1, fake.sent.size());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(GarminPush.AGAIN_MS + 100));
        assertEquals(2, fake.sent.size());
        assertEquals("124", bg(fake.sent.get(1)).get("sgv"));
        // the copy is not followed by another
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(GarminPush.AGAIN_MS * 3));
        assertEquals(2, fake.sent.size());
        // nor sent twice when two readings land close together: one copy, of the newest
        reading(now + 2_000, 126);
        push.push("new reading");
        reading(now + 3_000, 128);
        push.push("new reading");
        assertEquals(4, fake.sent.size());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(GarminPush.AGAIN_MS + 100));
        assertEquals(5, fake.sent.size());
        assertEquals("128", bg(fake.sent.get(4)).get("sgv"));
    }

    @Test
    public void storingAReadingPushesIt() {
        push.start();
        final long now = System.currentTimeMillis();
        reading(now, 120);
        bridge.pushToWatch("new reading"); // what the capture engine's listener calls
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("120", bg(fake.sent.get(fake.sent.size() - 1)).get("sgv"));
    }

    @Test
    public void aFailedSendIsRetriedWithTheNewestReading() {
        final long now = System.currentTimeMillis();
        reading(now - 5 * M, 130);
        fake.autoOk = false;
        push.start();
        assertEquals(1, fake.sent.size());
        fake.pending.remove(0).done(false, "FAILURE_DURING_TRANSFER");

        // a newer reading arrives before the retry: the retry carries it, not the old one
        reading(now, 136);
        fake.autoOk = true;
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(GarminPush.RETRY_MS + 100));
        assertEquals(2, fake.sent.size());
        assertEquals("136", bg(fake.sent.get(1)).get("sgv"));
        assertTrue(push.status().contains("failed 1"));
    }

    @Test
    public void aWatchThatReconnectsGetsTheCurrentReadingAgain() {
        reading(System.currentTimeMillis(), 120);
        push.start();
        assertEquals(1, fake.sent.size());
        fake.events.onDeviceConnected("Instinct E");
        assertEquals(2, fake.sent.size());
    }

    @Test
    public void noWatchConnectedSendsNothingUntilOneIs() {
        fake.connected = false;
        reading(System.currentTimeMillis(), 120);
        push.start();
        assertEquals(0, fake.sent.size());
        assertTrue(push.status(), push.status().contains("no watch connected since"));
        fake.connected = true;
        push.tick();
        assertEquals(1, fake.sent.size());
        assertFalse(push.status(), push.status().contains("no watch connected"));
    }

    @Test
    public void withoutGarminConnectTheSdkIsNeverStarted() {
        shadowOf(app.getPackageManager()).deletePackage(GarminPush.GARMIN_CONNECT);
        final FakeTransport other = new FakeTransport();
        final GarminPush p = new GarminPush(app, other);
        p.start();
        assertFalse(other.started);
        assertTrue(p.status().contains("Garmin Connect is not installed"));
    }

    @Test
    public void pebbleEndpointCarriesIobAndBadReportsAreRefused() throws Exception {
        final long now = System.currentTimeMillis();
        reading(now, 120);
        assertNull(new JSONObject(bridge.routes.pebble()).getJSONArray("bgs").getJSONObject(0).opt("iob"));
        assertTrue(bridge.setInsulin("nonsense").startsWith("bad insulin report"));
        assertEquals(null, bridge.setInsulin("{\"doses\":[{\"ts\":" + now + ",\"units\":2,\"kind\":\"bolus\"}]}"));
        assertEquals(2.0, new JSONObject(bridge.routes.pebble()).getJSONArray("bgs").getJSONObject(0).getDouble("iob"), 1e-9);

        // the report survives an app restart
        Bridge.resetForTests();
        assertEquals(2.0, Bridge.get(app).iob(now + 60_000), 1e-9);
    }
}
