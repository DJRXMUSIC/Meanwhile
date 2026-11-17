package app.meanwhile.bridge.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class JsonReaderTest {

    @Test
    @SuppressWarnings("unchecked")
    public void parsesValues() {
        final Map<String, Object> m = (Map<String, Object>) JsonReader.parse(
                " {\"a\": [1, -2.5, 3e2, true, false, null], \"b\": {\"c\": \"x\\\"y\\\\z\\n\\u00e9\\/\"}, \"d\": NaN, \"e\": -Infinity } ");
        assertEquals(Arrays.asList(1.0, -2.5, 300.0, true, false, null), m.get("a"));
        assertEquals("x\"y\\z\né/", ((Map<String, Object>) m.get("b")).get("c"));
        assertTrue(Double.isNaN((Double) m.get("d")));
        assertEquals(Double.NEGATIVE_INFINITY, (Double) m.get("e"), 0);
        assertEquals(0, ((List<?>) JsonReader.parse("[]")).size());
        assertEquals(0, ((Map<?, ?>) JsonReader.parse("{}")).size());
        assertNull(JsonReader.parse("null"));
    }

    @Test
    public void rejectsBrokenJson() {
        for (String bad : new String[]{"", "{", "[1,", "{\"a\" 1}", "{a:1}", "\"open", "[1] x", "tru", "\"\\u12\"", "\"\\q\"", "1-2"}) {
            try {
                JsonReader.parse(bad);
                fail("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
        final StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 100; i++) deep.append('[');
        try {
            JsonReader.parse(deep.toString());
            fail("accepted deep nesting");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
