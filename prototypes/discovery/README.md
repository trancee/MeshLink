# Discovery measurements (ticket 13): pre-registration and runbook

PROTOTYPE. Throwaway lab work. The app is the bake-off app in `../bearer-bakeoff` with `disc=` modes
(`androidApp/.../Discovery.kt`, `shared/src/iosMain/.../IosDiscovery.kt`, `host/discovery.py`).
Raw results go to `../bearer-bakeoff/host/results/<run>/` (gitignored).

This file is committed **before** each run. A criterion added after its run is marked "post hoc".

## Advertisement under test

**Change 2026-11-02 13:45 (project owner): layout E is primary on both platforms from run `d1-ios-*` on.**
`d1-rot-20261102` (12:24–13:59) used layout A; its address findings do not depend on the payload.

- **Layout E (both platforms, default `layout=E`):** legacy ADV, no local name, no scan response data:
  - fixed 32-bit service UUID `0x4D455348` ("MESH", base form `4D455348-0000-1000-8000-00805F9B34FB`);
  - one rotating 128-bit service UUID whose 16 bytes are placeholder metadata: 8 B random advertisement ID,
    4 B mesh tag (`ML01`), 1 B flags (Android: step << 4 | cycle; iOS: cycle), 3 B random. New at every start
    (iOS `restart_kind=same` keeps it, to test address vs data).
  - Budget: Android 3 + (2+4) + (2+16) = 27 B ≤ 31 B; iOS (2+4) + (2+16) = 24 B ≤ 28 B.
  - Extended steps (D3): the same two UUIDs plus `pad` B service data under MESH, so the ADV exceeds 31 B.
  - Scanners attribute by the first 8 B of the rotating UUID (`a:<hex>`); Android filters with `filter=mesh`
    (ScanFilter on the base-form UUID), iOS scans `withServices: [MESH]` (`filter=mesh`, default).
- Layout A stays selectable (`layout=A`) at no cost; it is no longer run unless stated.
- Owner questions E1–E5 → runs:
  - **E1** raw ADV bytes, AD types 0x05/0x04 and 0x07/0x06, iOS extras (TX power, name in scan response): T1 run (iPhones
    foreground, Nothing 2 active + Pixel 6 passive; Z Flip4 has no passive scan on API 36) and D2 `d2-and-*` (Android). Pass = bytes recorded per platform/iOS version.
  - **E2** Android MESH filter matches both platforms fg and screen-off; rotating UUID readable from ScanRecord: D2 cells
    (edge 30 / XCover 4 screen-off with `filter=mesh`). Pass = match in ≥ 1 cell per advertiser platform/state, and
    `a:` id present in ≥ 95 % of first sightings of foreground advertisers.
  - **E3** iOS `withServices:[MESH]` fg/bg finds the advertiser and returns the rotating UUID in
    `CBAdvertisementDataServiceUUIDsKey` or the overflow key; backgrounded iOS advertiser found by MESH alone, rotating UUID
    recoverable or not: D2 `d2-ios-*` (iOS scanners), `d2-and-*`, T3. Recorded per cell: share of sightings with `a:` id.
  - **E4** rotation by restart with a new UUID: D1-ctl (`d1-ios-*` iOS kinds `stop` = new UUID 2 s off, `swap` = stop and
    start at once with a new UUID, `same` = same UUID; Android controls rerun on layout E with new data per start).
    Also measured: restart → first scanner sighting of the new UUID; whether iOS scanners report the old UUID after
    the restart for the same identifier (stale cache). Pass/fail as T5/T6 below.
  - **E5** OS rejection/oddity for the unassigned 32-bit UUID: `adv_start` status/error on every start; any start error = finding.

### Layout A (used by `d1-rot-20261102` only)

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
| T9 | T9 | Android 17 scan allowance. Only Pixel 6 runs Android 17 (no second Android 17 OEM in the lab). Run `t9-*`, see below. |
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

## D1-ctl-E (run `d1-ctl-e-*`, 32 min, layout E, replaces the planned D1-ctl-ios)

- Every advertiser restarts every 120 s (≥ 15 cycles), layout E, no names. New rotating UUID at every start unless `same`.
- iOS: iPhone 15 (17) `stop`, SE 2016 (15) `stop`, SE 2020 (18) `swap` (stop + immediate start, new UUID),
  iPhone X (16) `same` (stop, 2 s, start with the same UUID). 12 mini (27) scans instead (fg, `filter=mesh`,
  duplicates) for E4 stale-cache: after a restart, does it report the old UUID under the identifier that carries the
  new one?
- Android on layout E: Z Flip4, Nokia X20, RMX3710, Nord 2, CPH2135 `new_set`; Pixel 4a, Mi Note 3, Blackview
  `stop`; OnePlus 6 `enable`; Nothing 1 `data`.
- Scanners: Nothing 2 and edge 30, unfiltered, LOW_LATENCY, `rescan_s=240`.
- Metrics: per restart, address new/same; **linkage gap** = first sighting of the new address − first sighting of the new
  UUID (1 s resolution), mixed sightings (old address with new UUID or the reverse), and, if the address stays, the
  linked window until the next address change. Restart → first sighting of the new UUID (host clock).
- **Criteria:** as T5/T6 (≥ 95 % new and no mixed sightings ⇒ "in step"); iOS layout-E rotation by restart works iff
  the address is new after 100 % of ≥ 15 restarts per iOS version. E5: any `adv_start` error.
- Also logged: D1 found iPhone 15, iPhone X and SE 2016 rotating within 5 s of each other at every change (intervals
  11.3, 19.3, 15.8, 18.0 min, identical on all three). This run records whether that repeats.

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
    300 s per cell. Scanners for the whole phase: Nothing 2 fg unfiltered active; (Pixel 6 passive dropped: Pixel 6 runs T9; ADV-only bytes come from T1); edge 30 screen-off FGS service filter BALANCED; XCover 4 screen-off FGS Apple 0x004C filter BALANCED.
    Per cell (relaunched): iPhone X fg service scan with duplicates; SE 2020 and 12 mini bg service scan.
  - `d2-and-*`: advertisers Pixel 6 (`AdvertisingSet`) then S10e (`startAdvertising`), each fg / screen-off FGS
    (`delay_s=8`, screen off and battery unplugged before the start), 300 s per cell. Scanners: Nothing 2 fg unfiltered;
    edge 30 and XCover 4 screen-off FGS service filter; per cell iPhone 15 and 12 mini fg, SE 2016 and iPhone X bg.
    **Deviation (17:15, before the run):** the 12 mini locked itself after T3 and `devicectl` refuses launches until
    someone unlocks it by hand ("device has not been unlocked recently"). SE 2020 (iOS 18) replaces it as the second
    fg iOS scanner (`--ios-fg ip15,se2020`). The T1 run lost the 12 mini for the same reason (T1 covers 4 iPhones).
    **Deviation (17:40, after `d2-and-20261102`):** the Android app truncated its JSONL at each relaunch, so the
    advertiser files of Pixel 6 and S10e kept only the screen-off cell; the fg cells cannot be attributed. Fix: append
    mode. The two fg cells rerun as `d2-and-fg-20261102` (`--states fg`, same scanners), after D3. The screen-off cells
    of `d2-and-20261102` stand (their scanner files and advertiser IDs are complete).
  - `t11-*`: Nokia X20 fg idle (the stable peer; Pixel 6 is busy with T9) + Z Flip4 "ticker" (new `AdvertisingSet` every 10 s, so background
    scanners get callbacks); SE 2016, iPhone X, 12 mini, iPhone 15 (no duplicates) bg with `rescan_s=10`; SE 2020 bg
    without rescans as the baseline; 300 s. Metric: share of scan restarts followed by a report of the stable peer
    (identified as the identifier seen for > 60 s) before the next restart.
  - `t3-*`: iPhone 15 advertises in the background for the whole run. iPhone X, SE 2020, 12 mini run fresh scanner
    processes, 40 s each, rotating cases fg-uuid / fg-nil / bg-uuid; 7 trials per case per scanner (21 per case).
    Latency = first discovery − scan start, same clock. Not found within 40 s = censored.
- **Deviation after `d2-ios-20261102` (15:30, before the rerun):** Android raw bytes showed that iPhones keep
  advertising the app's last background overflow bitmap (scan response of the system Nearby Info advertisement)
  after the app stopped or was killed: iPhone 15 still sent bitmap `00000000040000000000000000000008` 26 min after
  its last cell; its address matched its next foreground advertisement (`probe-ovf2`). SE 2016 and SE 2020 also sent
  stale local names `ML-<tag>` from earlier layout-A runs. So every iPhone that once advertised MESH in the
  background stays visible to a MESH-filtered iOS scan, and `d2-ios-20261102` mixes advertisers in iOS-scanner rows.
  - Fix (`d2.py scrub`): before each phase and after each bg/lock cell, every involved iPhone advertises layout A
    (bake-off UUID, no MESH bit) in the background for 15 s; `probe-scrub` showed this replaces the stale bitmap.
  - Android attribution of overflow sightings: the record's address must carry a bitmap with the MESH bit (last byte
    0x08: set in every layout-E background bitmap of both iPhones, while the rotating UUID's bit moves), and that
    bitmap must not have been on air in the 10 min before the advertiser's start. `name:` ids no longer count.
  - Rerun: `d2-ios2-20261102`, same cells and criteria. `d2-ios-20261102` stays as the record of the stale effect.

## T1 iOS layout (run `t1-*`, after T9 frees Pixel 6)

- `host/t1.py`: all five iPhones, one at a time, fresh launch per case, 30 s each, layout E:
  `e` (no name), `e-name8` (+ 8-char local name, 34 B > 28 B), `e-name10` (+ 10 chars), `e-uuid3` (+ a second
  128-bit UUID, 40 B), `e-bg` (backgrounded, no name), `e-again` (foreground, no name), then `post-stop` (app killed, 25 s).
- Captured by Pixel 6 passive scan (ADV PDUs only) and Nothing 2 active scan (ADV + scan response), both unfiltered
  `log=all`.
- Added after `smoke-bg2` (14:46): there the locked, backgrounded SE 2016 sent a scan response with local name
  `ML-se2016`, a name only set by an earlier run (layout A, `name=tag`); its device name is "iPhone SE (2016)". `e-bg` after
  the name cases checks whether iOS reuses an earlier app-set name.
- **Pass:** raw bytes per case per iOS version. Report: which UUIDs/name go to ADV vs scan response vs overflow when
  over budget; whether `e`/`e-bg`/`e-again` carry any name.

## D3 legacy vs extended (run `d3-*`)

- Deviation (before the run): the advertiser is Nothing Phone (1) (ext + coded, 1650 B), not Pixel 6, which runs T9.
- `host/d3.py`: Nothing 1 cycles `legacy`, `ext1m` (1M/1M), `ext2m` (1M primary, 2M secondary), `coded` (Coded/Coded),
  90 s each, 2 reps; layout E in every step; extended steps add 60 B service data under MESH (93 B ADV, > 31 B).
- Scanners: every other installed Android, foreground LOW_LATENCY unfiltered (extended-capable ones with
  `setLegacy(false)`, `PHY_LE_ALL_SUPPORTED`), and all five iPhones foreground unfiltered with duplicates.
- **Criteria:** per scanner and step, received = ≥ 1 sighting of a step's advertisement ID; n = 2 IDs per step.
  Report the matrix next to each scanner's caps. Analysis: `analyze_discovery.py d3`.

## D4 iPhone 15 incoming-link closes (runs `d4-*`)

- `bakeoff.py phub` (current build): hub iPhone 15, ≤ 4 centrals with ≥ 1–2 iPhones, arrivals 10 s apart, 3 reps.
- Then one factor at a time: Android-only centrals; iPhone-only centrals; 30 s stagger.
- **Criteria:** reproduced = a hub-side close of an established inbound link at ≤ 4 links in ≥ 2 of 3 reps.
- Concrete runs (args as step 4: `phy=2m connect_retries=3`, `--timeout 600`, so each central holds 500 s):
  - `d4-mix-r{1,2,3}`: centrals Pixel 6, Nothing 2, iPhone X, SE 2016 (step 4 order: Androids first), 10 s apart.
  - If reproduced: `d4-and-r*` (Pixel 6, Nothing 2, S10e, XCover 4), `d4-ios-r*` (iPhone X, SE 2016, SE 2020,
    12 mini), `d4-mix30-r*` (mixed set, `--stagger 30`); 3 reps each, stop early once a factor is clear.
  - If not reproduced in 3 reps: report "not reproduced with build `<sha>`"; the old build stays a candidate cause.
  - Close = hub record of an inbound link ending (`unsubscribed`/`closed`) before the central's `hold_end`.
  - Logs: hub `idevicesyslog`/`log collect` is attempted for `bluetoothd` lines around each close; listed, not shipped.

## T9 Android 17 scan allowance (run `t9-*`, 2 h, Pixel 6 only)

- `host/t9.py`: Pixel 6 FGS scan, `filter=mesh`, BALANCED, one scan session without app restarts, 60 min screen on
  (charging) then 60 min screen off with `dumpsys battery unplug`. nova 9 advertises layout E idle the whole time.
- Host polls `dumpsys bluetooth_manager` (our app's LE Scanner Map entry) every 60 s: ongoing scan mode, app importance,
  per-mode durations, suspend time, results screen on/off. Snapshots kept raw (diagnostic, not shipped).
- Runs in parallel with D1-ctl-E, d2-ios, t11 (stable peer Nokia X20 instead of Pixel 6), t3; `d2-and` runs after T9. Passive scan → T1 only.
- **Criteria:** pass = mode timeline recorded. Downgrade observed = ongoing mode leaves BALANCED, or results stop for
  ≥ 5 min while the beacon runs. Report the time of each change vs the desk note's 24 min/h allowance.

