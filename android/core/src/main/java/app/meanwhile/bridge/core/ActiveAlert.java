package app.meanwhile.bridge.core;

/**
 * The glucose alert that is going on now, if any (xDrip+ {@code ActiveBgAlert}): there is
 * at most one. While snoozed it stays here, quiet until {@link #nextAlertAt}.
 */
public final class ActiveAlert {

    public final String uuid;
    public final boolean snoozed;
    /** When it may sound again (the repeat or the end of a snooze), ms. */
    public final long nextAlertAt;
    /** When it started sounding (after the latest snooze); the ascending volume counts from here. */
    public final long startedAt;

    public ActiveAlert(String uuid, boolean snoozed, long nextAlertAt, long startedAt) {
        this.uuid = uuid;
        this.snoozed = snoozed;
        this.nextAlertAt = nextAlertAt;
        this.startedAt = startedAt;
    }

    /** xDrip+ {@code ready_to_alarm} / {@code alertSnoozeOver}. */
    public boolean ready(long now) {
        return now > nextAlertAt;
    }

    public ActiveAlert snoozedFor(int minutes, long now) {
        return new ActiveAlert(uuid, true, now + minutes * 60_000L, startedAt);
    }

    @Override
    public String toString() {
        return "ActiveAlert{" + uuid + (snoozed ? " snoozed" : "") + " next=" + nextAlertAt + "}";
    }
}
