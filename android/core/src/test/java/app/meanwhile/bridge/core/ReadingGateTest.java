package app.meanwhile.bridge.core;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

/**
 * Ported from xDrip+ UiBasedCollectorTest deDupeTest1..7 and the characterization tests,
 * with xDrip's handleNewValue(ts, mgdl) = check + insert.
 */
public class ReadingGateTest {

    private static final long SECOND = 1000L;
    private static final long MINUTE = 60 * SECOND;

    private MemoryReadingStore store;
    private ReadingGate gate;
    private long start;

    @Before
    public void setUp() {
        store = new MemoryReadingStore();
        gate = new ReadingGate(store, new MemoryKeyValueStore());
        start = 1_790_000_000_000L;
    }

    private boolean handleNewValue(long ts, int mgdl) {
        if (gate.check(ts, mgdl) != ReadingGate.Verdict.ACCEPT) return false;
        store.insert(new Reading(ts, mgdl, TimestampSource.POST_TIME, 0, ts, ts, "test", String.valueOf(mgdl)));
        return true;
    }

    // standard 5 minute apart readings are all accepted
    @Test
    public void deDupeTest1() {
        for (int i = 0; i < 50; i++) {
            assertEquals("i=" + i, true, handleNewValue(start + SECOND * 300 * i, i + 100));
        }
    }

    // differing 1 minute apart readings are all accepted
    @Test
    public void deDupeTest2() {
        for (int i = 0; i < 50; i++) {
            assertEquals("i=" + i, true, handleNewValue(start + SECOND * 60 * i, i + 100));
        }
    }

    // differing readings 5 seconds apart are rejected
    @Test
    public void deDupeTest3() {
        assertEquals(true, handleNewValue(start, 100));
        assertEquals(false, handleNewValue(start + SECOND * 5, 101));
    }

    // differing readings 15 seconds apart are accepted
    @Test
    public void deDupeTest4() {
        assertEquals(true, handleNewValue(start, 100));
        assertEquals(true, handleNewValue(start + SECOND * 15, 101));
    }

    // same readings 2 minutes apart are rejected
    @Test
    public void deDupeTest5() {
        assertEquals(true, handleNewValue(start, 100));
        assertEquals(false, handleNewValue(start + MINUTE * 2, 100));
    }

    // same readings 4 minutes apart are rejected
    @Test
    public void deDupeTest6() {
        assertEquals(true, handleNewValue(start, 100));
        assertEquals(false, handleNewValue(start + MINUTE * 4, 100));
    }

    // same readings 5 minutes apart are allowed
    @Test
    public void deDupeTest7() {
        assertEquals(true, handleNewValue(start, 100));
        assertEquals(true, handleNewValue(start + MINUTE * 5, 100));
    }

    @Test
    public void differentValues5minApartAccepted() {
        assertEquals(true, handleNewValue(start, 100));
        assertEquals(true, handleNewValue(start + MINUTE * 5, 110));
        assertEquals(true, handleNewValue(start + MINUTE * 10, 120));
    }

    @Test
    public void sameValue10minApartAccepted() {
        assertEquals(true, handleNewValue(start, 100));
        assertEquals(true, handleNewValue(start + MINUTE * 10, 100));
    }

    @Test
    public void alternatingValuesAllAccepted() {
        assertEquals(true, handleNewValue(start, 100));
        assertEquals(true, handleNewValue(start + MINUTE * 5, 110));
        assertEquals(true, handleNewValue(start + MINUTE * 10, 100));
    }

    // xDrip's Eversense jam threshold is 6: the 8th identical consecutive value is rejected
    @Test
    public void jamDetectionRejectsExcessiveRepeats() {
        for (int i = 0; i <= ReadingGate.JAM_THRESHOLD; i++) {
            assertEquals("reading " + i, true, handleNewValue(start + MINUTE * 5 * i, 100));
        }
        assertEquals(ReadingGate.Verdict.JAMMED, gate.check(start + MINUTE * 5 * (ReadingGate.JAM_THRESHOLD + 1), 100));
        // a changed value clears the jam
        assertEquals(true, handleNewValue(start + MINUTE * 5 * (ReadingGate.JAM_THRESHOLD + 2), 101));
        assertEquals(true, handleNewValue(start + MINUTE * 5 * (ReadingGate.JAM_THRESHOLD + 3), 101));
    }

    @Test
    public void rangeLimits() {
        assertEquals(ReadingGate.Verdict.OUT_OF_RANGE, gate.check(start, 39));
        assertEquals(ReadingGate.Verdict.OUT_OF_RANGE, gate.check(start, 406));
        assertEquals(ReadingGate.Verdict.ACCEPT, gate.check(start, 40));
        assertEquals(ReadingGate.Verdict.ACCEPT, gate.check(start + MINUTE * 5, 405));
    }

    @Test
    public void dedupeWindowIs250Seconds() {
        assertEquals(250_000L, ReadingGate.DEDUPE_SAME_VALUE_MS);
        assertEquals(true, handleNewValue(start, 100));
        assertEquals(false, handleNewValue(start + 250 * SECOND, 100));
        assertEquals(true, handleNewValue(start + 251 * SECOND, 100));
    }

    @Test
    public void backfilledOlderReadingIsCheckedAgainstNeighbours() {
        assertEquals(true, handleNewValue(start + MINUTE * 10, 120));
        assertEquals(false, handleNewValue(start + MINUTE * 10 - 3 * SECOND, 118));
        assertEquals(true, handleNewValue(start, 110));
    }
}
