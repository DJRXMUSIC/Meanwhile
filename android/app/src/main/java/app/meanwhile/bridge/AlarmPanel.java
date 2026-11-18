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
 * What the alarm shows, on the alarm screen and in the overlay: black, the alert and the
 * glucose, then the snooze lengths top to bottom with 90 minutes as the big one in the
 * middle, and Hide. A volume button snoozes for 90 minutes too.
 */
final class AlarmPanel extends LinearLayout {

    /** The big one in the middle, and what a volume button gives. */
    static final int MEDIUM = 90;
    static final int[] SNOOZES = {30, 60, MEDIUM, 120, 150, 180};
    /** "Until wake": the next 06:30. */
    static final int WAKE_MINUTE_OF_DAY = 6 * 60 + 30;
    /** Until wake is offered only while wake is this close (overnight), never as an all-day silence. */
    static final int WAKE_OFFER_WITHIN_MIN = 12 * 60;

    private static final int INK = Color.WHITE;
    private static final int MUTED = Color.rgb(150, 150, 150);
    private static final int LINE = Color.rgb(64, 64, 64);

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

    /** Volume up or down snoozes for {@link #MEDIUM} minutes; back hides. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        final int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_UP) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) actions.snooze(MEDIUM, "alarm volume key");
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

    /** Minutes from {@code now} to the next {@code minuteOfDay} (local time), 1 to 1440. */
    static int minutesUntil(int minuteOfDay, long now) {
        final java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(now);
        final java.util.Calendar at = (java.util.Calendar) cal.clone();
        at.set(java.util.Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        at.set(java.util.Calendar.MINUTE, minuteOfDay % 60);
        at.set(java.util.Calendar.SECOND, 0);
        at.set(java.util.Calendar.MILLISECOND, 0);
        if (!at.after(cal)) at.add(java.util.Calendar.DAY_OF_MONTH, 1);
        return (int) Math.max(1, Math.round((at.getTimeInMillis() - now) / 60_000.0));
    }

    static String label(int m) {
        if (m < 120) return m + " min";
        return (m % 60 == 0 ? String.valueOf(m / 60) : String.valueOf(m / 60.0)) + " h";
    }

    void render() {
        removeAllViews();
        final Context c = getContext();
        final AlertConfig cfg = Alerts.config(c);
        final ActiveAlert a = Alerts.active(c);
        final AlertRule rule = a == null ? null : cfg.rule(a.uuid);
        setBackgroundColor(Color.BLACK);
        setPadding(dp(24), dp(36), dp(24), dp(20));

        final TextView title = text(rule == null ? "No active alert" : Alerts.title(rule), 18, false);
        title.setTextColor(MUTED);
        title.setLetterSpacing(0.08f);
        addView(title, wrap(0));
        final Reading last = Bridge.get(c).latest();
        if (last != null) {
            addView(text(Alerts.bgText(last.mgdl, cfg.units), 88, true), wrap(dp(2)));
            final TextView sub = text(("mmol".equals(cfg.units) ? "mmol/L" : "mg/dL") + " · "
                    + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(last.timestamp)), 16, false);
            sub.setTextColor(MUTED);
            addView(sub, wrap(0));
        }

        // room above the choices, so they sit low, under the thumb
        addView(new android.view.View(c), new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        final TextView heading = text("Snooze", 14, false);
        heading.setTextColor(MUTED);
        addView(heading, wrap(dp(12)));
        for (int m : SNOOZES) {
            final boolean medium = m == MEDIUM;
            final Button b = choice(label(m), medium);
            b.setContentDescription("Snooze " + m + " minutes");
            b.setOnClickListener(v -> actions.snooze(m, "alarm"));
            final LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(medium ? 84 : 50));
            lp.topMargin = dp(medium ? 10 : 8);
            if (medium) lp.bottomMargin = dp(2);
            addView(b, lp);
        }
        final int toWake = minutesUntil(WAKE_MINUTE_OF_DAY, System.currentTimeMillis());
        if (toWake <= WAKE_OFFER_WITHIN_MIN) {
            final Button wake = choice("Until 6:30 am", false);
            wake.setContentDescription("Snooze until 6:30 in the morning");
            // worked out on the tap, so a screen left open doesn't snooze past 6:30
            wake.setOnClickListener(v -> actions.snooze(minutesUntil(WAKE_MINUTE_OF_DAY, System.currentTimeMillis()), "alarm until wake"));
            final LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(50));
            lp.topMargin = dp(8);
            addView(wake, lp);
        }

        final Button hide = new Button(c);
        hide.setText("Hide");
        hide.setAllCaps(false);
        hide.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        hide.setTextColor(MUTED);
        hide.setBackgroundColor(Color.TRANSPARENT);
        hide.setOnClickListener(v -> actions.hide());
        addView(hide, wrap(dp(6)));
    }

    /** A snooze length: outlined, or solid white for the big one. */
    private Button choice(String label, boolean medium) {
        final Button b = new Button(getContext());
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, medium ? 30 : 19);
        b.setTypeface(medium ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        b.setTextColor(medium ? Color.BLACK : INK);
        final GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(medium ? 22 : 14));
        if (medium) g.setColor(Color.WHITE);
        else {
            g.setColor(Color.BLACK);
            g.setStroke(dp(1), LINE);
        }
        b.setBackground(g);
        b.setStateListAnimator(null);
        return b;
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

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
