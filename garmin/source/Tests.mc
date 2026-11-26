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
    // 90 s old when the phone built the reply, which arrived 95 s after the reading by the
    // reading's own time: the older of the two, so it shows as 95 s old.
    Test.assertEqual(r[Bg.K_AT], T0);
    Test.assertEqual(r[Bg.K_F], T0 + 95);
    Test.assertEqual(r[Bg.K_DELTA], "-2");
    Test.assert(r[Bg.K_IOB] == null);
    return true;
}

(:test)
function parseReadsAPushedMessageWithIob(logger as Test.Logger) as Boolean {
    // What Meanwhile v6 pushes: the /pebble shape, times as strings (Monkey C Numbers
    // are 32-bit), IOB as a float.
    var r = Bg.parse(200, {
        "status" => [{"now" => "1790000002000"}],
        "bgs" => [{"sgv" => "142", "direction" => "FortyFiveUp", "datetime" => "1790000000000", "bgdelta" => 4, "iob" => 2.37}]
    }, T0 + 2);
    Test.assertEqual(r[Bg.K_SGV], "142");
    Test.assertEqual(r[Bg.K_DT], 1790000000000l);
    Test.assertEqual(r[Bg.K_AT], T0);
    Test.assertEqual(r[Bg.K_DELTA], "+4");
    Test.assertEqual(iobText(r[Bg.K_IOB]), "2.4U");
    return true;
}

(:test)
function deltaIsSignedAndDashedWhenUnknown(logger as Test.Logger) as Boolean {
    Test.assertEqual(Bg.delta(4), "+4");
    Test.assertEqual(Bg.delta(-12), "-12");
    Test.assertEqual(Bg.delta(0), "0");
    Test.assertEqual(Bg.delta(null), "--");
    Test.assertEqual(Bg.delta("0.3"), "+0.3"); // mmol/L arrive as strings
    Test.assertEqual(Bg.delta("-0.2"), "-0.2");
    Test.assertEqual(Bg.delta("0.0"), "0.0");
    Test.assertEqual(Bg.delta(""), "--");
    Test.assertEqual(Bg.delta(1.5), "+1.5");
    return true;
}

(:test)
function ageTagIoBAndStepsText(logger as Test.Logger) as Boolean {
    Test.assertEqual(ageText(0), "0m");
    Test.assertEqual(ageText(119), "1m");
    Test.assertEqual(ageText(24 * 60 + 5), "24m");
    Test.assertEqual(ageText(-30), "0m");
    Test.assertEqual(ageText(200 * 60), "99+m");
    Test.assertEqual(iobText(null), "--");
    Test.assertEqual(iobText(0), "0.0U");
    Test.assertEqual(iobText(12.46), "12.5U");
    Test.assertEqual(groupThousands(0), "0");
    Test.assertEqual(groupThousands(999), "999");
    Test.assertEqual(groupThousands(8412), "8,412");
    Test.assertEqual(groupThousands(18412), "18,412");
    Test.assertEqual(groupThousands(1234567), "1,234,567");
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

(:test)
function aStalePushLosesToTheNewerFetch(logger as Test.Logger) as Boolean {
    // The Instinct E can deliver the message before the newest: the face keeps whichever
    // of the pushed and fetched readings is newer.
    var pushed = Bg.parse(200, {
        "status" => [{"now" => "1790000301000"}],
        "bgs" => [{"sgv" => "119", "datetime" => "1790000000000", "bgdelta" => -1}]
    }, T0 + 301);
    var fetched = Bg.parse(200, {
        "status" => [{"now" => 1790000302000l}],
        "bgs" => [{"sgv" => "124", "datetime" => 1790000300000l, "bgdelta" => 5}]
    }, T0 + 302);
    Test.assertEqual(Bg.newer(fetched, pushed)[Bg.K_SGV], "124");
    Test.assertEqual(Bg.newer(pushed, fetched)[Bg.K_SGV], "124");
    // the fetch failed: the push stands; nothing at all: nothing
    Test.assertEqual(Bg.newer(null, pushed)[Bg.K_SGV], "119");
    Test.assertEqual(Bg.newer(fetched, null)[Bg.K_SGV], "124");
    Test.assert(Bg.newer(null, null) == null);
    // the same reading both ways: the fetch (first) is kept
    var again = Bg.parse(200, {
        "status" => [{"now" => 1790000303000l}],
        "bgs" => [{"sgv" => "124", "datetime" => 1790000300000l, "iob" => 0.3}]
    }, T0 + 303);
    Test.assertEqual(Bg.newer(again, fetched)[Bg.K_IOB], 0.3f);
    return true;
}


(:test)
function aHeldBackMessageDoesNotMakeAReadingLookFresh(logger as Test.Logger) as Boolean {
    // Sent 2 s after the reading, held on the watch and delivered 5 minutes later: it is
    // 5 minutes old, not 2 s.
    var held = Bg.parse(200, {
        "status" => [{"now" => "1790000002000"}],
        "bgs" => [{"sgv" => "124", "datetime" => "1790000000000"}]
    }, T0 + 302);
    Test.assertEqual(held[Bg.K_AT], T0);
    Test.assertEqual(ageText(T0 + 302 - held[Bg.K_AT]), "5m");
    // the watch's clock 60 s behind the phone's: the age measured on the phone counts
    var behind = Bg.parse(200, {
        "status" => [{"now" => "1790000002000"}],
        "bgs" => [{"sgv" => "124", "datetime" => "1790000000000"}]
    }, T0 - 58);
    Test.assertEqual(behind[Bg.K_AT], T0 - 60);
    return true;
}
