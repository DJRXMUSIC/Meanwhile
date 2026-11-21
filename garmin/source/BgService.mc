import Toybox.Background;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.PersistedContent;
import Toybox.System;
import Toybox.Time;

// Background process. Two ways a reading arrives, both handed to the face the same way:
//  - pushed: Meanwhile v6 sends each reading the moment it gets it, as a phone-app
//    message through Garmin Connect (onPhoneAppMessage). This is the fast path.
//  - fetched: the timed backup (onTemporalEvent), at most every 5 minutes, asks the
//    phone's /pebble. Garmin Connect performs the request, so 127.0.0.1 is the phone.
(:background)
class BgService extends System.ServiceDelegate {

    function initialize() {
        ServiceDelegate.initialize();
    }

    (:debug)
    function log(code as Number, r) as Void {
        System.println("fetch " + code + " -> " + r);
    }

    (:release)
    function log(code as Number, r) as Void {
    }

    // A reading pushed by Meanwhile v6: the same shape as the /pebble reply.
    function onPhoneAppMessage(msg as Communications.PhoneAppMessage) as Void {
        var r = Bg.parse(200, msg.data, Time.now().value());
        log(0, r);
        Background.exit(r);
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
        log(code, r);
        Background.exit(r);
    }
}
