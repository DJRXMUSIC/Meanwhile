#!/usr/bin/env bash
# End-to-end test on a running emulator:
#   fake Eversense app (same package name as Eversense 365) posts a glucose notification
#   -> Eversense Bridge captures it -> http://127.0.0.1:17580/sgv.json serves it.
# Also checks dedupe, mmol parsing, rejection of LO, recovery after the process is
# killed, and recovery after a reboot.
#
# usage: run-e2e.sh <bridge.apk> <fake-eversense.apk>
set -euo pipefail

APP_APK=$1
FAKE_APK=$2
APP=app.meanwhile.bridge
FAKE=com.senseonics.eversense365.us
PORT=17580
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
fail() { echo "FAIL: $*"; dump; exit 1; }
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

echo "all $PASSED end-to-end checks passed"
get "/sgv.json?count=6"
echo
