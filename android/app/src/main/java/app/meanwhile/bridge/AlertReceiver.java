package app.meanwhile.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The alert's wake-up timer, its notification's Snooze button, and a swipe on it. Not exported. */
public class AlertReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent != null ? intent.getAction() : null;
        if (action == null) return;
        final Context app = context.getApplicationContext();
        final long raisedAt = intent.getLongExtra(Alerts.EXTRA_RAISED_AT, 0);
        final PendingResult pending = goAsync();
        Alerts.handler().post(() -> {
            try {
                switch (action) {
                    case Alerts.ACTION_CHECK:
                        Alerts.checkNow(app, "timer");
                        break;
                    case Alerts.ACTION_SNOOZE:
                        Alerts.snoozeDefault(app, "notification button");
                        break;
                    case Alerts.ACTION_DISMISSED:
                        Alerts.dismissed(app, raisedAt);
                        break;
                    default:
                        break;
                }
            } catch (RuntimeException e) {
                EventLog.log(app, "ALERT", action + " failed: " + e);
            } finally {
                pending.finish();
            }
        });
    }
}
