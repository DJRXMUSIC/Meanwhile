package app.meanwhile.bridge;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.service.notification.StatusBarNotification;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import app.meanwhile.bridge.core.ActiveAlert;
import app.meanwhile.bridge.core.AlertConfig;
import app.meanwhile.bridge.core.AlertEngine;
import app.meanwhile.bridge.core.AlertRule;
import app.meanwhile.bridge.core.AlertSettings;
import app.meanwhile.bridge.core.Reading;

/**
 * Low and high glucose alerts, set up from xDrip+'s settings and working as xDrip+'s do.
 * {@link AlertEngine} decides; this stores the alerts and the one going on, makes the noise,
 * shows the notification (with Snooze; swiping it away snoozes too, as in xDrip+), and wakes
 * the phone for the next check. Checked after every stored reading, at each repeat or end of
 * snooze (an exact alarm), and once a minute by the keep-alive service.
 */
final class Alerts {

    static final String CHANNEL = "glucose_alerts";
    static final int NOTIFICATION_ID = 3;
    static final String ACTION_CHECK = "app.meanwhile.bridge.alerts.CHECK";
    static final String ACTION_SNOOZE = "app.meanwhile.bridge.alerts.SNOOZE";
    static final String ACTION_DISMISSED = "app.meanwhile.bridge.alerts.DISMISSED";
    static final String EXTRA_RAISED_AT = "raised_at";
    /** xDrip+ {@code MINIMUM_CANCEL_DELAY}: a swipe this soon after the alert is ignored. */
    static final long MINIMUM_CANCEL_DELAY = 2000;
    /** xDrip+'s snooze choices, minutes. */
    /** Snooze lengths offered in the Alerts tab: 30-minute steps up to 5 hours. */
    static final int[] SNOOZE_CHOICES = {30, 60, 90, 120, 150, 180, 210, 240, 270, 300};
    /** {@link #disable} minutes for "off until I turn it on". */
    static final int OFF_UNTIL_TURNED_ON = -1;
    /** What "off until I turn it on" stores: far enough ahead to never run out (2100-01-01). */
    static final long OFF_FOREVER_UNTIL = 4_102_444_800_000L;

    private static final String PREFS = "alerts";
    private static final String KEY_CONFIG = "config";
    private static final String KEY_ACTIVE = "active";
    /** When the latest snooze was pressed (the app returns to Now 2 minutes after one, if left alone). */
    private static final String KEY_SNOOZED_AT = "snoozed_at";
    static final double MGDL_PER_MMOL = 18.0182;

    /** Makes the sound and vibration. Replaced in tests. */
    interface Noise {
        void alert(Context context, AlertRule rule, AlertSettings settings, AlertEngine.Sound sound);

        /** Plays the alert's sound once, loud, as a test. */
        void test(Context context, AlertRule rule, float volume);

        void stop(Context context);
    }

    static volatile Noise noise;
    private static Handler handler;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Alerts() {
    }

    private static Noise noise() {
        Noise n = noise;
        if (n == null) {
            synchronized (Alerts.class) {
                if (noise == null) noise = new AlertSound(handler());
                n = noise;
            }
        }
        return n;
    }

    /** The thread all alert work runs on (and the media player calls back on). */
    static synchronized Handler handler() {
        if (handler == null) {
            final HandlerThread t = new HandlerThread("alerts");
            t.start();
            handler = new Handler(t.getLooper());
        }
        return handler;
    }

    /** Tests only: finish queued work and stop the alert thread. */
    static void resetForTests() {
        final Handler h;
        synchronized (Alerts.class) {
            h = handler;
            handler = null;
            noise = null;
        }
        if (h == null) return;
        h.getLooper().quitSafely();
        try {
            h.getLooper().getThread().join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Runs {@code work} on the alert thread, then {@code then} (if any) on the main thread. */
    static void post(Context c, Consumer<Context> work, Runnable then) {
        final Context app = c.getApplicationContext();
        handler().post(() -> {
            try {
                work.accept(app);
            } catch (RuntimeException e) {
                EventLog.log(app, "ALERT", "failed: " + e);
            }
            if (then != null) MAIN.post(then);
        });
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static AlertConfig config(Context c) {
        return AlertConfig.fromJson(prefs(c).getString(KEY_CONFIG, null));
    }

    static long snoozedAt(Context c) {
        return prefs(c).getLong(KEY_SNOOZED_AT, 0);
    }

    static ActiveAlert active(Context c) {
        return AlertConfig.activeFromJson(prefs(c).getString(KEY_ACTIVE, null));
    }

    private static void saveConfig(Context c, AlertConfig cfg) {
        prefs(c).edit().putString(KEY_CONFIG, cfg.toJson()).commit();
    }

    private static void saveActive(Context c, ActiveAlert a) {
        prefs(c).edit().putString(KEY_ACTIVE, AlertConfig.activeToJson(a)).commit();
    }

    // ------------------------------------------------------------------ checking

    /** Checks on the alert thread; returns at once. */
    static void check(Context c, String why) {
        final Context app = c.getApplicationContext();
        handler().post(() -> {
            try {
                checkNow(app, why);
            } catch (RuntimeException e) {
                EventLog.log(app, "ALERT", "check failed: " + e);
            }
        });
    }

    /** Decides and acts now. Returns the decision, or null when no alerts are set up. */
    static synchronized AlertEngine.Result checkNow(Context c, String why) {
        final AlertConfig cfg = config(c);
        final ActiveAlert before = active(c);
        final long now = System.currentTimeMillis();
        if (cfg.rules.isEmpty()) {
            if (before != null) saveActive(c, null);
            silence(c);
            cancelSchedule(c);
            return null;
        }
        final List<Reading> recent = Bridge.get(c).store.latest(3);
        final Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        final int minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);

        final AlertEngine.Result r = AlertEngine.evaluate(cfg.rules, cfg.settings, before, recent, now, minute);
        if (r.active != before) saveActive(c, r.active);
        switch (r.action) {
            case PLAY:
                EventLog.log(c, "ALERT", why + ": " + r);
                raise(c, cfg, r.rule, recent.get(0), r.minutesSinceStart, now);
                break;
            case STOP:
                final boolean wasShowing = silence(c);
                if (wasShowing || (before != null && r.active != before)) EventLog.log(c, "ALERT", why + ": " + r);
                break;
            default:
                if (r.active != before) EventLog.log(c, "ALERT", why + ": " + r);
        }
        schedule(c, r.active, now);
        return r;
    }

    private static void raise(Context c, AlertConfig cfg, AlertRule rule, Reading last, int minutes, long now) {
        final AlertEngine.Sound sound = AlertEngine.sound(rule, cfg.settings, minutes);
        try {
            noise().alert(c, rule, cfg.settings, sound);
        } catch (RuntimeException e) {
            EventLog.log(c, "ALERT", "sound failed: " + e);
        }
        createChannel(c);
        final NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, build(c, cfg, rule, last, now));
        // over whatever is on screen while the phone is in use (the lock screen gets the
        // notification's full-screen alarm screen)
        AlarmOverlay.show(c);
    }

    /** xDrip+'s alert notification: "85 LOW WARNING", "LOW alert: 85 at 14:05", Snooze. */
    static Notification build(Context c, AlertConfig cfg, AlertRule rule, Reading last, long now) {
        final String bg = bgText(last.mgdl, cfg.units);
        final String time = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(now));
        final Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_bridge)
                .setContentTitle(bg + " " + rule.name.trim())
                .setContentText((rule.above ? "HIGH" : "LOW") + " alert: " + bg + " at " + time)
                .setContentIntent(openAlerts(c, false))
                .setDeleteIntent(broadcast(c, ACTION_DISMISSED, 2, now))
                .addAction(new Notification.Action.Builder(null, "Snooze " + rule.snoozeMinutes() + " min",
                        broadcast(c, ACTION_SNOOZE, 3, now)).build())
                .setWhen(now)
                .setShowWhen(true)
                // the alarm screen: straight up when locked, a tap away when not
                .setCategory(Notification.CATEGORY_ALARM)
                .setFullScreenIntent(openAlerts(c, true), true);
        return b.build();
    }

    static String bgText(double mgdl, String units) {
        if ("mmol".equals(units)) return String.format(Locale.getDefault(), "%.1f", mgdl / MGDL_PER_MMOL);
        return String.valueOf(Math.round(mgdl));
    }

    /** Stops the sound and removes the notification. Returns whether the notification was showing. */
    static boolean silence(Context c) {
        final Noise n = noise;
        if (n != null) n.stop(c);
        AlarmOverlay.hide(c);
        final NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return false;
        boolean showing = false;
        try {
            for (StatusBarNotification s : nm.getActiveNotifications()) {
                if (s.getId() == NOTIFICATION_ID) showing = true;
            }
        } catch (RuntimeException ignored) {
            showing = true;
        }
        if (showing) nm.cancel(NOTIFICATION_ID);
        return showing;
    }

    static void createChannel(Context c) {
        final NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        final NotificationChannel ch = new NotificationChannel(CHANNEL, "Glucose alerts", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Low and high alerts. Meanwhile v6 plays the alert's own sound and vibration itself.");
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    // ------------------------------------------------------------------ waking up

    private static void schedule(Context c, ActiveAlert a, long now) {
        final AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        final long at = AlertEngine.nextCheck(a, now);
        final PendingIntent pi = broadcast(c, ACTION_CHECK, 1, 0);
        final boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        try {
            if (exact && a != null && !a.snoozed) {
                // an alert is going on: repeat on time even in deep sleep
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, openAlerts(c, false)), pi);
            } else if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    private static void cancelSchedule(Context c) {
        final AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(broadcast(c, ACTION_CHECK, 1, 0));
    }

    private static PendingIntent broadcast(Context c, String action, int code, long raisedAt) {
        final Intent i = new Intent(c, AlertReceiver.class).setAction(action).putExtra(EXTRA_RAISED_AT, raisedAt);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** The alarm screen (big Snooze). */
    static Intent alarmScreen(Context c) {
        return new Intent(c, AlertScreenActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    static PendingIntent openAlerts(Context c, boolean fullScreen) {
        return PendingIntent.getActivity(c, fullScreen ? 5 : 4, alarmScreen(c),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static String title(AlertRule r) {
        final String n = r.name.trim();
        return n.isEmpty() ? (r.above ? "High alert" : "Low alert") : n;
    }

    // ------------------------------------------------------------------ what the user does

    /** The notification's Snooze button, a swipe, or the alert screen's snooze: the alert's own length. */
    static synchronized void snoozeDefault(Context c, String why) {
        final ActiveAlert a = active(c);
        final AlertRule rule = a == null ? null : config(c).rule(a.uuid);
        snooze(c, rule != null ? rule.snoozeMinutes() : 35, why);
    }

    static synchronized void snooze(Context c, int minutes, String why) {
        final ActiveAlert a = active(c);
        silence(c);
        if (a == null) return;
        final long now = System.currentTimeMillis();
        final ActiveAlert s = AlertEngine.snooze(a, minutes, now);
        saveActive(c, s);
        prefs(c).edit().putLong(KEY_SNOOZED_AT, now).commit();
        EventLog.log(c, "ALERT", "snoozed for " + minutes + " min (" + why + ")");
        schedule(c, s, now);
    }

    /** Cancels a snooze (or a snooze set before the alert fired): it sounds again at once if it still applies. */
    static synchronized void unsnooze(Context c, String why) {
        final ActiveAlert a = active(c);
        if (a == null || !a.snoozed) return;
        saveActive(c, null);
        EventLog.log(c, "ALERT", "snooze cancelled (" + why + ")");
        checkNow(c, "unsnoozed");
    }

    /** A swipe on the notification snoozes it, as in xDrip+, unless it came straight after posting. */
    static void dismissed(Context c, long raisedAt) {
        if (System.currentTimeMillis() - raisedAt < MINIMUM_CANCEL_DELAY) {
            EventLog.log(c, "ALERT", "notification removed right after posting: not a snooze");
            return;
        }
        snoozeDefault(c, "notification swiped away");
    }

    /** "Turn off … alerts for": {@code which} is "all", "low" or "high"; 0 minutes turns them back on. */
    static synchronized void disable(Context c, String which, int minutes) {
        final AlertConfig cfg = config(c);
        final AlertSettings s = cfg.settings;
        final long now = System.currentTimeMillis();
        final long until = minutes == OFF_UNTIL_TURNED_ON ? OFF_FOREVER_UNTIL
                : minutes <= 0 ? 0 : now + minutes * 60_000L;
        final AlertSettings next;
        switch (which) {
            case "low": next = s.withDisabledUntil(s.allDisabledUntil, until, s.highDisabledUntil); break;
            case "high": next = s.withDisabledUntil(s.allDisabledUntil, s.lowDisabledUntil, until); break;
            default: next = s.withDisabledUntil(until, s.lowDisabledUntil, s.highDisabledUntil);
        }
        saveConfig(c, cfg.withSettings(next));
        // like a snooze: the app goes back to Now two minutes after, if left alone
        if (until > 0) prefs(c).edit().putLong(KEY_SNOOZED_AT, now).commit();
        EventLog.log(c, "ALERT", until == 0 ? which + " alerts back on"
                : until == OFF_FOREVER_UNTIL ? which + " alerts off until turned on"
                : which + " alerts off for " + minutes + " min");
        checkNow(c, "alerts turned " + (minutes <= 0 ? "on" : "off"));
    }

    static synchronized void update(Context c, AlertConfig cfg, String what) {
        saveConfig(c, cfg);
        EventLog.log(c, "ALERT", "changed: " + what);
        checkNow(c, "settings changed");
    }

    /** Plays the alert's sound once, as loud as alerts get with the current volume level. */
    static void test(Context c, AlertRule rule) {
        final Context app = c.getApplicationContext();
        final float volume = AlertEngine.testVolume(rule);
        handler().post(() -> noise().test(app, rule, volume));
    }

    /** xDrip+ "Snooze alert before it fires": quiet for this alert until then. */
    static synchronized void preSnooze(Context c, String uuid, int minutes) {
        final long now = System.currentTimeMillis();
        final ActiveAlert a = new ActiveAlert(uuid, true, now + minutes * 60_000L, now);
        final ActiveAlert current = active(c);
        if (current != null && !current.uuid.equals(uuid) && !current.ready(now)) {
            EventLog.log(c, "ALERT", "pre-snooze not set: another alert is going on");
            return;
        }
        saveActive(c, a);
        EventLog.log(c, "ALERT", "pre-snoozed " + uuid + " for " + minutes + " min");
        checkNow(c, "pre-snooze");
    }

    static void stopTest(Context c) {
        final Context app = c.getApplicationContext();
        handler().post(() -> noise().stop(app));
    }
}
