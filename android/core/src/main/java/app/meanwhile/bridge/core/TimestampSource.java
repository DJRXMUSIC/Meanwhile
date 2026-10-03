package app.meanwhile.bridge.core;

/** How {@link Reading#timestamp} was determined. */
public final class TimestampSource {
    /** The Eversense app's own {@code Notification.when}, verified to move with each new reading. */
    public static final String NOTIFICATION_WHEN = "notification_when";
    /** Android's post time for the notification update (within ~1 s of the app receiving it over BLE). */
    public static final String POST_TIME = "post_time";
    /** Time the listener callback ran; only used if the system gave no post time. */
    public static final String RECEIVED = "received";

    private TimestampSource() {
    }
}
