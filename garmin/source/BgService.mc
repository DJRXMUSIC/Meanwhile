import Toybox.Background;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.PersistedContent;
import Toybox.System;
import Toybox.Time;

// Background process. Two ways a reading arrives, both handed to the face the same way:
//  - pushed: Meanwhile v6 sends each reading the moment it gets it, as a phone-app
//    message through Garmin Connect (onPhoneAppMessage). On the Instinct E a message is
//    held back until the next one arrives, so Meanwhile follows each reading with a tiny
//    "nudge" message (ignored here) that releases it. Each reading that arrives is
//    confirmed to the phone (/pebble?have=<its time>), which answers with its newest
//    reading: the newer of the two is kept, so a late or backfilled reading is caught at
//    once, and the phone nudges again if a reading isn't confirmed within 20 s.
//  - fetched: the timed backup (onTemporalEvent), at most every 5 minutes, asks the
//    phone's /pebble. Garmin Connect performs the request, so 127.0.0.1 is the phone.
(:background)
class BgService extends System.ServiceDelegate {

    // The reading from a phone-app message, while its confirmation is under way.
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
        if (Bg.isNudge(msg.data)) {
            // only there to release the reading sent before it; nothing to do
            Background.exit(null);
            return;
        }
        _pushed = Bg.parse(200, msg.data, Time.now().value());
        log(0, _pushed);
        fetch(_pushed == null ? null : {"have" => (_pushed[Bg.K_DT] as Long).toString()});
    }

    function onTemporalEvent() as Void {
        fetch(null);
    }

    private function fetch(params as Dictionary?) as Void {
        Communications.makeWebRequest(
            Bg.URL,
            params,
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
