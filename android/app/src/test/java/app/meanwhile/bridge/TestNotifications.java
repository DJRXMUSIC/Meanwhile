package app.meanwhile.bridge;

import android.app.Notification;
import android.content.Context;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import android.text.SpannableString;
import android.text.style.StyleSpan;
import android.widget.RemoteViews;

/** Builds notifications shaped like the Eversense app's. */
final class TestNotifications {

    static final String EV365 = "com.senseonics.eversense365.us";

    private TestNotifications() {
    }

    /**
     * Custom content view with "value" and "unit" TextViews. Uses the framework's
     * two_line_list_item (LinearLayout + TextViews); RemoteViews refuses layouts with
     * non-@RemoteView classes such as simple_list_item_2's TwoLineListItem.
     */
    @SuppressWarnings("deprecation")
    static Notification customView(Context ctx, String value, String unit, long when) {
        final RemoteViews rv = new RemoteViews(ctx.getPackageName(), android.R.layout.two_line_list_item);
        rv.setTextViewText(android.R.id.text1, value);
        rv.setTextViewText(android.R.id.text2, unit);
        final Notification n = new Notification.Builder(ctx, "glucose")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setWhen(when)
                .build();
        n.contentView = rv;
        return n;
    }

    /** Standard notification; title is a styled CharSequence (xDrip's getString() misses these). */
    static Notification extrasOnly(Context ctx, String title, long when) {
        final SpannableString styled = new SpannableString(title);
        styled.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, title.length(), 0);
        return new Notification.Builder(ctx, "glucose")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(styled)
                .setContentText("Stable")
                .setWhen(when)
                .build();
    }

    @SuppressWarnings("deprecation")
    static StatusBarNotification sbn(String pkg, Notification n, long postTime) {
        return new StatusBarNotification(pkg, pkg, 1, null, Process.myUid(), 0, 0, n,
                Process.myUserHandle(), postTime);
    }
}
