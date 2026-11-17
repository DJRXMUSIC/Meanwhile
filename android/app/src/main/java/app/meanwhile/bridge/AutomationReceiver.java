package app.meanwhile.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import app.meanwhile.bridge.core.AlertConfig;

/**
 * The emulator test's alert controls, from adb only (see {@link Automation}):
 * {@code --es action alerts_config --es file <name>} sets the alerts from a staged file (this
 * app's own stored format); {@code --es action alerts_snooze} presses Snooze. Exported so adb
 * can reach it; without the adb-only token it does nothing.
 */
public class AutomationReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent != null ? intent.getStringExtra("action") : null;
        if (action == null) return;
        final Context app = context.getApplicationContext();
        if (!Automation.fromAdb(app, intent.getStringExtra("token"))) {
            EventLog.log(app, "ALERT", "ignored automation '" + action + "' not sent from adb");
            return;
        }
        if ("alerts_config".equals(action)) {
            final File f = Automation.stagedFile(app, intent.getStringExtra("file"));
            if (f == null) {
                EventLog.log(app, "ALERT", "automation: no such file");
                return;
            }
            try (InputStream in = new FileInputStream(f)) {
                final AlertConfig cfg = AlertConfig.fromJson(new String(Automation.readAll(in), StandardCharsets.UTF_8));
                Alerts.post(app, x -> Alerts.update(x, cfg, "alerts set by adb: " + cfg.rules.size() + " alerts"), null);
            } catch (IOException e) {
                EventLog.log(app, "ALERT", "automation: can't read " + f.getName());
            }
        } else if ("alerts_snooze".equals(action)) {
            Alerts.post(app, x -> Alerts.snoozeDefault(x, "adb"), null);
        }
    }
}
