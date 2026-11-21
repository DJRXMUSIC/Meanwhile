package app.meanwhile.bridge.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

/**
 * Expected values were computed by running Meanwhile's own src/lib/insulin.ts (iobFraction,
 * totalIOB), so the watch's IOB matches the app's tile exactly.
 */
public class InsulinOnBoardTest {

    private static final long T = 1_790_000_000_000L;
    private static final long M = 60_000L;

    @Test
    public void fractionMatchesInsulinTs() {
        assertEquals(1, InsulinOnBoard.fraction(-1, 5.5, 64, 15), 0);
        assertEquals(1, InsulinOnBoard.fraction(0, 5.5, 64, 15), 0);
        assertEquals(1, InsulinOnBoard.fraction(10, 5.5, 64, 15), 0);
        assertEquals(1, InsulinOnBoard.fraction(15, 5.5, 64, 15), 0);
        assertEquals(0.9736986777444868, InsulinOnBoard.fraction(30, 5.5, 64, 15), 1e-12);
        assertEquals(0.8229684948121029, InsulinOnBoard.fraction(60, 5.5, 64, 15), 1e-12);
        assertEquals(0.6292018656787686, InsulinOnBoard.fraction(90, 5.5, 64, 15), 1e-12);
        assertEquals(0.4470281643855948, InsulinOnBoard.fraction(120, 5.5, 64, 15), 1e-12);
        assertEquals(0.1292670714950317, InsulinOnBoard.fraction(200, 5.5, 64, 15), 1e-12);
        assertEquals(0.007123199104300926, InsulinOnBoard.fraction(300, 5.5, 64, 15), 1e-12);
        assertEquals(0.0000027171612951848445, InsulinOnBoard.fraction(344, 5.5, 64, 15), 1e-15);
        assertEquals(0, InsulinOnBoard.fraction(345, 5.5, 64, 15), 0);
        assertEquals(0.8597812232441501, InsulinOnBoard.fraction(60, 6, 75, 15), 1e-12);
        assertEquals(0.6878314288758401, InsulinOnBoard.fraction(60, 3, 75, 0), 1e-12);
        assertEquals(0.46218977704856845, InsulinOnBoard.fraction(30, 1, 200, 0), 1e-12);
    }

    private static InsulinOnBoard sample() {
        return InsulinOnBoard.parse("{\"dia\":5.5,\"peak\":64,\"delay\":15,\"doses\":["
                + "{\"ts\":" + (T - 30 * M) + ",\"units\":3,\"kind\":\"bolus\"},"
                + "{\"ts\":" + (T - 150 * M) + ",\"units\":2,\"kind\":\"correction\"},"
                + "{\"ts\":" + (T - 60 * M) + ",\"units\":20,\"kind\":\"basal\"},"
                + "{\"ts\":" + (T + 5 * M) + ",\"units\":4,\"kind\":\"bolus\"},"
                + "{\"ts\":" + (T - 400 * M) + ",\"units\":5,\"kind\":\"bolus\"}]}", T);
    }

    @Test
    public void totalMatchesInsulinTs() {
        // basal excluded, a legacy "correction" counted, a future dose not yet counted
        assertEquals(3.52, sample().total(T), 1e-9);
        assertEquals(5.51, sample().total(T + 60 * M), 1e-9);
        assertEquals(0.78, sample().total(T + 200 * M), 1e-9);
    }

    @Test
    public void survivesARoundTripThroughStorage() {
        final InsulinOnBoard a = sample();
        final InsulinOnBoard b = InsulinOnBoard.parse(a.toJson(), T + 1);
        assertEquals(a.doses.size(), b.doses.size());
        assertEquals(a.total(T + 60 * M), b.total(T + 60 * M), 0);
    }

    @Test
    public void defaultsAndBadInput() {
        final InsulinOnBoard d = InsulinOnBoard.parse("{\"doses\":[{\"ts\":" + T + ",\"units\":1}]}", T);
        assertEquals(5.5, d.diaHours, 0);
        assertEquals(64, d.peakMin, 0);
        assertEquals(15, d.delayMin, 0);
        assertEquals(1.0, d.total(T + 10 * M), 0);
        // doses without a time or with a silly amount are skipped
        assertEquals(0, InsulinOnBoard.parse("{\"doses\":[{\"units\":1},{\"ts\":1,\"units\":-1},{\"ts\":1,\"units\":500}]}", T).doses.size());
        assertThrows(IllegalArgumentException.class, () -> InsulinOnBoard.parse("[]", T));
        assertThrows(IllegalArgumentException.class, () -> InsulinOnBoard.parse("{\"dia\":0,\"doses\":[]}", T));
        assertThrows(IllegalArgumentException.class, () -> InsulinOnBoard.parse("{\"dia\":5}", T));
        assertThrows(IllegalArgumentException.class, () -> InsulinOnBoard.parse("not json", T));
    }
}
