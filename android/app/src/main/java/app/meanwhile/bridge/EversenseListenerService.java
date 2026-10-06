package app.meanwhile.bridge;

import android.content.ComponentName;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import app.meanwhile.bridge.core.CaptureEngine;

/**
 * Receives the Eversense app's glucose notification, the same way xDrip+ does in Companion
 * App mode. Android keeps this service bound while notification access is granted, and
 * restarts the process for it if it dies.
 */
public class EversenseListenerService extends NotificationListenerService {

    /** The bound instance, for diagnostics (active-notification dump). */
    private static volatile EversenseListenerService connected;

    static EversenseListenerService connected() {
        return connected;
    }

    @Override
    public void onListenerConnected() {
        connected = this;
        final Bridge bridge = Bridge.get(this);
        bridge.setListenerConnected(true);
        EventLog.log(this, "LISTENER", "connected");
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
            EventLog.log(this, "LISTENER", "getActiveNotifications failed: " + e);
        }
    }

    @Override
    public void onListenerDisconnected() {
        connected = null;
        Bridge.get(this).setListenerConnected(false);
        EventLog.log(this, "LISTENER", "disconnected; requesting rebind");
        try {
            requestRebind(new ComponentName(this, EversenseListenerService.class));
        } catch (RuntimeException e) {
            EventLog.log(this, "LISTENER", "requestRebind failed: " + e);
        }
    }

    @Override
    public void onDestroy() {
        if (connected == this) connected = null;
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        handle(sbn);
    }

    void handle(StatusBarNotification sbn) {
        final long receivedAt = System.currentTimeMillis();
        try {
            if (sbn == null) return;
            if (SelfTest.NOTIFICATION_TAG.equals(sbn.getTag()) && getPackageName().equals(sbn.getPackageName())) {
                SelfTest.onReceived(NotificationTexts.toInput(this, sbn, receivedAt));
                return;
            }
            final Bridge bridge = Bridge.get(this);
            if (!bridge.engine.isMonitored(sbn.getPackageName())) return;
            bridge.noteMonitoredPost();
            final CaptureEngine.Result r = bridge.engine.process(NotificationTexts.toInput(this, sbn, receivedAt));
            EventLog.log(this, "CAPTURE", r.outcome
                    + (r.reading != null ? " " + r.reading.mgdl + " mg/dL ts=" + r.reading.timestamp
                    + " (" + r.reading.timestampSource + ")" : "")
                    + " when=" + r.input.when + " post=" + r.input.postTime + " recv=" + receivedAt
                    + " ongoing=" + r.input.ongoing + " id=" + sbn.getId()
                    + " " + r.input.describeTexts()
                    + (r.detail != null ? " :: " + r.detail : ""));
        } catch (RuntimeException e) {
            // never let a malformed notification take the listener down
            Log.e(Bridge.TAG, "Failed to process notification", e);
            EventLog.log(this, "ERROR", "processing notification: " + e);
        }
    }
}
