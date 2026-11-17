# Stingray Detector v1.0 — art.phoenix.stingray

Passive, defensive IMSI-catcher / fake cell tower detector. **Local only.**

## What it does NOT do (by design)
- No transmitting, no intercepting, no jamming, no spoofing, no acting as an IMSI catcher.
- No IMSI capture, no call/SMS interception, no forcing the phone onto a tower.
- No INTERNET permission — core detection is local-only.
- No alert is proof of a stingray. The UI says this everywhere.

## How it works
Reads the phone's OWN serving cell via TelephonyManager.allCellInfo every 5 s
while monitoring (app open only, no background service in v1.0), plus GPS.
Anomaly rules (Logic.kt, Detector.analyze): 2G fallback from 4G/5G (High if
strong signal), operator MCC/MNC mismatch vs SIM (High), same CID seen >5 km
away (High), CID change while stationary <50 m (Medium), LAC/TAC change
without moving (Medium), signal jump ≥20 dB with no neighbours (Medium/High),
neighbour list suddenly empty (Medium), new CID vs learned baseline while
stationary (Medium; Low while moving).

## Loud alert (Medium/High)
Big red banner on the Status screen (tap it to open the alert), vibrates
(500 ms × 3 pattern), and posts a persistent (ongoing) notification with
risk level + reason. The Alerts screen shows the full alert + canary ID +
"What to do now": airplane mode on/off 10 s, turn off 2G if the phone allows
it, move away / Wi-Fi calling, encrypted apps only. Buttons open the relevant
system Settings screen — the app never changes a setting by itself.

## Canary ID per alert
Every Medium/High alert gets a unique local ID:
`STINGRAY-<UUID>-<epochMillis>-<8-hex cell fingerprint>`. Stored in the
alerts table, shown on the alert screen and in the Alerts list, included
in the alerts CSV export (`canary_id` column). It is a local tracking ID
only — NOT a canarytokens.org URL, never posted anywhere. No
canarytokens.org token is created by the app; that would be an optional,
opt-in step only.

## Map logging
Every alert is a pin on an offline map (Map tab): red = High, orange =
Medium, yellow = Low. Tap a pin for time, MCC/MNC/LAC/CID, radio, dBm,
GPS, risk, reason(s), and canary ID. Map uses the newtool TileMapView
approach, offline-only: 132 bundled Honolulu OSM tiles (assets/tiles,
z12–15, 2.9 MB) copied to the tile cache on first launch. No live tile
downloads, no internet. Areas with no bundled tile draw a grid + pins.

## Screens
- Status: big SAFE / WATCH / ALERT, current cell, red alert banner, Start/Stop, Export CSV.
- Alerts: alert list + latest alert detail + What to do now + settings buttons + Dismiss banner.
- Map: offline map, alert pins (tap for details), zoom +/−, "◎ Latest alert".
- History: latest 50 cell sightings.
- Learn: "Learn my area" baseline (known-good towers at home/work).
- Test: diagnostics — permissions, SIM operator, GPS, last scan, CellInfo count, vibrator, map pins, bundled tiles.

## On-phone test
1. Install, grant Phone + Location permissions.
2. Test tab: expect ✓ TelephonyManager, ✓ SIM operator (e.g. 310/260),
   ✓ GPS on, "Cell scan done" within 5 s of Start, "Cells in last scan: 1+".
   If "Cells in last scan: 0", the phone/carrier hides CellInfo — detection
   cannot work on that device (a phone limit, not an app bug).
3. Status > Start monitoring. Walk/drive: normal handovers while moving
   should stay SAFE. To see a loud alert, the honest test is a real
   anomaly — do not fake one by forcing 2G unless you want to check the
   banner/vibrate/notification path (that will fire a 2G-fallback alert).
4. Map tab: pins appear after any alert; tap a pin, check the canary ID
   matches the Alerts list. Export CSV from Status and check the
   `canary_id` column in stingray-alerts-*.csv in Downloads.

## Build / tests
- Manual pipeline (no Gradle): ~/workspace/stingray-detector/build.sh
  (kotlinc + aapt2 + d8 + zipalign + apksigner), same as newtool.
- Tests: **88/88 pass** (47 original anomaly/false-positive tests + 41 new:
  canary format/uniqueness/timestamp, What-to-do steps, map pins,
  TileMath, CSV with canary_id).
- APK: ~/workspace/your_files/stingray-detector.apk, 3.3 MB,
  sha256 5b700e9d2a353c1f180e73deae682ae84280998355a681dcd1648eb810260f6f,
  apksigner v2+v3 verified (v1 scheme reported false by the verifier,
  same as the first build — v2+v3 is what Android 7+ uses).
- Package art.phoenix.stingray, v1.0 (code 1), min SDK 26, target 34.
- Source: ~/workspace/stingray-detector/

## Honest limits
- Android CellInfo is limited / may be empty on some phones/carriers.
- Detection is heuristic, not proof — real handovers and coverage gaps
  can trigger alerts.
- Live phone behaviour is unverified — no phone test has been run yet.
- Map tiles cover Honolulu only (z12–15); elsewhere the map is a grid + pins.
- Monitoring runs only while the app is open.
