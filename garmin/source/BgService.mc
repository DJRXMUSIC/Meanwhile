import Toybox.Background;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.PersistedContent;
import Toybox.System;
import Toybox.Time;

// Background process. Two ways a reading arrives, both handed to the face the same way:
//  - pushed: Meanwhile v6 sends each reading the moment it gets it, as a phone-app
//    message through Garmin Connect (onPhoneAppMessage). This is the fast path. On the
//    Instinct E a message can be held back and delivered with the next one, minutes late;
//    a message that arrives late also asks the phone's /pebble for its newest reading, and
//    the newer of the two is kept. One on time is used as it is (each request wakes
//    Garmin Connect on the phone).
//  - fetched: the timed backup (onTemporalEvent), at most every 5 minutes, asks the
//    phone's /pebble. Garmin Connect performs the request, so 127.0.0.1 is the phone.
(:background)
class BgService extends System.ServiceDelegate {

    // The reading from a phone-app message, while its /pebble check is under way.
    private var _pushed as Dictionary? = null;

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
        var now = Time.now().value();
        _pushed = Bg.parse(200, msg.data, now);
        log(0, _pushed);
        if (_pushed != null && !Bg.isLate(msg.data, now)) {
            // on time: it is the newest reading, no need to ask the phone
            Background.exit(_pushed);
            return;
        }
        // held back (it can arrive with the next message, minutes late): ask for the newest
        fetch();
    }

    function onTemporalEvent() as Void {
        fetch();
    }

    private function fetch() as Void {
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
        var r = Bg.newer(Bg.parse(code, data, Time.now().value()), _pushed);
        log(code, r);
        Background.exit(r);
    }
}
