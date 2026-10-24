import Toybox.Lang;
import Toybox.Test;

// Unit tests, run in the simulator by CI (monkeydo -t). Not part of the release build.

const T0 = 1790000000; // a reading time, s

(:test)
function parseReadsBridgePebbleReply(logger as Test.Logger) as Boolean {
    // The bridge's /pebble: sgv is a string, status[0].now is the phone's clock.
    var r = Bg.parse(200, {
        "status" => [{"now" => 1790000090000l}],
        "bgs" => [{"sgv" => "123", "trend" => 4, "direction" => "Flat", "datetime" => 1790000000000l, "bgdelta" => -2}],
        "cals" => []
    }, T0 + 95);
    Test.assertEqual(r[Bg.K_SGV], "123");
    Test.assertEqual(r[Bg.K_DIR], "Flat");
    Test.assertEqual(r[Bg.K_DT], 1790000000000l);
    // 90 s old by the phone's clock; the watch's clock is 5 s ahead.
    Test.assertEqual(r[Bg.K_AT], T0 + 5);
    Test.assertEqual(r[Bg.K_F], T0 + 95);
    return true;
}

(:test)
function parseAcceptsNumericSgvAndNoStatus(logger as Test.Logger) as Boolean {
    var r = Bg.parse(200, {"bgs" => [{"sgv" => 287, "direction" => "DoubleUp", "datetime" => 1790000000000l}]}, T0 + 30);
    Test.assertEqual(r[Bg.K_SGV], "287");
    Test.assertEqual(r[Bg.K_AT], T0);
    return true;
}

(:test)
function parseRejectsErrorsAndEmptyReplies(logger as Test.Logger) as Boolean {
    Test.assert(Bg.parse(-104, null, T0) == null);
    Test.assert(Bg.parse(404, {"bgs" => []}, T0) == null);
    Test.assert(Bg.parse(200, {"status" => [{"now" => 1}], "bgs" => []}, T0) == null);
    Test.assert(Bg.parse(200, {"bgs" => [{"direction" => "Flat"}]}, T0) == null);
    Test.assert(Bg.parse(200, "nope", T0) == null);
    return true;
}

(:test)
function minutesSinceIsWholeMinutes(logger as Test.Logger) as Boolean {
    Test.assertEqual(minutesSince(T0, T0), 0);
    Test.assertEqual(minutesSince(T0, T0 + 59), 0);
    Test.assertEqual(minutesSince(T0, T0 + 60), 1);
    Test.assertEqual(minutesSince(T0, T0 + 12 * 60 + 30), 12);
    Test.assertEqual(minutesSince(T0, T0 - 30), 0);
    Test.assertEqual(minutesSince(T0, T0 + 100000), 999);
    return true;
}

(:test)
function nextFetchLocksOntoTheReadingCadence(logger as Test.Logger) as Boolean {
    var lag = 15;
    // Never run, no reading: now.
    Test.assertEqual(Sched.nextFetch(null, null, T0, lag), T0);
    // Ran 2 min ago, no reading: as soon as allowed.
    Test.assertEqual(Sched.nextFetch(null, T0 - 120, T0, lag), T0 + 180);
    // Reading at T0, last run T0+40 (it fired 25 s after the ideal T0+15): the
    // ideal T0+315 is 25 s out of reach, so as soon as allowed.
    Test.assertEqual(Sched.nextFetch(T0, T0 + 40, T0 + 41, lag), T0 + 340);
    // Last run with no lateness: exactly the ideal time.
    Test.assertEqual(Sched.nextFetch(T0, T0 + 15, T0 + 16, lag), T0 + 315);
    // Drifted more than SLACK (last run T0+120): skip a reading to get back in step.
    Test.assertEqual(Sched.nextFetch(T0, T0 + 120, T0 + 121, lag), T0 + 615);
    // An old reading: the next ideal time in its cadence that's allowed.
    Test.assertEqual(Sched.nextFetch(T0, T0 + 3600, T0 + 3600, lag), T0 + 3900 + 15);
    return true;
}

(:test)
function lagAdaptsOnlyToTimingEvidence(logger as Test.Logger) as Boolean {
    // Fresh reading on time: lag shrinks, but not below the minimum.
    Test.assertEqual(Sched.nextLag(45, true, T0, T0 - 300, T0 + 45), 40);
    Test.assertEqual(Sched.nextLag(15, true, T0, T0 - 300, T0 + 15), 15);
    // Fresh reading found long after it was taken (first fetch, phone was away): unchanged.
    Test.assertEqual(Sched.nextLag(45, true, T0, T0 - 300, T0 + 600), 45);
    // Late within SLACK still counts as on time.
    Test.assertEqual(Sched.nextLag(45, true, T0, T0 - 300, T0 + 90), 40);
    // Due reading not there yet: lag grows, but not above the maximum.
    Test.assertEqual(Sched.nextLag(15, false, T0, T0, T0 + 315), 45);
    Test.assertEqual(Sched.nextLag(120, false, T0, T0, T0 + 420), 120);
    // Nothing new for much longer than a cycle: an outage, unchanged.
    Test.assertEqual(Sched.nextLag(15, false, T0, T0, T0 + 1500), 15);
    return true;
}
