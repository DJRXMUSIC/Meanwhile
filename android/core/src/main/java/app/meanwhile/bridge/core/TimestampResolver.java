package app.meanwhile.bridge.core;

/**
 * Picks the most precise timestamp a notification offers for its reading.
 *
 * xDrip+ stamps companion-app readings with the time its listener ran. This class prefers
 * the Eversense app's own {@code Notification.when}, but only while it behaves like a
 * reading time. Otherwise it uses Android's post time, which is set when the Eversense app
 * posts the update, about a second after the reading arrives over Bluetooth.
 *
 * {@code when} is trusted when:
 * <ul>
 *   <li>it is no more than {@link #WHEN_MAX_AGE_MS} older than the post time and no more than
 *       {@link #WHEN_MAX_FUTURE_MS} after it, and</li>
 *   <li>it has never been seen to stay the same while the value changed. Once that happens
 *       the package is marked unreliable for good, because {@code when} is then a
 *       builder/service start time and not a reading time.</li>
 * </ul>
 * If a trusted {@code when} and the value are both unchanged, the notification is just the
 * same reading posted again, and is reported as a repost.
 */
public final class TimestampResolver {

    public static final long WHEN_MAX_AGE_MS = 6 * 60_000L;
    public static final long WHEN_MAX_FUTURE_MS = 60_000L;

    static final String KEY_LAST_WHEN = "ts.last_when.";
    static final String KEY_LAST_WHEN_MGDL = "ts.last_when_mgdl.";
    static final String KEY_WHEN_UNRELIABLE = "ts.when_unreliable.";

    public static final class Resolution {
        public final long timestamp;
        public final String source;
        /** Same trusted {@code when} and same value as last time: not a new reading. */
        public final boolean repost;

        Resolution(long timestamp, String source, boolean repost) {
            this.timestamp = timestamp;
            this.source = source;
            this.repost = repost;
        }
    }

    private final KeyValueStore state;

    public TimestampResolver(KeyValueStore state) {
        this.state = state;
    }

    public Resolution resolve(String pkg, long when, long postTime, long receivedAt, int mgdl) {
        final boolean havePost = postTime > 0;
        final long base = havePost ? postTime : receivedAt;
        final String baseSource = havePost ? TimestampSource.POST_TIME : TimestampSource.RECEIVED;

        if (state.getLong(KEY_WHEN_UNRELIABLE + pkg, 0) != 0 || !inWindow(when, base)) {
            return new Resolution(base, baseSource, false);
        }

        final long lastWhen = state.getLong(KEY_LAST_WHEN + pkg, 0);
        final long lastMgdl = state.getLong(KEY_LAST_WHEN_MGDL + pkg, 0);
        if (when == lastWhen) {
            if (mgdl == lastMgdl) {
                return new Resolution(when, TimestampSource.NOTIFICATION_WHEN, true);
            }
            state.putLong(KEY_WHEN_UNRELIABLE + pkg, 1);
            return new Resolution(base, baseSource, false);
        }
        state.putLong(KEY_LAST_WHEN + pkg, when);
        state.putLong(KEY_LAST_WHEN_MGDL + pkg, mgdl);
        return new Resolution(when, TimestampSource.NOTIFICATION_WHEN, false);
    }

    public boolean isWhenUnreliable(String pkg) {
        return state.getLong(KEY_WHEN_UNRELIABLE + pkg, 0) != 0;
    }

    private static boolean inWindow(long when, long base) {
        return when > 0 && when >= base - WHEN_MAX_AGE_MS && when <= base + WHEN_MAX_FUTURE_MS;
    }
}
