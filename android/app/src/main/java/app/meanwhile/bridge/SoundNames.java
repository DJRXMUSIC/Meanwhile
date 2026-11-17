package app.meanwhile.bridge;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.provider.OpenableColumns;

import app.meanwhile.bridge.core.AlertConfig;

/** An alert tone's name, and whether it can be played. */
final class SoundNames {

    private SoundNames() {
    }

    static String name(Context c, String uri) {
        if (uri == null) return "Phone alarm";
        if (AlertConfig.SOUND_BEEP.equals(uri)) return "Beep";
        if (AlertConfig.SOUND_VIBRATION.equals(uri)) return "Vibration sound";
        final Uri u = Uri.parse(uri);
        try {
            final String title = u.getQueryParameter("title");
            if (title != null && !title.isEmpty()) return title;
        } catch (UnsupportedOperationException ignored) {
            // not hierarchical
        }
        if (AlertSound.isPhoneSound(uri)) {
            try {
                final Ringtone r = RingtoneManager.getRingtone(c, u);
                final String t = r != null ? r.getTitle(c) : null;
                if (t != null && !t.isEmpty()) return t;
            } catch (RuntimeException ignored) {
                // fall through
            }
        }
        try (Cursor cur = c.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getString(0);
        } catch (Exception ignored) {
            // no permission: fall through
        }
        final String last = u.getLastPathSegment();
        return last != null ? last : uri;
    }

    /** Whether it plays: a phone sound always does (Android plays it for the app). */
    static boolean playable(Context c, String uri) {
        return uri == null || AlertSound.isPhoneSound(uri) || canOpen(c, uri);
    }

    static boolean canOpen(Context c, String uri) {
        try (AssetFileDescriptor fd = c.getContentResolver().openAssetFileDescriptor(Uri.parse(uri), "r")) {
            return fd != null;
        } catch (Exception e) {
            return false;
        }
    }
}
