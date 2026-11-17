package app.meanwhile.bridge;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The emulator test's key to actions an ordinary app must not be able to trigger: a token in
 * this app's own external files folder, which only this app and adb can write (Android 11+
 * keeps other apps out). An automation extra is acted on only with the matching token.
 */
final class Automation {

    static final String TOKEN_FILE = "automation-token";

    private Automation() {
    }

    static boolean fromAdb(Context c, String token) {
        if (Build.VERSION.SDK_INT < 30 || token == null || token.length() < 16) return false;
        final File f = new File(c.getExternalFilesDir(null), TOKEN_FILE);
        try (InputStream in = new FileInputStream(f)) {
            return token.equals(new String(AlertsActivity.readAll(in), StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            return false;
        }
    }

    /** A file adb put in this app's folder, by plain name only; null otherwise. */
    static File stagedFile(Context c, String name) {
        if (name == null || !name.matches("[A-Za-z0-9._-]{1,80}") || name.startsWith(".")) return null;
        final File f = new File(c.getExternalFilesDir(null), name);
        return f.isFile() ? f : null;
    }
}
