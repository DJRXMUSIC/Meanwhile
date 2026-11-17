package app.meanwhile.bridge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import app.meanwhile.bridge.core.ActiveAlert;
import app.meanwhile.bridge.core.AlertConfig;
import app.meanwhile.bridge.core.AlertRule;
import app.meanwhile.bridge.core.Reading;

/**
 * The alarm: shown over everything (and the lock screen) when a glucose alert sounds, with
 * one big Snooze. The volume buttons snooze too. It closes itself once the alert is snoozed
 * or over.
 */
public class AlertScreenActivity extends Activity {

    static final int[] QUICK_SNOOZE = {15, 30, 60, 120};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            if (!alertGoing()) {
                finish();
                return;
            }
            render();
            handler.postDelayed(this, 1_000);
        }
    };
    private LinearLayout root;
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
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            StatusActivity.applyInsets(v, insets);
            return insets;
        });
        setContentView(root);
        render();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        render();
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

    /** Volume up or down: snooze, as in xDrip+ ("buttons silence alarms"). */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            snooze(0, "alarm screen volume key");
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private boolean alertGoing() {
        if (snoozed) return false;
        final ActiveAlert a = Alerts.active(this);
        return a != null && !(a.snoozed && !a.ready(System.currentTimeMillis()))
                && Alerts.config(this).rule(a.uuid) != null;
    }

    /** @param minutes 0 for the alert's own snooze */
    private void snooze(int minutes, String why) {
        if (snoozed) return;
        snoozed = true;
        if (minutes > 0) Alerts.post(this, x -> Alerts.snooze(x, minutes, why), null);
        else Alerts.post(this, x -> Alerts.snoozeDefault(x, why), null);
        finish();
    }

    private void render() {
        root.removeAllViews();
        final AlertConfig cfg = Alerts.config(this);
        final ActiveAlert a = Alerts.active(this);
        final AlertRule rule = a == null ? null : cfg.rule(a.uuid);
        final int color = rule == null ? Color.rgb(60, 60, 66) : rule.above ? Color.rgb(176, 96, 0) : Color.rgb(176, 24, 24);
        root.setBackgroundColor(color);
        final int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        final TextView name = text(rule == null ? "No active alert" : Alerts.title(rule), 26, true);
        root.addView(name, wrap(dp(24)));
        final Reading last = Bridge.get(this).latest();
        if (last != null) {
            root.addView(text(Alerts.bgText(last.mgdl, cfg.units), 96, true), wrap(dp(8)));
            root.addView(text(("mmol".equals(cfg.units) ? "mmol/L" : "mg/dL") + " · "
                    + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(last.timestamp)), 18, false), wrap(0));
        }

        final int minutes = rule != null ? rule.snoozeMinutes() : 0;
        final Button big = new Button(this);
        big.setText("SNOOZE\n" + minutes + " min");
        big.setAllCaps(false);
        big.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setTextColor(color);
        big.setBackground(rounded(Color.WHITE, dp(28)));
        big.setOnClickListener(v -> snooze(0, "alarm screen"));
        big.setContentDescription("Snooze " + minutes + " minutes");
        final LinearLayout.LayoutParams bigLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        bigLp.topMargin = dp(28);
        root.addView(big, bigLp);

        final LinearLayout quick = new LinearLayout(this);
        for (int m : QUICK_SNOOZE) {
            final Button b = smallButton(m < 60 ? m + " min" : (m / 60) + " h");
            b.setOnClickListener(v -> snooze(m, "alarm screen"));
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(64), 1f);
            lp.setMargins(dp(4), 0, dp(4), 0);
            quick.addView(b, lp);
        }
        root.addView(quick, wrap(dp(16)));

        final Button hide = smallButton("Hide");
        hide.setOnClickListener(v -> finish());
        root.addView(hide, wrap(dp(12)));
    }

    private LinearLayout.LayoutParams wrap(int top) {
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private TextView text(String s, int sp, boolean bold) {
        final TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button smallButton(String label) {
        final Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setTextColor(Color.WHITE);
        b.setBackground(rounded(Color.argb(60, 255, 255, 255), dp(16)));
        return b;
    }

    private GradientDrawable rounded(int color, int radius) {
        final GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    View rootForTests() {
        return root;
    }
}
