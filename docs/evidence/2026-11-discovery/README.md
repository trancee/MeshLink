# BLE discovery and advertising: evidence

- **Date:** 2026-11-02, 12:14–19:34 CET. All runs ran on one day in one room.
- **Ticket:** `.scratch/meshlink-v1/issues/13-discovery-advertising.md`
- **Desk research it tests:** [`docs/research/2026-11-02-discovery-advertising.md`](../../research/2026-11-02-discovery-advertising.md), sections 4 and 5 (tests T1–T12)
- **Decisions it feeds:** [ADR 0001](../../adr/0001-threat-model-and-profile-guarantees.md), [ADR 0008](../../adr/0008-routing-topology-and-density-adaptation.md), [ADR 0009](../../adr/0009-ble-bearers-and-phy-policy.md)
- **Code:** throwaway prototype on branch `prototype/discovery`, under `prototypes/bearer-bakeoff/` (app and host scripts) and `prototypes/discovery/README.md` (pre-registration and runbook). It is never merged.

All numbers here were observed on real phones. Simulators were not used. Anything not observed is marked **unverified**.

## Evidence labels

| Label | Meaning |
|---|---|
| **[RUN]** | Measured in the named runs. Raw JSONL (gzipped) is in [`raw/`](raw/). |
| **[LOG]** | Seen in a logcat, syslog, `bluetoothd` or `dumpsys` capture. The captures are not in this folder (see [Diagnostic captures](#diagnostic-captures)). |
| **[UNVERIFIED]** | A likely explanation that no run or log proves. |

## 1. Question

How do nodes discover each other, and what goes in the advertisement?

- **D1.** How often does the BLE address rotate on each platform? Can an app force a new address, and can it keep its own identifiers in step with the address?
- **D2.** Who sees whom when the advertiser or the scanner is in the background or has its screen off? How fast, and how often in a 10 s window?
- **D3.** Which phones receive legacy, extended (1M, 2M) and Coded advertising?
- **D4.** Why did the iPhone 15 close incoming links at 3–4 links in bake-off step 4?
- The desk-research tests T1–T11 and the owner's layout E questions E1–E5 are folded in. [Section 3](#3-method) maps them.

## 2. Setup

### Advertisement under test: layout E

The project owner made layout E primary on 2026-11-02 at 13:45. Every run from `d1-ctl-e-20261102` on uses it.

- Legacy, connectable, scannable advertising on 1M. No local name. No scan response data.
- A fixed 32-bit service UUID `0x4D455348` ("MESH"). Its 128-bit base form is `4D455348-0000-1000-8000-00805F9B34FB`.
- One rotating 128-bit service UUID with 16 B of **placeholder** metadata. The real layout is not decided yet.
  - 8 B random advertisement ID. It stands in for the resolvable tag.
  - 4 B mesh tag, the constant `ML01`.
  - 1 B flags. Android uses step << 4 | cycle; iOS uses the cycle.
  - 3 B random.
  - The app makes a new UUID at every advertising start.
- Byte budget:
  - Android: 3 B flags + (2 + 4) + (2 + 16) = 27 B of 31 B.
  - iOS: (2 + 4) + (2 + 16) = 24 B of the 28 B foreground budget.
- Scanners attribute a sighting by the first 8 B of the rotating UUID (`a:<hex>` in the raw data).
- **Layout A** was used only by `d1-rot-20261102`, before the change. It has one 128-bit mesh UUID in the ADV and a scan response with 13 B of service data (8 B ID, 4 B tag, 1 B flags). Its address findings do not depend on the payload.

### Phones

The phones ran on chargers unless a cell turned the screen off. No phone was paired or bonded.

**iPhones** (all on the lab Mac; free provisioning, bundle `ch.trancee.lab.host`, team B `ch.trancee.lab.host.b`):

| Name | Model | iOS | Host tool | Used in |
|---|---|---|---|---|
| SE 2016 | iPhone8,4 | 15.8.8 | libimobiledevice | D1, D1-ctl-E, D2, T1, T11, D3, D4 |
| iPhone X | iPhone10,6 | 16.7.16 | libimobiledevice | D1, D1-ctl-E, D2, T1, T3, T11, D3, D4 |
| iPhone 15 | iPhone15,4 | 17.6.1 | `devicectl` | D1, D1-ctl-E, D2, T1, T3 (advertiser), T11, D3, D4 hub |
| SE 2020 | iPhone12,8 | 18.7.8 | `devicectl` | D1 control, D1-ctl-E, D2, T1, T3, T11, D3 |
| 12 mini | iPhone13,1 | 27.0.1 | `devicectl` | D1, D1-ctl-E (scanner), D2 iOS cells, T3, T11, T1 and T4 (after 19:01). Locked itself after T3 (see [Deviations](#8-deviations)). |

**Android phones** (remote adb; capabilities from `BluetoothAdapter`, run `caps-20261102`; full table in [`summary/caps-android.csv`](summary/caps-android.csv)):

| Name | Model | Android / API | Ext. adv | Coded | Max adv data (B) | Used in |
|---|---|---|---|---|---|---|
| Pixel 6 | Google Pixel 6 | 17 / 37 | yes | yes | 1650 | D1 (set), D2 adv, T1 passive, T9, D3, D4 |
| Nothing 2 | Nothing A065 | 16 / 36 | yes | yes | 1650 | D1 scanner, D2 scanner, T1 active, D3, D4 |
| Z Flip4 | Samsung SM-F721B | 16 / 36 | yes | yes | 1650 | D1 control (`new_set`), T11 ticker, D3 |
| Nothing 1 | Nothing A063 | 15 / 35 | yes | yes | 1650 | D1 control (`data`), D3 advertiser |
| RMX3710 | realme RMX3710 | 15 / 35 | yes | yes | 192 | D1-ctl-E (`new_set`), D3 |
| Nokia X20 | HMD Nokia X20 | 14 / 34 | yes | yes | 1650 | D1-ctl-E (`new_set`), T11 stable peer, D3 |
| edge 30 | motorola edge 30 fusion | 14 / 34 | yes | yes | 1650 | D1 scanner, D2 screen-off scanner, D3 |
| Nord 2 | OnePlus DN2103 | 13 / 33 | yes | yes | 304 | D1-ctl-E (`new_set`), D3 |
| Pixel 4a | Google Pixel 4a | 13 / 33 | yes | yes | 1650 | D1 control (`stop`), D3 |
| GX6 | Gigaset E940-2849-00 | 13 / 33 | yes | yes | 304 | D3 |
| CPH2251 | OPPO CPH2251 | 13 / 33 | yes | yes | 304 | D3 |
| Redmi 12 | Xiaomi 23053RN02A | 13 / 33 | yes | yes | 192 | D3 (`d3b` only) |
| S10e | Samsung SM-G970U1 | 12 / 31 | yes | yes | 1024 | D1 (legacy API), D2 adv, D3 |
| nova 9 | Huawei NAM-LX9 | 12 / 31 | yes | yes | 1650 | T9 beacon, D3 |
| CPH2135 | OPPO CPH2135 | 12 / 31 | no | no | 31 | D1-ctl-E (`new_set`), D3 |
| OnePlus 6 | OnePlus A6003 | 11 / 30 | yes | yes | 1650 | D1 control (`enable`); D3 invalid |
| RMX3263 | realme RMX3263 | 11 / 30 | yes | yes | 1650 | D3 |
| Mi 8 | Xiaomi MI 8 | 10 / 29 | yes | yes | 1650 | D1 (never seen); D3 invalid |
| POCO F1 | Xiaomi POCOPHONE F1 | 10 / 29 | yes | yes | 1650 | D3 |
| Blackview | Blackview A80 | 10 / 29 | no | no | 31 | D1 control (`stop`, single advertiser); D3 invalid |
| P30 lite | Huawei MAR-LX1A | 10 / 29 | no | no | 31 | D3 |
| Mi Note 3 | Xiaomi Mi Note 3 | 9 / 28 | no | no | 31 | D1 control (`stop`), D3 |
| XCover 4 | Samsung SM-G390F | 9 / 28 | no | no | 31 | D1 (legacy-only), D2 screen-off scanner, D3 |
| P10 lite | Huawei WAS-LX1A | 8.0 / 26 | no | no | 31 | D1 (legacy-only, single advertiser), D3 |

- Excluded: OPPO CPH2385 (unreachable) and Redmi Note 11 (`INSTALL_FAILED_USER_RESTRICTED`), as in the bake-off.
- **Controller classes [RUN]:** extended-capable (18 phones); legacy-only with multiple advertisers (XCover 4, Mi Note 3, CPH2135); legacy-only with one advertiser (P10 lite, P30 lite, Blackview).
- **Pixel 6 OTA:** the Pixel 6 installed a system update and rebooted at about 18:16 during `d4-mix-r1` (`sys.boot.reason` = `reboot,ota`). Its build went from `CP3A.260905.009` to `CP3A.261005.002.A1`. Every earlier Pixel 6 result is on the old build. The OTA was not triggered by the lab.

### Software

- The bake-off app (Kotlin Multiplatform; Android `ch.trancee.lab.host`, iOS CoreBluetooth in Kotlin/Native) with `disc=` modes: `Discovery.kt` (Android) and `IosDiscovery.kt` (iOS).
- Host: Python stdlib scripts `host/discovery.py`, `host/d2.py`, `host/t1.py`, `host/t9.py`, `host/d3.py`, `host/d4.sh` and the analyzer `host/analyze_discovery.py`.
- Toolchain as in the bake-off: Kotlin 2.4.21, Gradle 9.7.1, AGP 9.3.3, Xcode 16.4, JDK 21.
- **Android advertisers:** `AdvertisingSet` (legacy PDU, connectable, scannable, `ADVERTISE_TX_POWER_MEDIUM`, interval 160 = 100 ms) or `BluetoothLeAdvertiser.startAdvertising` (LOW_LATENCY), as named per run.
- **Android scanners:** foreground unfiltered `SCAN_MODE_LOW_LATENCY` active scans, restarted every 240 s (`rescan_s=240`; see [Deviations](#8-deviations)); or a foreground service with the screen off, `SCAN_MODE_BALANCED` and a `ScanFilter` on the MESH base UUID (or on Apple manufacturer ID 0x004C). The Pixel 6 also ran a passive scan (API 37) to see ADV PDUs alone.
- **iOS scanners:** `scanForPeripherals(withServices: [MESH])` with `CBCentralManagerScanOptionAllowDuplicatesKey` = true, foreground or background (app sent to the background with a background task, `bluetooth-central` mode). The T3 `nil` case scans with no service filter.
- Every scanner logs the first sighting of each (address, payload) pair with its raw bytes, and one bucket record per second per address after that. Android raw hex drops trailing zero bytes (display only).

### Clock

- Android phones were offset against the host by `adb shell date +%s%3N` (about ±20 ms). iOS phones were offset by the `idevicedate` second tick (about ±50 ms).
- D1 linkage and restart latencies use these offsets. Rotation intervals use one scanner's clock.

## 3. Method

Pass criteria were written into `prototypes/discovery/README.md` and committed **before** each run. Criteria added after a run are marked "post hoc". That file also logs every deviation as it happened.

### Map: questions ↔ runs

| Here | Desk test / owner question | Runs | n |
|---|---|---|---|
| D1a/b rotation | T5 (interval) | `d1-rot-20261102` (98 min) | 5 idle Android advertisers, 4 idle iPhones, 2 scanners |
| D1 restart control | T5 (new set, restart, data update), T6, E4 | `d1-rot-20261102` (180 s cycles), `d1-ctl-e-20261102` (120 s cycles, layout E) | 15–35 restarts per advertiser; 10 Android, 4 iOS advertisers |
| D1 own address | — | `d1-rot-20261102` (Pixel 6, `own_addr=1`) | 96 probes |
| D2 iOS advertiser | T2, T3, T10, E1–E3 | `d2-ios2-20261102` (rerun; `d2-ios-20261102` is the contaminated first run) | 6 cells × 300 s, 6 scanners |
| D2 Android advertiser | T8, T10, E2, E3 | `d2-and-20261102` (screen off), `d2-and-fg-20261102` (foreground) | 4 cells × 300 s, 7 scanners |
| T1 iOS layout | T1, E1, E5 | `t1-20261102`, `t1-ip12mini-20261102` (12 mini, after it was unlocked) | 5 iPhones × 7 cases × 30 s, 2 scanners |
| T3 overflow discovery | T3, E3 | `t3-20261102` | 21 trials per case, 3 cases |
| T11 re-report | T11 | `t11-20261102` | 5 iOS bg scanners × 300 s, 30–31 restarts each |
| T9 scan allowance | T9 | `t9-20261102` | Pixel 6, 2 × 60 min |
| D3 legacy vs extended | T7 | `d3-20261102`, `d3b-20261102` | 4 steps × 2 IDs; 23 Android + 4 iOS scanners |
| D4 iPhone 15 closes | — | `d4-mix-r1..r3` | 3 reps × 4 centrals × 500 s |
| T4 Live Activity | T4 | `t4-20261102` (12 mini, iOS 27) | 5 scanner cells × 180 s (n = 1 each), 2 advertiser cells × 2 reps × 120 s |
| T12 Play review | T12 | out of scope | — |

### Metrics

- **Address change (D1):** an Android scanner sees a new address carrying the same advertiser's payload. Android scanners report raw MACs; iOS scanners report a per-scanner identifier that followed the MAC in every case.
- **Exact rotation interval:** the time between two address changes that both happened while a scanner was receiving (the old address seen at most 10 s before the new one).
- **Restart linkage (D1):** per restart, with 1 s buckets and ms first-sighting times:
  - *Same packet:* the first sighting of the new UUID already carries the new address.
  - *Mixed sightings:* any sighting pairing the old address with the new UUID, or the reverse.
  - *Dark time:* first sighting of the new UUID − last sighting of the old address.
  - *Linked window* (address kept): first sighting of the new UUID → first sighting of any later address. A tracker can join the old and new UUID by address for this long.
- **D2 latency:** first sighting − advertiser start (host clock). Negative values mean the scanner saw the advertiser before the host logged the start call.
- **D2 recall (T10):** share of complete 10 s windows with at least one sighting, from the first sighting to the cell end. Also 30 s and 60 s windows.
- **Hint (T8, E2, E3):** share of sightings that carry the advertiser's metadata, here the rotating UUID.

### Pre-registered criteria (summary)

- **D1/T5:** "in step" is possible on a controller class if the address is new after ≥ 95 % of ≥ 10 restarts and the address never changes between restarts < 7 min apart. T5 iii (data update): expect 0 address changes at updates.
- **T6:** iOS restart rotation works only if the address is new after 100 % of ≥ 15 restarts per iOS version.
- **D2/T10:** recall ≥ 90 % at 10 s per state, else report the shortest window that reaches 90 %. T8: hint in ≥ 95 % of first sightings per scanner state.
- **T3:** 20+ trials per case with a latency distribution. **T11:** re-report rate after rescans every 10 s.
- **T1:** raw bytes per case per iOS version. **D3:** received = ≥ 1 sighting of a step's ID (n = 2 per step).
- **D4:** reproduced = a hub-side close of an established inbound link at ≤ 4 links in ≥ 2 of 3 reps.
- **E2:** a MESH filter match in ≥ 1 cell per advertiser platform and state, and the rotating UUID in ≥ 95 % of first sightings of foreground advertisers. **E5:** any `adv_start` error is a finding.

## 4. Results

### D1. Address rotation (T5, T6)

**Idle rotation [RUN `d1-rot-20261102`]:**

| Advertiser | API / controller | Exact intervals (min) | Median (min) |
|---|---|---|---|
| Pixel 6 (Android 17) | `AdvertisingSet`, extended-capable | 7.3, 8.7, 8.7, 7.7, 7.1, 7.9 | 7.8 |
| S10e (Android 12) | `startAdvertising`, extended-capable | 14.1, 14.1, 14.1, 14.1 | 14.1 |
| XCover 4 (Android 9) | legacy-only, multi-adv | 15.0, 15.0, 15.0 | 15.0 |
| P10 lite (Android 8.0) | legacy-only, single | 4.1, 10.9, 4.1, 10.9, 4.1, 10.9, 4.1 | alternating 4.1 / 10.9 |
| Mi 8 (Android 10) | `startAdvertising` | never seen by any scanner | — |
| iPhone 15, iPhone X, SE 2016 | foreground | 11.3, 19.3, 15.8, 18.0 (the same on all three) | 16.9 |
| 12 mini (iOS 27) | foreground | 17.2, 18.3 | 17.8 |

- Pixel 6 matches the AOSP 7–15 min range, at its low end. Samsung and the XCover 4 sit at 14–15 min. The P10 lite alternates 4.1 and 10.9 min, so one interval is below 5 min.
- **The three iPhones on iOS 15, 16 and 17 changed address within 5 s of each other at every change [RUN].** The cause is unverified. A shared schedule (for example, one tied to iCloud or wall-clock time) would let a tracker predict iPhone rotations.

**Restart control [RUN `d1-rot-20261102`, `d1-ctl-e-20261102`]:**

| Kind | Phones | Restarts | Address new | Same packet | Mixed | Dark time median / max (s) | Linked window median / max (s) |
|---|---|---|---|---|---|---|---|
| Android: new `AdvertisingSet` (`new_set`) | Z Flip4, Nokia X20, RMX3710, Nord 2, CPH2135 | 156 | 156 (100 %) | 156 | 0 | 2.5–4.3 / 11.3 | — |
| Android: `stopAdvertising` + `startAdvertising` (`stop`) | Pixel 4a, Mi Note 3, Blackview | 108 | 108 (100 %) | 108 | 0 | 2.7–2.9 / 10.7 | — |
| Android: `enableAdvertising(false/true)` on the same set | OnePlus 6 | 36 | 0 | — | — | — | 292 / 1309 and 399 / 794 |
| Android: `setAdvertisingData` on the live set | Nothing 1 | 36 | 0 | — | — | — | 263 / 1312 and 320 / 761 |
| iOS: `stopAdvertising`, 2 s, start with a new UUID | iPhone 15, SE 2016, SE 2020 | 63 | 0 | — | — | — | 318–451 / 1364 |
| iOS: stop and start at once with a new UUID (`swap`) | SE 2020 | 15 | 0 | — | — | — | 391 / 963 |
| iOS: stop, 2 s, start with the same UUID | iPhone X | 15 | 0 | — | — | — | 319 / 929 |

- Restart counts include only restarts that a scanner saw on both sides. Per-phone rows are in [`summary/summary-d1-rot.md`](summary/summary-d1-rot.md) and [`summary/summary-d1-ctl-e.md`](summary/summary-d1-ctl-e.md).
- **Android, new set or stop/start: the gap between the address change and our UUID change is 0 [RUN].** In 264 of 264 restarts on 8 models and all three controller classes, the first packet with the new UUID already had the new address. No packet ever paired an old address with the new UUID, or the reverse. The air is dark for 2.5–4.3 s (median) between the last old packet and the first new one; 2 s of that is the app's pause.
- **No extra address change happened between restarts** 120–180 s apart on Android (0 cycles with 2 addresses). So restarting more often than the OS rotates keeps the address in step. Restart periods between 3 and 7 min were not tested.
- **iOS: stop/start never changes the address [RUN].** 0 of 93 restarts on iOS 15, 16, 17 and 18. Changing the UUID, swapping at once, or keeping the UUID made no difference. The new UUID shows up under the old address. A tracker can join the old and new UUID for a median of 5.3–7.5 min, and up to 22.7 min.
- **iOS address changes are invisible to the app.** The OS rotates on its own schedule (above). No API reports it. So the gap between an iOS address change and the next UUID change is the time to the next app restart: up to one full UUID period.
- **Android data updates (T5 iii) keep the address [RUN]**, as predicted: 0 changes at 72 updates.
- **Restart → first sighting of the new UUID [RUN]:** Android scanners 0.2–1.5 s (median per phone); iOS scanners 0.1–2.8 s. iOS scanners never reported the old UUID again after they had reported the new one for the same identifier (0 stale reports, E4).
- **`AdvertisingSet.getOwnAddress()` [RUN]:** 96 of 96 reflective calls on the Pixel 6 failed with `NoSuchMethodException: android.bluetooth.le.AdvertisingSet.getOwnAddress []`. It is a hidden `@SystemApi` and is not callable by an app on Android 17. `AdvertisingSetCallback.onOwnAddressRead` fired 0 times.

**Can an app keep its identifier in step with the address?**

| Platform | Answer | Basis |
|---|---|---|
| Android, all 3 controller classes | **Yes, if the app rotates.** Start a new `AdvertisingSet` (or stop/start the legacy advertiser) with the new identifier. The address and the identifier change in the same packet. Restart more often than the shortest OS interval, or the OS changes the address mid-epoch. The shortest interval seen was 7.1 min (Pixel 6) and 4.1 min (P10 lite). | [RUN] 264/264 restarts, 0 mixed |
| Android, data update or enable/disable | **No.** The address stays; linkable for 4–7 min median, up to 22 min. | [RUN] 72 updates |
| iOS 15–18 (27 by idle rotation only) | **No.** Stop/start never rotates the address, and the app cannot see when the OS rotates it. Every UUID change is linkable by address for minutes. Every address change is linkable by UUID unless the UUID changes in the same instant, which the app cannot time. | [RUN] 0/93 restarts |

### D2. Background visibility (T2, T3, T8, T10, T11)

**iPhone as advertiser [RUN `d2-ios2-20261102`]** (latency in s; recall at 10 s; "—" = never seen):

| Advertiser state | Android fg, unfiltered | Android screen-off, MESH filter | Android screen-off, Apple 0x004C filter | iOS fg, MESH scan | iOS bg, MESH scan |
|---|---|---|---|---|---|
| iPhone 15 fg | 0.19 s, 1.0 | 0.69 s, 1.0 | 0.43 s, 1.0 | 0.06 s, 1.0 | 0.17–0.36 s, 1.0 |
| iPhone 15 bg, screen on | yes, 1.0, overflow only | first sighting only (pre-background), 0.03 | 0.14 s, 0.97, overflow only | 0.04 s, 1.0 | first sighting only, 0.03 |
| iPhone 15 bg, lock requested | yes, 1.0, overflow only | — | 11.6 s, 0.93 | 0.42 s, 1.0 | — |
| SE 2016 fg | 0.05 s, 1.0 | 0.12 s, 1.0 | — | 0.0 s, 1.0 | 0.08–0.13 s, 1.0 |
| SE 2016 bg, screen on | yes, 1.0, overflow only | — | 1.5 s, 0.93 | first only, 0.03 | first only, 0.03 |
| SE 2016 bg, lock requested | yes, 1.0, overflow only | — | 0.09 s, 0.80 | 1.24 s, 0.03 | — |

- Scanners: Nothing 2 (Android fg), edge 30 (MESH filter), XCover 4 (0x004C filter), iPhone X (iOS fg), SE 2020 and 12 mini (iOS bg).
- **A backgrounded iPhone drops both UUIDs from its ADV [RUN].** Android receives the system Nearby Info ADV (`0aff4c001005…`) with an Apple overflow area (`14ff4c0001` + 16 B bitmap) in the scan response. The SE 2016 (iOS 15) puts the overflow area in the ADV itself.
- **The rotating UUID cannot be recovered from a background iPhone [RUN].** The overflow area holds one hash bit per UUID. The MESH bit (last byte, `0x08`) was set in every layout-E background bitmap. The rotating UUID's bit moved with each new UUID (for example byte 5 `0x08` in one cell, byte 8 `0x01` in the next).
- **An Android MESH ScanFilter misses a background iPhone [RUN]:** 0 sightings after backgrounding. An Apple 0x004C manufacturer filter catches it (recall 0.80–0.97). It then needs the bitmap check to tell MeshLink from any other iPhone.
- **iPhone X in the foreground saw the background iPhone 15 the whole time** (recall 1.0, also locked), but saw the background SE 2016 only once or twice a minute (recall 0.03). The cause is unverified.
- **Background iOS scanners saw a background or locked iPhone at most once** per cell. T3 below measures first discovery.

**T2. Raw bytes from a backgrounded iPhone [RUN]** (Android active scan; ADV then scan response):

```text
iPhone 15 bg (iOS 17):   02011a 020a0c 0aff4c001005661c27dcc0 | 14ff4c0001 00000000000800000000000000000008
iPhone 15 lock request: 02011a 020a0c 0aff4c001005661c27dcc0 | 14ff4c0001 00000000000000000100000000000008
SE 2016 bg (iOS 15):     02011a 020a0c 14ff4c0001 00000000000000000000000001000008 | 0a094d4c2d736532303136
SE 2016 lock request:   02011a 020a0c 14ff4c0001 00000000800000000000000000000008 | 0a094d4c2d736532303136
```

- `0a094d4c2d736532303136` is the local name `ML-se2016`. Only an earlier layout-A run set that name. The SE 2016 still sent it (see [Stale scan response](#stale-ios-scan-response)).

**Android as advertiser [RUN `d2-and-20261102` screen off, `d2-and-fg-20261102` foreground]:**

| Advertiser | Android fg, unfiltered | Android screen-off, MESH filter (edge 30, XCover 4) | iOS fg, MESH scan (iPhone 15, SE 2020) | iOS bg, MESH scan: iPhone X | iOS bg: SE 2016 |
|---|---|---|---|---|---|
| Pixel 6 fg (`AdvertisingSet`) | −0.03 s, 1.0 | 2.6–6.2 s, 0.83–1.0 | −0.05–0.6 s, 1.0 | 0.74 s, 1.0 | 0.65 s, 0.52 |
| Pixel 6 screen off + FGS | −0.01 s, 1.0 | 0.4–1.0 s, 1.0 | 0.1–0.2 s, 1.0 | 0.43 s, 0.79 | 0.63 s, 0.48 |
| S10e fg (`startAdvertising`) | 0.37 s, 1.0 | 1.8–3.1 s, 0.93–1.0 | 0.2–0.5 s, 1.0 | 0.65 s, 0.97 | 0.96 s, 0.48 |
| S10e screen off + FGS | 0.35 s, 1.0 | 0.6–1.5 s, 1.0 | −0.01–0.05 s, 1.0 | 1.37 s, 1.0 | 0.68 s, 0.48 |

- **Hint (rotating UUID) in 100 % of sightings in every Android cell, on every scanner [RUN].** T8 and E2 pass for layout E.
- Rate per scanner: Android fg 136–213/min; Android screen-off filtered 10–49/min; iOS fg 115–189/min; iOS bg iPhone X about 6/min, SE 2016 about 3/min.
- **iOS background scanners report a known peer only every 10–20 s on iOS 15–16 [RUN].** The SE 2016 reaches 90 % recall only at 30 s windows (1.0). iPhone X reaches 0.79–1.0 at 10 s.
- **The 12 mini (iOS 27) background scanner reported foreground iPhones 408–434 times per minute** [RUN `d2-ios2`], the same as a foreground scanner. The SE 2020 (iOS 18) reported about 9 per minute in the same cells. The app confirmed `did_enter_background` on both. With n = 1 phone, an iOS 27 change of the duplicate filter in the background is likely but unverified.

**T10. 10 s hit rate per scanner state [RUN]** (foreground or screen-off advertisers, 300 s cells):

| Scanner state | Recall 10 s | Shortest window with ≥ 90 % |
|---|---|---|
| Android fg, unfiltered, LOW_LATENCY | 1.0 | 10 s |
| Android screen-off FGS, MESH filter, BALANCED | 0.83–1.0 (XCover 4 lowest) | 30 s for XCover 4, else 10 s |
| Pixel 6 screen-off, Android 17, 60 min (T9) | 0.970 | 10 s |
| iOS fg, MESH, duplicates | 1.0 | 10 s |
| iOS bg, MESH, iOS 15 (SE 2016) | 0.48–0.52 | 30 s |
| iOS bg, MESH, iOS 16 (iPhone X) | 0.79–1.0 | 30 s (10 s in 3 of 4 cells) |
| iOS bg, MESH, rescan every 10 s (T11) | 0.97–1.0 | 10 s |
| any scanner, background iPhone advertiser, by ID | 0 | never (no ID on air) |

**T3. Overflow discovery, background advertiser [RUN `t3-20261102`]:** iPhone 15 advertised layout E in the background. Three scanners (iPhone X iOS 16, SE 2020 iOS 18, 12 mini iOS 27) started a fresh scan for 40 s per trial.

| Scanner case | Trials | Found | Median (s) | p90 (s) |
|---|---|---|---|---|
| fg, `withServices: [MESH]` | 21 | 21 | 0.17 | 0.56 |
| fg, `withServices: nil` | 21 | 0 identified | — | — |
| bg, `withServices: [MESH]` (background ↔ background) | 21 | 13 (iPhone X 6/7, 12 mini 4/7, SE 2020 3/7) | 0.15 | 0.28 |

- A MESH filter finds a background iPhone through the overflow area (E3). The callback holds `CBAdvertisementDataOverflowServiceUUIDsKey = ["4D455348"]` only; the rotating UUID is never there.
- A `nil` scan returned thousands of results, but none carried overflow UUIDs. So it cannot tell a MeshLink iPhone from others.
- **Background ↔ background found the peer in 13 of 21 trials (62 %).** When it found it, it was fast (median 0.15 s). The misses were all-or-nothing within 40 s.

**T11. Background re-report after a scan restart [RUN `t11-20261102`]:** a stable Android peer (Nokia X20) and a "ticker" (Z Flip4, new set every 10 s) advertised. Background iOS scanners restarted their scan every 10 s.

| Scanner | Restarts | Restarts followed by a report of the stable peer |
|---|---|---|
| iPhone X (iOS 16) | 30 | 30 |
| SE 2016 (iOS 15) | 31 | 29 |
| 12 mini (iOS 27) | 31 | 30 |
| iPhone 15 (iOS 17) | 30 | 29 |

- A background scan restart re-reports peers it has already seen in 97 % of restarts (118 of 122). So restarting the scan every 10 s gives a background iPhone a 10 s density window (recall 0.97–1.0).

**T8. Hint reception:** layout E carries the hint in the ADV, so there is no scan response to lose. It was present in 100 % of sightings of foreground and screen-off Android and foreground iOS advertisers, on all scanner states [RUN]. Layout A's 13 B scan-response service data (`1e21<UUID>…`) reached Android active scanners in `d1-rot` (223 attributed first sightings on Nothing 2) [RUN]. Layout A on iOS scanners and screen-off scanners was not run; it is unverified.

### T1. iOS advertisement layout (E1, E5)

[RUN `t1-20261102`, `t1-ip12mini-20261102`] Pixel 6 passive scan = ADV only; Nothing 2 active scan = ADV + scan response. Five iPhones (iOS 15, 16, 17, 18, 27); the 12 mini ran later, in its own run. Full bytes in [`summary/summary-t1-t1.md`](summary/summary-t1-t1.md) and [`summary/summary-t1-t1mini.md`](summary/summary-t1-t1mini.md).

| Case | ADV (all 5 iOS versions) | Scan response |
|---|---|---|
| `e`: layout E, no name, foreground | `02011a 020a0c 1107<rotating UUID> 05054853454d` (30 B) | the last app-set name or overflow area (stale, see below) |
| `e-name8` / `e-name10` | unchanged | `09 09 <8 chars>` / `0b 09 <10 chars>` |
| `e-uuid3` (a second 128-bit UUID, 40 B) | `1106<first UUID> 05054853454d`; type 0x06 (incomplete) | the second UUID as an overflow bit: `14ff4c0001…04` |
| `e-bg` | Nearby Info `0aff4c001005…` (SE 2016: overflow area) | overflow area |
| `post-stop` (app killed) | Nearby Info after about 30 s | still the last app-set name or bitmap |

- **E1 [RUN]:** both UUIDs are in the main packet on every iOS version. The 128-bit UUID is AD type 0x07 (complete), and MESH is AD type 0x05 (complete 32-bit list). iOS adds flags `0x1a` and TX power level (`0x0c` = 12 dBm; SE 2020 `0x0b` = 11 dBm). That is 30 of 31 B.
- iOS keeps the 32-bit form: it does not expand MESH to 128 bits.
- **iOS 27 [RUN `t1-ip12mini-20261102`]:** the 12 mini sent the same bytes as iOS 15–18 in every case, for example `02011a 020a0c 1107<UUID> 05054853454d` in `e`. In `e-bg` the first packet still held both UUIDs (+2.0 s); 0.4 s later it was Nearby Info plus the overflow area. Its `e` scan response was a stale overflow bitmap (`14ff4c0001…08`).
- **Android layout E [RUN]:** `020102 05054853454d 1107<16 B>` from the Pixel 6 (flags `0x02`), `02011a …` from the S10e. That is 27 B, both UUIDs in the ADV, no scan response.
- **Name leak:** no iPhone ever sent its device name ("iPhone SE (2016)", …) [RUN]. A local name set by the app goes to the scan response.
- **E5 [RUN]:** 0 `adv_start` errors on either OS across every layout-E start (D1-ctl-E, D2, T1, T3, T11, D3). Neither OS rejected or rewrote the unassigned 32-bit UUID.

#### Stale iOS scan response

[RUN `t1-20261102`, `probe-ovf2`, `d2-ios-20261102`]

- After an app stops advertising, an iPhone keeps sending the scan response of its last app advertisement, with the system Nearby Info ADV. That is the last local name or overflow bitmap.
- It lasted at least 26 min and survived address rotations. It also appears as the scan response of the next foreground advertisement until the app sets a new one.
- Effects:
  - false presence: a MESH-filtered iOS scanner still finds a phone whose app stopped;
  - linkability: the stale name or bitmap links addresses across rotations.
- An app can overwrite it by advertising briefly in the background with a UUID that has no MESH bit (`probe-scrub`). The first D2 iOS run was contaminated by this effect before the scrub was added (see [Deviations](#8-deviations)).

### D3. Legacy vs extended vs Coded (T7)

[RUN `d3-20261102`, `d3b-20261102`] Advertiser Nothing 1 (Android 15, extended and Coded). Steps of 90 s, 2 reps: `legacy`; `ext1m` (1M/1M, non-legacy); `ext2m` (1M primary, 2M secondary); `coded` (Coded/Coded). Extended steps carry 93 B (layout E + 60 B service data under MESH). Full matrix in [`summary/d3-matrix-d3.csv`](summary/d3-matrix-d3.csv).

| Scanners | legacy | ext1m | ext2m | coded |
|---|---|---|---|---|
| Extended- and Coded-capable Android: Pixel 6, Nothing 2, Z Flip4, S10e, nova 9, edge 30, Nokia X20, POCO F1, RMX3710 (9) | 2/2 each | 2/2 | 2/2 (secondary PHY 2 reported) | 2/2 (primary and secondary PHY 3) |
| Extended-capable Android that report Coded but got none: GX6, RMX3263, CPH2251, Nord 2, Pixel 4a (5) | 2/2 | 2/2 | 2/2 | **0/4** (both runs) |
| Redmi 12 (`d3b`) | 2/2 | 2/2 | 2/2 | 0/2 |
| Legacy-only Android: P30 lite, XCover 4, Mi Note 3, P10 lite, CPH2135 (5) | 2/2 | 0/2 | 0/2 | 0/2 |
| iPhone 15, SE 2020, iPhone X, SE 2016 | 2/2 | 0/2 | 0/2 | 0/2 |
| Invalid: OnePlus 6, Mi 8 (`scan_failed 2` at start), Blackview (0 results from any source) | — | — | — | — |

- Every iPhone reported `CBCentralManager.supports(.extendedScanAndConnect)` = false [RUN].
- **Legacy advertising is the only format that every phone received [RUN].** Extended advertising reached only extended-capable Android phones.
- **Coded PHY reception is unreliable even where the API says yes [RUN].** 6 of 15 Android phones that report `isLeCodedPhySupported` = true received no Coded advertisement in 4 tries at short range. The cause is unverified (scanner duty cycle on Coded, or firmware).
- `getLeMaximumAdvertisingDataLength` ranged from 31 B (legacy-only) through 192, 304 and 1024 to 1650 B [RUN].

### D4. iPhone 15 incoming-link closes

[RUN `d4-mix-r1`..`r3`; LOG hub `bluetoothd` syslog] Hub iPhone 15 (`bakeoff.py phub`, current app build). Centrals in step 4 order: Pixel 6, Nothing 2, iPhone X, SE 2016, 10 s apart. Each central holds 500 s. Criterion: reproduced = a hub-side close of an established link at ≤ 4 links in ≥ 2 of 3 reps.

| Run | Max links | Hub-side early closes | Note |
|---|---|---|---|
| `d4-mix-r1` | 4 | 0 | Pixel 6 link ended 137 s after connect: the Pixel 6 rebooted for a system OTA (central side, not counted). The other 3 held 500 s. |
| `d4-mix-r2` | 4 | 0 | All 4 held 500 s. |
| `d4-mix-r3` | 4 | 0 | All 4 held 500 s. |
| Bake-off step 4 copy (`m4-phub-ip15-t01222247`, build `be5c761`, 10 centrals) | 4 | 5 | Closes at 2–4 links (Pixel 6 @3, Nothing 2 @2, SE 2020 @3, SE 2016 @3, iPhone X @4). |

- **Not reproduced with the current build [RUN].** 0 of 3 reps closed a link. The variants (Android-only, iPhone-only, 30 s stagger) were not run, as pre-registered.
- **A lead from the hub log [LOG].** For both iPhone centrals, `bluetoothd` logged `disconnecting device … since its scalable pipes are idle` every 5 s, from about 1 min after connect until hold end (72–173 lines per run). Those links carry an Apple `wp.nearby` pipe. The links held anyway, presumably because our app's GATT subscription kept them up. It is unverified whether this idle-pipe logic closed links in step 4.
- **Remaining candidate causes (unverified):** the old build `be5c761`, the larger fleet in step 4 (10 centrals in sequence), and the idle-pipe logic above.

### T9. Android 17 scan allowance

[RUN `t9-20261102`; LOG `dumpsys bluetooth_manager` every 60 s] Pixel 6, Android 17, foreground service, MESH filter, `SCAN_MODE_BALANCED`, one scan session for 2 h. The nova 9 advertised layout E the whole time.

| Phase | Minutes | Scan mode in `dumpsys` | Beacon sightings / min | Recall 10 s | 60 s | Suspend |
|---|---|---|---|---|---|---|
| Screen on, charging | 59 | BALANCED throughout | 36.2 | 0.997 | 1.0 | 0 ms |
| Screen off, `dumpsys battery unplug` | 60 | BALANCED throughout | 40.2 | 0.970 | 1.0 | 0 ms |

- No throttling, downgrade or suspension was seen in 2 h. A filtered scan is exempt from the desk note's 24 min/h allowance, or that allowance is not active on this build [RUN for this phone]. Only one phone runs Android 17, so other OEMs are unverified. Doze was not entered (no `deviceidle force-idle`).

### T4. Live Activity

[RUN `t4-20261102`, `host/t4.py`] 12 mini, iOS 27.0.1. A lab build (Xcode 16.4, iOS 18.5 SDK) added a minimal Live Activity widget extension. With `live=1`, the app starts a Live Activity after its `CBManager` exists, then sends itself to the background. All 4 starts succeeded (`areActivitiesEnabled` = true, state active). Pixel 6 advertised layout E in the scanner cells. Pixel 6 (passive) and Nothing 2 (active) scanned in the advertiser cells. Full tables: [`summary/summary-t4-t4.md`](summary/summary-t4-t4.md).

| Scanner cell (180 s, duplicates allowed) | Callbacks / min | Peers (MESH / other) | Pixel 6 sightings / min | Pixel 6 recall 10 s |
|---|---|---|---|---|
| `nil` scan, foreground (reference) | 3969 | 1 / 45 | 270 | 1.0 |
| `nil` scan, background | 16 | 1 / 21 | 2.0 | 0.056 |
| `nil` scan, background + Live Activity | 3959 | 2 / 47 | 261 | 1.0 |
| MESH scan, background | 93 | 1 / 0 | 93 | 1.0 |
| MESH scan, background + Live Activity | 251 | 1 / 0 | 251 | 1.0 |

| Advertiser cell (120 s, 2 reps × 2 scanners) | 1 s buckets with the rotating UUID | UUID seen, s after backgrounding |
|---|---|---|
| background | 0–1 per scanner | ≤ 0.1 |
| background + Live Activity | 27–34 per scanner | 0.0 to 32.7–33.1 |

- **Scanning: a Live Activity gives a background scanner foreground behavior on iOS 27 [RUN, n = 1 per cell].** The `nil` scan matched the foreground reference for the whole 180 s. Duplicates rose from 93 to 251 sightings per minute. Pre-registered criterion (i) is met.
- **Advertising: only while the app still runs [RUN, n = 2].** With a Live Activity the main packet kept both UUIDs after backgrounding. They left it 32.7–33.1 s later, 4.4–4.8 s after the app's background task expired (28.3 s after backgrounding in both reps). Then the advertisement was Nearby Info plus the overflow area, as without a Live Activity. Criterion (ii) is not met for the cell; it holds only while the app is not suspended.
- **Unverified:**
  - whether an app kept awake in the background (for example by scan callbacks under the Live Activity) keeps the foreground advertisement longer;
  - iOS 26, and an app built with a newer SDK;
  - (iii) behavior after the 8 h end and while an ended activity stays on screen (not run);
  - whether the screen was on: the 12 mini had auto-lock off.

## 5. Owner questions E1–E5 (layout E)

| | Question | Answer | Label |
|---|---|---|---|
| E1 | Both UUIDs in the main packet, as 0x05/0x04 and 0x07/0x06? iOS extras? | Yes on both platforms: MESH as 0x05, rotating UUID as 0x07. iOS adds flags and TX power (30 B). It adds no name. The scan response carries a stale name or bitmap from earlier app advertising. | [RUN] |
| E2 | Android ScanFilter on the MESH base UUID matches both platforms, fg and screen off; rotating UUID readable? | Yes for Android advertisers (fg and screen off) and foreground iPhones; rotating UUID in 100 % of sightings. **No for a background iPhone**: 0 matches; it needs a 0x004C filter plus the bitmap. | [RUN] |
| E3 | iOS `withServices: [MESH]` fg and bg finds the advertiser and gets the rotating UUID? Background iOS advertiser? | Foreground advertisers (iOS or Android): found fg and bg; `CBAdvertisementDataServiceUUIDsKey` holds both UUIDs. Background iPhone: found by MESH alone (fg scanner 21/21, bg scanner 13/21), but only `["4D455348"]` in the overflow key. The rotating UUID is unrecoverable. | [RUN] |
| E4 | Rotation by restart with a new UUID; does the address change? Restart → new UUID seen? Stale cache? | Android: address changes in the same packet (gap 0, 264/264). iOS: address never changes (0/93); linkable 5–7.5 min median. New UUID seen in 0.1–2.8 s. No stale UUID reports on iOS scanners. iOS does keep a stale scan response on air. | [RUN] |
| E5 | OS rejection of the unassigned 32-bit UUID? | None. 0 start errors; iOS sends it as 32-bit. | [RUN] |

## 6. What this changes in the ADRs

- **ADR 0001** ("every on-air identifier rotates in step with the BLE address"):
  - Android can meet it, but only by rotating through a new `AdvertisingSet` or stop/start, at least as often as the shortest OS interval (7.1 min on Pixel 6, 4.1 min on the P10 lite). A data update keeps the address and breaks it.
  - **iOS cannot meet it [RUN].** The app can neither trigger nor observe an address change. The guarantee needs an iOS exception: each identifier change is linkable by address for minutes, and each address change by identifier.
  - The stale iOS scan response leaks presence and links addresses after the app stops. That is a new item for the threat model.
  - Background iPhones carry no MeshLink identifier at all (overflow bit only). A Live Activity does not change this once the app is suspended (T4).
- **ADR 0008:**
  - The 15 min rotation is longer than the OS interval on every tested Android phone except the XCover 4. An epoch of at most 4–7 min, or a restart at every OS-sized interval, is needed for Android to stay in step. The simulation's shortest in-budget period was 5 min.
  - The density estimate counts advertisement IDs in 10 s. Background iPhones carry no ID, and background iOS scanners on iOS 15–16 report a peer only every 10–20 s. So density is undercounted unless background scanners restart every 10 s (T11).
  - Background ↔ background iPhone discovery succeeded in 62 % of 40 s trials. iOS background wake windows remain modeled, not measured, beyond this.
  - On iOS 27, a Live Activity lifts the background scan limits (`nil` scan, duplicates) [RUN, T4, n = 1 per cell]. It needs a user-started activity (8 h maximum) and does not help background advertising once the app is suspended. iOS 26 is unverified.
- **ADR 0009:** confirms the legacy 1M advertisement. iPhones receive no extended advertising, and 6 of 15 Coded-capable Android phones received no Coded advertising. D4 did not reproduce the iPhone 15 inbound closes at ≤ 4 links with the current build (0 of 3 reps), so the bake-off note on them stays open but is not confirmed as an iOS limit.

## 7. Answers to the desk-research tests

| Test | Result | Label |
|---|---|---|
| T1 | ADV holds flags, TX power, the 128-bit and 32-bit UUIDs (30 B). A name or the overflow area goes to the scan response. No device-name leak. Stale scan response after stop. | [RUN] |
| T2 | Background iPhone: Nearby Info ADV + `14ff4c0001` + 16 B bitmap in the scan response (SE 2016: in the ADV). The bitmap changes with the advertised UUID. | [RUN] |
| T3 | fg MESH scan 21/21 (median 0.17 s); `nil` scan 0/21; bg ↔ bg 13/21 (median 0.15 s). | [RUN] |
| T4 | iOS 27: (i) background `nil` scan with duplicates as in the foreground (3959 vs 16 callbacks/min). (ii) Background advert keeps both UUIDs only until the app is suspended (≈ 33 s), then overflow. (iii) 8 h end not run. | [RUN] (i), (ii); [UNVERIFIED] (iii) |
| T5 | New set or stop/start: new address 264/264, same packet. Data update or enable: 0/72. Idle: 7.1–8.7 min (Pixel 6), 14–15 min (Samsung), 4.1/10.9 min (P10 lite). | [RUN] |
| T6 | iOS stop/start: new address 0/93. | [RUN] |
| T7 | See D3. | [RUN] |
| T8 | Layout E hint: 100 % on every scanner state. Layout A scan response: Android active scanners only. | [RUN] |
| T9 | Pixel 6 Android 17: BALANCED for 2 h, no throttling. Other Android 17 OEMs unverified. | [RUN] |
| T10 | See the T10 table. | [RUN] |
| T11 | 118/122 background restarts re-reported the stable peer. | [RUN] |
| T12 | Out of scope. | — |

## 8. Deviations

All were logged in `prototypes/discovery/README.md` when they happened.

- **Unfiltered Android scans go opportunistic after 5 min** [LOG edge 30 logcat: `regularScanTimeout - client scan time was too long`, `Moving unfiltered scan client to opportunistic scan`]. In `d1-rot` the scanners saw almost nothing 12:29–12:49 and 12:54–13:01. They were relaunched with `rescan_s=240`; the first parts are kept as `*-part1.jsonl`. Rotation intervals count only changes seen while a scanner was receiving.
- **D1 control advertisers added at 13:01:** Nokia X20, RMX3710, Nord 2 and CPH2135 (`new_set`) joined `d1-rot` when the scanners were relaunched. The P30 lite (`stop`) log stops after its first steps (no `done`); it is not in the tables.
- **Mi 8 advertiser** reported status 0 twice in `d1-rot`, but no scanner ever saw it. Bluetooth could not be toggled over adb. It is excluded from D1.
- **Layout change:** `d1-rot` used layout A. Every later run used layout E.
- **`d2-ios-20261102` was contaminated** by the stale iOS scan response: iPhones that had advertised MESH in the background earlier kept the MESH bit on air. The rerun `d2-ios2-20261102` scrubs each iPhone (15 s of background layout A) before each phase and after each background cell. Overflow sightings count only if their bitmap was not on air in the 10 min before the advertiser started. Only the rerun is reported.
- **12 mini locked** after T3 and `devicectl` refused launches ("device has not been unlocked recently"). SE 2020 replaced it in `d2-and`. At 19:01 the owner unlocked it by hand and turned off auto-lock. T1 was then rerun on it alone (`t1-ip12mini-20261102`) and T4 ran (`t4-20261102`), both approved after the main runs.
- **T4 build:** only the 12 mini got the Live Activity build (commit `6648d9a`, installed 19:06, after its T1 rerun). The T4 advertiser table metric was changed after the run; see the runbook.
- **Android JSONL truncation:** the app truncated its log at each relaunch, so the advertiser files of the `d2-and` foreground cells were lost. Fixed (append mode, commit `ff74472`); the foreground cells reran as `d2-and-fg-20261102`.
- **D3:** Redmi 12 ran a pre-layout-E build in `d3-20261102` and was reinstalled; OnePlus 6, Mi 8 and Blackview failed; five Coded scanners got no Coded advertisement. `d3b-20261102` reran these. OnePlus 6 and Mi 8 failed again with `scan_failed 2` at the first scan; Blackview again got 0 results.
- **D3 advertiser:** Nothing 1 instead of Pixel 6, which was running T9.
- **D4 r1:** the Pixel 6 installed a system OTA and rebooted during the hold (`sys.boot.reason=reboot,ota`, CP3A.260905.009 → CP3A.261005.002.A1). Every earlier Pixel 6 result is on the old build. The `phub` fetch timed out, so files were fetched by hand. `d4.sh` left `idevicesyslog` running after r1 and r2; fixed in `fa4cb30` before r3.

## 9. Limitations and unverified items

- **iPhones:** the 12 mini (iOS 27) is missing from `d2-and` and D3 because it locked itself. iOS 27 is covered by D1 idle rotation, D1-ctl-E (scanner), `d2-ios2`, T1, T3, T4 and T11. T4 covers only iOS 27.
- **No Doze and no long screen-off periods.** Screen-off cells lasted 5 min (T9: 60 min) with `dumpsys battery unplug`. Real overnight behavior is untested.
- **"Lock requested" iPhone cells** called the private `GSEventLockDevice`. The app found the symbol, but it logged no lock or protected-data event, and no person checked the screen. Whether the phones locked is unverified, so these cells count as a second background cell, as pre-registered.
- **One room, short range, one session.** Many other BLE devices were nearby. RSSI and range were not studied.
- **Coverage:**
  - iOS restart rotation was tested on iOS 15–18, not 27.
  - The Android restart periods tested were 120 s and 180 s. Periods between 3 and 7 min were not.
  - D2 covers two iPhone advertisers (iPhone 15, SE 2016) and two Android advertisers (Pixel 6, S10e).
- **Unverified causes:**
  - the synchronized address rotation of three iPhones;
  - why an iPhone X foreground scanner kept seeing a background iPhone 15 but not a background SE 2016;
  - the 12 mini's high background report rate (iOS 27 or this phone). In T4, without a Live Activity, its background MESH scan reported one Android peer 93 times per minute. That suggests duplicates are allowed in the background on iOS 27, but different peers and states make it no direct comparison;
  - the 6 Coded non-receivers;
  - OnePlus 6 and Mi 8 `scan_failed 2` (`SCAN_FAILED_APPLICATION_REGISTRATION_FAILED`; likely leaked scanner registrations, as on the XCover 4 in the bake-off);
  - Blackview receiving nothing as a scanner (it did advertise in D1);
  - the Mi 8 advertiser that nobody saw.
- **Clock:** iOS offsets are about ±50 ms. D1 linkage uses 1 s buckets for the window and ms first-sighting times for "same packet".
- **The `commit` field** in records is the host `HEAD` when the run started, not the app build. It changed during `d3-20261102` while the APKs did not.
- **Placeholder payload:** the 16 B in the rotating UUID are placeholders. The real layout is not tested here.

## 10. Raw data

- [`raw/`](raw/) holds one directory per run with every JSONL file, gzipped (`gzip -9n`). Read one with `gzip -dc <file>.jsonl.gz`. [`raw/MANIFEST.csv`](raw/MANIFEST.csv) lists each run's status, phase, time, host commits, file count and gzipped bytes.
- [`summary/`](summary/) holds the analyzer output per run: `summary-*.md` tables and the CSVs behind them.
- Record format: `{t, test, run_id, commit, tag, …}`. Scanner sightings are `test: adv` (first sighting of an address-payload pair, with `raw` hex, `rssi`, `legacy`, `pphy`, `sphy`) and `test: s` (per-second bucket). Advertisers log `adv_start` with `ad_id`, `uuid_list` and `t_call`. `host.jsonl` holds the host's clock offsets and orchestration events.

### Diagnostic captures

These are **not** in this folder. They stay in `prototypes/bearer-bakeoff/host/results/` on branch `prototype/discovery`. They are gitignored, so they exist only on the lab Mac.

| Path | Contents |
|---|---|
| `t9-20261102/dumpsys/NNNN.txt` | `dumpsys bluetooth_manager` snapshots of the Pixel 6, every 60 s |
| `d4-mix-r*/ip15-bluetoothd.log` | iPhone 15 `bluetoothd` syslog during D4 (scan spam removed) |
| `d4-mix-r3.out` | `phub` console output of r3 (`run.json` files are in `raw/`) |
| edge 30 logcat excerpt (12:29–13:01) | The opportunistic-scan downgrade lines quoted above; seen live, not saved as a file |

## 11. How to reproduce

The iOS profiles from the free teams expire around 2026-11-07. Rerunning on iPhones after that needs re-signing.

```sh
git worktree add ../MeshLink-discovery-proto prototype/discovery
cd ../MeshLink-discovery-proto/prototypes/bearer-bakeoff
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ADB_SERVER_SOCKET=tcp:adb-server:5037

python3 host/bakeoff.py build android ios
python3 host/discovery.py install <android names> ip15 se2020 ip12mini ipx se2016
python3 host/discovery.py caps --run caps-20261102 <android names>

# D1: one `discovery.py launch RUN NAME key=value…` per phone; the arguments of each phone are in its
# disc_start record and in prototypes/discovery/README.md (D1, D1-ctl-E).
python3 host/discovery.py launch d1-ctl-e-20261102 zflip4 disc=adv adv=set restart_every=120 restart_kind=new_set duration=1920

python3 host/d2.py ios-cells d2-ios2-20261102                     # iPhone advertiser cells, scrub included
python3 host/d2.py android-cells d2-and-fg-20261102 --states fg --ios-fg ip15,se2020
python3 host/d2.py t3 t3-20261102
python3 host/d2.py t11 t11-20261102
python3 host/t9.py --run t9-20261102
python3 host/t1.py t1-20261102 --phones se2016,ipx,ip15,se2020
python3 host/t1.py t1-ip12mini-20261102 --phones ip12mini
python3 host/t4.py t4-20261102                                     # needs the Live Activity build (6648d9a+)
python3 host/d3.py d3-20261102 --scanners <names>
host/d4.sh d4-mix-r1 10 pixel6 nothing2 ipx se2016

# Analysis (writes host/results/summary/*-<tag>.*)
SUMMARY_TAG=rot python3 host/analyze_discovery.py d1 host/results/d1-rot-20261102
SUMMARY_TAG=t3  python3 host/analyze_discovery.py t3 host/results/t3-20261102
SUMMARY_TAG=mix python3 host/analyze_discovery.py d4 host/results/d4-mix-r*
SUMMARY_TAG=t4  python3 host/analyze_discovery.py t4 host/results/t4-20261102
```

- Run one advertiser per payload at a time, or give each a distinct ID. Scrub iPhones before any iOS scanner run (`d2.py` does this: 15 s of background layout A per iPhone), or stale scan responses will mix advertisers.
- Unfiltered Android scans need `rescan_s=240` or they go opportunistic after 5 min.

## 12. Evidence record (Constitution T9)

| Field | Value |
|---|---|
| Devices and OS | iPhones: SE 2016 iOS 15.8.8, iPhone X 16.7.16, iPhone 15 17.6.1, SE 2020 18.7.8, 12 mini 27.0.1. Android: 24 phones, Android 8.0 (API 26) to 17 (API 37); see [Phones](#phones). Excluded: OPPO CPH2385, Redmi Note 11. |
| Firmware | Android build fingerprints are in every `disc_start` record. Pixel 6 changed from `CP3A.260905.009` to `CP3A.261005.002.A1` by OTA at 18:17. Bluetooth controller firmware not recorded. |
| Covered platform pairs | Android → Android (D1, D2, D3, T9); iPhone → Android (D1, D2, T1, T2, T4); Android → iPhone (D1-ctl-E, D2, D3, T4); iPhone → iPhone (D1-ctl-E, D2, T3, T11, D4). States: foreground, background (screen on; a lock was requested but is unverified), Android screen-off with FGS. |
| Not covered | Doze; overnight; Live Activity 8 h end and iOS 26 (T4); iOS 27 restart rotation; Android restart periods 3–7 min; layout A on iOS scanners; range and RSSI. |
| Commit | Branch `prototype/discovery`. Host `HEAD` per run is in each record's `commit` field and in `raw/MANIFEST.csv`. App builds are not stamped in records: layout E from `1843835`; the Android append fix `ff74472` is on Pixel 6, S10e and Redmi 12 from 17:40; the Live Activity build `6648d9a` is on the 12 mini from 19:06. Analyzer at `6a13e48`. |
| Method | Python orchestrators over remote adb, `devicectl` and libimobiledevice; criteria pre-registered in `prototypes/discovery/README.md`; see [Method](#3-method). |
| Raw results | [`raw/`](raw/) (gzipped JSONL), [`summary/`](summary/); diagnostic captures on the lab Mac (see [Diagnostic captures](#diagnostic-captures)). |
| Date | 2026-11-02 12:14–19:34 CET |
