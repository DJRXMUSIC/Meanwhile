package app.meanwhile.bridge;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

import app.meanwhile.bridge.core.KeyValueStore;

/** Dedupe/jam/timestamp state; committed synchronously so a crash cannot lose it. */
final class PrefsKeyValueStore implements KeyValueStore {

    static final String PREFS = "bridge_state";

    private final SharedPreferences p;

    PrefsKeyValueStore(Context context) {
        p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public long getLong(String key, long defaultValue) {
        return p.getLong(key, defaultValue);
    }

    @Override
    public void putLong(String key, long value) {
        p.edit().putLong(key, value).commit();
    }

    @Override
    public void putLongs(Map<String, Long> values) {
        final SharedPreferences.Editor e = p.edit();
        for (Map.Entry<String, Long> v : values.entrySet()) e.putLong(v.getKey(), v.getValue());
        e.commit();
    }
}
