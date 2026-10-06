package app.meanwhile.bridge.core;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class JsonTest {

    @Test
    public void escapingAndTypesRoundTrip() {
        final String s = new Json().beginArray()
                .beginObject()
                .field("q", "he said \"hi\"\\\n\t\u0001 ")
                .field("n", 42L)
                .field("d", 2.5)
                .field("whole", 3.0)
                .field("nan", Double.NaN)
                .field("b", true)
                .name("nested").beginArray().value(1L).value("x").endArray()
                .endObject()
                .beginObject().endObject()
                .endArray().toString();
        final JSONArray a = new JSONArray(s);
        final JSONObject o = a.getJSONObject(0);
        assertEquals("he said \"hi\"\\\n\t\u0001 ", o.getString("q"));
        assertEquals(42, o.getInt("n"));
        assertEquals(2.5, o.getDouble("d"), 0);
        assertEquals("3", o.get("whole").toString());
        assertEquals(true, o.isNull("nan"));
        assertEquals(true, o.getBoolean("b"));
        assertEquals("x", o.getJSONArray("nested").getString(1));
        assertEquals(0, a.getJSONObject(1).length());
    }

    @Test
    public void isoUtc() {
        assertEquals("2026-10-03T13:01:02.345Z", Json.isoUtc(1_791_032_462_345L));
    }
}
