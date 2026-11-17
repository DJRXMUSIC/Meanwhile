package app.meanwhile.bridge;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
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
 * What the alarm shows, on the alarm screen and in the overlay: the alert, the glucose, one
 * big SNOOZE, quick snoozes, and Hide. A volume button snoozes too.
 */
final class AlarmPanel extends LinearLayout {

    static final int[] QUICK_SNOOZE = {15, 30, 60, 120};

    interface Actions {
        /** @param minutes 0 for the alert's own snooze */
        void snooze(int minutes, String why);

        void hide();
    }

    private final Actions actions;

    AlarmPanel(Context c, Actions actions) {
        super(c);
        this.actions = actions;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setFocusable(true);
        setFocusableInTouchMode(true);
        render();
    }

    /** Volume up or down snoozes, as in xDrip+ ("buttons silence alarms"); back hides. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        final int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_UP) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) actions.snooze(0, "alarm volume key");
            return true;
        }
        if (k == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) actions.hide();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** Whether an alert is sounding now (not snoozed, not over). */
    static boolean alertGoing(Context c) {
        final ActiveAlert a = Alerts.active(c);
        return a != null && !(a.snoozed && !a.ready(System.currentTimeMillis()))
                && Alerts.config(c).rule(a.uuid) != null;
    }

    void render() {
        removeAllViews();
        final Context c = getContext();
        final AlertConfig cfg = Alerts.config(c);
        final ActiveAlert a = Alerts.active(c);
        final AlertRule rule = a == null ? null : cfg.rule(a.uuid);
        final int color = rule == null ? Color.rgb(60, 60, 66) : rule.above ? Color.rgb(176, 96, 0) : Color.rgb(176, 24, 24);
        setBackgroundColor(color);
        setPadding(dp(20), dp(40), dp(20), dp(28));

        addView(text(rule == null ? "No active alert" : Alerts.title(rule), 26, true), wrap(dp(8)));
        final Reading last = Bridge.get(c).latest();
        if (last != null) {
            addView(text(Alerts.bgText(last.mgdl, cfg.units), 96, true), wrap(dp(8)));
            addView(text(("mmol".equals(cfg.units) ? "mmol/L" : "mg/dL") + " · "
                    + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(last.timestamp)), 18, false), wrap(0));
        }

        final int minutes = rule != null ? rule.snoozeMinutes() : 0;
        final Button big = new Button(c);
        big.setText("SNOOZE\n" + minutes + " min");
        big.setAllCaps(false);
        big.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setTextColor(color);
        big.setBackground(rounded(Color.WHITE, dp(28)));
        big.setOnClickListener(v -> actions.snooze(0, "alarm"));
        big.setContentDescription("Snooze " + minutes + " minutes");
        final LayoutParams bigLp = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        bigLp.topMargin = dp(28);
        addView(big, bigLp);

        final LinearLayout quick = new LinearLayout(c);
        for (int m : QUICK_SNOOZE) {
            final Button b = smallButton(m < 60 ? m + " min" : (m / 60) + " h");
            b.setOnClickListener(v -> actions.snooze(m, "alarm"));
            final LayoutParams lp = new LayoutParams(0, dp(64), 1f);
            lp.setMargins(dp(4), 0, dp(4), 0);
            quick.addView(b, lp);
        }
        addView(quick, wrap(dp(16)));

        final Button hide = smallButton("Hide");
        hide.setOnClickListener(v -> actions.hide());
        addView(hide, wrap(dp(12)));
    }

    private LayoutParams wrap(int top) {
        final LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private TextView text(String s, int sp, boolean bold) {
        final TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button smallButton(String label) {
        final Button b = new Button(getContext());
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
}
