package app.meanwhile.bridge;

import android.app.Notification;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RemoteViews;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.meanwhile.bridge.core.CaptureEngine;

/**
 * Reads the visible text out of a posted notification.
 *
 * The custom-view path is xDrip+ {@code UiBasedCollector.processRemote/getTextViews}: inflate
 * the app's RemoteViews and collect every visible TextView. The extras path improves on
 * xDrip's {@code extras.getString(EXTRA_TITLE)}, which returns null when the title is a
 * styled CharSequence.
 */
final class NotificationTexts {

    private NotificationTexts() {
    }

    static CaptureEngine.Input toInput(Context context, StatusBarNotification sbn, long receivedAt) {
        final Notification n = sbn.getNotification();
        final String[] viewError = new String[1];
        return new CaptureEngine.Input(
                sbn.getPackageName(),
                n != null ? n.when : 0,
                sbn.getPostTime(),
                receivedAt,
                sbn.isOngoing(),
                n != null ? viewTexts(context, n, viewError) : null,
                n != null ? extrasTexts(n) : null,
                viewError[0]);
    }

    /** Visible TextView texts of the custom content view, or null if there is none/it fails. */
    static List<String> viewTexts(Context context, Notification n) {
        return viewTexts(context, n, new String[1]);
    }

    @SuppressWarnings("deprecation") // Notification.contentView is what custom-view apps populate
    private static List<String> viewTexts(Context context, Notification n, String[] error) {
        final RemoteViews rv = n.contentView;
        if (rv == null) return null;
        try {
            final View root = rv.apply(context, null);
            final List<String> out = new ArrayList<>();
            collect(root, out);
            return out;
        } catch (RuntimeException e) {
            error[0] = "view not readable: " + e;
            Log.w(Bridge.TAG, "Could not inflate notification view from " + rv.getPackage() + ": " + e);
            return null;
        }
    }

    static List<String> extrasTexts(Notification n) {
        final Bundle e = n.extras;
        if (e == null) return null;
        return Arrays.asList(
                str(e.getCharSequence(Notification.EXTRA_TITLE)),
                str(e.getCharSequence(Notification.EXTRA_TEXT)),
                str(e.getCharSequence(Notification.EXTRA_BIG_TEXT)),
                str(e.getCharSequence(Notification.EXTRA_SUB_TEXT)),
                str(e.getCharSequence(Notification.EXTRA_INFO_TEXT)),
                str(e.getCharSequence(Notification.EXTRA_TITLE_BIG)));
    }

    private static void collect(View v, List<String> out) {
        if (v.getVisibility() != View.VISIBLE) return;
        if (v instanceof TextView) {
            final CharSequence t = ((TextView) v).getText();
            if (t != null) out.add(t.toString());
        } else if (v instanceof ViewGroup) {
            final ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    private static String str(CharSequence cs) {
        return cs == null ? null : cs.toString();
    }
}
