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

    static final String FOLDER = "EverBridge";

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
        final String name = "everbridge-log-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".txt";
        return saveBytes(app, name, "text/plain", data);
    }

    /** A safe file name: letters, digits, dot, dash and underscore only, at most 80 characters. */
    static String safeName(String name) {
        String n = name == null ? "" : name.replaceAll("[^A-Za-z0-9._-]", "_")
                .replaceAll("\\.{2,}", "_").replaceAll("^\\.+", "");
        if (n.length() > 80) n = n.substring(n.length() - 80);
        return n.isEmpty() ? "file" : n;
    }

    /** Something that can write itself out, so a large file is never held in memory whole. */
    interface Content {
        void writeTo(OutputStream out) throws IOException;
    }

    /** Saves a file to Downloads/EverBridge (app storage if Downloads isn't available). */
    static Saved saveBytes(Context context, String fileName, String mime, byte[] data) throws IOException {
        return save(context, fileName, mime, data.length, out -> out.write(data));
    }

    /** Copies {@code source} to Downloads/EverBridge under {@code fileName}. */
    static Saved saveFile(Context context, String fileName, String mime, File source) throws IOException {
        return save(context, fileName, mime, (int) Math.min(Integer.MAX_VALUE, source.length()),
                out -> java.nio.file.Files.copy(source.toPath(), out));
    }

    private static Saved save(Context context, String fileName, String mime, int bytes, Content content)
            throws IOException {
        final Context app = context.getApplicationContext();
        final String name = safeName(fileName);
        Saved saved = null;
        if (Build.VERSION.SDK_INT >= 29) saved = saveToDownloads(app, name, mime, bytes, content);
        if (saved == null) {
            File dir = app.getExternalFilesDir(null);
            if (dir == null) dir = app.getFilesDir();
            final File f = new File(dir, name);
            try (OutputStream out = new FileOutputStream(f)) {
                content.writeTo(out);
            }
            saved = new Saved(null, f.getAbsolutePath(), bytes);
        }
        EventLog.log(app, "EXPORT", "saved " + saved.where + " (" + saved.bytes / 1024 + " KB)");
        return saved;
    }

    @android.annotation.TargetApi(29)
    private static Saved saveToDownloads(Context app, String name, String mime, int bytes, Content content) {
        if (Build.VERSION.SDK_INT < 29) return null;
        final ContentResolver cr = app.getContentResolver();
        final ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = null;
        try {
            uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return null;
            try (OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) return null;
                content.writeTo(out);
            }
            final ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            return new Saved(uri, "Downloads/" + FOLDER + "/" + name, bytes);
        } catch (IOException | RuntimeException e) {
            EventLog.log(app, "EXPORT", "Downloads unavailable, using app storage: " + e);
            // don't leave a half-written, still-pending entry behind
            if (uri != null) {
                try {
                    cr.delete(uri, null, null);
                } catch (RuntimeException ignored) {
                }
            }
            return null;
        }
    }

    /** Share sheet for a saved report (falls back to sharing the text itself). */
    static Intent shareIntent(Context context, Saved saved) {
        final Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "EverBridge log");
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
