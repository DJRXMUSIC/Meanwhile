package app.meanwhile.bridge.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.json.JSONObject;
import org.junit.Test;

public class PebbleJsonTest {

    private static final long T = 1_790_000_000_000L;
    private static final long MIN = 60_000L;

    private static Reading r(long ts, int mgdl) {
        return new Reading(ts, mgdl, TimestampSource.NOTIFICATION_WHEN, ts, ts + 900, ts + 950, "p", String.valueOf(mgdl));
    }

    @Test
    public void xdripCompatibleObjectInMgdl() {
        final JSONObject o = new JSONObject(PebbleJson.reply(
                Arrays.asList(r(T + 5 * MIN, 135), r(T, 120)), "mgdl", T + 6 * MIN));
        assertEquals(T + 6 * MIN, o.getJSONArray("status").getJSONObject(0).getLong("now"));
        assertEquals(1, o.getJSONArray("bgs").length());
        assertEquals(0, o.getJSONArray("cals").length());

        final JSONObject bg = o.getJSONArray("bgs").getJSONObject(0);
        assertEquals("135", bg.getString("sgv")); // a string, as in xDrip+
        assertEquals("SingleUp", bg.getString("direction")); // +15 in 5 min = 3/min
        assertEquals(PebbleJson.ordinal("SingleUp"), bg.getInt("trend"));
        assertEquals(T + 5 * MIN, bg.getLong("datetime"));
        assertEquals(15, bg.getLong("bgdelta"));
        assertEquals(TimestampSource.NOTIFICATION_WHEN, bg.getString("ts_source"));
    }

    @Test
    public void mmolUsesOneDecimalStrings() {
        final JSONObject bg = new JSONObject(PebbleJson.reply(
                Arrays.asList(r(T + 5 * MIN, 126), r(T, 120)), "mmol", T + 5 * MIN))
                .getJSONArray("bgs").getJSONObject(0);
        assertEquals("7.0", bg.getString("sgv"));
        assertEquals("0.3", bg.getString("bgdelta"));
        assertEquals("FortyFiveUp", bg.getString("direction"));
    }

    @Test
    public void singleReadingHasNoTrend() {
        final JSONObject bg = new JSONObject(PebbleJson.reply(
                Collections.singletonList(r(T, 100)), "mgdl", T))
                .getJSONArray("bgs").getJSONObject(0);
        assertEquals(Trend.NOT_COMPUTABLE, bg.getString("direction"));
        assertEquals(8, bg.getInt("trend"));
        assertTrue(bg.isNull("bgdelta"));
    }

    @Test
    public void noReadingsIsStillAnObject() {
        final JSONObject o = new JSONObject(PebbleJson.reply(Collections.<Reading>emptyList(), "mgdl", T));
        assertEquals(0, o.getJSONArray("bgs").length());
        assertEquals(T, o.getJSONArray("status").getJSONObject(0).getLong("now"));
    }

    @Test
    public void trendOrdinalsMatchXdrip() {
        assertEquals(1, PebbleJson.ordinal("DoubleUp"));
        assertEquals(4, PebbleJson.ordinal("Flat"));
        assertEquals(7, PebbleJson.ordinal("DoubleDown"));
        assertEquals(0, PebbleJson.ordinal("NONE"));
    }
}
