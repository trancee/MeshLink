# Energy on real phones (ticket 23): pre-registration and runbook

PROTOTYPE. Throwaway lab work on branch `prototype/energy` (from `prototype/discovery`), never merged.
Ticket: `.scratch/meshlink-v1/issues/23-energy-on-devices.md` (main worktree). This file is committed **before**
the first measurement. Any later change is recorded under [Amendments](#amendments) with its time and reason.
The output is measurements and a results note (`RESULTS.md`); the power-policy decision is made elsewhere.

## Question

What does MeshLink's radio behavior cost in energy on real phones, per scenario: advertising and scanning duty
per density class (ADR 0008 / 0010), idle links (per link-hour), bulk transfer (per KB), relaying (per message
rate), and an overnight screen-off run? The numbers replace the routing simulation's assumed power index
(`0.6 × scan duty + 0.4 × advertising duty`, slow advertising = 0.4) and back battery claims.

## Devices

| Role | Device | OS | How measured |
|---|---|---|---|
| Device under test (DUT), Android | Google Pixel 6 (`pixel6-wifi:5555`, TCP adb) | Android 17, build CP3A.261005.002.A1 | On-device power rails (ODPM) via Perfetto `android.power` |
| Peers (peripherals, GATT server + CoC server) | Nothing Phone (2) `nothing2`, Galaxy Z Flip4 `flip4`, Gigaset GX6 `gx6`, OPPO CPH2359 `cph2359`, Huawei nova 9 `nova9` (USB) | Android 12–16 | not measured |
| MESH advertiser for scan cells | Xiaomi Mi 8 `mi8` (USB), layout E, fast | Android 10 | not measured |
| Cross-check (BT controller time only) | moto edge 30 fusion `moto30`, Galaxy S10e `s10e`, Galaxy A16 `lab-wifi:5555` | Android 14 / 12 / 17 | `dumpsys batterystats` Bluetooth idle/Rx/Tx time |
| iOS (later phase, needs a person) | iPhone 15 `ip15` (iOS 17.6.1); optional iPhone SE 2016 (iOS 15.8) | | Battery gauge `AppleSmartBattery` `InstantAmperage` × `Voltage` over Wi-Fi (libimobiledevice), unplugged |

Peers may be swapped for another 2M-capable Android 10+ phone if one fails; the swap is recorded in the run log.

## Method (Android DUT)

- **App:** the bake-off app (`../bearer-bakeoff`), new `energy=` mode in a `connectedDevice` foreground service
  (`EnergyService`), started from the shell with `am start-foreground-service`, so no activity and no screen.
- **Rails:** Perfetto `android.power` with `collect_power_rails: true`, `battery_poll_ms: 1000`, run on the phone
  in the background for the whole window plus setup. Rails are cumulative energy counters (µWs). Primary rail:
  `power.rails.wifi.bt` (combined Wi-Fi + Bluetooth). Also reported: CPU rails (`cpu.big`, `cpu.mid`,
  `cpu.little`) and the sum of all rails ("all rails").
- **Window:** the app logs `window_start` and `window_end` with `SystemClock.elapsedRealtimeNanos()` (the Perfetto
  boottime clock) after setup is done (links up, adverts and scans started). Measured window = **600 s**. Energy
  per rail = counter at end − counter at start (linear interpolation between samples); mean power = energy / time.
- **Wi-Fi off:** Wi-Fi shares the rail with Bluetooth. Every window runs from an on-device script started with
  `nohup sh … &`: start Perfetto, `svc wifi disable`, screen off (`input keyevent KEYCODE_SLEEP`), start the app,
  wait, stop the app, `svc wifi enable`. The host then reconnects (`adb connect pixel6-wifi:5555`) and pulls
  the trace and the app log. The procedure is validated once with a 60 s trial before any measured window.
- **Constant state:** screen off in every window; Pixel on its wireless charger (rails are valid while charging);
  automatic system updates off; Bluetooth on; Wi-Fi off. Battery level, plug state, battery temperature, screen
  state and Wi-Fi state are logged at window start and end.
- **Baseline (scenario 1):** same procedure, `energy=idle`: the service runs, Bluetooth on, no advertising, no
  scanning, no links. A baseline window runs **before the first scenario window and after every 3 scenario
  windows**. The MeshLink cost of a scenario window = its mean power − the mean of the two baseline windows that
  bracket it (the nearest one if only one exists).
- **Order:** each repetition runs all scenario windows below in a seeded random order (seed = repetition number
  1, 2, 3), with the baselines interleaved. 3 repetitions.
- **Links:** the DUT is always the GATT central and connects with the 1M|2M initiating mask (ADR 0009), retries
  133/147 up to 3 times, MTU 517, then (2M cells, and every link where both phones report 2M) requests 2M.
  `want=` picks peers by model. The DUT is never the peripheral in these cells (limitation).
- **Connection priority:** idle links and relay: no `requestConnectionPriority` call (Android default, BALANCED);
  bulk: `CONNECTION_PRIORITY_HIGH` as in the bake-off. The connection interval is recorded where `dumpsys
  bluetooth_manager` shows it.

## Scenarios (Pixel 6 DUT), each 600 s × 3 repetitions

| # | id | What the DUT does |
|---|---|---|
| 1 | `base` | idle (above) |
| 2 | `adv_fast`, `adv_slow` | ADR 0010 layout E: legacy, connectable `AdvertisingSet` on 1M, flags + 32-bit MESH UUID + 128-bit rotating UUID (16 B metadata), no name, no scan response, TX power HIGH (as the discovery prototype). Fast = `INTERVAL_LOW` (160 × 0.625 ms = 100 ms, ≙ `ADVERTISE_MODE_LOW_LATENCY`); slow = `INTERVAL_HIGH` (1600 × 0.625 ms = 1 s, ≙ `ADVERTISE_MODE_LOW_POWER`). A new set (new identifier) every 5 min (ADR 0008 amendment). No GATT server. |
| 3 | `scan_low`, `scan_med`, `scan_high` | ADR 0008 "scan every 2 / 4 / 6 s", read as the routing simulation does: one scan per 2 s tick, so scan duty 1, ½, ⅓. Filtered scan (ScanFilters: MESH UUID; Apple `0x004C` type `0x01` overflow, per the ADR 0010 screen-off rule), `SCAN_MODE_LOW_LATENCY`, active, legacy + extended if supported. low = one continuous scan; med = 4 s on / 4 s off; high = 4 s on / 8 s off. **Deviation from a literal "2 s scan every 4 s":** AOSP allows 5 `startScan` per 30 s per app; a 4 s or 6 s period with a 2 s window needs 7.5 or 5 starts per 30 s and is throttled. The 8 s and 12 s periods keep the simulated duty with 3.75 and 2.5 starts per 30 s. A 60 s functional check of the literal 2-of-4 and 2-of-6 schedules (scan results per burst, no energy) is reported alongside. |
| 3b | `scanmode_bal`, `scanmode_lp` | platform duty knobs instead of start/stop: one continuous filtered scan in `SCAN_MODE_BALANCED` and `SCAN_MODE_LOW_POWER` (the in-use window/interval is read from `dumpsys bluetooth_manager`). |
| 4 | `comb_low`, `comb_med`, `comb_high` | scenario 3 schedule + advertising: low = `scan_low` + fast; medium = `scan_med` + fast; high = `scan_high` + slow. |
| 5 | `idle_gatt_{1,3,5}`, `idle_coc_{1,3,5}` | N links to N peers, no traffic. GATT: connected and subscribed (CCCD). CoC: GATT link plus an unbonded (insecure) L2CAP CoC opened on the PSM read over GATT (ADR 0009). |
| 6 | `bulk_{gatt,coc}_{1m,2m}_{4k,128k}` | the DUT sends 4 KiB or 128 KiB messages back to back to the Nothing Phone (2) for the whole window (GATT write-without-response; CoC stream). PHY requested 1M or 2M (both phones support 2M). At the end the peer acknowledges the bytes it received. Throughput = acknowledged bytes / window. |
| 7 | `relay_{1,5,20}` | the source peer sends ~200 B messages at 1, 5 or 20 msg/s to the DUT on one CoC link; the DUT forwards each one on a second CoC link to the sink peer (receive on one link, re-send on another). **Stand-in:** the bake-off app has no MeshLink routing or crypto; the DUT only copies bytes, so CPU rails show framing and Bluetooth stack work, not routing/crypto. Delivered count acknowledged by the sink. |
| 8 | `overnight` | one 8 h run, screen off, medium duty (`scan_med` + fast advertising) with 2 idle CoC links. To let Doze engage on the charger, the framework is told the phone is unplugged (`dumpsys battery unplug`; reset at the end). The app logs device-idle mode changes and scan results per minute (the Mi 8 advertises MESH all night); a peer scanner logs the DUT's advertisement per minute. Rails polled every 10 s; `dumpsys batterystats` at the end. |

Scenario count per repetition: 2 + 3 + 2 + 3 + 6 + 8 + 3 = 27 scenario windows + 10 baselines.

## Cross-check (Android, controller time, not energy)

moto edge 30 fusion, Galaxy S10e, Galaxy A16 as DUT, one window each (600 s, n = 1): `base`, `scan_low`,
`idle_gatt_3`. `dumpsys batterystats --reset` before each window; after it, read "Bluetooth Idle / Rx / Tx time"
and the model-based "bluetooth" mAh estimate (labeled model-based). Wi-Fi stays on (controller time is BT-only).
Reported as ms per window and as the share of the window, scenario minus baseline.

## iOS (later phase, needs a person; not run in phase A)

iPhone 15, unplugged, auto-lock Never, app in the foreground at minimum brightness: `base`, `adv` (iOS
foreground, interval set by iOS), `scan_low` (continuous, filtered MESH), `comb_med` (4 s on / 4 s off + adv),
`idle_gatt_3` (iPhone as peripheral to three Android centrals), 600 s each × 3 with interleaved baselines; then
one 8 h background run (`comb_med`, app backgrounded, screen locked). Power = |InstantAmperage| × Voltage per
gauge update; the host sampler polls `idevicediagnostics -n ioregentry AppleSmartBattery`. Sign of
`InstantAmperage` and the gauge update cadence are measured first (plugged, then unplugged).

## Analysis

Per scenario and device: mean ± SD (and min–max) over repetitions of window power − bracketing baseline (mW),
for `wifi.bt` and for all rails; n. Derived:

- **Energy per KB** (bulk, relay): ΔP × 600 s / KB acknowledged (mJ/KB), on `wifi.bt` and on all rails.
- **Per link-hour** (idle links): ΔP / N (mW ≙ mWh per link-hour); also the slope over N = 1, 3, 5.
- **Battery share:** ΔP / (3,000 mAh × 3.85 V = 11,550 mWh) × 100 = % per hour.
- **Simulation index check:** measured `scan_*`, `adv_*`, `comb_*` deltas next to the assumed index.

## What makes a window invalid

- Wi-Fi was not off for the whole window (script log), or the `wifi.bt` track is missing.
- The app has no `window_end`, logs a failure, or crashed (no `done`).
- A peer link was lost during the window, fewer links came up than the cell needs, or the bulk/relay ack is missing.
- The screen turned on, or the plug state changed (Android: plug type at start ≠ end; iOS: `ExternalConnected`
  or `IsCharging` true at any sample).
- The window is shorter than 590 s.

An invalid window is rerun once at the end of its repetition; both are reported. Baselines that are invalid are
rerun before the next scenario window.

## Runbook

```sh
cd prototypes/bearer-bakeoff
export JAVA_HOME=/opt/homebrew/opt/openjdk@21 ADB_SERVER_SOCKET=tcp:adb-server:5037
python3 host/bakeoff.py build android && python3 host/bakeoff.py install pixel6 nothing2 ...
python3 ../energy/host/energy.py trial          # 60 s Wi-Fi-off validation
python3 ../energy/host/energy.py run --rep 1    # one repetition (shuffled, baselines interleaved)
python3 ../energy/host/analyze.py               # → results/summary.json, tables for RESULTS.md
```

## Amendments

**A1 (2026-11-03 20:50 CET, after the first 60 s trial, before any measured window): wake lock + script order.**
The first trial (`results/trial/trial-34369.*`) showed that with the screen off and no wake lock the Pixel
suspends between Bluetooth events, and both coroutine `delay()` and shell `sleep` stop advancing: the 60 s window
ran 403 s, the 4 s scan gaps stretched to 11–121 s (logged duty 0.08 instead of 0.5), and the script's
post-window steps stretched so Wi-Fi (and TCP adb) only came back after ~16 min. Perfetto `--background` printed
no PID on this build. Changes:
- Every measured window (baselines included) holds a `PARTIAL_WAKE_LOCK` in `EnergyService` for its whole run,
  so duty schedules and the window length are honored. This raises the absolute CPU/memory rails in every window
  (it cancels in window − baseline) and hides the CPU cost of waking up for scan results/link events (limitation).
- The overnight run passes `wakelock=0` (unassisted behavior is what it is meant to show); its window end uses a
  boottime deadline.
- The on-device script starts the app (wake lock) before Wi-Fi off and screen off, re-enables Wi-Fi immediately
  after the app is done, stops Perfetto via `$!`, and times out on wall-clock (`date +%s`), not loop counts.
- New validity rule: scan cells with a start/stop schedule are invalid if fewer than 90 % of the expected bursts ran.
The second trial (`trial-35592`) passed: window 60.05 s, duty 0.50, Wi-Fi back ~1 s after the app finished.

**A2 (2026-11-03): cross-checks ran in parallel and before the wake-lock build.** The three cross-check phones
ran their windows at the same time (separate peer sets: moto ← Nothing 2 / Flip4 / GX6; S10e ← CPH2359 /
nova 9 / OnePlus 6; A16 ← Nord 2 / RMX3710 / CPH2251; separate run tags), while the Pixel was idle, on the
build without the wake lock (A1). Wi-Fi on, screen as found. No MESH advertiser in their `scan_low` window.
Controller time from batterystats does not depend on the duty timer (only continuous scans and idle links).
batterystats only accumulates on battery: the first run (phones plugged) gave all-zero BT times and is voided
(`results/crosscheck/void-plugged/`). The rerun simulates unplug (`dumpsys battery unplug` +
`dumpsys batterystats --reset` before each window, `dumpsys battery reset` after). In that rerun the reset
happened before peers connected, so `idle_gatt_3` includes link setup (connect scan + retries); the runner was
later changed to reset at `window_start` (`--only idle_gatt_3` reruns, if any, are labelled). The A16 screen was
on (interactive) during its windows; the S10e `scan_low` window saw 0 scan results; the moto reports Idle time ≈ 0
(its controller does not report idle), so only Rx/Tx are comparable there.

**A3 (2026-11-03): iOS gauge source.** iOS 15 (SE 2016) returns an empty `AppleSmartBattery`; the same keys
(`InstantAmperage`, `Voltage`, `ExternalConnected`, `IsCharging`, `UpdateTime`) are under `AppleARMPMUCharger`.
The sampler tries both. Measured plugged cadence: `UpdateTime` advances every 20 s (iPhone 15) and 20–21 s
(SE 2016) while polled every 2 s (`results/ios/plugged-cadence-*.jsonl`); sign of `InstantAmperage` unplugged
is still unknown (needs the human).

**A4 (2026-11-03, after rep 1 had started; no window data changed): secondary robust wifi.bt metric.** Rep-1
windows show short system bursts on `wifi.bt` (~200–300 mW for ~3 s, e.g. ~19 s before every window end ≈ 10 min
after `svc wifi disable`, plus occasional others) in every scenario including baselines, and baseline plateaus
that differ between windows (≈2.5 vs ≈6.2 mW). The pre-registered metric (window mean minus bracketing
baselines) is unchanged and remains primary; `analyze.py summary` additionally reports the same delta computed
on the median of the per-minute `wifi.bt` power, which is robust to those bursts. Both are reported.

**A5 (2026-11-04, after rep 1): GATT bulk flow control.** All four rep-1 GATT bulk windows were invalid (`bulk ack
missing`). Root cause (prototype, verified with Wi-Fi-on probes, `results/probe/`): on the Pixel 6 (Android 17)
`onCharacteristicWrite` for write-without-response completes long before the data is on air, so the sender
queued ~170 KiB/s while the link carried ~80–90 KiB/s; the stack queue grew for the whole window and the peer was
still receiving ~1 min after a 60 s probe ended, so the 60 s ack timeout expired. Rep-1 GATT bulk windows are kept
as invalid (power reflects a saturated link but throughput is unknown). From here GATT bulk uses the bake-off knob
`write_ack_every=256` (every 256th write is a write-with-response, bounding the queue to ≤256 writes ≈ 123 KiB):
probes gave 79.6 KiB/s at 1M and 81.5 KiB/s at 2M with the ack 82–114 ms after window end. Rep 1 GATT bulk is
re-run with this setting (`run --rep 1 --only bulk_gatt_*`, its own interleaved baselines) before reps 2 and 3.
The rep-1 rerun phase that had started with the old setting was stopped by the operator (one window discarded,
not analysed). Also recorded: `wifi_scan_always_enabled=1` and `ble_scan_always_enabled=1` on the Pixel (left as
found, constant for all windows).
