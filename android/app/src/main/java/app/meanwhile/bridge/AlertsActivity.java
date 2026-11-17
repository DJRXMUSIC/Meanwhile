package app.meanwhile.bridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

import app.meanwhile.bridge.core.ActiveAlert;
import app.meanwhile.bridge.core.AlertConfig;
import app.meanwhile.bridge.core.AlertRule;
import app.meanwhile.bridge.core.AlertSettings;
import app.meanwhile.bridge.core.Reading;
import app.meanwhile.bridge.core.XdripAlertImport;

/**
 * Glucose alerts, laid out like xDrip+'s: its Snooze screen (the alert going on, Snooze,
 * Disable / Re-Enable alerts), its Glucose Level Alerts List with the Edit Alert screen (same
 * fields), and its Glucose Alerts Settings, with one addition: the Alert volume level. Then
 * the optional import of an xDrip+ settings file.
 *
 * Automation (the emulator test, from adb only): {@code --es action import_file} imports
 * {@code xdrip-import.xml} from the app's external files folder; {@code --es action snooze}
 * presses Snooze. Both need {@code --es token} to match the {@code automation-token} file in
 * that folder, which only this app and adb can write (Android 11+ keeps other apps out).
 */
public class AlertsActivity extends Activity {

    static final String EXTRA_FROM_ALERT = "from_alert";
    static final String IMPORT_FILE = "xdrip-import.xml";
    static final String AUTOMATION_TOKEN = Automation.TOKEN_FILE;
    private static final int REQ_IMPORT = 1;
    private static final int REQ_SOUND = 2;
    private static final int REQ_PHONE_SOUND = 3;
    private static final long REFRESH_MS = 5_000L;
    private static final int RED = Color.rgb(211, 47, 47);
    private static final int AMBER = Color.rgb(239, 152, 0);
    private static final int GREEN = Color.rgb(46, 160, 120);
    private static final int GREY = Color.rgb(140, 140, 140);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            renderNow();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    private int textColor;
    private LinearLayout nowBox;
    private LinearLayout listBox;
    private LinearLayout optionsBox;
    /** The snooze length picked on this screen (0: the alert's own). */
    private int snoozeChoice;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showOverLockScreen(getIntent());
        final TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.textColorPrimary, tv, true);
        textColor = tv.resourceId != 0 ? getColor(tv.resourceId) : tv.data;
        setContentView(buildUi());
        renderAll();
        handleAutomation(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        showOverLockScreen(intent);
        renderNow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresher);
        super.onPause();
    }

    /** Opened by an alert that overrides silent mode: show over the lock screen so it can be snoozed. */
    private void showOverLockScreen(Intent intent) {
        if (Build.VERSION.SDK_INT >= 27 && intent != null && intent.getBooleanExtra(EXTRA_FROM_ALERT, false)) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
    }

    // ---------------------------------------------------------------- automation

    private void handleAutomation(Intent intent) {
        final String action = intent != null ? intent.getStringExtra("action") : null;
        if (action == null) return;
        if (!fromAdb(intent.getStringExtra("token"))) {
            EventLog.log(this, "ALERT", "ignored automation '" + action + "' not sent from adb");
            return;
        }
        if ("import_file".equals(action)) {
            final File f = new File(getExternalFilesDir(null), IMPORT_FILE);
            try (InputStream in = new FileInputStream(f)) {
                final XdripAlertImport.Result r = XdripAlertImport.parse(readAll(in));
                Alerts.post(this, a -> Alerts.importFrom(a, r), this::renderAll);
            } catch (IOException | IllegalArgumentException e) {
                EventLog.log(this, "ALERT", "automation import failed: " + e.getMessage());
            }
        } else if ("snooze".equals(action)) {
            Alerts.post(this, a -> Alerts.snoozeDefault(a, "alerts screen"), this::renderNow);
        }
    }

    /** The token matches the one adb put in this app's own folder. */
    private boolean fromAdb(String token) {
        return Automation.fromAdb(this, token);
    }

    // ---------------------------------------------------------------- rendering

    private void renderAll() {
        renderNow();
        renderList();
        renderOptions();
    }

    /** xDrip+'s Snooze screen: the alert going on, Snooze, and Disable / Re-Enable alerts. */
    private void renderNow() {
        nowBox.removeAllViews();
        final AlertConfig cfg = Alerts.config(this);
        final long now = System.currentTimeMillis();
        final ActiveAlert a = Alerts.active(this);
        final AlertRule rule = a == null ? null : cfg.rule(a.uuid);
        if (cfg.rules.isEmpty()) {
            nowBox.addView(text("No alerts set up. Create one below.", 15, true));
        } else if (rule == null) {
            final TextView t = text("No active alert", 17, true);
            t.setTextColor(GREEN);
            nowBox.addView(t);
        } else if (a.snoozed && !a.ready(now)) {
            nowBox.addView(text(title(rule) + " snoozed until " + clock(a.nextAlertAt), 17, true));
        } else {
            final Reading last = Bridge.get(this).latest();
            final TextView t = text("Active alert: " + title(rule) + (last != null ? " · " + Alerts.bgText(last.mgdl, cfg.units)
                    + " " + unitLabel(cfg.units) : ""), 19, true);
            t.setTextColor(rule.above ? AMBER : RED);
            nowBox.addView(t);
            if (snoozeChoice == 0) snoozeChoice = rule.snoozeMinutes();
            final LinearLayout row = new LinearLayout(this);
            row.addView(button("Snooze", v -> {
                final int m = snoozeChoice;
                Alerts.post(this, x -> Alerts.snooze(x, m, "alerts screen"), this::renderNow);
            }), weight());
            row.addView(button("for " + minutesLabel(snoozeChoice) + " ▾", v -> pickMinutes("Snooze for", m -> {
                snoozeChoice = m;
                renderNow();
            })), weight());
            nowBox.addView(row, marginTop(4));
        }

        final AlertSettings s = cfg.settings;
        nowBox.addView(disableRow(s.lowDisabledUntil, "Low", "low", now), marginTop(8));
        nowBox.addView(disableRow(s.highDisabledUntil, "High", "high", now));
        nowBox.addView(disableRow(s.allDisabledUntil, "All", "all", now));

        final NotificationManager dnd = getSystemService(NotificationManager.class);
        if (dnd != null && !dnd.isNotificationPolicyAccessGranted()) {
            final TextView hint = text("Alerts with Override phone Silent Mode sound on silent and through Do Not Disturb, "
                    + "except its \"total silence\" setting. To get through that too, allow Meanwhile v5 to manage Do Not Disturb.", 13, false);
            hint.setAlpha(0.75f);
            nowBox.addView(hint, marginTop(10));
            nowBox.addView(smallButton("Allow through Do Not Disturb", v ->
                    open(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))));
        }
        if (Build.VERSION.SDK_INT >= 34) {
            boolean allowed = true;
            try {
                allowed = dnd == null || dnd.canUseFullScreenIntent();
            } catch (RuntimeException ignored) {
                // can't tell: say nothing
            }
            if (!allowed) {
                final TextView hint = text("Alerts can't show over the lock screen (sound and vibration still work).", 13, false);
                hint.setAlpha(0.75f);
                nowBox.addView(hint, marginTop(8));
                nowBox.addView(smallButton("Allow over lock screen", v -> open(new Intent(
                        Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + getPackageName())))));
            }
        }
    }

    /** xDrip+'s "Disable Low alerts" / "Re-Enable Low Alerts" (and High, All). */
    private View disableRow(long until, String label, String which, long now) {
        if (until > now) {
            final LinearLayout row = new LinearLayout(this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            final TextView t = text(label + " alerts off until " + clock(until), 14, true);
            t.setTextColor(AMBER);
            row.addView(t, weight());
            row.addView(smallButton("Re-Enable " + label + " Alerts", v -> disable(which, 0)));
            return row;
        }
        return smallButton("Disable " + label + " alerts", v ->
                pickMinutes("Disable " + label.toLowerCase(Locale.ROOT) + " alerts for", m -> disable(which, m)));
    }

    private void disable(String which, int minutes) {
        Alerts.post(this, x -> Alerts.disable(x, which, minutes), this::renderNow);
    }

    /** xDrip+'s Glucose Level Alerts List: create buttons, then the low alerts and the high alerts. */
    private void renderList() {
        listBox.removeAllViews();
        final AlertConfig cfg = Alerts.config(this);
        final LinearLayout create = new LinearLayout(this);
        create.addView(button("Create low alert", v -> edit(null, false)), weight());
        create.addView(button("Create high alert", v -> edit(null, true)), weight());
        listBox.addView(create);
        final TextView hint = text("Tap an alert to edit it.", 12, false);
        hint.setAlpha(0.6f);
        listBox.addView(hint);
        for (boolean above : new boolean[]{false, true}) {
            final TextView h = text(above ? "HIGH ALERTS" : "LOW ALERTS", 12, true);
            h.setAlpha(0.6f);
            listBox.addView(h, marginTop(10));
            boolean any = false;
            for (AlertRule r : cfg.rules) {
                if (r.above != above) continue;
                any = true;
                listBox.addView(ruleRow(cfg, r), marginTop(6));
            }
            if (!any) {
                final TextView none = text("None", 14, false);
                none.setAlpha(0.6f);
                listBox.addView(none);
            }
        }
    }

    private View ruleRow(AlertConfig cfg, AlertRule r) {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.setBackground(rounded(Color.argb(22, Color.red(textColor), Color.green(textColor), Color.blue(textColor))));
        final TextView name = text(title(r) + (r.active ? "" : " (disabled)"), 16, true);
        name.setTextColor(!r.active ? GREY : r.above ? AMBER : RED);
        card.addView(name);
        card.addView(text(describe(r, cfg.units), 13, false));
        final TextView tone = text("Alert Tone: …", 13, false);
        card.addView(tone);
        describeSound(r.soundUri, tone);
        card.setOnClickListener(v -> edit(r.uuid, r.above));
        card.setOnLongClickListener(v -> {
            edit(r.uuid, r.above);
            return true;
        });
        return card;
    }

    /** xDrip+'s Glucose Alerts Settings, with this app's volume level added. */
    private void renderOptions() {
        optionsBox.removeAllViews();
        final AlertSettings s = Alerts.config(this).settings;

        optionsBox.addView(text("Alert Volume Profile", 15, true));
        final RadioGroup profile = new RadioGroup(this);
        for (int i = 0; i < PROFILES.length; i++) {
            final RadioButton b = new RadioButton(this);
            b.setId(i + 1);
            b.setText(PROFILES[i][1]);
            profile.addView(b);
            if (PROFILES[i][0].equals(s.profile)) profile.check(i + 1);
        }
        profile.setOnCheckedChangeListener((g, id) -> {
            final String p = PROFILES[id - 1][0];
            final AlertConfig c = Alerts.config(this);
            if (!p.equals(c.settings.profile)) save(c.withSettings(c.settings.withProfile(p)), "volume profile " + p, false);
        });
        optionsBox.addView(profile);

        final TextView volumeLabel = text(volumeText(s.volumePercent), 15, true);
        optionsBox.addView(volumeLabel, marginTop(10));
        // 1% to 100%, every percent: the slider, ±1% for fine tuning, and quick picks
        final SeekBar volume = new SeekBar(this);
        volume.setMax(100 - AlertSettings.MIN_VOLUME);
        volume.setProgress(s.volumePercent - AlertSettings.MIN_VOLUME);
        volume.setContentDescription("Alert volume");
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar b, int progress, boolean fromUser) {
                volumeLabel.setText(volumeText(AlertSettings.MIN_VOLUME + progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar b) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar b) {
                setVolume(AlertSettings.MIN_VOLUME + b.getProgress());
            }
        });
        optionsBox.addView(volume);
        final LinearLayout steps = new LinearLayout(this);
        for (int delta : new int[]{-5, -1, 1, 5}) {
            steps.addView(smallButton((delta > 0 ? "+" : "−") + Math.abs(delta) + "%", v -> {
                volume.setProgress(Math.max(0, Math.min(volume.getMax(), volume.getProgress() + delta)));
                setVolume(AlertSettings.MIN_VOLUME + volume.getProgress());
            }), weight());
        }
        optionsBox.addView(steps);
        final LinearLayout picks = new LinearLayout(this);
        for (int p : new int[]{10, 25, 50, 75, 100}) {
            picks.addView(smallButton(p + "%", v -> {
                volume.setProgress(p - AlertSettings.MIN_VOLUME);
                setVolume(p);
            }), weight());
        }
        optionsBox.addView(picks);
        optionsBox.addView(button("Test volume", v -> testVolume()));
        optionsBox.addView(summary("Any level from 1% to 100%. How loud alerts get, as a share of the phone's alarm (or media) volume. High plays at "
                + "this level, Medium at 70% of it, and Ascending climbs to it. xDrip+ has no such setting: it always "
                + "plays at 100%."));

        optionsBox.addView(option("Smart Snoozing", "Keep snoozing if glucose is heading in the right direction",
                s.smartSnoozing, (c, v) -> c.withOptions(v, c.smartAlerting, c.disableStale,
                        c.staleMinutes, c.startSnoozed, c.delayAscending3min, c.ascendingToMedium, c.noAlarmsDuringCalls)));
        optionsBox.addView(option("Smart Alerting", "Don't alert if glucose is heading in the right direction",
                s.smartAlerting, (c, v) -> c.withOptions(c.smartSnoozing, v, c.disableStale, c.staleMinutes, c.startSnoozed,
                        c.delayAscending3min, c.ascendingToMedium, c.noAlarmsDuringCalls)));
        optionsBox.addView(option("Don't alarm during phone calls", "Alarms silenced during telephone calls",
                s.noAlarmsDuringCalls, (c, v) -> c.withOptions(c.smartSnoozing, c.smartAlerting, c.disableStale, c.staleMinutes,
                        c.startSnoozed, c.delayAscending3min, c.ascendingToMedium, v)));
        optionsBox.addView(option("Start Snoozed", "Alerts start out snoozed and must persist for a while to actually trigger",
                s.startSnoozed, (c, v) -> c.withOptions(c.smartSnoozing, c.smartAlerting, c.disableStale, c.staleMinutes, v,
                        c.delayAscending3min, c.ascendingToMedium, c.noAlarmsDuringCalls)));
        optionsBox.addView(option("Suppress Alerts if missed readings",
                "Suppress snoozed and active alerts after predefined period of missed readings",
                s.disableStale, (c, v) -> c.withOptions(c.smartSnoozing, c.smartAlerting, v, c.staleMinutes, c.startSnoozed,
                        c.delayAscending3min, c.ascendingToMedium, c.noAlarmsDuringCalls)));
        optionsBox.addView(summary("Suppress snoozed and active alerts after .. minutes (minimum 10)"));
        final EditText staleMin = field(InputType.TYPE_CLASS_NUMBER);
        staleMin.setText(String.valueOf(s.staleMinutes));
        staleMin.setOnFocusChangeListener((v, focused) -> {
            if (focused) return;
            final AlertConfig c = Alerts.config(this);
            final int m = clamp(parseInt(staleMin.getText().toString(), c.settings.staleMinutes), 10, 1440);
            staleMin.setText(String.valueOf(m));
            if (c.settings.staleMinutes == m) return;
            final AlertSettings o = c.settings;
            save(c.withSettings(o.withOptions(o.smartSnoozing, o.smartAlerting, o.disableStale, m, o.startSnoozed,
                    o.delayAscending3min, o.ascendingToMedium, o.noAlarmsDuringCalls)), "suppress after " + m + " min", false);
        });
        optionsBox.addView(staleMin);

        final TextView asc = text("Ascending Volume settings", 15, true);
        optionsBox.addView(asc, marginTop(12));
        optionsBox.addView(option("Delay Ascending Volume", "For ascending volume profile, delay the start of the alert "
                        + "sound by 3 minutes. The vibration (if enabled) starts as soon as the alert triggers regardless of this setting.",
                s.delayAscending3min, (c, v) -> c.withOptions(c.smartSnoozing, c.smartAlerting, c.disableStale, c.staleMinutes,
                        c.startSnoozed, v, c.ascendingToMedium, c.noAlarmsDuringCalls)));
        optionsBox.addView(option("Ascend to medium", "When enabled, ascending volume profile will ascend to medium volume "
                        + "instead of maximum",
                s.ascendingToMedium, (c, v) -> c.withOptions(c.smartSnoozing, c.smartAlerting, c.disableStale, c.staleMinutes,
                        c.startSnoozed, c.delayAscending3min, v, c.noAlarmsDuringCalls)));
    }

    /** xDrip+'s profiles, in its order and with its names. */
    static final String[][] PROFILES = {
            {AlertSettings.HIGH, "High"},
            {AlertSettings.MEDIUM, "Medium"},
            {AlertSettings.ASCENDING, "Ascending"},
            {AlertSettings.VIBRATE_ONLY, "Vibrate only"},
            {AlertSettings.SILENT, "Silent"},
    };

    static String volumeText(int percent) {
        return "Alert volume: " + percent + "%";
    }

    private void setVolume(int percent) {
        final AlertConfig c = Alerts.config(this);
        if (c.settings.volumePercent == percent) return;
        save(c.withSettings(c.settings.withVolume(percent)), "alert volume " + percent + "%", false);
    }

    /** Plays the first alert's sound (or the beep) at the level set. */
    private void testVolume() {
        final AlertConfig c = Alerts.config(this);
        final AlertRule r = c.rules.isEmpty()
                ? new AlertRule("test", "Test", true, false, 0, true, 0, 0, 1, 0, false, true, true, AlertConfig.SOUND_BEEP)
                : c.rules.get(0);
        playTest(r, "Playing " + title(r) + "'s sound at " + c.settings.volumePercent + "%.");
    }

    /** Stores a change on the alert thread (which checks again with it), then redraws. */
    private void save(AlertConfig cfg, String what, boolean redrawList) {
        Alerts.post(this, x -> Alerts.update(x, cfg, what), () -> {
            if (isDestroyed()) return;
            if (redrawList) renderList();
            renderNow();
        });
    }

    private interface Change {
        AlertSettings apply(AlertSettings current, boolean value);
    }

    private View option(String title, String summary, boolean checked, Change change) {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final CheckBox b = new CheckBox(this);
        b.setText(title);
        b.setChecked(checked);
        b.setOnCheckedChangeListener((v, value) -> {
            final AlertConfig c = Alerts.config(this);
            save(c.withSettings(change.apply(c.settings, value)), title + ": " + value, false);
        });
        box.addView(b);
        box.addView(summary(summary));
        return box;
    }

    private TextView summary(String s) {
        final TextView t = text(s, 12, false);
        t.setAlpha(0.65f);
        t.setPadding(dp(4), 0, 0, dp(6));
        return t;
    }

    // ---------------------------------------------------------------- texts

    static String title(AlertRule r) {
        final String n = r.name.trim();
        return n.isEmpty() ? (r.above ? "High alert" : "Low alert") : n;
    }

    static String describe(AlertRule r, String units) {
        final StringBuilder sb = new StringBuilder();
        sb.append(r.above ? "≥ " : "≤ ").append(threshold(r.threshold, units)).append(' ').append(unitLabel(units));
        sb.append(r.allDay ? " · all day" : " · " + hhmm(r.startMinutes) + "–" + hhmm(r.endMinutes));
        sb.append("\nRe-raise every ").append(r.minutesBetween).append(" min · Default Snooze ").append(r.snoozeMinutes()).append(" min");
        sb.append("\nVibrate ").append(r.vibrate ? "on" : "off");
        sb.append(r.overrideSilent ? " · Override Silent Mode" : " · quiet in silent mode");
        sb.append(r.forceSpeaker ? " · Force Speaker" : "");
        return sb.toString();
    }

    static String threshold(double mgdl, String units) {
        if ("mmol".equals(units)) return String.format(Locale.getDefault(), "%.1f", mgdl / Alerts.MGDL_PER_MMOL);
        return String.valueOf(Math.round(mgdl));
    }

    static String unitLabel(String units) {
        return "mmol".equals(units) ? "mmol/L" : "mg/dL";
    }

    static String minutesLabel(int m) {
        return m < 60 ? m + " minutes" : m % 60 == 0 ? (m / 60) + (m == 60 ? " hour" : " hours")
                : (m / 60) + " h " + (m % 60) + " min";
    }

    private static String hhmm(int minutes) {
        return String.format(Locale.ROOT, "%02d:%02d", (minutes / 60) % 24, minutes % 60);
    }

    private static String clock(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(ms));
    }

    /** Fills in the sound's name, and whether this app can open it, off the main thread. */
    private void describeSound(String uri, TextView into) {
        if (uri == null || AlertConfig.SOUND_BEEP.equals(uri) || AlertConfig.SOUND_VIBRATION.equals(uri)) {
            into.setText("Alert Tone: " + (uri == null ? "the phone's alarm sound" : soundName(this, uri)));
            return;
        }
        final Context app = getApplicationContext();
        new Thread(() -> {
            final String name = soundName(app, uri);
            // a phone sound plays even if this app can't open it: Android plays it for the app
            final boolean opens = AlertSound.isPhoneSound(uri) || canOpen(app, uri);
            handler.post(() -> {
                if (opens) {
                    into.setText("Alert Tone: " + name);
                } else {
                    into.setText("Alert Tone: " + name + " — Meanwhile v5 can't open this file, so it plays the phone's "
                            + "alarm sound. Edit the alert and choose the file again.");
                    into.setTextColor(AMBER);
                }
            });
        }, "sound-check").start();
    }

    static boolean canOpen(Context c, String uri) {
        try (AssetFileDescriptor fd = c.getContentResolver().openAssetFileDescriptor(Uri.parse(uri), "r")) {
            return fd != null;
        } catch (Exception e) {
            return false;
        }
    }

    static String soundName(Context c, String uri) {
        if (AlertConfig.SOUND_BEEP.equals(uri)) return "Beep (built in)";
        if (AlertConfig.SOUND_VIBRATION.equals(uri)) return "Vibration sound (built in)";
        final Uri u = Uri.parse(uri);
        try {
            final String title = u.getQueryParameter("title");
            if (title != null && !title.isEmpty()) return title;
        } catch (UnsupportedOperationException ignored) {
            // not hierarchical
        }
        if (AlertSound.isPhoneSound(uri)) {
            try {
                final Ringtone r = RingtoneManager.getRingtone(c, u);
                final String t = r != null ? r.getTitle(c) : null;
                if (t != null && !t.isEmpty()) return t + " (phone sound)";
            } catch (RuntimeException ignored) {
                // fall through
            }
        }
        try (Cursor cur = c.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getString(0);
        } catch (Exception ignored) {
            // no permission: fall through
        }
        final String last = u.getLastPathSegment();
        return last != null ? last : uri;
    }

    // ---------------------------------------------------------------- the alert editor (xDrip+'s Edit Alert)

    /** The alert being edited, before Save alert. */
    private static final class Draft {
        String uuid;
        boolean isNew;
        boolean above;
        String sound;
        int defaultSnooze;
        boolean allDay;
        int start;
        int end;
        TextView toneLabel;
        Button snoozeButton;
        Button startButton;
        Button endButton;
    }

    private Draft draft;

    /** @param uuid the alert to edit, or null to create one ({@code above}: high or low) */
    private void edit(String uuid, boolean above) {
        final AlertConfig cfg = Alerts.config(this);
        final AlertRule existing = uuid == null ? null : cfg.rule(uuid);
        if (uuid != null && existing == null) return;
        final AlertRule r = existing != null ? existing : new AlertRule(UUID.randomUUID().toString(),
                above ? "High alert" : "Low alert", true, above, above ? 180 : 70, true, 0, 0, above ? 3 : 1,
                above ? 120 : 35, true, true, true, above ? AlertConfig.SOUND_VIBRATION : AlertConfig.SOUND_BEEP);
        final Draft d = new Draft();
        d.uuid = r.uuid;
        d.isNew = existing == null;
        d.above = r.above;
        d.sound = r.soundUri;
        d.defaultSnooze = r.snoozeMinutes();
        d.allDay = r.allDay;
        d.start = r.startMinutes;
        d.end = r.endMinutes;
        draft = d;

        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));

        box.addView(label("Alert Name:"));
        final EditText name = field(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setText(r.name.trim());
        box.addView(name);

        box.addView(label("Threshold (" + (r.above ? "at or above, " : "at or below, ") + unitLabel(cfg.units) + "):"));
        final EditText threshold = field(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        threshold.setText(threshold(r.threshold, cfg.units));
        box.addView(threshold);

        box.addView(label("Default Snooze:"));
        d.snoozeButton = smallButton(minutesLabel(d.defaultSnooze), v -> pickMinutes("Default Snooze", m -> {
            d.defaultSnooze = m;
            d.snoozeButton.setText(minutesLabel(m));
        }));
        box.addView(d.snoozeButton);

        box.addView(label("Re-raise every x minutes if unacknowledged:"));
        final EditText reraise = field(InputType.TYPE_CLASS_NUMBER);
        reraise.setText(String.valueOf(r.minutesBetween));
        box.addView(reraise);

        box.addView(label("Alert Tone:"));
        d.toneLabel = text("", 14, false);
        describeSound(d.sound, d.toneLabel);
        box.addView(d.toneLabel);
        box.addView(smallButton("Choose file", v -> chooseSound(d)));

        box.addView(label("Select time for alert:"));
        final CheckBox allDay = new CheckBox(this);
        allDay.setText("all day");
        allDay.setChecked(d.allDay);
        box.addView(allDay);
        final LinearLayout times = new LinearLayout(this);
        d.startButton = smallButton(hhmm(d.start), v -> pickTime(d.start, m -> {
            d.start = m;
            d.startButton.setText(hhmm(m));
        }));
        d.endButton = smallButton(hhmm(d.end), v -> pickTime(d.end, m -> {
            d.end = m;
            d.endButton.setText(hhmm(m));
        }));
        times.addView(d.startButton, weight());
        times.addView(text("  –  ", 14, false));
        times.addView(d.endButton, weight());
        times.setVisibility(d.allDay ? View.GONE : View.VISIBLE);
        allDay.setOnCheckedChangeListener((b, on) -> {
            d.allDay = on;
            times.setVisibility(on ? View.GONE : View.VISIBLE);
        });
        box.addView(times);

        final CheckBox override = new CheckBox(this);
        override.setText("Override phone Silent Mode");
        override.setChecked(r.overrideSilent);
        box.addView(override);
        final TextView silentWarning = text("Warning, no alert will be played when phone in silent/vibrate mode!!!", 13, true);
        silentWarning.setTextColor(RED);
        silentWarning.setVisibility(r.overrideSilent ? View.GONE : View.VISIBLE);
        override.setOnCheckedChangeListener((b, on) -> silentWarning.setVisibility(on ? View.GONE : View.VISIBLE));
        box.addView(silentWarning);
        final CheckBox speaker = new CheckBox(this);
        speaker.setText("Force Speaker");
        speaker.setChecked(r.forceSpeaker);
        box.addView(speaker);
        box.addView(summary("Plays on the alarm volume (else the media volume)."));
        final CheckBox vibrate = new CheckBox(this);
        vibrate.setText("Vibrate on alert");
        vibrate.setChecked(r.vibrate);
        box.addView(vibrate);
        final CheckBox disabled = new CheckBox(this);
        disabled.setText("Disable alert");
        disabled.setChecked(!r.active);
        box.addView(disabled);

        final LinearLayout tests = new LinearLayout(this);
        tests.addView(smallButton("Test alert", v -> playTest(new AlertRule(d.uuid, name.getText().toString(), true, d.above,
                r.threshold, true, 0, 0, 1, 0, vibrate.isChecked(), true, speaker.isChecked(), d.sound),
                "Playing the alert's tone at " + Alerts.config(this).settings.volumePercent + "% (Alert volume).")), weight());
        if (!d.isNew) {
            tests.addView(smallButton("Snooze alert before it fires", v -> pickMinutes("Snooze before it fires, for", m ->
                    Alerts.post(this, x -> Alerts.preSnooze(x, d.uuid, m), this::renderNow))), weight());
        }
        box.addView(tests, marginTop(8));

        final ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        final AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle((d.isNew ? "Adding " : "Editing ") + (d.above ? "high" : "low") + " alert")
                .setView(scroll)
                .setPositiveButton("Save alert", null)
                .setNegativeButton("Cancel", null);
        if (!d.isNew) b.setNeutralButton("Remove alert", null);
        final AlertDialog dialog = b.create();
        dialog.setOnDismissListener(x -> {
            if (draft == d) draft = null;
        });
        dialog.setOnShowListener(x -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                final double mgdl = parseThreshold(threshold.getText().toString(), cfg.units);
                final int rep = parseInt(reraise.getText().toString(), -1);
                if (mgdl < 40 || mgdl > 400) {
                    threshold.setError("Between " + threshold(40, cfg.units) + " and " + threshold(400, cfg.units));
                    return;
                }
                if (rep < 1 || rep > 120) {
                    reraise.setError("1 to 120 minutes");
                    return;
                }
                final String n = name.getText().toString().trim();
                final AlertRule saved = new AlertRule(d.uuid, n.isEmpty() ? (d.above ? "High alert" : "Low alert") : n,
                        !disabled.isChecked(), d.above, mgdl, d.allDay, d.start, d.end, rep, d.defaultSnooze,
                        vibrate.isChecked(), override.isChecked(), speaker.isChecked(), d.sound);
                save(Alerts.config(this).withSaved(saved), (d.isNew ? "added " : "saved ") + saved + " re-raise " + rep
                        + " snooze " + d.defaultSnooze + " vibrate " + saved.vibrate + " override silent " + saved.overrideSilent
                        + " force speaker " + saved.forceSpeaker + " tone " + saved.soundUri, true);
                dialog.dismiss();
            });
            if (!d.isNew) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> new AlertDialog.Builder(this)
                        .setTitle("Remove " + title(r) + "?")
                        .setPositiveButton("Remove alert", (q, w) -> {
                            save(Alerts.config(this).without(d.uuid), "removed " + r, true);
                            dialog.dismiss();
                        })
                        .setNegativeButton("Cancel", null)
                        .show());
            }
        });
        dialog.show();
    }

    private TextView label(String s) {
        final TextView t = text(s, 13, true);
        t.setPadding(0, dp(10), 0, 0);
        return t;
    }

    private interface MinuteOfDay {
        void picked(int minuteOfDay);
    }

    private void pickTime(int minuteOfDay, MinuteOfDay then) {
        new TimePickerDialog(this, (v, h, m) -> then.picked(h * 60 + m), minuteOfDay / 60 % 24, minuteOfDay % 60, true).show();
    }

    private void chooseSound(Draft d) {
        new AlertDialog.Builder(this)
                .setTitle("Alert Tone")
                .setItems(new String[]{"Phone sounds (ringtones, notifications, alarms)…", "Beep (built in)",
                        "Vibration sound (built in)", "Choose your own sound file…", "The phone's alarm sound"}, (q, which) -> {
                    if (which == 0) {
                        final Intent i = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alert Tone")
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false);
                        if (AlertSound.isPhoneSound(d.sound)) {
                            i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(d.sound));
                        }
                        startForResult(i, REQ_PHONE_SOUND);
                    } else if (which == 1) {
                        setDraftSound(d, AlertConfig.SOUND_BEEP);
                    } else if (which == 2) {
                        setDraftSound(d, AlertConfig.SOUND_VIBRATION);
                    } else if (which == 3) {
                        final Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE)
                                .setType("audio/*");
                        startForResult(i, REQ_SOUND);
                    } else {
                        setDraftSound(d, null);
                    }
                })
                .show();
    }

    @SuppressWarnings("deprecation")
    private static Uri pickedRingtone(Intent data) {
        if (Build.VERSION.SDK_INT >= 33) return data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri.class);
        return data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
    }

    private void setDraftSound(Draft d, String uri) {
        d.sound = uri;
        d.toneLabel.setTextColor(textColor);
        describeSound(uri, d.toneLabel);
    }

    private void playTest(AlertRule r, String message) {
        Alerts.test(this, r);
        new AlertDialog.Builder(this)
                .setTitle("Testing " + title(r))
                .setMessage(message)
                .setPositiveButton("Stop", (q, w) -> Alerts.stopTest(this))
                .setOnCancelListener(q -> Alerts.stopTest(this))
                .show();
    }

    static double parseThreshold(String s, String units) {
        try {
            final double v = Double.parseDouble(s.trim().replace(',', '.'));
            return "mmol".equals(units) ? Math.round(v * Alerts.MGDL_PER_MMOL) : v;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void startImport() {
        final Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        startForResult(i, REQ_IMPORT);
    }

    private boolean startForResult(Intent i, int code) {
        try {
            startActivityForResult(i, code);
            return true;
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No file picker on this phone", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        final Uri uri = resultCode == RESULT_OK && data != null ? data.getData() : null;
        if (requestCode == REQ_PHONE_SOUND) {
            final Uri picked = resultCode == RESULT_OK && data != null ? pickedRingtone(data) : null;
            if (picked == null) return;
            if (draft != null) setDraftSound(draft, picked.toString());
            else Toast.makeText(this, "Open the alert again and choose the sound", Toast.LENGTH_LONG).show();
            return;
        }
        if (requestCode == REQ_SOUND) {
            if (uri == null) return;
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException e) {
                EventLog.log(this, "ALERT", "no lasting access to " + uri + ": " + e.getMessage());
            }
            if (draft != null) {
                setDraftSound(draft, uri.toString());
            } else {
                Toast.makeText(this, "Open the alert again and choose the file", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_IMPORT && uri != null) {
            final Context app = getApplicationContext();
            new Thread(() -> {
                XdripAlertImport.Result r = null;
                String error = null;
                try (InputStream in = app.getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("the file can't be opened");
                    r = XdripAlertImport.parse(readAll(in));
                } catch (IOException | SecurityException e) {
                    error = "The file can't be read: " + e.getMessage();
                } catch (IllegalArgumentException e) {
                    error = e.getMessage();
                }
                final XdripAlertImport.Result result = r;
                final String err = error;
                handler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (result != null) confirmImport(result);
                    else new AlertDialog.Builder(this).setTitle("Can't import").setMessage(err)
                            .setPositiveButton("OK", null).show();
                });
            }, "alert-import").start();
        }
    }

    private void confirmImport(XdripAlertImport.Result r) {
        final StringBuilder sb = new StringBuilder();
        for (AlertRule rule : r.rules) {
            sb.append("• ").append(title(rule)).append(rule.active ? "" : " (off in xDrip+)").append('\n')
                    .append(describe(rule, r.units)).append("\n\n");
        }
        sb.append("Volume: ").append(r.settings.profile).append('\n');
        sb.append("This replaces the alerts set up in Meanwhile v5.");
        new AlertDialog.Builder(this)
                .setTitle("Import " + r.rules.size() + (r.rules.size() == 1 ? " alert" : " alerts") + " from xDrip+?")
                .setMessage(sb.toString())
                .setPositiveButton("Import", (d, w) -> {
                    Alerts.post(this, x -> Alerts.importFrom(x, r), this::renderAll);
                    new AlertDialog.Builder(this)
                            .setTitle("Imported")
                            .setMessage("Use Test on each alert to hear it. Once you're happy, turn the alerts off in "
                                    + "xDrip+, or both apps will alarm.")
                            .setPositiveButton("OK", null)
                            .show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    static byte[] readAll(InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > XdripAlertImport.MAX_BYTES) throw new IOException("the file is too big for a settings file");
        }
        return out.toByteArray();
    }

    private interface Minutes {
        void picked(int minutes);
    }

    private void pickMinutes(String title, Minutes then) {
        final String[] labels = new String[Alerts.SNOOZE_CHOICES.length];
        for (int i = 0; i < labels.length; i++) labels[i] = minutesLabel(Alerts.SNOOZE_CHOICES[i]);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels, (d, which) -> then.picked(Alerts.SNOOZE_CHOICES[which]))
                .show();
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Not available on this phone", Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        final LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(16);
        col.setPadding(pad, pad, pad, pad);
        col.addView(text("Glucose alerts", 20, true));
        col.addView(header("Snooze"));

        nowBox = new LinearLayout(this);
        nowBox.setOrientation(LinearLayout.VERTICAL);
        nowBox.setPadding(dp(16), dp(12), dp(16), dp(12));
        nowBox.setBackground(rounded(Color.argb(22, Color.red(textColor), Color.green(textColor), Color.blue(textColor))));
        col.addView(nowBox);

        col.addView(header("Glucose Level Alerts List"));
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(listBox);

        col.addView(header("Glucose Alerts Settings"));
        optionsBox = new LinearLayout(this);
        optionsBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(optionsBox);

        col.addView(header("Import from xDrip+ (optional)"));
        final TextView how = text("The alerts above start out as your xDrip+ ones. To bring over a different xDrip+ setup: "
                + "in xDrip+, menu → Import / Export Features → Save settings, then tap Import and pick "
                + "com.eveningoutpost.dexdrip_preferences.xml from Downloads/xDrip-export. It replaces the alerts above "
                + "(the Alert volume stays as set here).", 13, false);
        how.setAlpha(0.75f);
        col.addView(how);
        col.addView(button("Import xDrip+ settings file", v -> startImport()));

        final ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            StatusActivity.applyInsets(v, insets);
            return insets;
        });
        return scroll;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private GradientDrawable rounded(int color) {
        final GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(14));
        return g;
    }

    private LinearLayout.LayoutParams marginTop(int topDp) {
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private static LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(String s, int sp, boolean bold) {
        final TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView header(String s) {
        final TextView t = text(s, 16, true);
        t.setPadding(0, dp(20), 0, dp(6));
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        final Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private Button smallButton(String label, View.OnClickListener l) {
        final Button b = button(label, l);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        return b;
    }

    private EditText field(int inputType) {
        final EditText e = new EditText(this);
        e.setInputType(inputType);
        e.setSingleLine(true);
        return e;
    }
}
