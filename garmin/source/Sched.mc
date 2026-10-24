import Toybox.Application;
import Toybox.Background;
import Toybox.Lang;
import Toybox.Time;

// When to fetch.
//
// Connect IQ runs a watch face's background at most once every 5 minutes, and
// Eversense sends a reading every 5 minutes. A plain 5-minute timer would show
// each reading 0-5 minutes late (2.5 on average). Instead every fetch is a
// one-shot timer set for just after the next reading is due, so it is shown
// about `lag` seconds after it reaches the phone.
//
// `lag` adapts: a fetch that finds the reading not there yet (due but late)
// adds LAG_UP; a fetch that gets a fresh reading takes LAG_DOWN off.
module Sched {
    const PERIOD = 300;   // s; Eversense cadence and Connect IQ's minimum
    const LAG_MIN = 15;   // s after the reading is due
    const LAG_MAX = 120;
    const LAG_UP = 30;
    const LAG_DOWN = 5;
    const SLACK = 60;     // s; how late a fetch may be before it skips a reading to re-sync

    // The next fetch time, s since epoch.
    //   readingAt: time of the newest reading held (null if none)
    //   lastRun:   when the background last ran (null if never)
    //
    // Readings are due at readingAt + k * PERIOD, and the ideal fetch is `lag` after
    // one. Each run fires a little after it was asked to, and the next can't be
    // sooner than 5 minutes after that, so the ideal time is often just out of
    // reach: up to SLACK late, fetch as soon as allowed; beyond that, skip to the
    // following reading, which puts the fetches back in step with the readings.
    function nextFetch(readingAt, lastRun, now as Number, lag as Number) as Number {
        var earliest = (lastRun == null) ? now : lastRun + PERIOD;
        if (earliest < now) {
            earliest = now;
        }
        if (readingAt == null) {
            return earliest;
        }
        var t = readingAt + PERIOD + lag;
        if (t >= earliest) {
            return t;
        }
        t += ((earliest - t) / PERIOD) * PERIOD; // the last ideal time not after earliest
        if (earliest - t <= SLACK) {
            return earliest;
        }
        return t + PERIOD;
    }

    // The lag after a fetch at fetchedAt that returned the reading taken at gotAt.
    //   isNew:  the reading wasn't held before
    //   prevAt: time of the reading held before the fetch
    function nextLag(lag as Number, isNew as Boolean, gotAt as Number, prevAt, fetchedAt as Number) as Number {
        if (isNew) {
            // Only a fetch that was on time says anything about the timing.
            if (fetchedAt - gotAt <= lag + SLACK) {
                lag -= LAG_DOWN;
            }
        } else if (prevAt != null) {
            // The next reading was due and isn't there yet. (A longer gap is an
            // Eversense outage or a missed reading, not a timing problem.)
            var since = fetchedAt - prevAt;
            if (since > PERIOD && since <= PERIOD + LAG_MAX + 60) {
                lag += LAG_UP;
            }
        }
        if (lag < LAG_MIN) {
            lag = LAG_MIN;
        }
        if (lag > LAG_MAX) {
            lag = LAG_MAX;
        }
        return lag;
    }

    function currentLag() as Number {
        var v = Application.Storage.getValue(Bg.K_LAG);
        return (v instanceof Number) ? v : LAG_MIN;
    }

    // Stores the background's result and sets the next fetch.
    function onData(data) as Void {
        log("onData " + data);
        var lag = currentLag();
        if (data instanceof Dictionary) {
            var prevDt = Application.Storage.getValue(Bg.K_DT);
            var prevAt = Application.Storage.getValue(Bg.K_AT);
            var dt = data[Bg.K_DT];
            var isNew = prevDt == null || dt > prevDt;
            lag = nextLag(lag, isNew, data[Bg.K_AT], prevAt, data[Bg.K_F]);
            if (isNew) {
                Application.Storage.setValue(Bg.K_SGV, data[Bg.K_SGV]);
                Application.Storage.setValue(Bg.K_DIR, data[Bg.K_DIR]);
                Application.Storage.setValue(Bg.K_DT, dt);
                Application.Storage.setValue(Bg.K_AT, data[Bg.K_AT]);
            }
        }
        Application.Storage.setValue(Bg.K_LAG, lag);
        arm();
    }

    function arm() as Void {
        var now = Time.now().value();
        var last = Background.getLastTemporalEventTime();
        var at = Application.Storage.getValue(Bg.K_AT);
        var t = nextFetch(
            (at instanceof Number) ? at : null,
            (last == null) ? null : last.value(),
            now,
            currentLag()
        );
        log("arm: now " + now + " next fetch in " + (t - now) + " s (lag " + currentLag() + ")");
        try {
            Background.registerForTemporalEvent(new Time.Moment(t));
        } catch (e) {
            // Fall back to a plain 5-minute timer rather than stop fetching.
            Background.registerForTemporalEvent(new Time.Duration(PERIOD));
        }
    }

    (:debug)
    function log(msg as String) as Void {
        System.println(msg);
    }

    (:release)
    function log(msg as String) as Void {
    }

    // Called once a minute by the face: re-arms if the one-shot timer is gone.
    function ensureArmed() as Void {
        var reg = Background.getTemporalEventRegisteredTime();
        if (reg == null || (reg instanceof Time.Moment && reg.value() < Time.now().value() - 60)) {
            arm();
        }
    }
}
