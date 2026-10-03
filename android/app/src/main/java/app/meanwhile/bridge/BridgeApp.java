package app.meanwhile.bridge;

import android.app.Application;
import android.os.Build;
import android.util.Log;

public class BridgeApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        EventLog.log(this, "START", "process " + android.os.Process.myPid() + " started; Android "
                + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ") " + Build.MODEL);
        installCrashLogger();
        // Any process start (listener bind, boot, UI) brings the endpoint up.
        Bridge.get(this).ensureServer();
    }

    /** Records uncaught exceptions in the event log, then lets Android handle them as usual. */
    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            try {
                EventLog.log(this, "CRASH", "thread " + thread.getName() + ": " + Log.getStackTraceString(e));
            } catch (Throwable ignored) {
                // nothing more we can do
            }
            if (previous != null) previous.uncaughtException(thread, e);
        });
    }
}
