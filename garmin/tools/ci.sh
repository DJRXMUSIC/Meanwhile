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
Xvfb :1 -screen 0 1024x1400x24 >/dev/null 2>&1 &
sleep 2

start_sim() {
  pkill -f '/connectiq/bin/simulator' 2>/dev/null; sleep 1
  simulator >/dev/null 2>&1 &
  sleep 8
}

# The simulator applies the watch's HTTPS rule to every request, but on the phone
# Garmin Connect allows plain http to 127.0.0.1, which is what the face uses. Turn
# off Settings > Use Device HTTPS Requirements (found by OCR; it's only in the menu).
disable_device_https() {
  xdotool mousemove 73 12 click 1; sleep 2
  xwd -root -silent | convert xwd:- /tmp/menu.png
  local box
  box=$(tesseract /tmp/menu.png - --psm 11 tsv 2>/dev/null | awk -F'\t' '$12 ~ /HTTPS|Requirements/ {print $7, $8, $9, $10; exit}')
  if [ -z "$box" ]; then
    # Where it sits in SDK 9.2's menu (seen in an earlier screenshot).
    echo "OCR didn't find the item; using its known position"
    box="68 604 215 16"
  fi
  set -- $box
  local y=$(( $2 + $4 / 2 ))
  # Ticked? The checkbox sits about 20 px left of the menu's text column (x=68).
  local mean
  mean=$(convert /tmp/menu.png -crop 12x12+47+$(( y - 6 )) -colorspace Gray -format "%[fx:mean]" info:)
  echo "HTTPS setting at y=$y, checkbox brightness $mean"
  if awk "BEGIN{exit !($mean < 0.8)}"; then
    xdotool mousemove $(( $1 + $3 / 2 )) $y click 1
    echo "unticked Use Device HTTPS Requirements"
  else
    xdotool key Escape
    echo "already unticked"
  fi
  sleep 1
  # what the menu looks like now, for the record
  xdotool mousemove 73 12 click 1; sleep 2
  xwd -root -silent | convert xwd:- -crop 600x1000+0+0 +repage "$OUT/sim-settings-menu.png"
  xdotool key Escape; sleep 1
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
shot() { xwd -root -silent | convert xwd:- -crop 400x520+0+26 +repage "$1" || fail "screenshot $1"; }
start_sim
disable_device_https
for d in $DEVICES; do
  echo "--- screenshot on $d"
  monkeydo "/tmp/debug-$d.prg" "$d" > "/tmp/run-$d.txt" 2>&1 &
  RUN=$!
  sleep 25
  shot "$OUT/screen-$d-start.png"
  # past the next minute boundary, when a watch face redraws in low-power mode
  sleep $(( 65 - $(date +%S | sed 's/^0//') ))
  shot "$OUT/screen-$d.png"
  kill $RUN 2>/dev/null
  echo "simulator output:"; cat "/tmp/run-$d.txt"
done
kill $FAKE 2>/dev/null
echo "--- fake endpoint log"; cat /tmp/fake.log
grep -q "served /pebble" /tmp/fake.log || echo "::warning::the simulator never fetched /pebble"

ls -la "$OUT"
exit $FAILED
