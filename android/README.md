# Eversense Bridge (Android)

A small Android app for the Pixel 9a. It captures each Eversense 365 glucose reading (value and timestamp) from the Eversense app as the reading arrives. It stores the readings and serves them at `http://127.0.0.1:17580/sgv.json`, the same endpoint and JSON format as xDrip+'s local web service. Meanwhile reads it with no changes; you don't need to install xDrip+.

```
Eversense 365 transmitter ──BLE──▶ Eversense app ──posts/updates its glucose notification──▶
  Eversense Bridge (NotificationListenerService) ──▶ SQLite ──▶ 127.0.0.1:17580/sgv.json ──▶ Meanwhile
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
| `webservices/WebServiceSgv`, `XdripWebService` | `core/SgvJson`, `core/HttpServer`, `core/BridgeRoutes` | Port 17580, loopback-only by default, `count` 1–1000 (default 24), xDrip-style `api-secret` for LAN clients, `Access-Control-Allow-Origin: *` |
| `UiBasedCollectorTest` | `core/…/NotificationParserTest`, `ReadingGateTest` | Ported test-for-test |

**Left out:** everything else, including every other collector (Dexcom, Libre, Medtronic, …), calibration, alarms, Nightscout upload, graphs, watch integration, and xDrip's ActiveAndroid database, `Pref`/`PersistentStore` and dagger plumbing. Because of those transitive dependencies, `UiBasedCollector` can't simply be copied out of xDrip. The bridge is about 2,600 lines of Java and has no third-party runtime dependencies.

**Changes from xDrip+, each covered by tests:**

* **Timestamps.** xDrip stamps a reading with the time its listener ran. The bridge uses the Eversense app's own `Notification.when` (the reading time), but only while it behaves like one: it must sit near the post time and change with each new value. Otherwise it uses Android's post time for that notification update. Each stored reading records which source was used (`ts_source`) along with all three raw times.
* **Reposts.** If the Eversense app refreshes the notification without a new reading, the update is recognised and not stored again.
* **Units.** mg/dL vs mmol/L is auto-detected (integer vs decimal); you can also force it in settings.
* **Styled titles.** Titles that are styled `CharSequence`s are read correctly; xDrip's `getString()` misses them.
* **Duplicated values.** A value shown twice in the same view isn't treated as ambiguous.
* **Trend gaps.** No trend is reported across a gap longer than 20 minutes.

## Reliability features

* The listener is bound by Android itself, which restarts the process if it dies. If the binding drops, the app calls `requestRebind`, and a once-a-minute watchdog checks it again.
* A foreground service (`specialUse` type, so no Android 15 time limit) keeps the process at high priority. It also restarts the endpoint if needed, and shows the latest reading and endpoint state in its notification.
* On reconnect after a crash, update or reboot, the app re-reads the active Eversense notification. Readings already stored are not duplicated.
* Readings are written to SQLite (WAL) and dedupe state is committed synchronously, so nothing is lost or duplicated across restarts. History is kept for 120 days.
* After reboot and after app updates, `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` restart everything.
* A **missing readings** alert fires if nothing arrives for 20 minutes (configurable). It tells you whether notification access, the listener or the Eversense app is the problem.
* `GET /status.json` reports health (`ok`, listener state, last reading and its age, last capture outcome). The app's screen shows a setup checklist and a capture log, and **Copy diagnostics** puts everything, including raw notification texts, on the clipboard.

## Install on the Pixel 9a

1. Download the `eversense-bridge-apk` artifact from the latest successful **Eversense Bridge (Android)** run in this repo's GitHub Actions. Unzip it and open `app-release.apk` on the phone. Do **not** install the `fake-eversense` APK: it is a test double that uses the Eversense package name.
2. Open **Eversense Bridge** and work through the checklist:
   * **Grant notification access** → enable Eversense Bridge. If the switch is greyed out (Android 13+ blocks this for sideloaded apps), go to Settings → Apps → Eversense Bridge → ⋮ → **Allow restricted settings**, then try again.
   * **Set battery to unrestricted** → Allow.
   * **Allow notifications.**
3. Open the Eversense app once. The next reading appears at the top of the bridge's screen, with its time to the millisecond.
4. In Meanwhile → Settings, set the xDrip+ URL to `http://127.0.0.1:17580` and tap **Test connection**.

If xDrip+ is also installed with its local web service on, it already holds port 17580. Turn its web service off, or pick another port in the bridge's settings.

## Signing (so updates install over each other)

The repo is public, so no signing key is committed. Create one once and store it as two repository secrets (Settings → Secrets and variables → Actions):

```bash
keytool -genkeypair -keystore bridge.jks -alias sideload -keyalg RSA -keysize 2048 -validity 36500
base64 -w0 bridge.jks   # paste the output as BRIDGE_KEYSTORE_B64
#                         and the password you chose as BRIDGE_KEYSTORE_PASSWORD
```

Keep `bridge.jks` somewhere safe. Without the secrets, CI still builds a working APK, but it is signed with a throwaway key: a later build won't install over it without uninstalling first, which deletes the stored history. Meanwhile keeps its own copy of the history.

## Build and test

```bash
cd android
./gradlew -PcoreOnly :core:test          # pure-Java logic; no Android SDK needed
./gradlew :core:test :app:testDebugUnitTest :app:lintRelease :app:assembleRelease :fake-eversense:assembleDebug
```

CI (`.github/workflows/android-bridge.yml`) runs:

* **build-test:** the 69 core tests, the 25 Robolectric tests (listener with real `RemoteViews` inflation, SQLite store, foreground service and stale alert, boot receiver, settings, UI, and notification → HTTP JSON over a real socket), Android lint, and the release APK build.
* **e2e:** boots an Android 15 emulator, installs the bridge and a fake Eversense app (same package name as Eversense 365), and runs `e2e/run-e2e.sh`. That script checks the exact `when` timestamp, repost dedupe, trend, mmol title-only parsing, `LO` rejection, the CORS/Private Network Access preflight, recovery after `kill -9`, and recovery after a full reboot.

## Known limits

* The real Eversense 365 notification layout couldn't be inspected here. The parser uses xDrip's generic rules, which xDrip+ applies to this package today. If a reading is ever missed, **Copy diagnostics** shows the exact texts the app saw.
* The timestamp is the Eversense app's reading time (or its notification post time, about a second after the Bluetooth packet arrives). It is not the transmitter's internal measurement clock; the notification doesn't expose that.
* Readings the Eversense app backfills without showing them in its notification are not captured.
* Not a medical device. Keep the Eversense app's own alerts on.
