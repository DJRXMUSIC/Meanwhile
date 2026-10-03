package app.meanwhile.bridge;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Persistent, rotating event log (files/logs/events.log + events.1.log, ~2 MB, a few days).
 * Survives crashes and restarts so a saved log file shows what happened overnight.
 */
public final class EventLog {

    static final long MAX_BYTES = 1024 * 1024;
    private static final Object LOCK = new Object();

    private EventLog() {
    }

    private static File dir(Context context) {
        final File d = new File(context.getApplicationContext().getFilesDir(), "logs");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Appends one line; never throws. */
    public static void log(Context context, String tag, String message) {
        Log.i(Bridge.TAG, tag + ": " + message);
        try {
            final File dir = dir(context);
            final String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date())
                    + " [" + tag + "] " + message.replace('\n', ' ') + "\n";
            synchronized (LOCK) {
                final File current = new File(dir, "events.log");
                if (current.length() > MAX_BYTES) {
                    final File old = new File(dir, "events.1.log");
                    //noinspection ResultOfMethodCallIgnored
                    old.delete();
                    //noinspection ResultOfMethodCallIgnored
                    current.renameTo(old);
                }
                try (Writer w = new OutputStreamWriter(new FileOutputStream(current, true), StandardCharsets.UTF_8)) {
                    w.write(line);
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.w(Bridge.TAG, "event log write failed: " + e);
        }
    }

    /** Older file first, then current. */
    static String readAll(Context context) {
        final File dir = dir(context);
        final StringBuilder sb = new StringBuilder();
        synchronized (LOCK) {
            for (String name : new String[]{"events.1.log", "events.log"}) {
                final File f = new File(dir, name);
                if (!f.exists()) continue;
                try {
                    sb.append(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    sb.append("(could not read ").append(name).append(": ").append(e).append(")\n");
                }
            }
        }
        return sb.toString();
    }

    static void clear(Context context) {
        final File dir = dir(context);
        synchronized (LOCK) {
            //noinspection ResultOfMethodCallIgnored
            new File(dir, "events.1.log").delete();
            //noinspection ResultOfMethodCallIgnored
            new File(dir, "events.log").delete();
        }
    }
}
