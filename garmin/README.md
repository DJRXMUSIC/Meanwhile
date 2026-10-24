# EverBridge watch face (Garmin Instinct E)

A Connect IQ watch face for the Instinct E (40 mm and 45 mm), laid out like the watch's own "Instinct" face:

* **top-left panel** (where that face says INSTINCT): your glucose, and under it the trend arrow and the number of minutes since the reading was taken
* **subscreen:** today's steps
* **centre:** the time, hours solid and minutes striped
* **right panel:** heart rate
* **bottom:** the date (`24 OCT`)

![40 mm, in the simulator](https://github.com/DJRXMUSIC/Meanwhile/releases/download/watchface-latest/screen-instincte40mm.png)

```
Eversense app ─▶ EverBridge (127.0.0.1:17590/pebble on the phone)
             ─▶ Garmin Connect app ─Bluetooth─▶ watch face background fetch ─▶ screen
```

## Install

1. Download the file for your watch from the **watchface-latest** release:
   * 40 mm: https://github.com/DJRXMUSIC/Meanwhile/releases/download/watchface-latest/everbridge-instincte40mm.prg
   * 45 mm: https://github.com/DJRXMUSIC/Meanwhile/releases/download/watchface-latest/everbridge-instincte45mm.prg
2. Connect the watch to a computer with its charging cable. It shows up as a drive (on a Mac, use Garmin Express or Android File Transfer).
3. Copy the `.prg` into the watch's `GARMIN/APPS` folder, then unplug it.
4. On the watch, hold **MENU** → **Watch Face**, and pick **EverBridge**.

To update, copy the new `.prg` over the old one.

## Phone setup

* **EverBridge** (`android/`) must be installed and running; the face reads only its `/pebble` endpoint on port 17590. To check it, open `http://127.0.0.1:17590/pebble` in Chrome on the phone: it should show your latest reading.
* **xDrip+** can stay installed with its web service on. It uses port 17580, and nothing here reads it.
* **Garmin Connect:** the face's fetch goes through it, so keep it running and exempt it from battery optimisation (Android Settings → Apps → Garmin Connect → Battery → Unrestricted).

The first reading shows up within about 5 minutes of choosing the face.

## How fresh is the number

Eversense sends a reading every 5 minutes, and Connect IQ lets a watch face fetch at most once every 5 minutes. A plain 5-minute timer would show each reading 0–5 minutes after it arrived, 2.5 minutes on average.

Instead each fetch is a one-shot timer set for 15 seconds after the next reading is due, worked out from the time of the last one (`source/Sched.mc`). The timer can only drift later, never earlier, so when it's more than a minute behind it skips one reading to get back in step. If readings arrive later than expected, the 15 seconds grows (up to 2 minutes) and then shrinks back.

Simulated over two days of readings, a reading typically reaches the watch about a minute after it reaches the phone. The number on the watch averages 3.3–3.6 minutes old, against 5.1 with a plain timer (2.5 is the floor with 5-minute readings). How punctual the watch's timer really is decides where in that range you land.

The minutes number counts from when the sensor reading was taken, measured on the phone's clock, so it is right even if the watch and phone clocks differ.

## Build and test

CI (`.github/workflows/garmin-watchface.yml`) runs in `ghcr.io/matco/connectiq-tester`, a public image with the Connect IQ SDK, device files and simulator. It:

* builds release, debug and test versions for both sizes (debug builds log each fetch and schedule to the simulator console)
* runs the unit tests (`source/Tests.mc`: reply parsing, minutes, scheduler) in the simulator
* runs the face in the simulator against `tools/fake_pebble.py` (a fake phone endpoint) and screenshots it. The simulator applies the watch's HTTPS rule to every request, while on the phone Garmin Connect allows plain http to 127.0.0.1, so CI first turns off the simulator's **Settings → Use Device HTTPS Requirements**
* if all of that passes, replaces the **watchface-latest** release

Locally, with the Connect IQ SDK installed:

```bash
cd garmin
monkeyc -f monkey.jungle -d instincte40mm -y <developer_key.der> -o bin/everbridge.prg
python3 tools/fake_pebble.py 123 Flat 2 &   # pretend bridge on :17590
monkeydo bin/everbridge.prg instincte40mm      # untick Settings > Use Device HTTPS Requirements first
```

Builds are signed with a throwaway key unless the repository secret `GARMIN_DEVELOPER_KEY_B64` (base64 of a `.der` developer key) is set. Any key works for sideloading.

## Limits

* No alarms: watch faces can't vibrate or alert. Keep the Eversense app's alerts on; they reach the watch as normal phone notifications.
* The phone must be in Bluetooth range with Garmin Connect running.
* The minutes number keeps counting if readings stop; there is no separate stale warning.
