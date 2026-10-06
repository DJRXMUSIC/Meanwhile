package app.meanwhile.bridge.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class TimestampResolverTest {

    private static final String PKG = "com.senseonics.eversense365.us";
    private static final long T = 1_790_000_000_000L;
    private static final long MIN = 60_000L;

    private TimestampResolver r;

    @Before
    public void setUp() {
        r = new TimestampResolver(new MemoryKeyValueStore());
    }

    @Test
    public void usesWhenWhenItTracksReadings() {
        TimestampResolver.Resolution a = r.resolve(PKG, T - 1_500, T, T + 20, 120);
        assertEquals(T - 1_500, a.timestamp);
        assertEquals(TimestampSource.NOTIFICATION_WHEN, a.source);
        assertFalse(a.repost);

        TimestampResolver.Resolution b = r.resolve(PKG, T + 5 * MIN - 900, T + 5 * MIN, T + 5 * MIN + 15, 125);
        assertEquals(T + 5 * MIN - 900, b.timestamp);
        assertEquals(TimestampSource.NOTIFICATION_WHEN, b.source);
    }

    @Test
    public void sameWhenSameValueIsRepost() {
        r.resolve(PKG, T - 1_000, T, T, 120);
        TimestampResolver.Resolution again = r.resolve(PKG, T - 1_000, T + MIN, T + MIN, 120);
        assertTrue(again.repost);
        assertEquals(T - 1_000, again.timestamp);
    }

    @Test
    public void sameWhenDifferentValueMarksWhenUnreliableForGood() {
        r.resolve(PKG, T, T, T, 120);
        TimestampResolver.Resolution b = r.resolve(PKG, T, T + 5 * MIN, T + 5 * MIN, 125);
        assertEquals(T + 5 * MIN, b.timestamp);
        assertEquals(TimestampSource.POST_TIME, b.source);
        assertTrue(r.isWhenUnreliable(PKG));

        // even a fresh-looking when is ignored afterwards
        TimestampResolver.Resolution c = r.resolve(PKG, T + 10 * MIN - 500, T + 10 * MIN, T + 10 * MIN, 130);
        assertEquals(T + 10 * MIN, c.timestamp);
        assertEquals(TimestampSource.POST_TIME, c.source);
        assertFalse(r.isWhenUnreliable("some.other.pkg"));
    }

    @Test
    public void staleOrFutureWhenFallsBackToPostTime() {
        TimestampResolver.Resolution stale = r.resolve(PKG, T - 3 * 60 * MIN, T, T + 10, 120);
        assertEquals(T, stale.timestamp);
        assertEquals(TimestampSource.POST_TIME, stale.source);

        TimestampResolver.Resolution future = r.resolve(PKG, T + 2 * MIN, T, T, 121);
        assertEquals(T, future.timestamp);

        TimestampResolver.Resolution none = r.resolve(PKG, 0, T, T, 122);
        assertEquals(T, none.timestamp);
        assertFalse(r.isWhenUnreliable(PKG));
    }

    @Test
    public void windowEdges() {
        assertEquals(TimestampSource.NOTIFICATION_WHEN,
                r.resolve(PKG, T - TimestampResolver.WHEN_MAX_AGE_MS, T, T, 100).source);
        assertEquals(TimestampSource.NOTIFICATION_WHEN,
                r.resolve("p2", T + TimestampResolver.WHEN_MAX_FUTURE_MS, T, T, 100).source);
        assertEquals(TimestampSource.POST_TIME,
                r.resolve("p3", T - TimestampResolver.WHEN_MAX_AGE_MS - 1, T, T, 100).source);
    }

    @Test
    public void noPostTimeUsesReceived() {
        TimestampResolver.Resolution x = r.resolve(PKG, 0, 0, T + 42, 120);
        assertEquals(T + 42, x.timestamp);
        assertEquals(TimestampSource.RECEIVED, x.source);
    }
}
