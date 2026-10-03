package app.meanwhile.bridge;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

import app.meanwhile.bridge.core.Reading;
import app.meanwhile.bridge.core.ReadingStore;

/** Readings in SQLite (WAL). Keeps {@link #RETENTION_DAYS} of history. */
final class SqliteReadingStore extends SQLiteOpenHelper implements ReadingStore {

    static final String DB_NAME = "readings.db";
    static final int RETENTION_DAYS = 120;
    private static final int VERSION = 1;
    private static final long DAY_MS = 86_400_000L;

    private long lastPrune;

    SqliteReadingStore(Context context) {
        this(context, DB_NAME);
    }

    /** {@code name == null} gives an in-memory database (tests). */
    SqliteReadingStore(Context context, String name) {
        super(context, name, null, VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.enableWriteAheadLogging();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE readings ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "ts INTEGER NOT NULL,"
                + "mgdl INTEGER NOT NULL,"
                + "ts_source TEXT NOT NULL,"
                + "notification_when INTEGER NOT NULL,"
                + "post_time INTEGER NOT NULL,"
                + "received_at INTEGER NOT NULL,"
                + "pkg TEXT,"
                + "raw TEXT)");
        db.execSQL("CREATE INDEX idx_readings_ts ON readings(ts)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // single schema version so far
    }

    @Override
    public boolean existsWithin(long timestamp, long windowMs) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM readings WHERE ts BETWEEN ? AND ? LIMIT 1",
                new String[]{String.valueOf(timestamp - windowMs), String.valueOf(timestamp + windowMs)})) {
            return c.moveToFirst();
        }
    }

    @Override
    public void insert(Reading r) {
        final ContentValues v = new ContentValues();
        v.put("ts", r.timestamp);
        v.put("mgdl", r.mgdl);
        v.put("ts_source", r.timestampSource);
        v.put("notification_when", r.notificationWhen);
        v.put("post_time", r.postTime);
        v.put("received_at", r.receivedAt);
        v.put("pkg", r.sourcePackage);
        v.put("raw", r.rawText);
        final SQLiteDatabase db = getWritableDatabase();
        db.insertOrThrow("readings", null, v);
        pruneOccasionally(db, r.timestamp);
    }

    @Override
    public List<Reading> latest(int count) {
        final List<Reading> out = new ArrayList<>(Math.min(count, 1024));
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT ts, mgdl, ts_source, notification_when, post_time, received_at, pkg, raw "
                        + "FROM readings ORDER BY ts DESC LIMIT ?",
                new String[]{String.valueOf(count)})) {
            while (c.moveToNext()) {
                out.add(new Reading(c.getLong(0), c.getInt(1), c.getString(2), c.getLong(3),
                        c.getLong(4), c.getLong(5), c.getString(6), c.getString(7)));
            }
        }
        return out;
    }

    int count() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM readings", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    private void pruneOccasionally(SQLiteDatabase db, long now) {
        if (now - lastPrune < DAY_MS) return;
        lastPrune = now;
        db.delete("readings", "ts < ?", new String[]{String.valueOf(now - RETENTION_DAYS * DAY_MS)});
    }
}
