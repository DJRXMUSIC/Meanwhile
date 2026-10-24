import Toybox.Background;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.PersistedContent;
import Toybox.System;
import Toybox.Time;

// Background process: fetch the newest reading from the Eversense Bridge and hand it to
// the face. Garmin Connect on the phone performs the request, so 127.0.0.1 is the phone.
(:background)
class BgService extends System.ServiceDelegate {

    function initialize() {
        ServiceDelegate.initialize();
    }

    (:debug)
    function log(msg as String) as Void {
        System.println(msg);
    }

    (:release)
    function log(msg as String) as Void {
    }

    function onTemporalEvent() as Void {
        Communications.makeWebRequest(
            Bg.URL,
            null,
            {
                :method => Communications.HTTP_REQUEST_METHOD_GET,
                :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON
            },
            method(:onReceive)
        );
    }

    function onReceive(code as Number, data as Dictionary or String or PersistedContent.Iterator or Null) as Void {
        var r = Bg.parse(code, data, Time.now().value());
        log("fetch " + code + " -> " + r);
        Background.exit(r);
    }
}
