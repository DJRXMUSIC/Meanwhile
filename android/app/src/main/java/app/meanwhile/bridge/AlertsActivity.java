package app.meanwhile.bridge;

import android.app.Activity;
import android.app.AlertDialog;
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

import app.meanwhile.bridge.core.ActiveAlert;
import app.meanwhile.bridge.core.AlertConfig;
import app.meanwhile.bridge.core.AlertRule;
import app.meanwhile.bridge.core.AlertSettings;
import app.meanwhile.bridge.core.Reading;
import app.meanwhile.bridge.core.XdripAlertImport;

/**
 * Glucose alerts: what is going on now (snooze it, or turn alerts off for a while, as on
 * xDrip+'s snooze screen), the alerts imported from xDrip+ (on/off, edit, sound, test), the
 * options for all alerts, and the import itself.
 *
 * Automation (the emulator test, from adb only): {@code --es action import_file} imports
 * {@code xdrip-import.xml} from the app's external files folder; {@code --es action snooze}
 * presses Snooze. Both need {@code --es token} to match the {@code automation-token} file in
 * that folder, which only this app and adb can write (Android 11+ keeps other apps out).
 */
public class AlertsActivity extends Activity {

    static final String EXTRA_FROM_ALERT = "from_alert";
    static final String IMPORT_FILE = "xdrip-import.xml";
    static final String AUTOMATION_TOKEN = "automation-token";
    private static final int REQ_IMPORT = 1;
    private static final int REQ_SOUND = 2;
    private static final String STATE_SOUND_FOR = "sound_for";
    private static final long REFRESH_MS = 5_000L;
    private static final int RED = Color.rgb(211, 47, 47);
    private static final int AMBER = Color.rgb(239, 152, 0);
    private static final int GREEN = Color.rgb(46, 160, 120);

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
    private String soundFor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) soundFor = savedInstanceState.getString(STATE_SOUND_FOR);
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
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(STATE_SOUND_FOR, soundFor);
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
        if (Build.VERSION.SDK_INT < 30 || token == null || token.length() < 16) return false;
        final File f = new File(getExternalFilesDir(null), AUTOMATION_TOKEN);
        try (InputStream in = new FileInputStream(f)) {
            return token.equals(new String(readAll(in), java.nio.charset.StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- rendering

    private void renderAll() {
        renderNow();
        renderList();
        renderOptions();
    }

    private void renderNow() {
        nowBox.removeAllViews();
        final AlertConfig cfg = Alerts.config(this);
        final long now = System.currentTimeMillis();
        if (cfg.rules.isEmpty()) {
            nowBox.addView(text("No alerts set up yet. Import them from xDrip+ below.", 15, true));
            return;
        }
        final ActiveAlert a = Alerts.active(this);
        final AlertRule rule = a == null ? null : cfg.rule(a.uuid);
        if (rule == null) {
            final TextView t = text("No alert going on", 17, true);
            t.setTextColor(GREEN);
            nowBox.addView(t);
        } else if (a.snoozed && !a.ready(now)) {
            nowBox.addView(text(title(rule) + " — snoozed until " + clock(a.nextAlertAt), 17, true));
        } else {
            final Reading last = Bridge.get(this).latest();
            final TextView t = text(title(rule) + (last != null ? ": " + Alerts.bgText(last.mgdl, cfg.units)
                    + " " + unitLabel(cfg.units) : ""), 20, true);
            t.setTextColor(rule.above ? AMBER : RED);
            nowBox.addView(t);
            final LinearLayout row = new LinearLayout(this);
            row.addView(button("Snooze " + rule.snoozeMinutes() + " min", v ->
                    Alerts.post(this, x -> Alerts.snoozeDefault(x, "alerts screen"), this::renderNow)), weight());
            row.addView(button("Snooze for…", v -> pickMinutes("Snooze for", m ->
                    Alerts.post(this, x -> Alerts.snooze(x, m, "alerts screen"), this::renderNow))), weight());
            nowBox.addView(row);
        }

        final AlertSettings s = cfg.settings;
        addDisabledRow(s.allDisabledUntil, "All alerts", "all", now);
        addDisabledRow(s.lowDisabledUntil, "Low alerts", "low", now);
        addDisabledRow(s.highDisabledUntil, "High alerts", "high", now);
        final LinearLayout off = new LinearLayout(this);
        off.addView(smallButton("All off for…", v -> pickMinutes("Turn all alerts off for", m -> disable("all", m))), weight());
        off.addView(smallButton("Lows off for…", v -> pickMinutes("Turn low alerts off for", m -> disable("low", m))), weight());
        off.addView(smallButton("Highs off for…", v -> pickMinutes("Turn high alerts off for", m -> disable("high", m))), weight());
        nowBox.addView(off, marginTop(6));

        final NotificationManager dnd = getSystemService(NotificationManager.class);
        if (dnd != null && !dnd.isNotificationPolicyAccessGranted()) {
            final TextView hint = text("Critical alerts sound on silent and through Do Not Disturb, except its "
                    + "\"total silence\" setting. To get through that too, allow EverBridge to manage Do Not Disturb.", 13, false);
            hint.setAlpha(0.75f);
            nowBox.addView(hint, marginTop(8));
            nowBox.addView(smallButton("Allow through Do Not Disturb", v ->
                    open(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))));
        }

        if (Build.VERSION.SDK_INT >= 34) {
            final NotificationManager nm = getSystemService(NotificationManager.class);
            boolean allowed = true;
            try {
                allowed = nm == null || nm.canUseFullScreenIntent();
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

    private void addDisabledRow(long until, String label, String which, long now) {
        if (until <= now) return;
        final LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        final TextView t = text(label + " off until " + clock(until), 14, true);
        t.setTextColor(AMBER);
        row.addView(t, weight());
        row.addView(smallButton("Turn back on", v -> disable(which, 0)));
        nowBox.addView(row, marginTop(6));
    }

    private void disable(String which, int minutes) {
        Alerts.post(this, x -> Alerts.disable(x, which, minutes), this::renderNow);
    }

    private void renderList() {
        listBox.removeAllViews();
        final AlertConfig cfg = Alerts.config(this);
        for (AlertRule r : cfg.rules) listBox.addView(ruleCard(cfg, r), marginTop(8));
        if (cfg.rules.isEmpty()) {
            final TextView t = text("None yet.", 14, false);
            t.setAlpha(0.7f);
            listBox.addView(t);
        }
    }

    private View ruleCard(AlertConfig cfg, AlertRule r) {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.setBackground(rounded(Color.argb(22, Color.red(textColor), Color.green(textColor), Color.blue(textColor))));

        final LinearLayout top = new LinearLayout(this);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        final TextView name = text(title(r), 16, true);
        name.setTextColor(r.above ? AMBER : RED);
        top.addView(name, weight());
        final CheckBox on = new CheckBox(this);
        on.setText("On");
        on.setChecked(r.active);
        on.setOnCheckedChangeListener((b, checked) -> {
            final AlertConfig c = Alerts.config(this);
            final AlertRule cur = c.rule(r.uuid);
            if (cur == null || cur.active == checked) return;
            save(c.withRule(cur.withActive(checked)), title(cur) + (checked ? " on" : " off"), true);
        });
        top.addView(on);
        card.addView(top);

        card.addView(text(describe(r, cfg.units), 13, false));
        final TextView sound = text("Sound: …", 13, false);
        card.addView(sound);
        describeSound(r.soundUri, sound);

        final LinearLayout row = new LinearLayout(this);
        row.addView(smallButton("Edit", v -> edit(r.uuid)), weight());
        row.addView(smallButton("Sound", v -> chooseSound(r.uuid)), weight());
        row.addView(smallButton("Test", v -> test(r.uuid)), weight());
        card.addView(row);
        return card;
    }

    private void renderOptions() {
        optionsBox.removeAllViews();
        final AlertSettings s = Alerts.config(this).settings;
        optionsBox.addView(text("Volume", 14, true));
        final RadioGroup profile = new RadioGroup(this);
        final String[][] profiles = {
                {AlertSettings.ASCENDING, "Ascending: vibrate first, then louder each minute"},
                {AlertSettings.HIGH, "High: full volume"},
                {AlertSettings.MEDIUM, "Medium"},
                {AlertSettings.VIBRATE_ONLY, "Vibrate only"},
                {AlertSettings.SILENT, "Silent"},
        };
        for (int i = 0; i < profiles.length; i++) {
            final RadioButton b = new RadioButton(this);
            b.setId(i + 1);
            b.setText(profiles[i][1]);
            profile.addView(b);
            if (profiles[i][0].equals(s.profile)) profile.check(i + 1);
        }
        profile.setOnCheckedChangeListener((g, id) -> {
            final String p = profiles[id - 1][0];
            final AlertConfig c = Alerts.config(this);
            if (!p.equals(c.settings.profile)) save(c.withSettings(c.settings.withProfile(p)), "volume " + p, false);
        });
        optionsBox.addView(profile);

        optionsBox.addView(option("Ascending: vibration only for the first 3 minutes", s.delayAscending3min,
                (c, v) -> with(c, v, c.smartSnoozing, c.smartAlerting, c.startSnoozed, c.ascendingToMedium, c.noAlarmsDuringCalls)));
        optionsBox.addView(option("Ascending: go no louder than medium", s.ascendingToMedium,
                (c, v) -> with(c, c.delayAscending3min, c.smartSnoozing, c.smartAlerting, c.startSnoozed, v, c.noAlarmsDuringCalls)));
        optionsBox.addView(option("Smart snoozing: don't repeat while glucose is already heading back", s.smartSnoozing,
                (c, v) -> with(c, c.delayAscending3min, v, c.smartAlerting, c.startSnoozed, c.ascendingToMedium, c.noAlarmsDuringCalls)));
        optionsBox.addView(option("Smart alerting: don't start while glucose is already heading back", s.smartAlerting,
                (c, v) -> with(c, c.delayAscending3min, c.smartSnoozing, v, c.startSnoozed, c.ascendingToMedium, c.noAlarmsDuringCalls)));
        optionsBox.addView(option("Start snoozed: a new alert starts silent", s.startSnoozed,
                (c, v) -> with(c, c.delayAscending3min, c.smartSnoozing, c.smartAlerting, v, c.ascendingToMedium, c.noAlarmsDuringCalls)));
        optionsBox.addView(option("No sound or vibration during a phone call", s.noAlarmsDuringCalls,
                (c, v) -> with(c, c.delayAscending3min, c.smartSnoozing, c.smartAlerting, c.startSnoozed, c.ascendingToMedium, v)));

        final CheckBox stale = new CheckBox(this);
        stale.setText("No alerts when the latest reading is older than (minutes):");
        stale.setChecked(s.disableStale);
        optionsBox.addView(stale);
        final EditText staleMin = field(InputType.TYPE_CLASS_NUMBER);
        staleMin.setText(String.valueOf(s.staleMinutes));
        optionsBox.addView(staleMin);
        final Runnable saveStale = () -> {
            final AlertConfig c = Alerts.config(this);
            final int m = clamp(parseInt(staleMin.getText().toString(), c.settings.staleMinutes), 5, 1440);
            if (c.settings.disableStale == stale.isChecked() && c.settings.staleMinutes == m) return;
            final AlertSettings o = c.settings;
            save(c.withSettings(new AlertSettings(o.profile, o.smartSnoozing, o.smartAlerting, stale.isChecked(), m,
                    o.startSnoozed, o.delayAscending3min, o.ascendingToMedium, o.noAlarmsDuringCalls,
                    o.allDisabledUntil, o.lowDisabledUntil, o.highDisabledUntil)), "stale data " + stale.isChecked() + " " + m + " min", false);
        };
        stale.setOnCheckedChangeListener((b, v) -> saveStale.run());
        staleMin.setOnFocusChangeListener((v, focused) -> {
            if (!focused) saveStale.run();
        });
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

    private CheckBox option(String label, boolean checked, Change change) {
        final CheckBox b = new CheckBox(this);
        b.setText(label);
        b.setChecked(checked);
        b.setOnCheckedChangeListener((v, value) -> {
            final AlertConfig c = Alerts.config(this);
            save(c.withSettings(change.apply(c.settings, value)), label + ": " + value, false);
        });
        return b;
    }

    private static AlertSettings with(AlertSettings o, boolean delay, boolean smartSnoozing, boolean smartAlerting,
                                      boolean startSnoozed, boolean toMedium, boolean calls) {
        return new AlertSettings(o.profile, smartSnoozing, smartAlerting, o.disableStale, o.staleMinutes, startSnoozed,
                delay, toMedium, calls, o.allDisabledUntil, o.lowDisabledUntil, o.highDisabledUntil);
    }

    // ---------------------------------------------------------------- texts

    static String title(AlertRule r) {
        final String n = r.name.trim();
        return n.isEmpty() ? (r.above ? "High alert" : "Low alert") : n;
    }

    static String describe(AlertRule r, String units) {
        final StringBuilder sb = new StringBuilder();
        sb.append(r.above ? "High: at or above " : "Low: at or below ").append(threshold(r.threshold, units))
                .append(' ').append(unitLabel(units));
        sb.append(r.allDay ? " · all day" : " · " + hhmm(r.startMinutes) + "–" + hhmm(r.endMinutes));
        sb.append("\nRepeats every ").append(r.minutesBetween).append(" min · snooze ").append(r.snoozeMinutes()).append(" min");
        sb.append("\nVibrate ").append(r.vibrate ? "on" : "off");
        sb.append(r.overrideSilent ? " · critical: sounds on silent and Do Not Disturb" : " · quiet when the phone is on silent");
        sb.append(r.forceSpeaker ? " · alarm volume" : " · media volume");
        return sb.toString();
    }

    static String threshold(double mgdl, String units) {
        if ("mmol".equals(units)) return String.format(Locale.getDefault(), "%.1f", mgdl / Alerts.MGDL_PER_MMOL);
        return String.valueOf(Math.round(mgdl));
    }

    static String unitLabel(String units) {
        return "mmol".equals(units) ? "mmol/L" : "mg/dL";
    }

    private static String hhmm(int minutes) {
        return String.format(Locale.ROOT, "%02d:%02d", (minutes / 60) % 24, minutes % 60);
    }

    private static String clock(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(ms));
    }

    /** Fills in the sound's name, and whether this app can open it, off the main thread. */
    private void describeSound(String uri, TextView into) {
        if (uri == null) {
            into.setText("Sound: the phone's alarm sound");
            return;
        }
        final Context app = getApplicationContext();
        new Thread(() -> {
            final String name = soundName(app, uri);
            final boolean opens = canOpen(app, uri);
            handler.post(() -> {
                if (opens) {
                    into.setText("Sound: " + name);
                } else {
                    into.setText("Sound: " + name + " — EverBridge can't open this file, so it plays the phone's alarm "
                            + "sound. Tap Sound to pick the file again.");
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
        try (Cursor cur = c.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getString(0);
        } catch (Exception ignored) {
            // no permission: fall through
        }
        final String last = u.getLastPathSegment();
        return last != null ? last : uri;
    }

    // ---------------------------------------------------------------- actions

    private void edit(String uuid) {
        final AlertConfig cfg = Alerts.config(this);
        final AlertRule r = cfg.rule(uuid);
        if (r == null) return;
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(text((r.above ? "Alert at or above (" : "Alert at or below (") + unitLabel(cfg.units) + ")", 13, false));
        final EditText threshold = field(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        threshold.setText(threshold(r.threshold, cfg.units));
        box.addView(threshold);
        box.addView(text("Repeat every (minutes)", 13, false));
        final EditText repeat = field(InputType.TYPE_CLASS_NUMBER);
        repeat.setText(String.valueOf(r.minutesBetween));
        box.addView(repeat);
        box.addView(text("Snooze for (minutes)", 13, false));
        final EditText snooze = field(InputType.TYPE_CLASS_NUMBER);
        snooze.setText(String.valueOf(r.snoozeMinutes()));
        box.addView(snooze);
        final CheckBox vibrate = new CheckBox(this);
        vibrate.setText("Vibrate");
        vibrate.setChecked(r.vibrate);
        box.addView(vibrate);
        final CheckBox override = new CheckBox(this);
        override.setText("Critical: sound even on silent and Do Not Disturb");
        override.setChecked(r.overrideSilent);
        box.addView(override);
        final ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(title(r))
                .setView(scroll)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final double mgdl = parseThreshold(threshold.getText().toString(), cfg.units);
            final int rep = parseInt(repeat.getText().toString(), -1);
            final int sn = parseInt(snooze.getText().toString(), -1);
            if (mgdl < 40 || mgdl > 400) {
                threshold.setError("Between " + threshold(40, cfg.units) + " and " + threshold(400, cfg.units));
                return;
            }
            if (rep < 1 || rep > 120) {
                repeat.setError("1 to 120 minutes");
                return;
            }
            if (sn < 1 || sn > 1440) {
                snooze.setError("1 to 1440 minutes");
                return;
            }
            final AlertConfig c = Alerts.config(this);
            final AlertRule cur = c.rule(uuid);
            if (cur == null) {
                d.dismiss();
                return;
            }
            final AlertRule changed = cur.withSettings(mgdl, rep, sn, vibrate.isChecked(), override.isChecked());
            save(c.withRule(changed), title(changed) + ": " + changed + " repeat " + rep + " snooze " + sn
                    + " vibrate " + changed.vibrate + " override silent " + changed.overrideSilent, true);
            d.dismiss();
        }));
        d.show();
    }

    static double parseThreshold(String s, String units) {
        try {
            final double v = Double.parseDouble(s.trim().replace(',', '.'));
            return "mmol".equals(units) ? Math.round(v * Alerts.MGDL_PER_MMOL) : v;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void chooseSound(String uuid) {
        new AlertDialog.Builder(this)
                .setTitle("Sound")
                .setItems(new String[]{"Beep (built in)", "Vibration sound (built in)", "Choose your own sound file…",
                        "The phone's alarm sound"}, (d, which) -> {
                    if (which == 0) {
                        setSound(uuid, AlertConfig.SOUND_BEEP);
                    } else if (which == 1) {
                        setSound(uuid, AlertConfig.SOUND_VIBRATION);
                    } else if (which == 2) {
                        soundFor = uuid;
                        final Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE)
                                .setType("audio/*");
                        if (!startForResult(i, REQ_SOUND)) soundFor = null;
                    } else {
                        setSound(uuid, null);
                    }
                })
                .show();
    }

    private void setSound(String uuid, String uri) {
        final AlertConfig c = Alerts.config(this);
        final AlertRule cur = c.rule(uuid);
        if (cur == null) return;
        save(c.withRule(cur.withSound(uri)), title(cur) + " sound " + (uri == null ? "phone alarm" : uri), true);
    }

    private void test(String uuid) {
        final AlertRule r = Alerts.config(this).rule(uuid);
        if (r == null) return;
        Alerts.test(this, r);
        new AlertDialog.Builder(this)
                .setTitle("Testing " + title(r))
                .setMessage("Playing the alert's sound at full " + (r.forceSpeaker ? "alarm" : "media") + " volume.")
                .setPositiveButton("Stop", (d, w) -> Alerts.stopTest(this))
                .setOnCancelListener(d -> Alerts.stopTest(this))
                .show();
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
        if (requestCode == REQ_SOUND) {
            final String uuid = soundFor;
            soundFor = null;
            if (uri == null || uuid == null) return;
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException e) {
                EventLog.log(this, "ALERT", "no lasting access to " + uri + ": " + e.getMessage());
            }
            setSound(uuid, uri.toString());
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
        sb.append("This replaces the alerts set up in EverBridge.");
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
        for (int i = 0; i < labels.length; i++) {
            final int m = Alerts.SNOOZE_CHOICES[i];
            labels[i] = m < 60 ? m + " minutes" : m % 60 == 0 ? (m / 60) + (m == 60 ? " hour" : " hours")
                    : (m / 60) + " h " + (m % 60) + " min";
        }
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

        nowBox = new LinearLayout(this);
        nowBox.setOrientation(LinearLayout.VERTICAL);
        nowBox.setPadding(dp(16), dp(12), dp(16), dp(12));
        nowBox.setBackground(rounded(Color.argb(22, Color.red(textColor), Color.green(textColor), Color.blue(textColor))));
        col.addView(nowBox, marginTop(12));

        col.addView(header("Alerts"));
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(listBox);

        col.addView(header("For all alerts"));
        optionsBox = new LinearLayout(this);
        optionsBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(optionsBox);

        col.addView(header("Import from xDrip+ (optional)"));
        final TextView how = text("The alerts above start out as your xDrip+ ones. To bring over a different xDrip+ setup: "
                + "in xDrip+, menu → Import / Export Features → Save settings, then tap Import and pick "
                + "com.eveningoutpost.dexdrip_preferences.xml from Downloads/xDrip-export. It replaces the alerts above.", 13, false);
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
