package app.meanwhile.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.robolectric.RuntimeEnvironment;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

import app.meanwhile.bridge.core.Reading;
import app.meanwhile.bridge.core.TimestampSource;

@RunWith(RobolectricTestRunner.class)
public class SqliteReadingStoreTest {

    private static final long T = 1_790_000_000_000L;
    private static final long DAY = 86_400_000L;
    private SqliteReadingStore store;

    @Before
    public void setUp() {
        store = new SqliteReadingStore(RuntimeEnvironment.getApplication(), "test-readings.db");
    }

    @After
    public void tearDown() {
        store.close();
    }

    private static Reading r(long ts, int mgdl) {
        return new Reading(ts, mgdl, TimestampSource.NOTIFICATION_WHEN, ts, ts + 1, ts + 2, "pkg", mgdl + " mg/dL");
    }

    @Test
    public void roundTripNewestFirst() {
        store.insert(r(T, 100));
        store.insert(r(T + 600_000, 120));
        store.insert(r(T + 300_000, 110));
        final List<Reading> latest = store.latest(2);
        assertEquals(2, latest.size());
        assertEquals(120, latest.get(0).mgdl);
        assertEquals(110, latest.get(1).mgdl);
        final Reading a = latest.get(0);
        assertEquals(T + 600_000, a.timestamp);
        assertEquals(TimestampSource.NOTIFICATION_WHEN, a.timestampSource);
        assertEquals(T + 600_000, a.notificationWhen);
        assertEquals(T + 600_001, a.postTime);
        assertEquals(T + 600_002, a.receivedAt);
        assertEquals("pkg", a.sourcePackage);
        assertEquals("120 mg/dL", a.rawText);
        assertEquals(3, store.count());
    }

    @Test
    public void existsWithinIsInclusive() {
        store.insert(r(T, 100));
        assertTrue(store.existsWithin(T + 250_000, 250_000));
        assertTrue(store.existsWithin(T - 250_000, 250_000));
        assertFalse(store.existsWithin(T + 250_001, 250_000));
    }

    @Test
    public void oldReadingsArePruned() {
        store.insert(r(T - (SqliteReadingStore.RETENTION_DAYS + 1) * DAY, 90));
        store.insert(r(T - 2 * DAY, 95));
        store.insert(r(T, 100)); // first insert after a day triggers the prune
        assertEquals(2, store.count());
    }

    @Test
    public void survivesReopen() {
        store.insert(r(T, 100));
        store.close();
        store = new SqliteReadingStore(RuntimeEnvironment.getApplication(), "test-readings.db");
        assertEquals(100, store.latest(1).get(0).mgdl);
    }
}
