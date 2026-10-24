#!/usr/bin/env bash
# Builds the watch face, runs its unit tests in the Connect IQ simulator and takes a
# screenshot of it on each Instinct E. Runs inside ghcr.io/matco/connectiq-tester
# (Connect IQ SDK + device files + simulator); see .github/workflows/garmin-watchface.yml.
#
# usage: ci.sh <output dir>
set -uo pipefail

GARMIN=$(cd "$(dirname "$0")/.." && pwd)
OUT=$(mkdir -p "$1" && cd "$1" && pwd)
DEVICES="instincte40mm instincte45mm"
CIQ=/root/.Garmin/ConnectIQ
# The simulator looks for device files and fonts under $HOME; Actions sets HOME elsewhere.
[ "$HOME" != /root ] && mkdir -p "$HOME" && ln -sfn /root/.Garmin "$HOME/.Garmin"

FAILED=0
fail() { echo "::error::$*"; FAILED=1; }

echo "=== Instinct devices in this SDK image"
ls "$CIQ/Devices" | grep -i instinct || true
for d in $DEVICES; do
  echo "--- $d"
  python3 - "$CIQ/Devices/$d/compiler.json" <<'PY' || fail "no device files for $d"
import json, sys
c = json.load(open(sys.argv[1]))
print("resolution:", c.get("resolution"), "display:", c.get("displayType"), "bpp:", c.get("bitsPerPixel"))
print("CIQ versions:", sorted({p.get("connectIQVersion") for p in c.get("partNumbers", [])}))
print("launcher icon:", c.get("launcherIcon"))
for a in c.get("appTypes", []):
    if a.get("type") in ("watchFace", "background"):
        print("memory", a.get("type"), a.get("memoryLimit"))
PY
done

echo "=== signing key"
KEY="$OUT/../developer_key.der"
if [ -n "${DEVELOPER_KEY_B64:-}" ]; then
  echo "$DEVELOPER_KEY_B64" | base64 -d > "$KEY"
  echo "using GARMIN_DEVELOPER_KEY_B64"
else
  openssl genrsa -out /tmp/key.pem 4096 2>/dev/null
  openssl pkcs8 -topk8 -inform PEM -outform DER -in /tmp/key.pem -out "$KEY" -nocrypt
  echo "generated a throwaway key (fine for sideloading)"
fi

cd "$GARMIN"
echo "=== build"
for d in $DEVICES; do
  monkeyc -f monkey.jungle -d "$d" -y "$KEY" -r -w -o "$OUT/meanwhile-$d.prg" || fail "release build for $d"
  monkeyc -f monkey.jungle -d "$d" -y "$KEY" -o "/tmp/debug-$d.prg" || fail "debug build for $d"
  monkeyc -f monkey.jungle -d "$d" -y "$KEY" -t -o "/tmp/test-$d.prg" || fail "test build for $d"
done
[ $FAILED = 0 ] || exit 1

echo "=== simulator"
export DISPLAY=:1
Xvfb :1 -screen 0 1024x768x24 >/dev/null 2>&1 &
sleep 2

start_sim() {
  pkill -f '/connectiq/bin/simulator' 2>/dev/null; sleep 1
  simulator >/dev/null 2>&1 &
  sleep 6
}

for d in $DEVICES; do
  start_sim
  echo "--- unit tests on $d"
  timeout 180 monkeydo "/tmp/test-$d.prg" "$d" -t > "/tmp/result-$d.txt" 2>&1
  cat "/tmp/result-$d.txt"
  tail -1 "/tmp/result-$d.txt" | grep -q '^PASSED' || fail "unit tests on $d"
done

# Screenshot: the real path, end to end. The fake phone endpoint serves a reading
# taken 3 minutes ago; the face's background fetches it like it would on the phone.
python3 "$GARMIN/tools/fake_pebble.py" 287 DoubleUp 3 > /tmp/fake.log 2>&1 &
FAKE=$!
for d in $DEVICES; do
  start_sim
  echo "--- screenshot on $d"
  monkeydo "/tmp/debug-$d.prg" "$d" > "/tmp/run-$d.txt" 2>&1 &
  RUN=$!
  sleep 25
  xwd -root -silent | convert xwd:- "$OUT/screen-$d.png" || fail "screenshot $d"
  kill $RUN 2>/dev/null
  cat "/tmp/run-$d.txt"
done
kill $FAKE 2>/dev/null
echo "--- fake endpoint log"; cat /tmp/fake.log
grep -q "served /pebble" /tmp/fake.log || echo "::warning::the simulator never fetched /pebble"

ls -la "$OUT"
exit $FAILED
