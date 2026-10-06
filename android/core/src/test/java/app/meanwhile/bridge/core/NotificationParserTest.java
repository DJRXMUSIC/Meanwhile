package app.meanwhile.bridge.core;

import static app.meanwhile.bridge.core.NotificationParser.Units.AUTO;
import static app.meanwhile.bridge.core.NotificationParser.Units.MGDL;
import static app.meanwhile.bridge.core.NotificationParser.Units.MMOL;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class NotificationParserTest {

    // ---- ported from xDrip+ UiBasedCollectorTest.isValidMmolTest ----
    @Test
    public void isValidMmol() {
        assertTrue(NotificationParser.isValidMmol("5.6"));
        assertTrue(NotificationParser.isValidMmol("5.55"));
        assertTrue(NotificationParser.isValidMmol("12.34"));

        assertFalse(NotificationParser.isValidMmol("555"));
        assertFalse(NotificationParser.isValidMmol("abc"));
        assertFalse(NotificationParser.isValidMmol("abc 12.34"));
        assertFalse(NotificationParser.isValidMmol("abc12.34"));
        assertFalse(NotificationParser.isValidMmol("12.34abc"));
        assertFalse(NotificationParser.isValidMmol("5..55"));
        assertFalse(NotificationParser.isValidMmol("5."));
        assertFalse(NotificationParser.isValidMmol(".5"));
        assertFalse(NotificationParser.isValidMmol("5"));
    }

    // ---- ported from xDrip+ UiBasedCollectorTest.filterStringTest (package-set branch) ----
    @Test
    public void filterString() {
        assertEquals("non spec", NotificationParser.filterString("non spec"));
        assertEquals("5.123", NotificationParser.filterString("≤5.123"));
        assertEquals("100.123", NotificationParser.filterString("≥100.123"));
        final String gc1 = "8,9 mmol⁠/⁠l ";
        assertEquals("8,9", NotificationParser.filterString(gc1));
        final String gc2 = "8,9 mmol⁠/⁠↑⬆l ";
        assertEquals("8,9", NotificationParser.filterString(gc2));
    }

    @Test(expected = IllegalArgumentException.class)
    public void filterUnicodeRangeRejectsInvertedRange() {
        NotificationParser.filterUnicodeRange("x", 'z', 'a');
    }

    @Test
    public void extractMgdlAuto() {
        assertEquals(123, NotificationParser.extractMgdl("123", AUTO));
        assertEquals(123, NotificationParser.extractMgdl("123 mg/dL", AUTO));
        assertEquals(123, NotificationParser.extractMgdl(" 123 mg/dl ", AUTO));
        assertEquals(123, NotificationParser.extractMgdl("123 ↗", AUTO));
        assertEquals(123, NotificationParser.extractMgdl("⇈123", AUTO));
        assertEquals(40, NotificationParser.extractMgdl("≤40", AUTO));
        assertEquals(130, NotificationParser.extractMgdl("7.2 mmol/L", AUTO)); // 7.2 * 18.0182 = 129.7
        assertEquals(160, NotificationParser.extractMgdl("8,9 mmol/l", AUTO));
        assertEquals(-1, NotificationParser.extractMgdl("mg/dL", AUTO));
        assertEquals(-1, NotificationParser.extractMgdl("10:32 AM", AUTO));
        assertEquals(-1, NotificationParser.extractMgdl("Glucose 123", AUTO));
        assertEquals(-1, NotificationParser.extractMgdl("5 min ago", AUTO));
        assertEquals(-1, NotificationParser.extractMgdl("123456", AUTO));
        assertEquals(-1, NotificationParser.extractMgdl("", AUTO));
        assertEquals(-1, NotificationParser.extractMgdl(null, AUTO));
    }

    @Test
    public void extractMgdlForcedUnitsMatchXdrip() {
        // xDrip mg/dL mode: Integer.parseInt of the filtered string
        assertEquals(123, NotificationParser.extractMgdl("123", MGDL));
        assertEquals(-1, NotificationParser.extractMgdl("7.2", MGDL));
        assertEquals(-1, NotificationParser.extractMgdl("99999999999", MGDL));
        // xDrip mmol mode: only decimals are accepted
        assertEquals(130, NotificationParser.extractMgdl("7.2", MMOL));
        assertEquals(-1, NotificationParser.extractMgdl("123", MMOL));
    }

    @Test
    public void unitsParse() {
        assertEquals(AUTO, NotificationParser.Units.parse(null));
        assertEquals(AUTO, NotificationParser.Units.parse("whatever"));
        assertEquals(MGDL, NotificationParser.Units.parse("mg/dL"));
        assertEquals(MMOL, NotificationParser.Units.parse(" MMOL "));
    }

    @Test
    public void customViewSingleValue() {
        final NotificationParser.Result r = NotificationParser.parse(
                Arrays.asList("Eversense", "123", "mg/dL", "10:32 AM"), null, AUTO);
        assertTrue(r.ok());
        assertEquals(123, r.mgdl);
        assertEquals("123", r.matchedText);
    }

    @Test
    public void customViewSameValueTwiceIsNotAmbiguous() {
        final NotificationParser.Result r = NotificationParser.parse(Arrays.asList("123", "123 mg/dL"), null, AUTO);
        assertTrue(r.ok());
        assertEquals(123, r.mgdl);
    }

    @Test
    public void customViewTwoDifferentValuesIsAmbiguous() {
        final NotificationParser.Result r = NotificationParser.parse(
                Arrays.asList("123", "118"), Collections.singletonList("123 mg/dL"), AUTO);
        assertFalse(r.ok());
        assertEquals(NotificationParser.Failure.AMBIGUOUS, r.failure);
    }

    @Test
    public void fallsBackToExtrasWhenViewHasNoValue() {
        final NotificationParser.Result r = NotificationParser.parse(
                Arrays.asList("Eversense", "Connected"), Arrays.asList("123 mg/dL", "Stable"), AUTO);
        assertTrue(r.ok());
        assertEquals(123, r.mgdl);
    }

    @Test
    public void extrasOnlyTitle() {
        final NotificationParser.Result r = NotificationParser.parse(null, Arrays.asList("6.8 mmol/L", null, ""), AUTO);
        assertTrue(r.ok());
        assertEquals(123, r.mgdl);
    }

    @Test
    public void noTextAndNoValue() {
        assertEquals(NotificationParser.Failure.NO_TEXT, NotificationParser.parse(null, null, AUTO).failure);
        assertEquals(NotificationParser.Failure.NO_TEXT,
                NotificationParser.parse(Collections.<String>emptyList(), Arrays.asList(null, " "), AUTO).failure);
        assertEquals(NotificationParser.Failure.NO_VALUE,
                NotificationParser.parse(Arrays.asList("Signal loss"), null, AUTO).failure);
    }

    @Test
    public void reportsLoHiMarker() {
        final NotificationParser.Result lo = NotificationParser.parse(Arrays.asList("LO", "mg/dL"), null, AUTO);
        assertEquals(NotificationParser.Failure.NO_VALUE, lo.failure);
        assertEquals("LO", lo.rangeMarker);
        final List<String> hi = Arrays.asList("HI ↑");
        assertEquals("HI", NotificationParser.parse(null, hi, AUTO).rangeMarker);
        assertNull(NotificationParser.parse(Arrays.asList("123"), null, AUTO).rangeMarker);
    }

    @Test
    public void smallNumbersCountAsMatchesLikeXdrip() {
        // xDrip counts any positive parse as a match, so "2" next to "123" is ambiguous rather
        // than silently picking one; the gate then rejects out-of-range single values.
        assertEquals(NotificationParser.Failure.AMBIGUOUS,
                NotificationParser.parse(Arrays.asList("123", "2"), null, AUTO).failure);
    }
}
