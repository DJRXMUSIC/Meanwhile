package app.meanwhile.bridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * The alarm as a screen: over the lock screen (the notification's full-screen intent) or
 * when the notification is tapped. While the phone is in use, {@link AlarmOverlay} shows the
 * same panel over whatever app is open. It closes itself once the alert is snoozed or over.
 */
public class AlertScreenActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            if (snoozed || !AlarmPanel.alertGoing(AlertScreenActivity.this)) {
                finish();
                return;
            }
            panel.render();
            handler.postDelayed(this, 1_000);
        }
    };
    private AlarmPanel panel;
    private boolean snoozed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        panel = new AlarmPanel(this, new AlarmPanel.Actions() {
            @Override
            public void snooze(int minutes, String why) {
                AlertScreenActivity.this.snooze(minutes, why);
            }

            @Override
            public void hide() {
                finish();
            }
        });
        panel.setOnApplyWindowInsetsListener((v, insets) -> insets);
        setContentView(panel);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        panel.render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(watch);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(watch);
        super.onPause();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            snooze(AlarmPanel.MEDIUM, "alarm volume key");
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** @param minutes 0 for the alert's own snooze */
    private void snooze(int minutes, String why) {
        if (snoozed) return;
        snoozed = true;
        if (minutes > 0) Alerts.post(this, x -> Alerts.snooze(x, minutes, why), null);
        else Alerts.post(this, x -> Alerts.snoozeDefault(x, why), null);
        finish();
    }

    View rootForTests() {
        return panel;
    }
}
