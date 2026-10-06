package app.meanwhile.bridge.core;

/** Small persistent key/value state (SharedPreferences on Android, a map in tests). */
public interface KeyValueStore {
    long getLong(String key, long defaultValue);

    void putLong(String key, long value);
}
