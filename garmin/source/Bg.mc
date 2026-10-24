import Toybox.Lang;

// The glucose reading as fetched from the phone. This module also runs in the
// background process, which has very little memory, so it holds only the URL,
// the storage keys and the parser.
(:background)
module Bg {
    // EverBridge app on the phone (its own port; xDrip+, which may also be
    // installed, uses 17580 and is never read). /pebble rather than /sgv.json because
    // Connect IQ rejects a JSON reply whose top level is an array.
    const URL = "http://127.0.0.1:17590/pebble";

    // Keys of the background's result, and of the stored reading (see Sched).
    const K_SGV = "sgv"; // String, in the user's units, as shown
    const K_DIR = "dir"; // Nightscout direction name
    const K_DT = "dt";   // reading time on the phone's clock, ms (identifies the reading)
    const K_AT = "at";   // reading time on the watch's clock, s
    const K_F = "f";     // when the fetch completed, watch clock, s
    const K_LAG = "lag"; // scheduler state, see Sched

    // Turns a /pebble reply into the dictionary the face stores, or null if the
    // reply holds no reading. nowSec is the watch's clock when the reply arrived.
    //
    // The reply's status[0].now is the phone's clock when it was built, so the
    // reading's age is measured entirely on the phone and then placed on the
    // watch's clock: a skew between the two clocks doesn't change the minutes shown.
    function parse(code as Number, data, nowSec as Number) as Dictionary? {
        if (code != 200 || !(data instanceof Dictionary)) {
            return null;
        }
        var bgs = data["bgs"];
        if (!(bgs instanceof Array) || bgs.size() == 0) {
            return null;
        }
        var bg = bgs[0];
        if (!(bg instanceof Dictionary)) {
            return null;
        }
        var sgv = bg["sgv"];
        var dt = toLong(bg["datetime"]);
        if (sgv == null || dt == null) {
            return null;
        }
        var ageMs = null;
        var status = data["status"];
        if (status instanceof Array && status.size() > 0 && status[0] instanceof Dictionary) {
            var phoneNow = toLong(status[0]["now"]);
            if (phoneNow != null) {
                ageMs = phoneNow - dt;
            }
        }
        var at;
        if (ageMs != null) {
            at = nowSec - (ageMs / 1000l).toNumber();
        } else {
            at = (dt / 1000l).toNumber();
        }
        var dir = bg["direction"];
        return {
            K_SGV => sgv.toString(),
            K_DIR => (dir instanceof String) ? dir : "",
            K_DT => dt,
            K_AT => at,
            K_F => nowSec
        };
    }

    function toLong(v) as Long? {
        if (v instanceof Number || v instanceof Long || v instanceof Float || v instanceof Double) {
            return v.toLong();
        }
        if (v instanceof String) {
            return v.toLong();
        }
        return null;
    }
}
