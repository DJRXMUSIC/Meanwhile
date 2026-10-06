package app.meanwhile.bridge;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Saves the diagnostics report as a .txt file the user can download/share. */
final class LogExport {

    static final String FOLDER = "EversenseBridge";

    static final class Saved {
        /** Shareable content URI (Downloads), or null when saved to app storage. */
        final Uri uri;
        /** Human-readable location. */
        final String where;
        final int bytes;

        Saved(Uri uri, String where, int bytes) {
            this.uri = uri;
            this.where = where;
            this.bytes = bytes;
        }
    }

    private LogExport() {
    }

    /** Builds and saves the report. Slow (runs logcat); call off the main thread. */
    static Saved save(Context context) throws IOException {
        final Context app = context.getApplicationContext();
        final byte[] data = DiagnosticsReport.build(app).getBytes(StandardCharsets.UTF_8);
        final String name = "eversense-bridge-log-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".txt";

        Saved saved = null;
        if (Build.VERSION.SDK_INT >= 29) saved = saveToDownloads(app, name, data);
        if (saved == null) {
            File dir = app.getExternalFilesDir(null);
            if (dir == null) dir = app.getFilesDir();
            final File f = new File(dir, name);
            try (OutputStream out = new FileOutputStream(f)) {
                out.write(data);
            }
            saved = new Saved(null, f.getAbsolutePath(), data.length);
        }
        EventLog.log(app, "EXPORT", "saved " + saved.where + " (" + saved.bytes / 1024 + " KB)");
        return saved;
    }

    @android.annotation.TargetApi(29)
    private static Saved saveToDownloads(Context app, String name, byte[] data) {
        if (Build.VERSION.SDK_INT < 29) return null;
        final ContentResolver cr = app.getContentResolver();
        final ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        try {
            final Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return null;
            try (OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) return null;
                out.write(data);
            }
            final ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            return new Saved(uri, "Downloads/" + FOLDER + "/" + name, data.length);
        } catch (IOException | RuntimeException e) {
            EventLog.log(app, "EXPORT", "Downloads unavailable, using app storage: " + e);
            return null;
        }
    }

    /** Share sheet for a saved report (falls back to sharing the text itself). */
    static Intent shareIntent(Context context, Saved saved) {
        final Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Eversense Bridge log");
        if (saved.uri != null) {
            send.putExtra(Intent.EXTRA_STREAM, saved.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(ClipData.newRawUri("log", saved.uri));
        } else {
            String text = DiagnosticsReport.build(context);
            if (text.length() > 400_000) text = text.substring(0, 400_000) + "\n…(truncated)";
            send.putExtra(Intent.EXTRA_TEXT, text);
        }
        return Intent.createChooser(send, "Share log");
    }
}
