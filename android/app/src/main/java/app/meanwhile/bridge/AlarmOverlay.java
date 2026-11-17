package app.meanwhile.bridge;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.WindowManager;

/**
 * The alarm drawn over whatever is on screen while the phone is in use, like an incoming
 * call: needs "Display over other apps". (On the lock screen, the notification's full-screen
 * intent shows {@link AlertScreenActivity} instead.) All on the main thread.
 */
final class AlarmOverlay {

    /** The window's title, which the emulator test looks for. */
    static final String TITLE = "MeanwhileAlarm";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static AlarmPanel panel;

    private AlarmOverlay() {
    }

    static boolean allowed(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    /** Shows it (or refreshes it if it is up). */
    static void show(Context c) {
        final Context app = c.getApplicationContext();
        MAIN.post(() -> showNow(app));
    }

    static void hide(Context c) {
        final Context app = c.getApplicationContext();
        MAIN.post(() -> hideNow(app));
    }

    static boolean isShowing() {
        return panel != null;
    }

    static AlarmPanel panelForTests() {
        return panel;
    }

    /** Tests only. */
    static void resetForTests() {
        panel = null;
    }

    private static void showNow(Context c) {
        if (!allowed(c) || !AlarmPanel.alertGoing(c)) return;
        if (panel != null) {
            panel.render();
            return;
        }
        final WindowManager wm = c.getSystemService(WindowManager.class);
        if (wm == null) return;
        final AlarmPanel p = new AlarmPanel(c, new AlarmPanel.Actions() {
            @Override
            public void snooze(int minutes, String why) {
                hideNow(c);
                if (minutes > 0) Alerts.post(c, x -> Alerts.snooze(x, minutes, why), null);
                else Alerts.post(c, x -> Alerts.snoozeDefault(x, why), null);
            }

            @Override
            public void hide() {
                hideNow(c);
            }
        });
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.OPAQUE);
        lp.setTitle(TITLE);
        try {
            wm.addView(p, lp);
            panel = p;
            p.requestFocus();
            EventLog.log(c, "ALERT", "alarm shown over the screen");
        } catch (RuntimeException e) {
            EventLog.log(c, "ALERT", "could not show the alarm over the screen: " + e);
        }
    }

    private static void hideNow(Context c) {
        final AlarmPanel p = panel;
        panel = null;
        if (p == null) return;
        final WindowManager wm = c.getSystemService(WindowManager.class);
        try {
            if (wm != null) wm.removeView(p);
        } catch (RuntimeException ignored) {
            // already gone
        }
    }
}
