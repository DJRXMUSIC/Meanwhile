package app.meanwhile.bridge.core;

/**
 * One glucose reading captured from the Eversense app.
 *
 * {@link #timestamp} is the best available estimate of when the reading was taken; the
 * other time fields are kept so it is always possible to see how that estimate was made.
 */
public final class Reading {

    /** Best estimate of the reading time, ms since epoch. */
    public final long timestamp;
    public final int mgdl;
    /** One of the {@link TimestampSource} constants. */
    public final String timestampSource;
    /** {@code Notification.when} as set by the Eversense app (0 if absent). */
    public final long notificationWhen;
    /** {@code StatusBarNotification.getPostTime()}: when Android accepted the post/update. */
    public final long postTime;
    /** When this app's listener received the callback. */
    public final long receivedAt;
    public final String sourcePackage;
    /** The text the value was parsed from, for diagnostics. */
    public final String rawText;

    public Reading(long timestamp, int mgdl, String timestampSource, long notificationWhen,
                   long postTime, long receivedAt, String sourcePackage, String rawText) {
        this.timestamp = timestamp;
        this.mgdl = mgdl;
        this.timestampSource = timestampSource;
        this.notificationWhen = notificationWhen;
        this.postTime = postTime;
        this.receivedAt = receivedAt;
        this.sourcePackage = sourcePackage;
        this.rawText = rawText;
    }

    @Override
    public String toString() {
        return "Reading{" + mgdl + " mg/dL @ " + Json.isoUtc(timestamp) + " (" + timestampSource + ")}";
    }
}
