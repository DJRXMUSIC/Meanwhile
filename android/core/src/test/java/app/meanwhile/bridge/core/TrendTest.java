package app.meanwhile.bridge.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TrendTest {

    private static final long T = 1_790_000_000_000L;
    private static final long MIN = 60_000L;

    private static Reading r(long ts, int mgdl) {
        return new Reading(ts, mgdl, TimestampSource.POST_TIME, 0, ts, ts, "p", "");
    }

    private static String dirPerMin(double mgdlPerMin) {
        return Trend.direction(mgdlPerMin / 60_000);
    }

    // thresholds from xDrip+ Dex_Constants.TREND_ARROW_VALUES.getTrend
    @Test
    public void directionThresholdsMatchXdrip() {
        assertEquals("NONE", dirPerMin(40.1));
        assertEquals("DoubleUp", dirPerMin(40));
        assertEquals("DoubleUp", dirPerMin(3.6));
        assertEquals("SingleUp", dirPerMin(3.5));
        assertEquals("SingleUp", dirPerMin(2.1));
        assertEquals("FortyFiveUp", dirPerMin(2));
        assertEquals("FortyFiveUp", dirPerMin(1.1));
        assertEquals("Flat", dirPerMin(1));
        assertEquals("Flat", dirPerMin(0));
        assertEquals("Flat", dirPerMin(-0.9));
        assertEquals("FortyFiveDown", dirPerMin(-1));
        assertEquals("FortyFiveDown", dirPerMin(-1.9));
        assertEquals("SingleDown", dirPerMin(-2));
        assertEquals("SingleDown", dirPerMin(-3.4));
        assertEquals("DoubleDown", dirPerMin(-3.5));
        assertEquals("DoubleDown", dirPerMin(-10));
        assertEquals(Trend.NOT_COMPUTABLE, Trend.direction(Double.NaN));
    }

    @Test
    public void slopeAndDelta() {
        double s = Trend.slope(r(T + 5 * MIN, 110), r(T, 100));
        assertEquals(2.0, s * MIN, 1e-9);
        assertEquals(10.0, Trend.delta5min(s), 1e-9);
        assertEquals("FortyFiveUp", Trend.direction(s));

        double down = Trend.slope(r(T + 5 * MIN, 85), r(T, 100));
        assertEquals(-15.0, Trend.delta5min(down), 1e-9);
        assertEquals("SingleDown", Trend.direction(down));

        assertEquals(0.0, Trend.slope(r(T + 5 * MIN, 100), r(T, 100)), 0);
    }

    @Test
    public void noSlopeAcrossGapsOrWithoutPrevious() {
        assertTrue(Double.isNaN(Trend.slope(r(T, 100), null)));
        assertTrue(Double.isNaN(Trend.slope(r(T + Trend.MAX_GAP_MS + 1, 100), r(T, 90))));
        assertTrue(Double.isNaN(Trend.slope(r(T, 100), r(T, 90))));
        assertEquals(1.0, Trend.slope(r(T + Trend.MAX_GAP_MS, 120), r(T, 100)) * Trend.MAX_GAP_MS / 20, 1e-9);
        assertTrue(Double.isNaN(Trend.delta5min(Double.NaN)));
    }

    @Test
    public void arrows() {
        assertEquals("↗", Trend.arrow("FortyFiveUp"));
        assertEquals("", Trend.arrow(Trend.NOT_COMPUTABLE));
    }
}
