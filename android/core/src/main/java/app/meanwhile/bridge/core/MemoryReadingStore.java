package app.meanwhile.bridge.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** In-memory {@link ReadingStore}, for tests. */
public final class MemoryReadingStore implements ReadingStore {
    private final List<Reading> readings = new ArrayList<>();

    @Override
    public synchronized boolean existsWithin(long timestamp, long windowMs) {
        for (Reading r : readings) {
            if (r.timestamp >= timestamp - windowMs && r.timestamp <= timestamp + windowMs) {
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized void insert(Reading reading) {
        readings.add(reading);
        readings.sort(Comparator.comparingLong((Reading r) -> r.timestamp).reversed());
    }

    @Override
    public synchronized List<Reading> latest(int count) {
        return new ArrayList<>(readings.subList(0, Math.min(count, readings.size())));
    }

    public synchronized int size() {
        return readings.size();
    }

    public synchronized void clear() {
        readings.clear();
    }
}
