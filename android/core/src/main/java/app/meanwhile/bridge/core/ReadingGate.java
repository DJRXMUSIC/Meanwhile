package app.meanwhile.bridge.core;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Decides whether a parsed value becomes a stored reading.
 *
 * Ported from xDrip+ {@code UiBasedCollector.handleNewValue}, {@code isDifferentToLast},
 * {@code isJammed} and {@code jamThreshold}, with xDrip's defaults for a 5-minute sensor
 * ({@code DexCollectionType.getCurrentDeduplicationPeriod()} = 250 s). The Dexcom
 * "BlueTails" grace window is dropped because it only applies when xDrip is also
 * receiving Dexcom data over Bluetooth.
 *
 * Two additions for how the Eversense 365 app actually posts (seen in a day of logs):
 * <ul>
 *   <li>It re-posts its notification about once a minute with the value it is showing.
 *       A real new reading instead comes as a burst of posts within a second. So a repeat
 *       of the last value is only a new reading when it comes in such a burst
 *       ({@link #BURST_POSTS} posts within {@link #BURST_WINDOW_MS}); a lone re-post is
 *       not, however long since the last reading. Otherwise the age would go back to 0
 *       every 5 minutes even with no new reading.</li>
 *   <li>The first post of a reading's burst often still shows the old value, and the new
 *       value follows a fraction of a second later. If that old value was taken as a
 *       repeat reading, the new value replaces it ({@link Verdict#REPLACE_REPEAT}) rather
 *       than being dropped as a glitch until the next re-post, up to a minute later.</li>
 * </ul>
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
    /** Posts this close together are one burst... */
    public static final long BURST_WINDOW_MS = 1_000L;
    /** ...and a burst of at least this many posts is a new reading. */
    public static final int BURST_POSTS = 3;

    static final String KEY_LAST_VALUE = "gate.last_value";
    static final String KEY_LAST_REPEAT = "gate.last_repeat";

    public enum Verdict {
        ACCEPT,
        /** Accept, after deleting the newest stored reading: an old value re-posted just before this one. */
        REPLACE_REPEAT,
        OUT_OF_RANGE,
        DUPLICATE,
        /** The same value again, but posted on its own: the app refreshing, not a new reading. */
        LONE_REPEAT,
        JAMMED
    }

    private final ReadingStore store;
    private final KeyValueStore state;
    /** Times of the recent posts (any value), oldest first; only the last BURST_WINDOW_MS. */
    private final ArrayDeque<Long> recentPosts = new ArrayDeque<>();

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
        final int burst = notePost(timestamp);
        if (mgdl < MIN_MGDL || mgdl > MAX_MGDL) {
            return Verdict.OUT_OF_RANGE;
        }
        final boolean different = isDifferentToLast(mgdl);
        final long period = different ? DEDUPE_DIFFERENT_VALUE_MS : DEDUPE_SAME_VALUE_MS;
        if (store.existsWithin(timestamp, period)) {
            if (different && newestIsRepeatJustBefore(timestamp, mgdl)) {
                isJammed(mgdl); // resets the repeat count
                state.putLong(KEY_LAST_VALUE, mgdl);
                return Verdict.REPLACE_REPEAT;
            }
            return Verdict.DUPLICATE;
        }
        if (!different && burst < BURST_POSTS && !store.latest(1).isEmpty()) {
            return Verdict.LONE_REPEAT;
        }
        if (isJammed(mgdl)) {
            return Verdict.JAMMED;
        }
        state.putLong(KEY_LAST_VALUE, mgdl);
        return Verdict.ACCEPT;
    }

    /** Records a post at this time; returns how many posts (this one included) are in its burst. */
    private int notePost(long timestamp) {
        while (!recentPosts.isEmpty() && (timestamp - recentPosts.peekFirst() >= BURST_WINDOW_MS
                || recentPosts.peekFirst() > timestamp)) {
            recentPosts.pollFirst();
        }
        recentPosts.addLast(timestamp);
        return recentPosts.size();
    }

    /**
     * True if the newest stored reading was taken at most DEDUPE_DIFFERENT_VALUE_MS before
     * this one and repeats the value of the reading before it, while this one has a new
     * value: the repeat was the old value still showing as the new reading came in.
     */
    private boolean newestIsRepeatJustBefore(long timestamp, int mgdl) {
        final List<Reading> last = store.latest(2);
        if (last.size() < 2) {
            return false;
        }
        final Reading newest = last.get(0);
        final long age = timestamp - newest.timestamp;
        return age >= 0 && age < DEDUPE_DIFFERENT_VALUE_MS
                && newest.mgdl == last.get(1).mgdl && newest.mgdl != mgdl;
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
