import Toybox.Application;
import Toybox.Background;
import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

(:background)
class EverBridgeApp extends Application.AppBase {

    function initialize() {
        AppBase.initialize();
    }

    function getInitialView() {
        Sched.arm();
        // Wake the background whenever Meanwhile v6 pushes a reading.
        if (Background has :registerForPhoneAppMessageEvent) {
            Background.registerForPhoneAppMessageEvent();
        }
        return [new EverBridgeView()];
    }

    function getServiceDelegate() {
        return [new BgService()];
    }

    // Runs in the foreground when the background fetch finishes.
    function onBackgroundData(data) as Void {
        Sched.onData(data);
        WatchUi.requestUpdate();
    }
}
