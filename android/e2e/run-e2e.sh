#!/usr/bin/env bash
# End-to-end test on a running emulator:
#   fake Eversense app (same package name as Eversense 365) posts a glucose notification
#   -> Meanwhile v5 captures it -> http://127.0.0.1:17590/sgv.json serves it.
# Also checks dedupe, mmol parsing, rejection of LO, recovery after the process is
# killed, and recovery after a reboot. Then the app as it is used: Meanwhile, packaged
# inside the app, shows the captured reading straight from the bridge and the next one
# when it arrives, saves a backup file, and backs up to a Meanwhile server. Last, the glucose
# alerts imported from xDrip+: a low sounds and repeats, stops when back up, and snoozes.
#
# usage: run-e2e.sh <bridge.apk> <fake-eversense.apk> <meanwhile server url>
#   <meanwhile server url>: the website version (next start) on the machine running adb,
#   e.g. http://localhost:3000. Only the cloud backup check uses it; the device reaches it
#   through adb reverse.
set -euo pipefail

APP_APK=$1
FAKE_APK=$2
SERVER_URL=$3
SERVER_PORT=$(echo "$SERVER_URL" | sed -E 's|.*:([0-9]+)/?$|\1|')
APP=app.meanwhile.bridge
FAKE=com.senseonics.eversense365.us
PORT=17590
PASSED=0

get() { curl -sS --max-time 5 "http://127.0.0.1:$PORT$1"; }
# py <path> <python expression over parsed JSON d>
py() { get "$1" 2>/dev/null | python3 -c "import json,sys; d=json.load(sys.stdin); print($2)" 2>/dev/null || echo ERR; }
count() { py "/sgv.json?count=1000" "len(d)"; }
newest() { py "/sgv.json?count=1" "d[0]['$1']"; }
outcome() { py /status.json "(d['last_capture'] or {}).get('outcome')"; }

dump() {
  echo "----- diagnostics -----"
  get /status.json || true
  echo
  get "/sgv.json?count=10" || true
  echo
  adb shell cmd notification list 2>/dev/null | head -20 || true
  adb logcat -d -s EversenseBridge:V FakeEversense:V AndroidRuntime:E ActivityManager:W | tail -120 || true
}
shot() { mkdir -p e2e-out; adb exec-out screencap -p > "e2e-out/$1.png" 2>/dev/null || true; }
fail() { echo "FAIL: $*"; shot failure; dump; exit 1; }
ok() { PASSED=$((PASSED + 1)); echo "ok $PASSED - $*"; }

# wait_until <seconds> <command...>: retry the command once a second
wait_until() {
  local t=$1; shift
  for _ in $(seq 1 "$t"); do
    if "$@"; then return 0; fi
    sleep 1
  done
  return 1
}
listener_up() { [ "$(py /status.json "d['listener_connected']")" = True ]; }
count_is() { [ "$(count)" = "$1" ]; }

wait_boot() {
  adb wait-for-device
  wait_until 300 sh -c '[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d "\r")" = 1 ]' || fail "no boot"
  adb forward tcp:$PORT tcp:$PORT >/dev/null
}

post() { adb shell am start -W -n "$FAKE/com.senseonics.fake.PostActivity" "$@" >/dev/null; }
last_fake_when() { adb logcat -d -s FakeEversense:I | grep FAKE_POSTED | tail -1 | sed 's/.*when=\([0-9]*\).*/\1/'; }

# ------------------------------------------------------------------ setup
wait_boot
adb install -r -g "$APP_APK" >/dev/null
adb install -r -g "$FAKE_APK" >/dev/null
adb shell cmd notification allow_listener "$APP/$APP.EversenseListenerService"
adb shell dumpsys deviceidle whitelist +$APP >/dev/null
# the app icon: with no Meanwhile address yet it asks for one, and brings the bridge up
adb shell am start -W -n "$APP/.MainActivity" >/dev/null
adb forward tcp:$PORT tcp:$PORT >/dev/null

wait_until 60 listener_up || fail "listener never connected"
ok "endpoint up on :$PORT and notification listener connected"
[ "$(count)" = 0 ] || fail "expected empty store, got $(count)"

# ------------------------------------------------------------------ capture with exact time
adb logcat -c
post --es value 123 --es unit mg/dL --el when_offset_ms 1500
wait_until 15 count_is 1 || fail "custom-view reading not captured"
WHEN=$(last_fake_when)
[ "$(newest sgv)" = 123 ] || fail "sgv $(newest sgv) != 123"
[ "$(newest date)" = "$WHEN" ] || fail "date $(newest date) != notification when $WHEN"
[ "$(newest ts_source)" = notification_when ] || fail "ts_source $(newest ts_source)"
ok "custom-view notification -> sgv 123 with the app's exact reading time ($WHEN)"

# ------------------------------------------------------------------ refresh of same reading
post --es value 123 --el fixed_when "$WHEN"
sleep 3
[ "$(count)" = 1 ] || fail "refresh duplicated the reading"
[ "$(outcome)" = REPOST ] || fail "outcome $(outcome) != REPOST"
ok "re-posted notification (same reading) is not duplicated"

# ------------------------------------------------------------------ next reading + trend
sleep 11
post --es value 131 --el when_offset_ms 1200
wait_until 15 count_is 2 || fail "second reading not captured"
[ "$(newest sgv)" = 131 ] || fail "sgv $(newest sgv) != 131"
[ "$(newest direction)" != NotComputable ] || fail "no trend computed"
ok "second reading stored with direction $(newest direction), delta $(newest delta)"

# /pebble: the same newest reading as one JSON object, for the Garmin watch face
PEB=$(py /pebble "'%s %s %s' % (d['bgs'][0]['sgv'], d['bgs'][0]['datetime'], d['bgs'][0]['direction'])")
[ "$PEB" = "131 $(newest date) $(newest direction)" ] || fail "/pebble '$PEB' != sgv.json newest"
ok "/pebble serves the newest reading as an object: $PEB"

# ------------------------------------------------------------------ mmol, standard notification
sleep 11
post --es value 7.6 --es unit mmol/L --es mode extras
wait_until 15 count_is 3 || fail "mmol title-only reading not captured"
[ "$(newest sgv)" = 137 ] || fail "7.6 mmol/L -> $(newest sgv), expected 137"
ok "title-only notification '7.6 mmol/L' -> 137 mg/dL"

# ------------------------------------------------------------------ LO is not a number
sleep 11
post --es value LO
sleep 3
[ "$(count)" = 3 ] || fail "LO produced a reading"
[ "$(outcome)" = NO_VALUE ] || fail "outcome $(outcome) != NO_VALUE"
ok "'LO' is logged, not stored"

# ------------------------------------------------------------------ CORS / preflight for the PWA
curl -sS -i -X OPTIONS -H "Origin: https://example.netlify.app" -H "Access-Control-Request-Method: GET" \
  -H "Access-Control-Request-Private-Network: true" "http://127.0.0.1:$PORT/sgv.json" > /tmp/preflight.txt
grep -qi "Access-Control-Allow-Private-Network: true" /tmp/preflight.txt || fail "no PNA header: $(cat /tmp/preflight.txt)"
grep -qi "Access-Control-Allow-Origin: \*" /tmp/preflight.txt || fail "no CORS header"
ok "CORS + Private Network Access preflight answered"

# ------------------------------------------------------------------ process killed
sleep 11
post --es value 142 --el when_offset_ms 900
wait_until 15 count_is 4 || fail "reading before kill not captured"
adb root >/dev/null 2>&1 || true
wait_boot
PID=$(adb shell pidof $APP | tr -d '\r')
[ -n "$PID" ] || fail "bridge not running"
adb shell kill -9 "$PID" || fail "could not kill $PID"
sleep 2
wait_until 120 listener_up || fail "did not recover after kill -9"
NEWPID=$(adb shell pidof $APP | tr -d '\r')
[ "$NEWPID" != "$PID" ] || fail "same pid after kill"
[ "$(count)" = 4 ] || fail "readings lost or duplicated after restart: $(count)"
ok "killed (pid $PID) -> Android restarted it (pid $NEWPID), 4 readings intact, replay not duplicated"

sleep 11
post --es value 150 --el when_offset_ms 1000
wait_until 15 count_is 5 || fail "no capture after restart"
ok "capturing again after restart"

# ------------------------------------------------------------------ reboot
adb reboot
sleep 5
wait_boot
wait_until 240 listener_up || fail "bridge did not come back after reboot"
[ "$(count)" = 5 ] || fail "readings lost after reboot: $(count)"
adb logcat -c
sleep 2
post --es value 155 --el when_offset_ms 1000
wait_until 20 count_is 6 || fail "no capture after reboot"
[ "$(newest date)" = "$(last_fake_when)" ] || fail "timestamp mismatch after reboot"
ok "after reboot: endpoint + listener back by themselves, history kept, capture works"

# the built-in alerts (the owner's xDrip+ setup) work with nothing set up: 155 is the Elevated Alert
bridge_log() { adb logcat -d -s EversenseBridge:I; }
wait_until 20 sh -c 'adb logcat -d -s EversenseBridge:I | grep -q "reading 155: PLAY high Elevated Alert"' \
  || fail "155 did not start the built-in Elevated Alert: $(bridge_log | grep -E 'ALERT:|SOUND:')"
wait_until 15 sh -c "adb logcat -d -s EversenseBridge:I | grep -q \"SOUND: playing the alert's sound\"" \
  || fail "the built-in vibration sound did not play: $(bridge_log | grep -E 'ALERT:|SOUND:')"
ok "built-in alerts: 155 -> Elevated Alert plays its built-in vibration sound on the alarm volume"

# ------------------------------------------------------------------ Meanwhile inside the app
# webcheck [extras...]: ask the app to report what its page shows (optionally on another
# page, after pressing a button, once some text appears); webcheck_line is the report.
# (adb shell joins its arguments into one command line for the device's shell, which
# would split "Download backup" in two: each argument is single-quoted for that shell)
webcheck() {
  local args="" a
  for a in "$@"; do args="$args '$(printf %s "$a" | sed "s/'/'\\\\''/g")'"; done
  adb shell "am start -W -n $APP/.MainActivity --es action web_check$args" >/dev/null
}
webcheck_line() { adb logcat -d -s EversenseBridge:I | grep "WEBCHECK:" | tail -1; }
reported() { [ -n "$(webcheck_line)" ]; }
# page_shows <text>: ask again until the page contains <text>
page_shows() { webcheck; sleep 2; webcheck_line | grep -qF -- "$1"; }

adb logcat -c
# the page first shows what it had saved, then the bridge's newest reading: wait for that
webcheck --es until "155"
wait_until 150 reported || fail "the Meanwhile page never reported"
W=$(webcheck_line)
echo "$W" | grep -q "url=https://appassets.androidplatform.net/" || fail "not the packaged page: $W"
echo "$W" | grep -Eq "native_reads=[1-9]" || fail "the page did not read from the bridge directly: $W"
echo "$W" | grep -q "155" || fail "the page does not show the newest reading (155): $W"
echo "$W" | grep -Eq "next in [0-9]+:[0-9]{2}|due now" || fail "no countdown on the page: $W"
shot meanwhile-in-app
ok "Meanwhile, packaged in the app, shows 155 with a countdown, read straight from the bridge ($(echo "$W" | grep -o 'native_reads=[0-9]*'))"

# a new reading arrives while the page is open: it shows up by itself
sleep 11
post --es value 162 --el when_offset_ms 1000
wait_until 20 count_is 7 || fail "reading 162 not captured"
wait_until 45 page_shows "162" || fail "the open page never showed the new reading: $(webcheck_line)"
webcheck_line | grep -q "+7" || fail "the page does not show the change since the last reading (+7): $(webcheck_line)"
ok "a new reading (162, +7) appeared on the open page by itself"

# the endpoint for everything else (the watch) is still up alongside
[ "$(newest sgv)" = 162 ] || fail "endpoint newest $(newest sgv) != 162"
ok "endpoint still serving on :$PORT with the app open"

# backup file: Settings > Download backup saves a file you can copy off the phone
adb logcat -c
webcheck --es page settings/ --es click "Download backup" --es until "Backup saved to"
wait_until 60 reported || fail "the backup never reported"
webcheck_line | grep -q "Backup saved to Downloads/Meanwhile/meanwhile-" || fail "backup not saved: $(webcheck_line)"
BK=$(adb shell ls /sdcard/Download/Meanwhile/ | tr -d '\r' | grep '^meanwhile-.*\.json$' | tail -1)
[ -n "$BK" ] || fail "no backup file in Downloads/Meanwhile"
mkdir -p e2e-out
adb pull "/sdcard/Download/Meanwhile/$BK" e2e-out/ >/dev/null
python3 - "e2e-out/$BK" <<'PYB' || fail "backup file is wrong"
import json, sys
d = json.load(open(sys.argv[1]))
sgvs = sorted(r["mgdl"] for r in d["bg"])
assert 155 in sgvs and 162 in sgvs, sgvs
assert len(d["profile"]) == 1 and d["profile"][0]["xdrip_url"] == "http://127.0.0.1:17590", d["profile"]
for t in ("insulin", "decisions", "context"): assert isinstance(d[t], list), t
print("backup:", len(d["bg"]), "readings", sgvs)
PYB
ok "backup file saved to Downloads/Meanwhile/$BK with the readings and profile"

# Settings starts with the way into the glucose alerts
adb logcat -c
webcheck --es page settings/ --es until "Open glucose alerts"
wait_until 60 reported || fail "the settings page never reported"
webcheck_line | grep -q "Open glucose alerts" || fail "no glucose alerts entry in Settings: $(webcheck_line)"
ok "Meanwhile Settings shows 'Open glucose alerts'"

# cloud backup: Settings > Sync now sends the data to a Meanwhile server
curl -fsS --max-time 10 -o /dev/null "$SERVER_URL" || fail "the Meanwhile server isn't running at $SERVER_URL"
adb reverse "tcp:$SERVER_PORT" "tcp:$SERVER_PORT" >/dev/null
adb logcat -c
webcheck --es page settings/ --es api "$SERVER_URL" --es click "Sync now" --es until "Sync memory: pushed"
wait_until 60 reported || fail "the sync never reported"
W=$(webcheck_line)
echo "$W" | grep -Eq "Sync memory: pushed [1-9]" || fail "cloud backup failed: $(echo "$W" | grep -o 'Sync[^|]*' | head -1) :: $W"
ok "cloud backup from the app reached the server ($(echo "$W" | grep -Eo 'Sync memory: pushed [0-9]+, pulled [0-9]+'))"

# ------------------------------------------------------------------ self-test button
adb shell am start -W -n "$APP/.StatusActivity" --es action self_test >/dev/null
selftest_line() { adb logcat -d -s EversenseBridge:I | grep "SELFTEST:" | tail -1; }
selftest_done() { [ -n "$(selftest_line)" ]; }
wait_until 30 selftest_done || fail "self-test did not finish"
selftest_line | grep -q "SELFTEST: PASS" || fail "self-test reported problems: $(selftest_line)"
ok "self-test PASS: $(selftest_line | sed 's/.*SELFTEST: //' | cut -c1-200)"

# ------------------------------------------------------------------ log file export
adb shell am start -W -n "$APP/.StatusActivity" --es action export_log >/dev/null
log_saved() { adb shell ls /sdcard/Download/Meanwhile/ 2>/dev/null | grep -q "meanwhile-log-"; }
wait_until 60 log_saved || fail "log file not saved to Downloads/Meanwhile"
mkdir -p e2e-out
LOGFILE=$(adb shell ls /sdcard/Download/Meanwhile/ | tr -d '\r' | grep meanwhile-log- | tail -1)
adb pull "/sdcard/Download/Meanwhile/$LOGFILE" e2e-out/ >/dev/null
L="e2e-out/$LOGFILE"
for needle in "===== Health" "===== Active notifications (raw)" "package/id/tag: $FAKE" \
    "parse result: 162 mg/dL" "[CAPTURE] STORED 155 mg/dL" "[CAPTURE] STORED 162 mg/dL" "[LISTENER] connected" "[BOOT]" \
    "[SELFTEST] PASS" "===== Logcat" "===== end ====="; do
  grep -qF "$needle" "$L" || { echo "--- saved log ---"; cat "$L"; fail "saved log is missing: $needle"; }
done
ok "log file saved to Downloads ($(wc -c < "$L") bytes) with raw Eversense notification, events, self-test PASS and logcat"

# ------------------------------------------------------------------ glucose alerts (from xDrip+)
# An xDrip+ settings export with a low alert at 80 (repeat every minute, snooze 15, sounds
# even on silent, no sound file: the phone's alarm sound) and a high at 300.
cat > e2e-out/xdrip-import.xml <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="saved_alerts">[{&quot;above&quot;:false,&quot;active&quot;:true,&quot;all_day&quot;:true,&quot;default_snooze&quot;:15,&quot;end_time_minutes&quot;:0,&quot;force_speaker&quot;:true,&quot;light&quot;:false,&quot;minutes_between&quot;:1,&quot;mp3_file&quot;:&quot;&quot;,&quot;name&quot;:&quot;E2E LOW&quot;,&quot;override_silent_mode&quot;:true,&quot;predictive&quot;:false,&quot;start_time_minutes&quot;:0,&quot;threshold&quot;:80.0,&quot;time_until_threshold_crossed&quot;:0.0,&quot;uuid&quot;:&quot;e2e-low-80&quot;,&quot;vibrate&quot;:true,&quot;volume&quot;:0},{&quot;above&quot;:true,&quot;active&quot;:true,&quot;all_day&quot;:true,&quot;default_snooze&quot;:90,&quot;force_speaker&quot;:true,&quot;minutes_between&quot;:3,&quot;name&quot;:&quot;E2E HIGH&quot;,&quot;override_silent_mode&quot;:true,&quot;threshold&quot;:300.0,&quot;uuid&quot;:&quot;e2e-high-300&quot;,&quot;vibrate&quot;:true}]</string>
    <string name="bg_alert_profile">High</string>
    <boolean name="smart_snoozing" value="false" />
    <boolean name="smart_alerting" value="false" />
    <boolean name="no_alarms_during_calls" value="true" />
    <long name="alerts_disabled_until" value="99999999999999" />
</map>
XML
alert_log() { adb logcat -d -s EversenseBridge:I | grep -E "ALERT:|SOUND:" || true; }
has_log() { alert_log | grep -qF -- "$1"; }
plays() { alert_log | grep -c "PLAY low" || true; }
alert_shown() { adb shell cmd notification list 2>/dev/null | tr -d '\r' | grep -q "|$APP|3|"; }
alert_gone() { ! alert_shown; }

# automation on the alerts screen needs a token that only adb can put in the app's folder
TOKEN=$(head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')
printf '%s' "$TOKEN" > e2e-out/automation-token
adb logcat -c
adb shell mkdir -p "/sdcard/Android/data/$APP/files"
adb push e2e-out/xdrip-import.xml "/sdcard/Android/data/$APP/files/xdrip-import.xml" >/dev/null
adb push e2e-out/automation-token "/sdcard/Android/data/$APP/files/automation-token" >/dev/null
adb shell am start -W -n "$APP/.AlertsActivity" --es action snooze --es token wrong-token-0123456789 >/dev/null
wait_until 10 has_log "ignored automation 'snooze'" || fail "automation with a wrong token was not refused: $(alert_log)"
adb shell am start -W -n "$APP/.AlertsActivity" --es action import_file --es token "$TOKEN" >/dev/null
wait_until 20 has_log "imported 2 alerts from xDrip+" || fail "alerts not imported: $(alert_log)"
shot alerts-screen
ok "xDrip+ alert settings imported (low ≤80, high ≥300, profile High)"

sleep 11
post --es value 70 --el when_offset_ms 1000
wait_until 20 count_is 8 || fail "reading 70 not captured"
wait_until 20 has_log "reading 70: PLAY low E2E LOW" || fail "a reading of 70 did not start the low alert: $(alert_log)"
wait_until 15 alert_shown || fail "no alert notification: $(adb shell cmd notification list)"
wait_until 15 sh -c 'adb logcat -d -s EversenseBridge:I | grep -qE "SOUND: (playing|.*beeping instead)"' \
  || fail "the alert made no sound: $(alert_log)"
SOUND=$(alert_log | grep "SOUND:" | tail -1 | sed 's/.*SOUND: //')
shot alert-notification
ok "70 mg/dL -> low alert sounds ($SOUND) with its notification"

# it repeats every minute, by itself, while still low
wait_until 80 has_log "PLAY low E2E LOW ≤80 (low E2E LOW ≤80 repeats)" || fail "the alert did not repeat after a minute: $(alert_log)"
ok "still low a minute later -> the alert sounds again"

# back in range: it stops
sleep 5
post --es value 120 --el when_offset_ms 1000
wait_until 20 count_is 9 || fail "reading 120 not captured"
wait_until 20 has_log "reading 120: STOP" || fail "the alert did not stop at 120: $(alert_log)"
wait_until 10 alert_gone || fail "alert notification still shown at 120"
ok "back to 120 -> the alert stops and its notification goes"
# as in xDrip+, the stopped alert's record lasts until its repeat time, then a check clears it
STOPS=$(alert_log | grep -c "STOP (no alert applies)" || true)
cleared() { [ "$(alert_log | grep -c "STOP (no alert applies)" || true)" -gt "$STOPS" ]; }
wait_until 80 cleared || fail "the stopped alert was never cleared: $(alert_log)"

# low again, then Snooze: quiet for the alert's 15 minutes even as readings stay low
sleep 11
post --es value 68 --el when_offset_ms 1000
wait_until 20 count_is 10 || fail "reading 68 not captured"
wait_until 20 has_log "reading 68: PLAY low" || fail "68 did not start the alert again: $(alert_log)"
adb shell am start -W -n "$APP/.AlertsActivity" --es action snooze --es token "$TOKEN" >/dev/null
wait_until 20 has_log "snoozed for 15 min" || fail "snooze did not work: $(alert_log)"
wait_until 10 alert_gone || fail "alert notification still shown after snooze"
BEFORE=$(plays)
sleep 11
post --es value 71 --el when_offset_ms 1000
wait_until 20 count_is 11 || fail "reading 71 not captured"
sleep 8
[ "$(plays)" = "$BEFORE" ] || fail "the alert sounded while snoozed: $(alert_log)"
alert_gone || fail "alert notification came back while snoozed"
ok "snoozed for 15 min -> a further low reading (71) stays quiet"

echo "all $PASSED end-to-end checks passed"
get "/sgv.json?count=7"
echo
