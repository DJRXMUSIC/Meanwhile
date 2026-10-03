package app.meanwhile.bridge;

import android.content.ComponentName;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * Receives the Eversense app's glucose notification, the same way xDrip+ does in Companion
 * App mode. Android keeps this service bound while notification access is granted, and
 * restarts the process for it if it dies.
 */
public class EversenseListenerService extends NotificationListenerService {

    @Override
    public void onListenerConnected() {
        final Bridge bridge = Bridge.get(this);
        bridge.setListenerConnected(true);
        bridge.ensureServer();
        BridgeService.start(this);
        // Catch up on whatever is showing now (e.g. after a crash, update or reboot).
        // Already-stored readings come back as REPOST/DUPLICATE, not new rows.
        try {
            final StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification sbn : active) handle(sbn);
            }
        } catch (RuntimeException e) {
            Log.w(Bridge.TAG, "getActiveNotifications failed: " + e);
        }
    }

    @Override
    public void onListenerDisconnected() {
        Bridge.get(this).setListenerConnected(false);
        try {
            requestRebind(new ComponentName(this, EversenseListenerService.class));
        } catch (RuntimeException e) {
            Log.w(Bridge.TAG, "requestRebind failed: " + e);
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        handle(sbn);
    }

    void handle(StatusBarNotification sbn) {
        final long receivedAt = System.currentTimeMillis();
        try {
            final Bridge bridge = Bridge.get(this);
            if (sbn == null || !bridge.engine.isMonitored(sbn.getPackageName())) return;
            bridge.noteMonitoredPost();
            bridge.engine.process(NotificationTexts.toInput(this, sbn, receivedAt));
        } catch (RuntimeException e) {
            // never let a malformed notification take the listener down
            Log.e(Bridge.TAG, "Failed to process notification", e);
        }
    }
}
