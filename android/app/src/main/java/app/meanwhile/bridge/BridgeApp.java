package app.meanwhile.bridge;

import android.app.Application;

public class BridgeApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Any process start (listener bind, boot, UI) brings the endpoint up.
        Bridge.get(this).ensureServer();
    }
}
