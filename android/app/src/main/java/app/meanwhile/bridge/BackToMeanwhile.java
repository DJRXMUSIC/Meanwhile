package app.meanwhile.bridge;

import android.app.Activity;
import android.content.Intent;

/**
 * Back from the app's own screens (glucose alerts, Eversense connection) always lands on
 * Meanwhile: opened from the app icon's shortcut or an alert, nothing is behind them, and
 * plain back would close the app.
 */
final class BackToMeanwhile {

    private BackToMeanwhile() {
    }

    static void go(Activity from) {
        if (from.isTaskRoot()) {
            from.startActivity(new Intent(from, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        }
        from.finish();
    }
}
