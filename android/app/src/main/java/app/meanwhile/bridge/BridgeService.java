package app.meanwhile.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.service.notification.NotificationListenerService;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import app.meanwhile.bridge.core.Reading;

/**
 * Foreground service that keeps the process at high priority and runs a once-a-minute
 * watchdog: restarts the HTTP endpoint if needed, asks Android to rebind the notification
 * listener if it dropped, and raises an alert if no reading has arrived for a while.
 */
public class BridgeService extends Service {

    static final int STATUS_ID = 1;
    static final int STALE_ID = 2;
    static final String CHANNEL_STATUS = "status";
    static final String CHANNEL_STALE = "stale";
    static final long WATCHDOG_MS = 60_000L;
    static final long REBIND_AFTER_MS = 2 * 60_000L;
    static final long STALE_REALERT_MS = 30 * 60_000L;

    private static volatile boolean running;
    /** Title and text of the status notification as last posted, to skip identical reposts. */
    private static volatile String postedStatus;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            try {
                tick();
            } catch (RuntimeException e) {
                EventLog.log(BridgeService.this, "ERROR", "watchdog: " + e);
            }
            handler.postDelayed(this, WATCHDOG_MS);
        }
    };
    private long lastStaleAlert;

    static void start(Context context) {
        try {
            context.startForegroundService(new Intent(context, BridgeService.class));
        } catch (RuntimeException e) {
            // e.g. ForegroundServiceStartNotAllowedException when started from the background
            // without a battery-optimisation exemption. Capture still works while the listener
            // is bound; the UI flags the missing exemption.
            EventLog.log(context, "SERVICE", "could not start keep-alive service: " + e);
        }
    }

    /** Updates the status notification after a new reading (no-op if not running). */
    static void refresh(Context context) {
        if (!running) return;
        postStatus(context);
    }

    /** Posts the status notification if what it says has changed. */
    private static void postStatus(Context context) {
        final String[] t = statusTexts(Bridge.get(context));
        final String key = t[0] + "\n" + t[1];
        if (key.equals(postedStatus)) return;
        final NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.notify(STATUS_ID, buildStatus(context, t));
        postedStatus = key;
    }

    static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels(this);
        goForeground();
        running = true;
        EventLog.log(this, "SERVICE", "keep-alive service started");
        Bridge.get(this).ensureServer();
        handler.post(watchdog);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // every startForegroundService() must be matched by startForeground()
        goForeground();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        postedStatus = null;
        handler.removeCallbacks(watchdog);
        EventLog.log(this, "SERVICE", "keep-alive service stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void goForeground() {
        final String[] t = statusTexts(Bridge.get(this));
        final Notification n = buildStatus(this, t);
        postedStatus = t[0] + "\n" + t[1];
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(STATUS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(STATUS_ID, n);
        }
    }

    void tick() {
        final Bridge bridge = Bridge.get(this);
        bridge.ensureServer();

        final long now = System.currentTimeMillis();
        if (bridge.hasNotificationAccess() && !bridge.isListenerConnected()
                && now - bridge.listenerChangedAt() > REBIND_AFTER_MS) {
            EventLog.log(this, "WATCHDOG", "listener not connected; requesting rebind");
            NotificationListenerService.requestRebind(new ComponentName(this, EversenseListenerService.class));
        }

        final Reading last = bridge.latest();
        final boolean stale = last == null || now - last.timestamp > bridge.staleThresholdMs();
        final NotificationManager nm = getSystemService(NotificationManager.class);
        if (stale && last != null && bridge.settings.staleAlert() && now - lastStaleAlert > STALE_REALERT_MS) {
            lastStaleAlert = now;
            EventLog.log(this, "STALE", "no reading for " + ((now - last.timestamp) / 60_000) + " min: " + staleHint(bridge));
            nm.notify(STALE_ID, new Notification.Builder(this, CHANNEL_STALE)
                    .setSmallIcon(R.drawable.ic_stat_bridge)
                    .setContentTitle("No Eversense reading for " + ((now - last.timestamp) / 60_000) + " min")
                    .setContentText(staleHint(bridge))
                    .setContentIntent(openApp(this))
                    .setAutoCancel(true)
                    .build());
        } else if (!stale) {
            nm.cancel(STALE_ID);
            lastStaleAlert = 0;
        }
        postStatus(this);
    }

    private static String staleHint(Bridge bridge) {
        if (!bridge.hasNotificationAccess()) return "Notification access is off for EverBridge";
        if (!bridge.isListenerConnected()) return "Notification listener disconnected; open the app";
        if (bridge.installedEversensePackage() == null) return "Eversense app not found";
        return "Check the Eversense app and transmitter";
    }

    static void createChannels(Context context) {
        final NotificationManager nm = context.getSystemService(NotificationManager.class);
        final NotificationChannel status = new NotificationChannel(CHANNEL_STATUS, "Bridge status",
                NotificationManager.IMPORTANCE_LOW);
        status.setShowBadge(false);
        final NotificationChannel stale = new NotificationChannel(CHANNEL_STALE, "Missing readings",
                NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(status);
        nm.createNotificationChannel(stale);
    }

    /** {title, text} of the status notification. */
    private static String[] statusTexts(Bridge bridge) {
        final Reading last = bridge.latest();
        final String title;
        if (last == null) {
            title = "Waiting for first Eversense reading";
        } else {
            title = last.mgdl + " mg/dL at "
                    + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(last.timestamp));
        }
        final String server = bridge.server.isRunning()
                ? "Serving 127.0.0.1:" + bridge.server.port() + (bridge.server.isLan() ? " + LAN" : "")
                : "Endpoint down: " + bridge.server.lastError();
        final String listener = bridge.isListenerConnected() ? "listening" : "listener NOT connected";
        return new String[]{title, server + " · " + listener};
    }

    private static Notification buildStatus(Context context, String[] texts) {
        return new Notification.Builder(context, CHANNEL_STATUS)
                .setSmallIcon(R.drawable.ic_stat_bridge)
                .setContentTitle(texts[0])
                .setContentText(texts[1])
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(openApp(context))
                .build();
    }

    private static PendingIntent openApp(Context context) {
        return PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
