package app.meanwhile.bridge.core;

import java.util.Map;

/** Small persistent key/value state (SharedPreferences on Android, a map in tests). */
public interface KeyValueStore {
    long getLong(String key, long defaultValue);

    void putLong(String key, long value);

    /** Writes several values together; on Android, one disk write instead of one each. */
    default void putLongs(Map<String, Long> values) {
        for (Map.Entry<String, Long> e : values.entrySet()) putLong(e.getKey(), e.getValue());
    }
}
