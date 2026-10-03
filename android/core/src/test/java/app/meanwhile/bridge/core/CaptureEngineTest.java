package app.meanwhile.bridge.core;

import static app.meanwhile.bridge.core.CaptureEngine.Outcome.AMBIGUOUS;
import static app.meanwhile.bridge.core.CaptureEngine.Outcome.DUPLICATE;
import static app.meanwhile.bridge.core.CaptureEngine.Outcome.IGNORED_PACKAGE;
import static app.meanwhile.bridge.core.CaptureEngine.Outcome.JAMMED;
import static app.meanwhile.bridge.core.CaptureEngine.Outcome.NO_VALUE;
import static app.meanwhile.bridge.core.CaptureEngine.Outcome.OUT_OF_RANGE;
import static app.meanwhile.bridge.core.CaptureEngine.Outcome.REPOST;
import static app.meanwhile.bridge.core.CaptureEngine.Outcome.STORED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;

public class CaptureEngineTest {

    private static final String EV365 = "com.senseonics.eversense365.us";
    private static final long T = 1_790_000_000_000L;
    private static final long MIN = 60_000L;

    private MemoryReadingStore store;
    private MemoryKeyValueStore state;
    private CaptureEngine engine;
    private final List<Reading> stored = new ArrayList<>();

    @Before
    public void setUp() {
        store = new MemoryReadingStore();
        state = new MemoryKeyValueStore();
        engine = new CaptureEngine(store, state, new CaptureLog(50));
        engine.setListener(stored::add);
    }

    /** An Eversense-style ongoing notification with a custom view. */
    private static CaptureEngine.Input view(long when, long postTime, String... texts) {
        return new CaptureEngine.Input(EV365, when, postTime, postTime + 30, true,
                Arrays.asList(texts), Arrays.asList("Eversense", null));
    }

    @Test
    public void realisticFiveMinuteStreamWithReadingTimeInWhen() {
        final int[] values = {112, 115, 119, 124, 124, 121};
        for (int i = 0; i < values.length; i++) {
            final long readingTime = T + i * 5 * MIN;
            // the app posts ~1.2 s after the transmitter reading, and sets when = reading time
            final CaptureEngine.Result r = engine.process(view(readingTime, readingTime + 1_200,
                    String.valueOf(values[i]), "mg/dL", "↗"));
            assertEquals("i=" + i, STORED, r.outcome);
            assertEquals(readingTime, r.reading.timestamp);
            assertEquals(TimestampSource.NOTIFICATION_WHEN, r.reading.timestampSource);
        }
        assertEquals(values.length, store.size());
        assertEquals(values.length, stored.size());
        assertEquals(121, store.latest(1).get(0).mgdl);
    }

    @Test
    public void constantWhenFallsBackToPostTimeAfterFirstChange() {
        final long serviceStart = T - 30 * 1000;
        assertEquals(STORED, engine.process(view(serviceStart, T, "120")).outcome);
        final CaptureEngine.Result second = engine.process(view(serviceStart, T + 5 * MIN, "125"));
        assertEquals(STORED, second.outcome);
        assertEquals(T + 5 * MIN, second.reading.timestamp);
        assertEquals(TimestampSource.POST_TIME, second.reading.timestampSource);
        assertTrue(engine.timestamps().isWhenUnreliable(EV365));
    }

    @Test
    public void repostsAndRefreshesAreNotDuplicated() {
        assertEquals(STORED, engine.process(view(T, T + 1_000, "120")).outcome);
        // app refreshes the ongoing notification (e.g. "1 min ago") without a new reading
        assertEquals(REPOST, engine.process(view(T, T + MIN, "120")).outcome);
        assertEquals(REPOST, engine.process(view(T, T + 6 * MIN, "120")).outcome);
        assertEquals(1, store.size());
        assertEquals(1, stored.size());
    }

    @Test
    public void untrustedWhenUsesGateDedupe() {
        // when = 0: fall back to post time, then xDrip's dedupe rules apply
        assertEquals(STORED, engine.process(view(0, T, "120")).outcome);
        assertEquals(DUPLICATE, engine.process(view(0, T + 2 * MIN, "120")).outcome);
        assertEquals(DUPLICATE, engine.process(view(0, T + 5_000, "121")).outcome);
        assertEquals(STORED, engine.process(view(0, T + 5 * MIN, "121")).outcome);
    }

    @Test
    public void listenerReconnectReplaysActiveNotificationWithoutDuplicating() {
        assertEquals(STORED, engine.process(view(T, T + 900, "140")).outcome);
        // listener rebinds and re-reads the still-showing notification (same when, same post time)
        assertEquals(REPOST, engine.process(view(T, T + 900, "140")).outcome);
        assertEquals(1, store.size());
    }

    @Test
    public void restartWithPersistedStateStillDedupes() {
        assertEquals(STORED, engine.process(view(0, T, "120")).outcome);
        final CaptureEngine restarted = new CaptureEngine(store, state, new CaptureLog(10));
        assertEquals(DUPLICATE, restarted.process(view(0, T + MIN, "120")).outcome);
    }

    @Test
    public void jammedSourceIsRejected() {
        for (int i = 0; i <= ReadingGate.JAM_THRESHOLD; i++) {
            assertEquals(STORED, engine.process(view(0, T + i * 5 * MIN, "150")).outcome);
        }
        assertEquals(JAMMED, engine.process(view(0, T + (ReadingGate.JAM_THRESHOLD + 1) * 5 * MIN, "150")).outcome);
    }

    @Test
    public void failuresAreLoggedWithDetail() {
        final CaptureEngine.Result none = engine.process(view(T, T, "Signal loss", "Check transmitter"));
        assertEquals(NO_VALUE, none.outcome);
        assertTrue(none.detail.contains("Signal loss"));

        final CaptureEngine.Result lo = engine.process(view(T, T, "LO", "mg/dL"));
        assertEquals(NO_VALUE, lo.outcome);
        assertTrue(lo.detail.startsWith("app shows LO"));

        assertEquals(AMBIGUOUS, engine.process(view(T, T, "120", "118")).outcome);
        assertEquals(OUT_OF_RANGE, engine.process(view(T + MIN, T + MIN, "35")).outcome);

        assertEquals(4, engine.log().recent().size());
        assertEquals(OUT_OF_RANGE, engine.log().last().outcome);
        assertEquals(0, store.size());
    }

    @Test
    public void otherPackagesAreIgnoredAndNotLogged() {
        final CaptureEngine.Input other = new CaptureEngine.Input("com.whatsapp", T, T, T, false,
                null, Collections.singletonList("123"));
        assertEquals(IGNORED_PACKAGE, engine.process(other).outcome);
        assertNull(engine.log().last());
        assertEquals(0, store.size());
    }

    @Test
    public void packagesAndUnitsAreConfigurable() {
        engine.setPackages(Collections.singleton("com.senseonics.eversense365.eu"));
        assertEquals(IGNORED_PACKAGE, engine.process(view(T, T, "120")).outcome);
        final CaptureEngine.Input eu = new CaptureEngine.Input("com.senseonics.eversense365.eu", T, T, T, true,
                Collections.singletonList("6,7 mmol/L"), null);
        engine.setUnits(NotificationParser.Units.MMOL);
        final CaptureEngine.Result r = engine.process(eu);
        assertEquals(STORED, r.outcome);
        assertEquals(121, r.reading.mgdl);
    }

    @Test
    public void extrasOnlyNotification() {
        final CaptureEngine.Input in = new CaptureEngine.Input(EV365, T, T + 800, T + 820, false,
                null, Arrays.asList("118 mg/dL", "Stable"));
        final CaptureEngine.Result r = engine.process(in);
        assertEquals(STORED, r.outcome);
        assertEquals(118, r.reading.mgdl);
        assertEquals(T, r.reading.timestamp);
        assertEquals(T + 800, r.reading.postTime);
        assertEquals(T + 820, r.reading.receivedAt);
        assertEquals("118 mg/dL", r.reading.rawText);
    }

    @Test
    public void concurrentDuplicatePostsStoreExactlyOnce() throws Exception {
        final int threads = 16;
        final CountDownLatch go = new CountDownLatch(1);
        final AtomicInteger storedCount = new AtomicInteger();
        final List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final Thread t = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException ignored) {
                }
                if (engine.process(view(0, T, "133")).outcome == STORED) storedCount.incrementAndGet();
            });
            ts.add(t);
            t.start();
        }
        go.countDown();
        for (Thread t : ts) t.join(5_000);
        assertEquals(1, storedCount.get());
        assertEquals(1, store.size());
    }

    @Test
    public void captureLogIsBounded() {
        final CaptureLog log = new CaptureLog(3);
        final CaptureEngine e = new CaptureEngine(new MemoryReadingStore(), new MemoryKeyValueStore(), log);
        for (int i = 0; i < 10; i++) e.process(view(0, T + i * MIN, "x" + i));
        assertEquals(3, log.recent().size());
        assertTrue(log.recent().get(0).detail.contains("x9"));
    }

    @Test
    public void defaultPackagesMatchXdrip() {
        assertEquals(Arrays.asList("com.senseonics.eversense365.us", "com.senseonics.gen12androidapp",
                "com.senseonics.androidapp"), CaptureEngine.DEFAULT_PACKAGES);
        assertTrue(engine.isMonitored(EV365));
    }
}
