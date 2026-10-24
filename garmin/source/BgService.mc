import Toybox.Background;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.System;
import Toybox.Time;

// Background process: fetch the newest reading from the phone and hand it to the face.
// Garmin Connect on the phone performs the request, so 127.0.0.1 is the phone.
(:background)
class BgService extends System.ServiceDelegate {

    function initialize() {
        ServiceDelegate.initialize();
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

    function onReceive(code, data) as Void {
        Background.exit(Bg.parse(code, data, Time.now().value()));
    }
}
