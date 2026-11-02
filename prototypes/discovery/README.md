# Discovery measurements (ticket 13): pre-registration and runbook

PROTOTYPE. Throwaway lab work. The app is the bake-off app in `../bearer-bakeoff` with `disc=` modes
(`androidApp/.../Discovery.kt`, `shared/src/iosMain/.../IosDiscovery.kt`, `host/discovery.py`).
Raw results go to `../bearer-bakeoff/host/results/<run>/` (gitignored).

This file is committed **before** each run. A criterion added after its run is marked "post hoc".

## Advertisement under test

- **Android, layout A** (desk research `docs/research/2026-11-02-discovery-advertising.md` §4):
  - ADV: flags + one 128-bit mesh UUID `6e4b0001-8f3c-4d2a-9b61-2c7a5d3e9f10`. Legacy, connectable, scannable.
  - Scan response: service data under the mesh UUID, 13 B = 8 B random advertisement ID (new at every start) +
    4 B mesh tag (placeholder constant `ML01`) + 1 B flags (placeholder: step << 4 | cycle).
  - Extended steps (D3): the same service data in the ADV, plus `pad` bytes so the ADV exceeds 31 B.
  - The advertiser logs every advertisement ID it uses, so scanners attribute sightings by ID.
- **iOS, layout A:** service UUID only, no local name.
  - Deviation: when several iPhones advertise at once (D1), each sets local name `ML-<tag>` so scanners can tell
    them apart. Only one iPhone advertises UUID-only at a time (D2, T1).

## Map: brief measurements ↔ desk-research tests

| Here | Desk test | What |
|---|---|---|
| D1a/b, D1-ctl | T5 | Android rotation interval; new set / restart / setAdvertisingData vs address, per controller class |
| D1c, D1-ctl | T6 | iOS rotation interval; stop/start, name change, UUID change vs address |
| D2 | T2, T3, T8, T10, T11 | Background matrix: bytes from backgrounded iPhones, overflow discovery, hint reception, 10 s hit rate, iOS re-report |
| T1 | T1 | iOS ADV vs scan-response layout and name leak |
| D3 | T7 | Legacy vs extended reception, Coded on iOS |
| D4 | — | iPhone 15 incoming-link closes |
| T4 | T4 | Live Activity. **Skipped:** needs an ActivityKit widget extension and Live Activity UI in the lab app; not cheap. Unverified. |
| T9 | T9 | Android 17 scan allowance. Only Pixel 6 runs Android 17. Run only if lab time remains. |
| — | T12 | Play review. Out of scope (external submission). |

## Controller classes (measured, run `caps-20261102`)

- Extended advertising: Pixel 6, Nothing 2, S10e, Mi 8, Z Flip4, edge 30, nova 9, Nothing 1, Pixel 4a, Nokia X20,
  POCO F1, OnePlus 6, Nord 2, GX6, RMX3263, CPH2251, RMX3710, Redmi 12.
- Legacy-only with multi-advertising: XCover 4, Mi Note 3, CPH2135.
- Legacy-only, single advertiser: P10 lite, P30 lite, Blackview A80.

## D1 rotation + T5 + T6 (run `d1-rot-*`, ≥ 90 min)

- Scanners: Nothing 2 and edge 30, unfiltered, active, LOW_LATENCY, foreground, screen on, on chargers.
- Idle advertisers (no restarts), all foreground on chargers:
  - Android: Pixel 6 `AdvertisingSet` (legacy PDU), S10e and Mi 8 `startAdvertising` (extended-capable),
    XCover 4 (legacy-only, multi-adv), P10 lite (legacy-only, single).
  - iOS: iPhone 15, SE 2016, iPhone X, 12 mini; foreground, `ML-<tag>`.
- Control advertisers, one restart every 180 s (≥ 30 cycles):
  - Z Flip4: new `AdvertisingSet` each cycle (stop set, 2 s off, new set).
  - Pixel 4a: `startAdvertising` stop/start (2 s off).
  - Nothing 1: `setAdvertisingData` + `setScanResponseData` on the live set (T5 iii).
  - OnePlus 6: `enableAdvertising(false/true)` on the same set with new data.
  - Mi Note 3 (legacy-only, multi-adv) and Blackview (legacy-only, single): stop/start.
  - SE 2020: iOS stop/start every 120 s (2 s off).
- `own_addr=1` on Pixel 6: reflective `AdvertisingSet.getOwnAddress()` every 60 s; record the exact exception.
- Metrics: address change times per advertiser (first sighting of a new address carrying the same attribution);
  idle interval distribution; per control cycle, whether the first post-restart address is new.
- **Criteria (pre-registered):**
  - Rotation: report every interval. Hypothesis from AOSP: idle extended-capable Android rotates every 7–15 min,
    mostly 7–9 min while awake. Pass = interval list recorded for ≥ 3 Android models and ≥ 2 iPhones.
  - "In step" possible on a class iff the address is new after **≥ 95 %** of ≥ 10 restarts **and** the address
    never changes between restarts that are < 7 min apart.
  - T5 iii: expect **0** address changes at data updates beyond the idle rotation schedule.
  - T6: Layout B is allowed only if the address is new after **100 %** of ≥ 10 restarts on every tested iOS version.

- **Deviation during the run (logged, 13:01):** Android moved each unfiltered LOW_LATENCY scan to opportunistic after
  5 min (`BtGatt.ScanManager: regularScanTimeout - client scan time was too long` / `Moving unfiltered scan client to
  opportunistic scan`, edge 30 logcat), so the scanners saw almost nothing 12:29–12:49 and 12:54–13:01. Scanners were
  relaunched at 13:01 with `rescan_s=240` (earlier files kept as `*-part1.jsonl`). Rotation intervals count only
  between two changes observed while a scanner was receiving. Mi 8 was excluded: its legacy advertiser reported
  status 0 twice but no scanner ever saw it; Bluetooth could not be toggled over adb (`BLUETOOTH_ADMIN`).
- Every later unfiltered Android scan restarts every 240 s (`rescan_s=240`). Filtered scans are not affected.

## D1-ctl-ios (run `d1-ios-*`, 32 min)

- Every iPhone restarts every 120 s (≥ 15 cycles), named `ML-<tag>`:
  iPhone 15 `name` (stop, start with a new name), SE 2016 `stop` (2 s off), iPhone X `uuids` (stop, start with a
  second UUID added or removed), 12 mini `stop`, SE 2020 `name`.
- Same scanners and criteria as T6 above, per kind and iOS version.

## D2 background matrix (runs `d2-*`)

- Cell = one advertiser in one state, ≥ 5 min (≥ 30 complete 10 s windows), several scanners at once.
- Scanner states: Android LOW_LATENCY foreground unfiltered (raw bytes); Android screen off + FGS + service-UUID
  filter (BALANCED); iOS foreground service scan with duplicates; iOS background service scan.
- Advertiser states: iOS foreground / background screen on / background locked (if locking can be automated);
  Android foreground / FGS with screen off.
- Latency = first sighting − later of (advertiser start, scanner ready). Hit rate = fraction of 10 s windows
  (also 30 s, 60 s) with ≥ 1 sighting, from the first sighting on.
- **Criteria:** T10: recall ≥ 90 % at 10 s per state, else report the shortest window reaching 90 %.
  T8: hint present in ≥ 95 % of first sightings per scanner state. T2: 0x004C payload recorded (or absence
  documented), and whether it changes with the advertised UUID. T3: 20 trials per case (fg UUID scan, fg `nil`
  scan, bg UUID scan) with a latency distribution. T11: re-report rate after rescans every 10 s.
- Android screen-off cells: `dumpsys battery unplug` during the cell (reset after). Limitation: no Doze.
- Concrete runs (`host/d2.py`, added before the runs; analysis `analyze_discovery.py d2|t3`):
  - `d2-ios-*`: advertisers iPhone 15 then SE 2016, each fg / bg (`bg=1`, screen on) / lock (`bg=1 lock=1`, private
    GSEventLockDevice; if the lock does not happen, the cell counts as a second bg cell and "locked" stays unverified),
    300 s per cell. Scanners for the whole phase: Nothing 2 fg unfiltered active; Z Flip4 fg unfiltered passive (ADV-only
    bytes, T2/T1); edge 30 screen-off FGS service filter BALANCED; XCover 4 screen-off FGS Apple 0x004C filter BALANCED.
    Per cell (relaunched): iPhone X fg service scan with duplicates; SE 2020 and 12 mini bg service scan.
  - `d2-and-*`: advertisers Pixel 6 (`AdvertisingSet`) then S10e (`startAdvertising`), each fg / screen-off FGS
    (`delay_s=8`, screen off and battery unplugged before the start), 300 s per cell. Scanners: Nothing 2 fg unfiltered;
    edge 30 and XCover 4 screen-off FGS service filter; per cell iPhone 15 and 12 mini fg, SE 2016 and iPhone X bg.
  - `t11-*`: Pixel 6 fg idle (the stable peer) + Z Flip4 "ticker" (new `AdvertisingSet` every 10 s, so background
    scanners get callbacks); SE 2016, iPhone X, 12 mini, iPhone 15 (no duplicates) bg with `rescan_s=10`; SE 2020 bg
    without rescans as the baseline; 300 s. Metric: share of scan restarts followed by a report of the stable peer
    (identified as the identifier seen for > 60 s) before the next restart.
  - `t3-*`: iPhone 15 advertises in the background for the whole run. iPhone X, SE 2020, 12 mini run fresh scanner
    processes, 40 s each, rotating cases fg-uuid / fg-nil / bg-uuid; 7 trials per case per scanner (21 per case).
    Latency = first discovery − scan start, same clock. Not found within 40 s = censored.

## T1 iOS layout (run `t1-*`)

- iPhones: SE 2016 (iOS 15), iPhone X (16), 12 mini (27); more if cheap. One at a time, foreground, 30 s per case:
  (a) UUID only, (b) UUID + 8-char name, (c) UUID + 10-char name, (d) two 128-bit UUIDs, (e) no name key (= a).
- Captured by Pixel 6 passive scan (ADV only) and Nothing 2 active scan (ADV + scan response).
- **Pass:** raw bytes per case per iOS version; (a)/(e) contain no device name.

## D3 legacy vs extended (run `d3-*`)

- Pixel 6 cycles `legacy`, `ext1m`, `ext2m`, `coded`, 90 s each, 2 reps; extended steps carry 13 B hint + 60 B pad.
- Scanners: every installed Android (extended-capable ones scan with `setLegacy(false)`, all PHYs) and all five iPhones
  foreground, unfiltered.
- **Criteria:** per scanner and step, received = ≥ 1 sighting with the step's hint. Report the matrix.

## D4 iPhone 15 incoming-link closes (runs `d4-*`)

- `bakeoff.py phub` (current build): hub iPhone 15, ≤ 4 centrals with ≥ 1–2 iPhones, arrivals 10 s apart, 3 reps.
- Then one factor at a time: Android-only centrals; iPhone-only centrals; 30 s stagger.
- **Criteria:** reproduced = a hub-side close of an established inbound link at ≤ 4 links in ≥ 2 of 3 reps.
