package app.meanwhile.bridge;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import app.meanwhile.bridge.core.CaptureEngine;
import app.meanwhile.bridge.core.Reading;
import app.meanwhile.bridge.core.Trend;

/**
 * One screen: last reading, a status list where every problem has a fix
 * button, self-test, log export, recent readings, and (collapsed) advanced settings.
 *
 * Automation (used by the emulator test): start with {@code --es action export_log} or
 * {@code --es action self_test} to run those buttons.
 */
public class MainActivity extends Activity {

    static final String RELEASE_PAGE = "https://github.com/DJRXMUSIC/Meanwhile/releases/tag/bridge-latest";
    static final String EXTRA_ACTION = "action";
    private static final long REFRESH_MS = 5_000L;
    private static final int GREEN = Color.rgb(46, 160, 120);
    private static final int AMBER = Color.rgb(239, 152, 0);
    private static final int RED = Color.rgb(211, 47, 47);
    private static final int GREY = Color.rgb(140, 140, 140);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    private Bridge bridge;
    private int textColor;

    private LinearLayout setupBanner;
    private TextView setupText;
    private Button setupButton;
    private TextView value;
    private TextView age;
    private TextView detail;
    private LinearLayout statusList;
    private TextView selfTestResult;
    private Button selfTestButton;
    private TextView logResult;
    private LogExport.Saved lastSaved;
    private Button shareButton;
    private TextView readings;
    private LinearLayout advanced;
    private TextView captures;

    private EditText portField;
    private CheckBox lanBox;
    private EditText secretField;
    private RadioGroup unitsGroup;
    private EditText packagesField;
    private CheckBox staleBox;
    private EditText staleField;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        bridge = Bridge.get(this);
        bridge.ensureServer();
        BridgeService.start(this);
        final TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.textColorPrimary, tv, true);
        textColor = tv.resourceId != 0 ? getColor(tv.resourceId) : tv.data;
        setContentView(buildUi());
        loadSettings();
        askForNotificationPermissionOnce();
        handleAutomation(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleAutomation(intent);
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

    private void handleAutomation(Intent intent) {
        final String action = intent != null ? intent.getStringExtra(EXTRA_ACTION) : null;
        if ("export_log".equals(action)) saveLog(false);
        else if ("self_test".equals(action)) runSelfTest();
    }

    // ---------------------------------------------------------------- state

    private static final class Check {
        final String label;
        final boolean ok;
        final String value;
        final String fix;
        final Runnable action;

        Check(String label, boolean ok, String value, String fix, Runnable action) {
            this.label = label;
            this.ok = ok;
            this.value = value;
            this.fix = fix;
            this.action = action;
        }
    }

    private Check[] checks(long now) {
        final String ev = bridge.installedEversensePackage();
        final boolean access = bridge.hasNotificationAccess();
        final boolean battery = bridge.isIgnoringBatteryOptimizations();
        final boolean notify = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        final long post = bridge.lastMonitoredPostAt();
        return new Check[]{
                new Check("Eversense app", ev != null, ev != null ? "installed" : "not found",
                        null, null),
                new Check("Notification access", access, access ? "on" : "off",
                        "Turn on", this::openNotificationAccess),
                new Check("Listener", bridge.isListenerConnected(), bridge.isListenerConnected() ? "connected" : "not connected",
                        access ? "Reconnect" : null, () -> {
                            EventLog.log(this, "UI", "reconnect listener requested");
                            NotificationListenerService.requestRebind(new ComponentName(this, EversenseListenerService.class));
                        }),
                new Check("Battery", battery, battery ? "unrestricted" : "optimised (may be killed)",
                        "Fix", this::requestBatteryExemption),
                new Check("App notifications", notify, notify ? "allowed" : "blocked",
                        "Allow", this::requestNotificationPermission),
                new Check("Keep-alive service", BridgeService.isRunning(), BridgeService.isRunning() ? "running" : "stopped",
                        "Start", () -> BridgeService.start(this)),
                new Check("Data endpoint", bridge.server.isRunning(), bridge.server.isRunning()
                        ? "127.0.0.1:" + bridge.server.port() + " · " + bridge.server.requestsServed() + " requests"
                        : "down: " + bridge.server.lastError(),
                        "Restart", () -> bridge.ensureServer()),
                new Check("Last Eversense update", post > 0, post > 0 ? ago(now - post) : "none since app start",
                        null, null),
        };
    }

    // ---------------------------------------------------------------- rendering

    private void render() {
        final long now = System.currentTimeMillis();
        final List<Reading> recent = bridge.store.latest(48);

        if (recent.isEmpty()) {
            value.setText("--");
            value.setTextColor(GREY);
            age.setText("Waiting for the first reading");
            detail.setText("Open the Eversense app once everything below shows a green dot.");
        } else {
            final Reading r = recent.get(0);
            final double slope = Trend.slope(r, recent.size() > 1 ? recent.get(1) : null);
            final long ageMs = now - r.timestamp;
            final int color = ageMs <= 6 * 60_000L ? GREEN : ageMs <= 15 * 60_000L ? AMBER : RED;
            value.setText(r.mgdl + " " + Trend.arrow(Trend.direction(slope)));
            value.setTextColor(color);
            age.setText(ago(ageMs) + "  ·  " + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(r.timestamp)));
            age.setTextColor(color);
            final double d = Trend.delta5min(slope);
            detail.setText("mg/dL"
                    + (Double.isNaN(d) ? "" : String.format(Locale.ROOT, "  ·  %+.0f per 5 min", d))
                    + "  ·  time from " + (r.timestampSource.equals("notification_when") ? "Eversense" : "notification post")
                    + "\n" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date(r.timestamp)));
        }

        final Check[] checks = checks(now);
        statusList.removeAllViews();
        Check firstProblem = null;
        int problems = 0;
        for (Check c : checks) {
            statusList.addView(statusRow(c));
            if (!c.ok && c.fix != null) {
                problems++;
                if (firstProblem == null) firstProblem = c;
            }
        }
        if (firstProblem != null) {
            final Check fix = firstProblem;
            setupBanner.setVisibility(View.VISIBLE);
            setupText.setText("Setup: " + problems + (problems == 1 ? " step" : " steps") + " left. Next: "
                    + fix.label.toLowerCase(Locale.ROOT) + " (" + fix.value + ")"
                    + (fix.label.equals("Notification access")
                    ? "\nIf the switch is greyed out: App info › ⋮ › Allow restricted settings, then try again." : ""));
            setupButton.setText(fix.fix + ": " + fix.label);
            setupButton.setOnClickListener(v -> fix.action.run());
        } else {
            setupBanner.setVisibility(View.GONE);
        }

        final StringBuilder rs = new StringBuilder();
        for (int i = 0; i < Math.min(8, recent.size()); i++) {
            final Reading r = recent.get(i);
            final double slope = Trend.slope(r, i + 1 < recent.size() ? recent.get(i + 1) : null);
            rs.append(new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(r.timestamp)))
                    .append("   ").append(String.format(Locale.ROOT, "%3d", r.mgdl)).append(' ')
                    .append(Trend.arrow(Trend.direction(slope))).append('\n');
        }
        rs.append("Stored: ").append(bridge.store.count()).append(" readings");
        readings.setText(rs.toString());

        if (advanced.getVisibility() == View.VISIBLE) {
            final StringBuilder cs = new StringBuilder();
            final List<CaptureEngine.Result> log = bridge.engine.log().recent();
            for (int i = 0; i < Math.min(20, log.size()); i++) {
                final CaptureEngine.Result r = log.get(i);
                cs.append(new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(r.input.receivedAt)))
                        .append(' ').append(r.outcome);
                if (r.reading != null) cs.append(' ').append(r.reading.mgdl);
                if (r.detail != null) cs.append("  ").append(r.detail);
                cs.append('\n');
            }
            if (log.isEmpty()) cs.append("Nothing from the Eversense app since this app started.");
            captures.setText(cs.toString());
        }
    }

    private View statusRow(Check c) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        final TextView dot = text("●", 16, false);
        dot.setTextColor(c.ok ? GREEN : c.label.startsWith("Last") ? AMBER : RED);
        dot.setPadding(0, 0, dp(10), 0);
        row.addView(dot);
        final TextView label = text(c.label, 15, false);
        row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView val = text(c.value, 14, false);
        val.setAlpha(0.75f);
        val.setGravity(Gravity.END);
        row.addView(val, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f));
        if (!c.ok && c.fix != null) {
            final Button b = smallButton(c.fix, v -> c.action.run());
            row.addView(b);
        }
        return row;
    }

    // ---------------------------------------------------------------- actions

    private void askForNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return;
        final SharedPreferences p = getSharedPreferences("ui", Context.MODE_PRIVATE);
        if (p.getBoolean("asked_notifications", false)) return;
        p.edit().putBoolean("asked_notifications", true).apply();
        requestNotificationPermission();
    }

    private void openNotificationAccess() {
        Intent i;
        if (Build.VERSION.SDK_INT >= 30) {
            i = new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            new ComponentName(this, EversenseListenerService.class).flattenToString());
        } else {
            i = new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        }
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }
    }

    @android.annotation.SuppressLint("BatteryLife") // a CGM relay is the textbook exemption case
    private void requestBatteryExemption() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private void runSelfTest() {
        selfTestButton.setEnabled(false);
        selfTestResult.setVisibility(View.VISIBLE);
        selfTestResult.setText("Running…");
        SelfTest.run(this, report -> {
            selfTestButton.setEnabled(true);
            selfTestResult.setText(report);
            selfTestResult.setTextColor(report.startsWith("PASS") ? GREEN : RED);
        });
    }

    private void saveLog(boolean thenShare) {
        logResult.setVisibility(View.VISIBLE);
        logResult.setText("Collecting…");
        final Context app = getApplicationContext();
        new Thread(() -> {
            String msg;
            LogExport.Saved saved = null;
            try {
                saved = LogExport.save(app);
                msg = "Saved " + saved.where + " (" + Math.max(1, saved.bytes / 1024) + " KB)";
            } catch (Exception e) {
                msg = "Could not save log: " + e;
                EventLog.log(app, "EXPORT", msg);
            }
            final LogExport.Saved result = saved;
            final String text = msg;
            handler.post(() -> {
                lastSaved = result;
                logResult.setText(text);
                shareButton.setEnabled(result != null);
                if (thenShare && result != null) share();
            });
        }, "bridge-export").start();
    }

    private void share() {
        if (lastSaved == null) {
            saveLog(true);
            return;
        }
        try {
            startActivity(LogExport.shareIntent(this, lastSaved));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app to share with", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggle(View v) {
        v.setVisibility(v.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        render();
    }

    private void loadSettings() {
        final Settings s = bridge.settings;
        portField.setText(String.valueOf(s.port()));
        lanBox.setChecked(s.lan());
        secretField.setText(s.apiSecret());
        final String units = s.unitsRaw();
        unitsGroup.check(units.equals("mgdl") ? 2 : units.equals("mmol") ? 3 : 1);
        packagesField.setText(s.packagesCsv());
        staleBox.setChecked(s.staleAlert());
        staleField.setText(String.valueOf(s.staleMinutes()));
    }

    private void saveSettings() {
        final int port = parseInt(portField.getText().toString(), -1);
        if (port < 1024 || port > 65535) {
            portField.setError("1024-65535");
            return;
        }
        final int stale = parseInt(staleField.getText().toString(), -1);
        if (stale < 6 || stale > 240) {
            staleField.setError("6-240");
            return;
        }
        final int checked = unitsGroup.getCheckedRadioButtonId();
        final String units = checked == 2 ? "mgdl" : checked == 3 ? "mmol" : "auto";
        bridge.settings.save(port, lanBox.isChecked(), secretField.getText().toString(), units,
                packagesField.getText().toString(), staleBox.isChecked(), stale);
        bridge.applySettings();
        EventLog.log(this, "SETTINGS", "port=" + port + " lan=" + lanBox.isChecked() + " units=" + units
                + " packages=" + bridge.settings.packagesCsv() + " stale=" + staleBox.isChecked() + "/" + stale);
        final boolean ok = bridge.ensureServer();
        loadSettings();
        render();
        Toast.makeText(this, ok ? "Saved" : "Saved, but endpoint failed: " + bridge.server.lastError(),
                Toast.LENGTH_LONG).show();
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static String ago(long ms) {
        final long s = Math.max(0, ms / 1000);
        if (s < 60) return s + " s ago";
        if (s < 5400) return (s / 60) + " min ago";
        return (s / 3600) + " h " + (s % 3600) / 60 + " min ago";
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        final LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(16);
        col.setPadding(pad, pad, pad, pad);

        final LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text("Eversense Bridge", 20, true),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView version = text("v" + bridge.versionName(), 12, false);
        version.setAlpha(0.6f);
        header.addView(version);
        col.addView(header);

        setupBanner = new LinearLayout(this);
        setupBanner.setOrientation(LinearLayout.VERTICAL);
        setupBanner.setPadding(dp(12), dp(10), dp(12), dp(10));
        setupBanner.setBackground(rounded(Color.argb(40, 239, 152, 0)));
        setupText = text("", 14, false);
        setupBanner.addView(setupText);
        setupButton = button("", null);
        setupBanner.addView(setupButton);
        col.addView(setupBanner, marginTop(12));

        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(14));
        card.setBackground(rounded(Color.argb(22, Color.red(textColor), Color.green(textColor), Color.blue(textColor))));
        final TextView lastLabel = text("LAST READING", 11, true);
        lastLabel.setAlpha(0.6f);
        card.addView(lastLabel);
        value = text("--", 56, true);
        card.addView(value);
        age = text("", 16, true);
        card.addView(age);
        detail = text("", 13, false);
        detail.setAlpha(0.75f);
        card.addView(detail);
        col.addView(card, marginTop(12));

        col.addView(header("Status"));
        statusList = new LinearLayout(this);
        statusList.setOrientation(LinearLayout.VERTICAL);
        col.addView(statusList);

        col.addView(header("Check & troubleshoot"));
        selfTestButton = button("Run self-test", v -> runSelfTest());
        col.addView(selfTestButton);
        selfTestResult = text("", 13, false);
        selfTestResult.setVisibility(View.GONE);
        col.addView(selfTestResult);
        final LinearLayout logRow = new LinearLayout(this);
        logRow.addView(button("Save log file", v -> saveLog(false)),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        shareButton = button("Share log", v -> share());
        logRow.addView(shareButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(logRow);
        logResult = text("", 13, false);
        logResult.setVisibility(View.GONE);
        col.addView(logResult);
        final TextView logHint = text("The log file has everything needed to diagnose a problem "
                + "(status, raw Eversense notification, readings, events, app logcat). "
                + "It is saved in Downloads/" + LogExport.FOLDER + ".", 12, false);
        logHint.setAlpha(0.6f);
        col.addView(logHint);

        col.addView(header("Recent readings"));
        readings = mono();
        col.addView(readings);

        advanced = new LinearLayout(this);
        advanced.setOrientation(LinearLayout.VERTICAL);
        advanced.setVisibility(View.GONE);
        col.addView(button("Advanced ▾", v -> toggle(advanced)), marginTop(16));
        col.addView(advanced);
        buildAdvanced(advanced);

        final ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            applyInsets(v, insets);
            return insets;
        });
        return scroll;
    }

    private void buildAdvanced(LinearLayout a) {
        a.addView(header("Capture log (this session)"));
        captures = mono();
        a.addView(captures);
        a.addView(button("Open sgv.json in browser", v -> openUrl(
                "http://127.0.0.1:" + bridge.server.port() + "/sgv.json?count=12")));
        a.addView(button("Get latest app version", v -> openUrl(RELEASE_PAGE)));

        a.addView(header("Settings"));
        a.addView(text("Port (xDrip+ uses 17580; stop xDrip's web service if both are installed)", 13, false));
        portField = field(InputType.TYPE_CLASS_NUMBER);
        a.addView(portField);
        lanBox = new CheckBox(this);
        lanBox.setText("Also listen on Wi-Fi/LAN (other devices can read glucose)");
        a.addView(lanBox);
        a.addView(text("API secret for LAN clients (blank = none)", 13, false));
        secretField = field(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        a.addView(secretField);
        a.addView(text("Units shown by the Eversense app", 13, false));
        unitsGroup = new RadioGroup(this);
        unitsGroup.setOrientation(RadioGroup.HORIZONTAL);
        unitsGroup.addView(radio(1, "Auto"));
        unitsGroup.addView(radio(2, "mg/dL"));
        unitsGroup.addView(radio(3, "mmol/L"));
        a.addView(unitsGroup);
        a.addView(text("Eversense app package names (comma separated)", 13, false));
        packagesField = field(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        a.addView(packagesField);
        staleBox = new CheckBox(this);
        staleBox.setText("Alert when no reading arrives for (minutes):");
        a.addView(staleBox);
        staleField = field(InputType.TYPE_CLASS_NUMBER);
        a.addView(staleField);
        a.addView(button("Save settings", v -> saveSettings()));
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException ignored) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        }
    }

    @SuppressWarnings("deprecation")
    private static void applyInsets(View v, WindowInsets insets) {
        // targetSdk 35 is edge-to-edge on Android 15+: keep content clear of the system bars
        if (Build.VERSION.SDK_INT >= 30) {
            final android.graphics.Insets i = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            v.setPadding(i.left, i.top, i.right, i.bottom);
        } else {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
        }
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

    private TextView mono() {
        final TextView t = text("", 13, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
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
        e.setSingleLine((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        return e;
    }

    private RadioButton radio(int id, String label) {
        final RadioButton r = new RadioButton(this);
        r.setId(id);
        r.setText(label);
        return r;
    }
}
