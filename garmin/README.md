# EverBridge watch face (Garmin Instinct E)

A Connect IQ watch face for the Instinct E (designed for 40 mm, also built for 45 mm), glucose first:

* **top:** your glucose, with the change from the reading before (`+4`) and an age tag (`2 MIN`) stacked to its right. The tag is outlined while the reading is fresh and turns solid after 6 minutes. The glucose number itself is never changed or crossed out, however old it is.
* **subscreen:** heart rate (`68 BPM`)
* **middle, between two lines, largest:** the time (`10:47`) and the date (`21 Nov`)
* **bottom, as low as the round screen allows:** insulin on board from Meanwhile (`IOB 2.4U`) and today's steps (`8,412`)

![40 mm, in the simulator](https://github.com/DJRXMUSIC/Meanwhile/releases/download/watchface-latest/screen-instincte40mm.png)

```
Eversense app ─▶ Meanwhile v6 on the phone ─push, each reading─▶ Garmin Connect ─Bluetooth─▶ watch face
                                            ◀──backup fetch every 5 min (127.0.0.1:17590/pebble)──
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

* **Meanwhile v6** (`android/`) must be installed and running; the face reads only its `/pebble` endpoint on port 17590. To check it, open `http://127.0.0.1:17590/pebble` in Chrome on the phone: it should show your latest reading.
* **xDrip+** can stay installed with its web service on. It uses port 17580, and nothing here reads it.
* **Garmin Connect:** the face's fetch goes through it, so keep it running and exempt it from battery optimisation (Android Settings → Apps → Garmin Connect → Battery → Unrestricted).

The first reading shows up within about 5 minutes of choosing the face.

## How fresh is the number

**Pushed (normal):** the moment Meanwhile v6 stores a reading, it sends it to the watch face through Garmin Connect (Connect IQ Mobile SDK). The watch face's background wakes on that message, and the face redraws the reading area within about a second (`onPartialUpdate`), instead of waiting for its once-a-minute refresh. A reading normally reaches the wrist a few seconds after it reaches the phone. On average the number you see is about 2.5 minutes old: Eversense only sends a reading every 5 minutes, so that is the floor.

Meanwhile v6 sends the newest reading only, and resends it when a send fails, when the watch reconnects, and on its once-a-minute check, until the watch has it.

**Backup (if a push is lost):** the face also fetches `/pebble` itself, at most every 5 minutes (Connect IQ's limit for a watch face). Each fetch is timed for just after the next reading is due, based on the last one (`source/Sched.mc`). If the timer drifts more than a minute late, it skips one reading to get back in step. If readings keep arriving late, its 15-second wait grows, up to 2 minutes.

## Build and test

CI (`.github/workflows/garmin-watchface.yml`) runs in `ghcr.io/matco/connectiq-tester`, a public image with the Connect IQ SDK, device files and simulator. It:

* builds release, debug and test versions for both sizes (debug builds log each fetch and schedule to the simulator console)
* runs the unit tests (`source/Tests.mc`: reply and pushed-message parsing, change/age/IOB/steps text, scheduler) in the simulator
* runs the face in the simulator against `tools/fake_pebble.py` (a fake phone endpoint) and screenshots it. The simulator applies the watch's HTTPS rule to every request, while on the phone Garmin Connect allows plain http to 127.0.0.1, so CI first turns off the simulator's **Settings → Use Device HTTPS Requirements**
* checks the layout fits: the debug build measures every element with the widest text it can show (`388`, `-12`, `99+ MIN`, `12.5U`, `18,412`, `23:58`) on the device's own fonts, and fails on anything that leaves the round screen or runs into the subscreen. The screenshot is also printed into the log as text.
* if all of that passes, replaces the **watchface-latest** release (from `main`) or **watchface-preview** (from any other branch)

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
* If readings stop, the age tag keeps counting (solid after 6 minutes); there is no other stale warning.
* Pushing needs Meanwhile v6 with Garmin Connect installed on the same phone. Without a push, the backup fetch still updates the face every 5 minutes.
