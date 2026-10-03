package app.meanwhile.bridge;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings.Secure;
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
import app.meanwhile.bridge.core.SgvJson;
import app.meanwhile.bridge.core.Trend;

/** Status, setup checklist, recent readings, capture diagnostics and settings. */
public class MainActivity extends Activity {

    private static final long REFRESH_MS = 5_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    private Bridge bridge;
    private TextView value;
    private TextView valueDetail;
    private TextView checklist;
    private TextView readings;
    private TextView captures;
    private Button accessButton;
    private Button batteryButton;
    private Button notifyButton;

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
        setContentView(buildUi());
        loadSettings();
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

    // ---------------------------------------------------------------- rendering

    private void render() {
        final long now = System.currentTimeMillis();
        final List<Reading> recent = bridge.store.latest(13);
        if (recent.isEmpty()) {
            value.setText("—");
            valueDetail.setText("No reading captured yet. Open the Eversense app once the checklist is all ✓.");
        } else {
            final Reading r = recent.get(0);
            final String dir = Trend.direction(Trend.slope(r, recent.size() > 1 ? recent.get(1) : null));
            value.setText(r.mgdl + " mg/dL " + Trend.arrow(dir));
            valueDetail.setText(fullTime(r.timestamp) + "  ·  " + ago(now - r.timestamp) + "\n"
                    + "time source: " + r.timestampSource
                    + "  ·  posted " + time(r.postTime) + "  ·  received " + time(r.receivedAt));
        }

        final boolean access = bridge.hasNotificationAccess();
        final boolean battery = bridge.isIgnoringBatteryOptimizations();
        final boolean notify = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        final String ev = bridge.installedEversensePackage();
        final StringBuilder c = new StringBuilder();
        line(c, ev != null, "Eversense app installed" + (ev != null ? " (" + ev + ")" : ""));
        line(c, access, "Notification access granted");
        line(c, bridge.isListenerConnected(), "Notification listener connected");
        line(c, battery, "Battery: unrestricted (no optimisation)");
        line(c, notify, "Allowed to show notifications");
        line(c, BridgeService.isRunning(), "Keep-alive service running");
        line(c, bridge.server.isRunning(), bridge.server.isRunning()
                ? "Endpoint http://127.0.0.1:" + bridge.server.port() + "/sgv.json"
                : "Endpoint down: " + bridge.server.lastError());
        final long post = bridge.lastMonitoredPostAt();
        c.append("\nLast Eversense notification seen: ").append(post == 0 ? "not since app start" : ago(now - post));
        if (!access) {
            c.append("\n\nIf the switch for this app is greyed out (Android 13+ sideloaded apps): ")
                    .append("Settings › Apps › Eversense Bridge › ⋮ › Allow restricted settings, then try again.");
        }
        checklist.setText(c.toString());
        accessButton.setVisibility(access ? View.GONE : View.VISIBLE);
        batteryButton.setVisibility(battery ? View.GONE : View.VISIBLE);
        notifyButton.setVisibility(notify ? View.GONE : View.VISIBLE);

        final StringBuilder rs = new StringBuilder();
        for (int i = 0; i < Math.min(12, recent.size()); i++) {
            final Reading r = recent.get(i);
            final double slope = Trend.slope(r, i + 1 < recent.size() ? recent.get(i + 1) : null);
            rs.append(fullTime(r.timestamp)).append("   ")
                    .append(String.format(Locale.ROOT, "%3d", r.mgdl)).append(' ')
                    .append(Trend.arrow(Trend.direction(slope))).append("   ")
                    .append(r.timestampSource).append('\n');
        }
        rs.append("\nStored readings: ").append(bridge.store.count());
        readings.setText(rs.toString());

        final StringBuilder cs = new StringBuilder();
        final List<CaptureEngine.Result> log = bridge.engine.log().recent();
        for (int i = 0; i < Math.min(25, log.size()); i++) {
            final CaptureEngine.Result r = log.get(i);
            cs.append(time(r.input.receivedAt)).append("  ").append(r.outcome);
            if (r.reading != null) cs.append("  ").append(r.reading.mgdl);
            if (r.detail != null) cs.append("  ").append(r.detail);
            cs.append('\n');
        }
        if (log.isEmpty()) cs.append("Nothing from the Eversense app since this app started.");
        captures.setText(cs.toString());
    }

    private static void line(StringBuilder sb, boolean ok, String text) {
        if (sb.length() > 0) sb.append('\n');
        sb.append(ok ? "✓  " : "✗  ").append(text);
    }

    private static String time(long ms) {
        return ms <= 0 ? "-" : new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date(ms));
    }

    private static String fullTime(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date(ms));
    }

    private static String ago(long ms) {
        final long s = Math.max(0, ms / 1000);
        if (s < 90) return s + " s ago";
        if (s < 5400) return (s / 60) + " min ago";
        return (s / 3600) + " h " + (s % 3600) / 60 + " min ago";
    }

    // ---------------------------------------------------------------- actions

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

    private void copyDiagnostics() {
        final StringBuilder sb = new StringBuilder();
        sb.append("status.json:\n").append(bridge.statusJson()).append("\n\n");
        sb.append("sgv.json (latest 6):\n").append(SgvJson.entries(bridge.store.latest(7), 6, "mgdl")).append("\n\n");
        sb.append("enabled_notification_listeners: ")
                .append(Secure.getString(getContentResolver(), "enabled_notification_listeners")).append("\n\n");
        sb.append("capture log:\n");
        for (CaptureEngine.Result r : bridge.engine.log().recent()) {
            sb.append(fullTime(r.input.receivedAt)).append(' ').append(r.outcome)
                    .append(" when=").append(r.input.when)
                    .append(" post=").append(r.input.postTime)
                    .append(" ongoing=").append(r.input.ongoing)
                    .append(' ').append(r.input.describeTexts());
            if (r.detail != null) sb.append(" :: ").append(r.detail);
            sb.append('\n');
        }
        final ClipboardManager cm = getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText("Eversense Bridge diagnostics", sb.toString()));
        Toast.makeText(this, "Diagnostics copied", Toast.LENGTH_SHORT).show();
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

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        final LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(16);
        col.setPadding(pad, pad, pad, pad);

        final TextView title = text("Eversense → Meanwhile bridge", 20, true);
        col.addView(title);

        value = text("—", 44, true);
        value.setGravity(Gravity.START);
        col.addView(value);
        valueDetail = text("", 13, false);
        col.addView(valueDetail);

        col.addView(header("Setup"));
        checklist = text("", 14, false);
        col.addView(checklist);
        accessButton = button("Grant notification access", v -> openNotificationAccess());
        batteryButton = button("Set battery to unrestricted", v -> requestBatteryExemption());
        notifyButton = button("Allow notifications", v -> requestNotificationPermission());
        col.addView(accessButton);
        col.addView(batteryButton);
        col.addView(notifyButton);

        col.addView(header("Recent readings"));
        readings = mono();
        col.addView(readings);

        col.addView(header("Capture log (this session)"));
        captures = mono();
        col.addView(captures);
        col.addView(button("Copy diagnostics", v -> copyDiagnostics()));
        col.addView(button("Open sgv.json in browser", v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("http://127.0.0.1:" + bridge.server.port() + "/sgv.json?count=12")));
            } catch (ActivityNotFoundException ignored) {
            }
        }));

        col.addView(header("Settings"));
        col.addView(text("Port (xDrip+ uses 17580; stop xDrip's web service if both are installed)", 13, false));
        portField = field(InputType.TYPE_CLASS_NUMBER);
        col.addView(portField);
        lanBox = new CheckBox(this);
        lanBox.setText("Also listen on Wi-Fi/LAN (other devices can read glucose)");
        col.addView(lanBox);
        col.addView(text("API secret for LAN clients (sent as SHA-1 in the api-secret header; blank = none)", 13, false));
        secretField = field(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        col.addView(secretField);
        col.addView(text("Units shown by the Eversense app", 13, false));
        unitsGroup = new RadioGroup(this);
        unitsGroup.setOrientation(RadioGroup.HORIZONTAL);
        unitsGroup.addView(radio(1, "Auto"));
        unitsGroup.addView(radio(2, "mg/dL"));
        unitsGroup.addView(radio(3, "mmol/L"));
        col.addView(unitsGroup);
        col.addView(text("Eversense app package names (comma separated)", 13, false));
        packagesField = field(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        col.addView(packagesField);
        staleBox = new CheckBox(this);
        staleBox.setText("Alert when no reading arrives for (minutes):");
        col.addView(staleBox);
        staleField = field(InputType.TYPE_CLASS_NUMBER);
        col.addView(staleField);
        col.addView(button("Save settings", v -> saveSettings()));

        final ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            applyInsets(v, insets, pad);
            return insets;
        });
        return scroll;
    }

    @SuppressWarnings("deprecation")
    private static void applyInsets(View v, WindowInsets insets, int pad) {
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

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(String s, int sp, boolean bold) {
        final TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextIsSelectable(true);
        return t;
    }

    private TextView header(String s) {
        final TextView t = text(s, 16, true);
        t.setPadding(0, dp(20), 0, dp(6));
        return t;
    }

    private TextView mono() {
        final TextView t = text("", 12, false);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        final Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
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
