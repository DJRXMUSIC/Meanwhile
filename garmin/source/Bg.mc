import Toybox.Lang;

// The glucose reading as the phone sends it: pushed by Meanwhile v6 the moment it
// arrives (a phone-app message), or fetched from its /pebble endpoint as a backup.
// Both have the same shape. This module also runs in the background process, which
// has very little memory, so it holds only the URL, the storage keys and the parser.
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
    const K_DELTA = "d"; // change from the reading before, as shown ("+4", "-12", "--")
    const K_IOB = "i";   // insulin on board, units (Float), or null if Meanwhile hasn't reported
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
        var iob = bg["iob"];
        return {
            K_SGV => sgv.toString(),
            K_DIR => (dir instanceof String) ? dir : "",
            K_DT => dt,
            K_AT => at,
            K_F => nowSec,
            K_DELTA => delta(bg["bgdelta"]),
            K_IOB => (iob instanceof Number || iob instanceof Float || iob instanceof Double || iob instanceof Long)
                ? iob.toFloat() : null
        };
    }

    // The message Meanwhile sends right after each reading, to release it on the watch.
    function isNudge(data) as Boolean {
        return data instanceof Dictionary && data.hasKey("nudge");
    }

    // Of two parsed readings (either may be null), the newer by its phone time; a, if
    // they are the same reading.
    function newer(a as Dictionary?, b as Dictionary?) as Dictionary? {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return (b[K_DT] as Long) > (a[K_DT] as Long) ? b : a;
    }

    // The change from the reading before, signed: "+4", "-12", "0"; "+0.3" in mmol/L;
    // "--" when there is no reading close enough before it.
    function delta(v) as String {
        if (v == null) {
            return "--";
        }
        if (v instanceof Number || v instanceof Long) {
            return v > 0 ? "+" + v.toString() : v.toString();
        }
        if (v instanceof Float || v instanceof Double) {
            var t = v.format("%.1f");
            return v > 0 ? "+" + t : t;
        }
        if (v instanceof String) {
            if (v.length() == 0) {
                return "--";
            }
            var f = v.toFloat();
            if (f != null && f > 0 && !v.substring(0, 1).equals("+")) {
                return "+" + v;
            }
            return v;
        }
        return "--";
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
