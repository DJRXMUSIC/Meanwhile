package app.meanwhile.bridge.core;

import java.util.List;

/** Durable storage for readings. Implementations must be thread safe. */
public interface ReadingStore {

    /** True if any stored reading lies within {@code windowMs} either side of {@code timestamp}. */
    boolean existsWithin(long timestamp, long windowMs);

    void insert(Reading reading);

    /** Removes the reading taken at exactly this time, if there is one. */
    void delete(long timestamp);

    /** Up to {@code count} readings, newest first. */
    List<Reading> latest(int count);
}
