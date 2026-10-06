package app.meanwhile.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Brings the endpoint and keep-alive service up after a reboot or an app update. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent != null ? intent.getAction() : null;
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            EventLog.log(context, "BOOT", action);
            Bridge.get(context).ensureServer();
            BridgeService.start(context);
        }
    }
}
