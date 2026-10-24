package app.meanwhile.bridge.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holds writes in memory until {@link #commit()}. {@link CaptureEngine} uses it so a
 * capture stores its reading first and then persists the state that marks it as seen, in
 * one write: a crash in between can then cause a duplicate check, never a lost reading.
 */
final class StagedKeyValueStore implements KeyValueStore {

    private final KeyValueStore backing;
    private final Map<String, Long> pending = new LinkedHashMap<>();

    StagedKeyValueStore(KeyValueStore backing) {
        this.backing = backing;
    }

    @Override
    public synchronized long getLong(String key, long defaultValue) {
        final Long v = pending.get(key);
        return v != null ? v : backing.getLong(key, defaultValue);
    }

    @Override
    public synchronized void putLong(String key, long value) {
        pending.put(key, value);
    }

    synchronized void commit() {
        if (pending.isEmpty()) return;
        backing.putLongs(new LinkedHashMap<>(pending));
        pending.clear();
    }

    synchronized void discard() {
        pending.clear();
    }
}
