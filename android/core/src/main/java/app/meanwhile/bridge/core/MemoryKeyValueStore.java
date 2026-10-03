package app.meanwhile.bridge.core;

import java.util.HashMap;
import java.util.Map;

/** In-memory {@link KeyValueStore}, for tests and as a fallback. */
public final class MemoryKeyValueStore implements KeyValueStore {
    private final Map<String, Long> values = new HashMap<>();

    @Override
    public synchronized long getLong(String key, long defaultValue) {
        final Long v = values.get(key);
        return v == null ? defaultValue : v;
    }

    @Override
    public synchronized void putLong(String key, long value) {
        values.put(key, value);
    }
}
