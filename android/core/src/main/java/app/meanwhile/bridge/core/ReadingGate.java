package app.meanwhile.bridge.core;

/**
 * Decides whether a parsed value becomes a stored reading.
 *
 * Ported from xDrip+ {@code UiBasedCollector.handleNewValue}, {@code isDifferentToLast},
 * {@code isJammed} and {@code jamThreshold}, with xDrip's defaults for a 5-minute sensor
 * ({@code DexCollectionType.getCurrentDeduplicationPeriod()} = 250 s). The Dexcom
 * "BlueTails" grace window is dropped because it only applies when xDrip is also
 * receiving Dexcom data over Bluetooth.
 *
 * Not thread safe; {@link CaptureEngine} serialises calls.
 */
public final class ReadingGate {

    public static final int MIN_MGDL = 40;
    public static final int MAX_MGDL = 405;
    public static final long SAMPLE_PERIOD_MS = 5 * 60_000L;
    /** A repeat of the last value within this window is the same reading re-posted. */
    public static final long DEDUPE_SAME_VALUE_MS = SAMPLE_PERIOD_MS - SAMPLE_PERIOD_MS / 6;
    /** A different value closer than this to a stored reading is treated as a UI glitch. */
    public static final long DEDUPE_DIFFERENT_VALUE_MS = 10_000L;
    /** More identical consecutive readings than this means the source is stuck. */
    public static final int JAM_THRESHOLD = 6;

    static final String KEY_LAST_VALUE = "gate.last_value";
    static final String KEY_LAST_REPEAT = "gate.last_repeat";

    public enum Verdict { ACCEPT, OUT_OF_RANGE, DUPLICATE, JAMMED }

    private final ReadingStore store;
    private final KeyValueStore state;

    public ReadingGate(ReadingStore store, KeyValueStore state) {
        this.store = store;
        this.state = state;
    }

    /**
     * Same state transitions as xDrip+: the repeat counter advances on every value that is
     * not a duplicate, and the last value is only updated when the reading is accepted.
     * The caller must store the reading when this returns ACCEPT.
     */
    public Verdict check(long timestamp, int mgdl) {
        if (mgdl < MIN_MGDL || mgdl > MAX_MGDL) {
            return Verdict.OUT_OF_RANGE;
        }
        final long period = isDifferentToLast(mgdl) ? DEDUPE_DIFFERENT_VALUE_MS : DEDUPE_SAME_VALUE_MS;
        if (store.existsWithin(timestamp, period)) {
            return Verdict.DUPLICATE;
        }
        if (isJammed(mgdl)) {
            return Verdict.JAMMED;
        }
        state.putLong(KEY_LAST_VALUE, mgdl);
        return Verdict.ACCEPT;
    }

    private boolean isDifferentToLast(int mgdl) {
        return state.getLong(KEY_LAST_VALUE, 0) != mgdl;
    }

    // note: this updates the stored repeat counter, as in xDrip+
    private boolean isJammed(int mgdl) {
        if (state.getLong(KEY_LAST_VALUE, 0) == mgdl) {
            state.putLong(KEY_LAST_REPEAT, state.getLong(KEY_LAST_REPEAT, 0) + 1);
        } else {
            state.putLong(KEY_LAST_REPEAT, 0);
        }
        return state.getLong(KEY_LAST_REPEAT, 0) > JAM_THRESHOLD;
    }
}
