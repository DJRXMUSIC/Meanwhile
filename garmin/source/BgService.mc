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

    // One short line per message or request. On the watch it goes to GARMIN/APPS/LOGS/
    // <the .prg's name>.TXT, if that file has been created over USB (see README).
    function log(what as String, code as Number, r) as Void {
        var t = System.getClockTime();
        System.println(t.hour.format("%02d") + ":" + t.min.format("%02d") + ":" + t.sec.format("%02d")
            + " " + what + " " + code + " " + (r == null ? "-" : r[Bg.K_SGV] + " @" + r[Bg.K_DT]));
    }

    // A reading pushed by Meanwhile v6: the same shape as the /pebble reply.
    function onPhoneAppMessage(msg as Communications.PhoneAppMessage) as Void {
        if (Bg.isNudge(msg.data)) {
            // only there to release the reading sent before it; nothing to do
            log("nudge", 0, null);
            Background.exit(null);
            return;
        }
        _pushed = Bg.parse(200, msg.data, Time.now().value());
        log("pushed", 0, _pushed);
        // The reading's time goes in the address itself: a request with makeWebRequest's
        // params never reached the phone through Garmin Connect (seen in a log).
        fetch(_pushed == null ? Bg.URL : Bg.URL + "?have=" + (_pushed[Bg.K_DT] as Long).toString());
    }

    function onTemporalEvent() as Void {
        fetch(Bg.URL);
    }

    private function fetch(url as String) as Void {
        Communications.makeWebRequest(
            url,
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
        log("fetched", code, r);
        Background.exit(r);
    }
}
