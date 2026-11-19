package app.meanwhile.bridge;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The daily backup file, written to a folder the user picks once (normally in Google Drive,
 * through Android's folder picker; the Drive app uploads it). The page builds the file's
 * contents; this writes it as {@code meanwhile-YYYY-MM-DD.json}, replacing that day's file,
 * and keeps the newest {@link #KEEP} of them.
 *
 * The emulator test points it at a plain folder ({@code file://}) instead of a picked one.
 */
final class DriveBackup {

    static final int KEEP = 14;
    static final Pattern NAME = Pattern.compile("meanwhile-\\d{4}-\\d{2}-\\d{2}\\.json");

    private static final String PREFS = "backup";
    private static final String KEY_FOLDER = "folder";
    private static final String KEY_FOLDER_NAME = "folder_name";
    private static final String KEY_OK_AT = "ok_at";
    private static final String KEY_BYTES = "bytes";
    private static final String KEY_FILE = "file";
    private static final String KEY_ATTEMPT_AT = "attempt_at";
    private static final String KEY_ERROR = "error";

    private DriveBackup() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String folder(Context c) {
        return prefs(c).getString(KEY_FOLDER, "");
    }

    static void setFolder(Context c, String uri, String name) {
        prefs(c).edit().putString(KEY_FOLDER, uri).putString(KEY_FOLDER_NAME, name).remove(KEY_ERROR).commit();
        EventLog.log(c, "BACKUP", "folder set: " + name);
    }

    /** {folder, folder_name, ok_at, bytes, file, attempt_at, error} for the page. */
    static String state(Context c) {
        final SharedPreferences p = prefs(c);
        try {
            return new JSONObject()
                    .put("folder", p.getString(KEY_FOLDER, ""))
                    .put("folder_name", p.getString(KEY_FOLDER_NAME, ""))
                    .put("ok_at", p.getLong(KEY_OK_AT, 0))
                    .put("bytes", p.getLong(KEY_BYTES, 0))
                    .put("file", p.getString(KEY_FILE, ""))
                    .put("attempt_at", p.getLong(KEY_ATTEMPT_AT, 0))
                    .put("error", p.getString(KEY_ERROR, ""))
                    .toString();
        } catch (org.json.JSONException e) {
            return "{}";
        }
    }

    /** Writes {@code contents} as the backup {@code name}; returns {@link #state}. Never throws. */
    static String write(Context c, String name, String contents) {
        final File tmp = new File(c.getCacheDir(), "backup-" + System.nanoTime());
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(contents.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return write(c, name, (File) null);
        }
        try {
            return write(c, name, tmp);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    /** Copies {@code src} to the backup folder as {@code name}; returns {@link #state}. Never throws. */
    static synchronized String write(Context c, String name, File src) {
        final long now = System.currentTimeMillis();
        final SharedPreferences.Editor e = prefs(c).edit().putLong(KEY_ATTEMPT_AT, now);
        final String folder = folder(c);
        try {
            if (folder.isEmpty()) throw new IOException("No backup folder chosen");
            if (name == null || !NAME.matcher(name).matches()) throw new IOException("Bad backup file name");
            if (src == null || !src.isFile()) throw new IOException("The backup couldn't be put together");
            final long size = src.length();
            final int pruned = folder.startsWith("file://")
                    ? writeFile(new File(Uri.parse(folder).getPath()), name, src)
                    : writeDocument(c.getContentResolver(), Uri.parse(folder), name, src);
            e.putLong(KEY_OK_AT, now).putLong(KEY_BYTES, size).putString(KEY_FILE, name).remove(KEY_ERROR);
            EventLog.log(c, "BACKUP", "wrote " + name + " (" + size + " bytes)"
                    + (pruned > 0 ? ", removed " + pruned + " old" : ""));
        } catch (Exception ex) {
            final String why = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            e.putString(KEY_ERROR, ex instanceof SecurityException ? "No access to the backup folder: choose it again" : why);
            EventLog.log(c, "BACKUP", "failed: " + ex);
        }
        e.commit();
        return state(c);
    }

    /** The backup files to delete: all but the newest {@code keep}, by name (the date). */
    static List<String> toPrune(List<String> names, int keep) {
        final List<String> ours = new ArrayList<>();
        for (String n : names) if (n != null && NAME.matcher(n).matches()) ours.add(n);
        Collections.sort(ours, Collections.reverseOrder());
        return ours.size() > keep ? new ArrayList<>(ours.subList(keep, ours.size())) : new ArrayList<>();
    }

    // ------------------------------------------------------------ a plain folder (tests)

    private static void copy(File src, OutputStream out) throws IOException {
        try (java.io.InputStream in = new java.io.FileInputStream(src)) {
            final byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static int writeFile(File dir, String name, File src) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Can't make " + dir);
        try (OutputStream out = new FileOutputStream(new File(dir, name))) {
            copy(src, out);
        }
        final List<String> names = new ArrayList<>();
        final String[] all = dir.list();
        if (all != null) Collections.addAll(names, all);
        int n = 0;
        for (String old : toPrune(names, KEEP)) if (new File(dir, old).delete()) n++;
        return n;
    }

    // ------------------------------------------------------------ a picked folder (Drive)

    private static int writeDocument(ContentResolver cr, Uri tree, String name, File src) throws IOException {
        final String rootId = DocumentsContract.getTreeDocumentId(tree);
        final Uri dir = DocumentsContract.buildDocumentUriUsingTree(tree, rootId);
        // that day's file is replaced: removed, then written new (not every provider truncates)
        final List<String[]> before = children(cr, tree, rootId);
        for (String[] f : before) {
            if (name.equals(f[1])) delete(cr, tree, f[0]);
        }
        final Uri doc = DocumentsContract.createDocument(cr, dir, "application/json", name);
        if (doc == null) throw new IOException("Couldn't create " + name + " in the backup folder");
        try (OutputStream out = cr.openOutputStream(doc, "w")) {
            if (out == null) throw new IOException("Couldn't open " + name + " for writing");
            copy(src, out);
        }
        final List<String> names = new ArrayList<>();
        final List<String[]> after = children(cr, tree, rootId);
        for (String[] f : after) names.add(f[1]);
        final List<String> prune = toPrune(names, KEEP);
        int n = 0;
        for (String[] f : after) {
            if (prune.contains(f[1]) && delete(cr, tree, f[0])) n++;
        }
        return n;
    }

    /** [documentId, displayName] of each file in the folder. */
    private static List<String[]> children(ContentResolver cr, Uri tree, String parentId) {
        final List<String[]> out = new ArrayList<>();
        final Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        try (Cursor cur = cr.query(kids, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null, null, null)) {
            while (cur != null && cur.moveToNext()) out.add(new String[]{cur.getString(0), cur.getString(1)});
        } catch (RuntimeException e) {
            // listing isn't essential: worst case an old file stays
        }
        return out;
    }

    private static boolean delete(ContentResolver cr, Uri tree, String docId) {
        try {
            return DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, docId));
        } catch (Exception e) {
            return false;
        }
    }

    /** The picked folder's name, for showing ("Meanwhile backups"). */
    static String folderName(ContentResolver cr, Uri tree) {
        try {
            final Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            try (Cursor cur = cr.query(doc, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (cur != null && cur.moveToFirst() && cur.getString(0) != null) return cur.getString(0);
            }
        } catch (RuntimeException e) {
            // fall through
        }
        return "Chosen folder";
    }
}
