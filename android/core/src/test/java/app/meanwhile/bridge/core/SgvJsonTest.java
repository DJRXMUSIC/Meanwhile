package app.meanwhile.bridge.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class SgvJsonTest {

    private static final long T = 1_790_000_000_000L;
    private static final long MIN = 60_000L;

    private static Reading r(long ts, int mgdl) {
        return new Reading(ts, mgdl, TimestampSource.NOTIFICATION_WHEN, ts, ts + 900, ts + 950, "p", String.valueOf(mgdl));
    }

    @Test
    public void xdripCompatibleFields() {
        final JSONArray a = new JSONArray(SgvJson.entries(
                Arrays.asList(r(T + 10 * MIN, 130), r(T + 5 * MIN, 120), r(T, 112)), 2, "mgdl"));
        assertEquals(2, a.length());

        final JSONObject first = a.getJSONObject(0);
        assertEquals(T + 10 * MIN, first.getLong("date"));
        assertEquals(130, first.getInt("sgv"));
        assertEquals(10.0, first.getDouble("delta"), 1e-9);
        assertEquals("FortyFiveUp", first.getString("direction"));
        assertEquals("sgv", first.getString("type"));
        assertEquals(Json.isoUtc(T + 10 * MIN), first.getString("dateString"));
        assertEquals("mgdl", first.getString("units_hint"));
        assertEquals(TimestampSource.NOTIFICATION_WHEN, first.getString("ts_source"));
        assertEquals(SgvJson.DEVICE, first.getString("device"));

        final JSONObject second = a.getJSONObject(1);
        // the extra (third) reading supplies this entry's delta even though it is not output
        assertEquals(8.0, second.getDouble("delta"), 1e-9);
        assertEquals("FortyFiveUp", second.getString("direction"));
        assertFalse(second.has("units_hint"));
    }

    @Test
    public void oldestEntryWithoutPredecessorIsNotComputable() {
        final JSONArray a = new JSONArray(SgvJson.entries(Collections.singletonList(r(T, 100)), 24, "mgdl"));
        assertEquals(1, a.length());
        assertTrue(a.getJSONObject(0).isNull("delta"));
        assertEquals(Trend.NOT_COMPUTABLE, a.getJSONObject(0).getString("direction"));
    }

    @Test
    public void empty() {
        assertEquals("[]", SgvJson.entries(Collections.<Reading>emptyList(), 24, "mgdl"));
    }
}
