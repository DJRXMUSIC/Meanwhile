# Meanwhile v5 (Android)

One Android app for the Pixel 9a: **Meanwhile**, with the Eversense bridge built in. The app icon opens Meanwhile; its pages are packaged inside the APK (built from this repository's web app), so it opens instantly and works with no internet. Underneath, the bridge runs whether Meanwhile is open or not.

The bridge captures each Eversense 365 glucose reading (value and timestamp) from the Eversense app as the reading arrives. It stores the readings and serves them at `http://127.0.0.1:17590/sgv.json`, in the same JSON format as xDrip+'s local web service. It is the only glucose source for Meanwhile and its watch face. xDrip+ can stay installed with its own web service on 17580; the bridge neither needs nor reads it.

```
Eversense 365 transmitter ──BLE──▶ Eversense app ──posts/updates its glucose notification──▶
  Meanwhile v5 (NotificationListenerService) ──▶ SQLite ──▶ 127.0.0.1:17590 (/sgv.json, /pebble) ──▶ Meanwhile, Garmin watch face
```

## Why it reads the notification instead of Bluetooth

* Android gives no app access to another app's Bluetooth traffic. HCI snoop logs are a developer-options debugging dump. They are not an API and are not real time.
* The transmitter talks to one bonded app. A second app connecting directly would compete with the Eversense app's link and break it.
* xDrip+ has no Eversense 365 Bluetooth driver to borrow. Today its only Eversense support is **Companion App mode** (`services/UiBasedCollector`), which reads the Eversense app's glucose notification. The older ESEL route (the `NSEmulator` collector) needs a modified Eversense E3 app and does not apply to 365.

The Eversense app updates its notification as soon as each reading arrives over Bluetooth. Reading that notification is the same, field-proven path xDrip+ uses.

## What was taken from xDrip+, and what was left out

Source: `NightscoutFoundation/xDrip` master at commit `eebfd19` (2 Oct 2026).

| xDrip+ code | Kept as | Notes |
| --- | --- | --- |
| `services/UiBasedCollector` – package list (`com.senseonics.eversense365.us`, `…gen12androidapp`, `…androidapp`), `processRemote`/`getTextViews`, `filterString`, `arrowFilterString`, `basicFilterString`, `filterUnicodeRange`, `isValidMmol`, `tryExtractString` | `core/NotificationParser`, `app/NotificationTexts` | Same filters and the "exactly one number" rule |
| `UiBasedCollector.handleNewValue`, `isDifferentToLast`, `isJammed`, `jamThreshold`; `DexCollectionType.getCurrentDeduplicationPeriod` | `core/ReadingGate` | 40–405 mg/dL, 10 s / 250 s dedupe, jam after 6 repeats |
| `BgReading.calculateSlope`, `Dex_Constants.TREND_ARROW_VALUES` thresholds | `core/Trend` | Same direction names and thresholds |
| `webservices/WebServiceSgv`, `XdripWebService` | `core/SgvJson`, `core/HttpServer`, `core/BridgeRoutes` | Port 17590 (xDrip+ keeps 17580), loopback-only by default, `count` 1–1000 (default 24), xDrip-style `api-secret` for LAN clients, `Access-Control-Allow-Origin: *` |
| `UiBasedCollectorTest` | `core/…/NotificationParserTest`, `ReadingGateTest` | Ported test-for-test |
| `models/AlertType` (`get_highest_active_alert`, `should_alarm`, `s_in_time_frame`, `HigherAlert`, `toSettings`/`fromSettings`), `ActiveBgAlert`, `Notifications.FileBasedNotifications`, `AlertPlayer` (`startAlert`, `ClockTick`, `Snooze`, `VibrateNotifyMakeNoise`, `playFile`, volume adjust and revert), `BgReading.trendingToAlertEnd` | `core/AlertEngine`, `AlertRule`, `AlertSettings`, `ActiveAlert`, `XdripAlertImport`; `app/Alerts`, `AlertSound` | Low and high glucose alerts only, step for step (commit `10739aa`) |

**Left out:** everything else, including every other collector (Dexcom, Libre, Medtronic, …), calibration, alerts other than low/high glucose (rising/falling, persistent high, predicted low, missed readings), Nightscout upload, graphs, watch integration, and xDrip's ActiveAndroid database, `Pref`/`PersistentStore` and dagger plumbing. Because of those transitive dependencies, `UiBasedCollector` can't simply be copied out of xDrip. The app (bridge, alerts and the Meanwhile shell) is about 7,000 lines of Java and has no third-party runtime dependencies.

**Changes from xDrip+, each covered by tests:**

* **Timestamps.** xDrip stamps a reading with the time its listener ran. The bridge uses the Eversense app's own `Notification.when` (the reading time), but only while it behaves like one: it must sit near the post time and change with each new value. Otherwise it uses Android's post time for that notification update. Each stored reading records which source was used (`ts_source`) along with all three raw times.
* **Reposts.** If the Eversense app refreshes the notification without a new reading, the update is recognised and not stored again.
* **Units.** mg/dL vs mmol/L is auto-detected (integer vs decimal); you can also force it in settings.
* **Styled titles.** Titles that are styled `CharSequence`s are read correctly; xDrip's `getString()` misses them.
* **Duplicated values.** A value shown twice in the same view isn't treated as ambiguous.
* **Delta.** `delta` (and `/pebble`'s `bgdelta`) is the plain change since the previous reading in mg/dL, e.g. `6`. xDrip+ scales the change to a 5-minute rate, so a 10 mg/dL rise over 5:06 shows as 9.8. The arrow is that same change divided by the real time between the two readings, against xDrip+'s thresholds.
* **Trend gaps.** No trend or delta is reported across a gap longer than 20 minutes.

## Reliability features

* The listener is bound by Android itself, which restarts the process if it dies. If the binding drops, the app calls `requestRebind`, and a once-a-minute watchdog checks it again.
* A foreground service (`specialUse` type, so no Android 15 time limit) keeps the process at high priority. It also restarts the endpoint if needed, and shows the latest reading and endpoint state in its notification.
* On reconnect after a crash, update or reboot, the app re-reads the active Eversense notification. Readings already stored are not duplicated.
* Readings are written to SQLite (WAL) and dedupe state is committed synchronously, so nothing is lost or duplicated across restarts. History is kept for 120 days.
* After reboot and after app updates, `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` restart everything.
* A **missing readings** alert fires if nothing arrives for 20 minutes (configurable). It tells you whether notification access, the listener or the Eversense app is the problem.
* `GET /pebble` returns the newest reading as one JSON object (the Nightscout `/pebble` shape). The Garmin watch face (`garmin/`) reads it, because Connect IQ can't read `sgv.json`'s top-level array.
* `GET /status.json` reports health (`ok`, listener state, last reading and its age, last capture outcome). The app's screen shows a setup checklist and a capture log, and **Copy diagnostics** puts everything, including raw notification texts, on the clipboard.

## Meanwhile inside the app

* **Pages:** built by `npm run build:app` (a static export of the web app) into `app/src/main/assets/web`, which the Gradle build does by itself, and served from `https://appassets.androidplatform.net/`, an address Android reserves for exactly this. Links anywhere else open in the browser.
* **Readings:** the page reads the bridge's store directly (`window.Meanwhile v5Native.sgv`), with the HTTP endpoint as a fallback. New readings show within seconds.
* **Data:** Meanwhile's database lives in the app's own storage, separate from Chrome's. To move it, see *Moving from the Chrome version* below.
* **AI advice and cloud backup** go to your Meanwhile server (the website version, e.g. on Netlify), whose address you enter once in Meanwhile → Settings → **Meanwhile server**. Everything else works without it. The server's `/api/sync` and `/api/ai/decide` accept requests from the app's address and no other site.
* **Backup file:** Settings → **Download backup** saves `Downloads/Meanwhile/meanwhile-<date>.json`; **Restore from file** opens the phone's file picker.
* **Voice:** the mic button uses the phone's voice input.
* **The bridge's own screen** (status, self-test, log file, settings): Meanwhile → Settings → **Meanwhile v5 status & setup**. It also opens by itself until notification access is on.
* **Safety:** if the page's renderer crashes, the app replaces it instead of going down, so capture never stops because of the page.

## Install on the Pixel 9a

**Download link (always the newest build that passed every test):**
https://github.com/DJRXMUSIC/Meanwhile/releases/download/bridge-latest/meanwhile-v5.apk

1. Open that link in Chrome on the phone and open the downloaded file. If asked, allow Chrome to install apps. Updates install the same way, on top of the old version, and keep your history.
2. Open **Meanwhile v5**. Meanwhile opens, and on first run the bridge's setup screen opens over it. It asks to show notifications (allow it), then a yellow **Setup** box walks you through what's left, one button at a time:
   * **Turn on: Notification access** → enable Meanwhile v5. If the switch is greyed out (Android 13+ does this for apps installed from a browser), go to Settings → Apps → Meanwhile v5 → ⋮ → **Allow restricted settings**, then try again.
   * **Fix: Battery** → Allow.
3. When every dot under **Status** is green, open the Eversense app once. The next reading appears in the **Last reading** card with its exact time.
4. Tap **Run self-test**: it should say PASS. Press Back to get to Meanwhile.
5. In Meanwhile → Settings → **Meanwhile server**, enter the address you used to open Meanwhile in Chrome (e.g. `https://your-site.netlify.app`) and tap **Save**. That turns on AI advice and cloud backup.

### Moving from the Chrome version

Your history in Chrome stays in Chrome until you bring it over:

1. In Chrome, open Meanwhile → Settings → **Download backup**. Keep this file somewhere safe.
2. In the app, Meanwhile → Settings → **Restore from file**, and pick that file. Readings the app has already collected are kept, and nothing is counted twice.
3. Your cloud sync key comes across with the backup, so cloud backup carries on where it was once the server address is set.

The bridge listens on 17590 so xDrip+ can keep 17580. If you saved settings in an older version (when the default was 17580), the port moves to 17590 by itself once; you can still choose any port under **Advanced**.

## Glucose alerts (from xDrip+)

Meanwhile v5 has xDrip+'s low and high glucose alerts built in, and starts with the owner's xDrip+ setup, nothing to import:

| Alert | When | Repeats | Snooze | Sound | Vibrate |
| --- | --- | --- | --- | --- | --- |
| LOW WARNING | at or below 90 mg/dL, all day | every 1 min | 60 min | Beep (built in) | off |
| Elevated Alert | at or above 155 mg/dL, all day | every 3 min | 90 min | Vibration sound (built in) | on |

Both are **critical**: they sound on silent and through Do Not Disturb, on the alarm volume. Volume profile High, smart snoozing and smart alerting off, no alarms during phone calls. **Alert volume 50%** (see below).

* **Find them:** Meanwhile → **Settings** → **Open glucose alerts** (the first item), or press and hold the app icon → **Glucose alerts**, or the bridge screen's **Glucose alerts** button under the last reading.
* **Back:** the **← Meanwhile** button at the top (or the phone's back gesture) always returns to Meanwhile, even when the screen was opened from the app icon's shortcut or an alert.
* **The screen is laid out like xDrip+'s**, with its names:
  * **Snooze:** the alert going on, **Snooze** for a chosen time, **Disable / Re-Enable Low, High, All alerts**.
  * **Glucose Level Alerts List:** **Create low alert**, **Create high alert**, the low and high alerts. Tap one for xDrip+'s Edit Alert fields: Alert Name, Threshold, Default Snooze, Re-raise every x minutes, Alert Tone (Phone sounds: Android's own ringtones, notification and alarm sounds such as Kernel; Beep or Vibration sound built in; any sound file of your own; or the phone's alarm sound), Select time for alert (all day or a window), Override phone Silent Mode, Force Speaker, Vibrate on alert, Disable alert, then **Test alert**, **Snooze alert before it fires**, **Remove alert**, **Save alert**.
  * **Glucose Alerts Settings:** Alert Volume Profile (High, Medium, Ascending, Vibrate only, Silent), Smart Snoozing, Smart Alerting, Don't alarm during phone calls, Start Snoozed, Suppress Alerts if missed readings, Ascending Volume settings (Delay Ascending Volume, Ascend to medium).
* **Alert volume (not in xDrip+):** any level from 1% to 100%: a slider, **−5% −1% +1% +5%** buttons, quick picks (10%, 25%, 50%, 75%, 100%) and **Test volume**. xDrip+ always plays at 100% of the alarm volume; here High plays at this level, Medium at 70% of it, and Ascending climbs to it. The stream is set to the step at or just above the level and the sound itself is turned down the rest of the way, so even 1% steps change the sound even though the phone's alarm volume has only a few steps.
* **Do Not Disturb:** the alarm volume gets through Do Not Disturb except its "total silence" setting. To get through that too, tap **Allow through Do Not Disturb** on the alerts screen; then a critical alert switches total silence to alarms only.
* **Turn the alerts off in xDrip+**, or both apps will alarm.
* **Optional import:** to bring over another xDrip+ setup, in xDrip+ use menu → **Import / Export Features** → **Save settings**, then **Import xDrip+ settings file** on the alerts screen and pick `com.eveningoutpost.dexdrip_preferences.xml` (a zip with it inside works too). If an imported sound can't be opened (xDrip+'s permission to it doesn't carry over), tap **Sound** and pick it again.

What an import brings: every low and high alert (threshold, on/off, time of day, repeat interval, snooze length, vibrate, sounds even on silent, alarm vs media volume, sound file) and the options for all alerts (volume profile, ascending ramp, smart snoozing and alerting, no alerts on stale data, start snoozed, no alarms during calls). xDrip+'s "alerts off until" times are not taken, so a snooze in xDrip+ doesn't silence Meanwhile v5.

How they behave, as in xDrip+: checked on every reading and again at each repeat (an exact alarm wakes the phone, even in deep sleep). The lowest low that applies wins, else the highest high. The sound plays once per repeat at the profile's volume, and the stream volume is put back afterwards. **Snooze** on the notification, or swiping it away, snoozes for the alert's own snooze time. A lower low (or higher high) breaks through a snooze; going back in range stops the alert. The alerts screen also snoozes for a chosen time, turns all, low or high alerts off for a while, and edits each alert.

## The screen

* **Last reading:** big value and trend arrow. Green means the reading is under 6 minutes old, amber under 15, red older. It also shows how long ago the reading was, its exact time to the millisecond, the rate per 5 minutes, and where the time came from.
* **Glucose alerts:** the alerts screen (see above).
* **Status:** Eversense app, notification access, listener, battery, app notifications, keep-alive service, data endpoint, and last Eversense update. Anything red has a button that fixes it.
* **Run self-test:** checks the endpoint answers, a test notification reaches the listener and parses, storage works, and the keep-alive service is running. It doesn't need the Eversense app and stores nothing.
* **Save log file / Share log** (see below).
* **Recent readings**, plus **Advanced** (collapsed): capture log, settings, open `sgv.json`, and a link to the latest version.

## When something isn't working: the log file

Tap **Save log file**. It writes `Downloads/Meanwhile/meanwhile-log-<date>-<time>.txt`, or use **Share log** to send it anywhere. Upload that file to Claude and ask what's wrong. It contains:

* **App and device:** app version, device, Android version, and process uptime.
* **Health:** notification access and enabled listeners, listener state, keep-alive service, battery optimisation, power-save/doze, app standby bucket, notification permission, and endpoint state.
* **Settings**, with the API secret masked.
* **Eversense app** version.
* **Raw dump of the Eversense notification currently showing:** every extra, timestamps, flags, the texts read from its view, and what the parser makes of them. This is what's needed if Eversense changes its notification. It also lists every package with a notification, to catch a renamed Eversense package.
* **Data:** the last 60 readings with all raw times, the capture log, and `status.json`.
* **Persistent event log** (about 2 MB, a few days, survives restarts): every capture and its raw texts, listener connects and disconnects, service starts and stops, endpoint restarts, watchdog actions, missing-reading alerts, boots, settings changes, self-tests, and crashes with stack traces.
* **This app's logcat.**

## Signing (no setup needed)

The APK is signed with a key committed at `signing/bridge-sideload.p12`, protected by a simple passcode in `app/build.gradle`. That is what lets every new build install over the previous one with no setup. It is only "quasi secure": because the repo is public, someone could build an APK signed with the same key. Android would only accept it as an update if you chose to install it yourself, and you only install from the link above.

To switch to a private key later, add repository secrets `BRIDGE_KEYSTORE_B64` (base64 of a `.jks`/`.p12`) and `BRIDGE_KEYSTORE_PASSWORD`. You'd need to uninstall once, because the key changes.

## Build and test

```bash
cd android
./gradlew -PcoreOnly :core:test          # pure-Java logic; no Android SDK needed
./gradlew :core:test :app:testDebugUnitTest :app:lintRelease :app:assembleRelease :fake-eversense:assembleDebug
```

CI (`.github/workflows/android-bridge.yml`) runs:

* **build-test:** the core tests (including the alert engine, ported case by case, and the xDrip+ import), the Robolectric tests (glucose alerts: notification, snooze, swipe, wake-up timer, alerts screen; listener with real `RemoteViews` inflation, SQLite store, foreground service and stale alert, boot receiver, settings, screen, event log rotation, diagnostics report, log export, self-test round trip, and notification → HTTP JSON over a real socket), Android lint, and the release APK build.
* **e2e:** boots an Android 15 emulator, installs the bridge and a fake Eversense app (same package name as Eversense 365), and runs `e2e/run-e2e.sh`. That script checks the exact `when` timestamp, repost dedupe, trend, mmol title-only parsing, `LO` rejection, the CORS/Private Network Access preflight, recovery after `kill -9`, and recovery after a full reboot. It then runs the self-test and the log export from the app, pulls the saved log file off the device and checks its contents. It checks the built-in Elevated Alert plays its built-in sound at 155. Last, it imports an xDrip+ settings file and checks that a low reading sounds the alert with its notification, that it repeats a minute later, stops when glucose is back in range, and stays quiet after Snooze.
* **release:** only if both jobs pass, replaces the `bridge-latest` GitHub release, so the download link above always serves a fully tested build.

## Known limits

* The real Eversense 365 notification layout couldn't be inspected here. The parser uses xDrip's generic rules, which xDrip+ applies to this package today. If a reading is ever missed, **Copy diagnostics** shows the exact texts the app saw.
* The timestamp is the Eversense app's reading time (or its notification post time, about a second after the Bluetooth packet arrives). It is not the transmitter's internal measurement clock; the notification doesn't expose that.
* Readings the Eversense app backfills without showing them in its notification are not captured.
* Not a medical device. Keep the Eversense app's own alerts on.
* On Android 14 and later, an alert shows over the lock screen only if the phone allows it; the alerts screen has a button for that if needed. The sound and vibration work either way.
