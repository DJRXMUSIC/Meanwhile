package com.senseonics.fake;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * adb shell am start -n com.senseonics.eversense365.us/com.senseonics.fake.PostActivity \
 *     --es value 123 --es unit mg/dL --es mode custom|extras --el when_offset_ms 1500
 *
 * when_offset_ms: when = now - offset (reading time). Use --el fixed_when <ms> to pin it.
 * Logs "FAKE_POSTED value=... when=..." so the test can compare timestamps.
 */
public class PostActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Intent in = getIntent();
        final String value = in.getStringExtra("value");
        final String unit = in.hasExtra("unit") ? in.getStringExtra("unit") : "mg/dL";
        final String mode = in.hasExtra("mode") ? in.getStringExtra("mode") : "custom";
        final long now = System.currentTimeMillis();
        final long when = in.hasExtra("fixed_when") ? in.getLongExtra("fixed_when", now)
                : now - in.getLongExtra("when_offset_ms", 0);

        final NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("glucose", "Glucose", NotificationManager.IMPORTANCE_LOW));
        final Notification.Builder b = new Notification.Builder(this, "glucose")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setWhen(when)
                .setShowWhen(true);
        if (mode.equals("custom")) {
            final RemoteViews rv = new RemoteViews(getPackageName(), R.layout.glucose_notification);
            rv.setTextViewText(R.id.value, value);
            rv.setTextViewText(R.id.unit, unit);
            rv.setTextViewText(R.id.time, new SimpleDateFormat("h:mm a", Locale.US).format(new Date(when)));
            b.setCustomContentView(rv).setStyle(new Notification.DecoratedCustomViewStyle());
        } else {
            b.setContentTitle(value + " " + unit).setContentText("Stable");
        }
        nm.notify(1, b.build());
        Log.i("FakeEversense", "FAKE_POSTED value=" + value + " when=" + when + " mode=" + mode);
        finish();
    }
}
