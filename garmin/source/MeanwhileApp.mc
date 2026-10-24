import Toybox.Application;
import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

(:background)
class MeanwhileApp extends Application.AppBase {

    function initialize() {
        AppBase.initialize();
    }

    function getInitialView() {
        Sched.arm();
        return [new MeanwhileView()];
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
