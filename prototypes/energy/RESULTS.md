# Energy on real phones (ticket 23): results

PROTOTYPE results, **measured** on a Google Pixel 6 (Android 17, build CP3A.261005.002.A1) from 2026-11-03 21:42 to
2026-11-04 21:42 CET (overnight run until 2026-11-05 05:48), plus three cross-check phones on 2026-11-03/04. Method, validity rules and amendments
A1–A6: [`README.md`](README.md) (pre-registered before the first measurement). Raw per-window data:
`results/windows.final.jsonl` (recomputed with the final analysis, A6), app logs `results/windows/`, runner logs
`results/rep{1,2,3}.out`; Perfetto traces (~1–2 MB each) in the session's `files/energy-raw/`. Tables:
`python3 host/analyze.py reanalyze && python3 host/analyze.py summary` (output also in `results/summary.md`).
These are measurements, not a power-policy decision.

## How to read the numbers

- **Δ** = window mean power − mean of the two bracketing baseline windows (screen off, Wi-Fi off, Bluetooth on,
  app idle, wake lock held). n = 3 repetitions × 600 s for every scenario; baseline windows n = 34.
- **`wifi.bt`** is the Pixel's combined Wi-Fi + Bluetooth rail (Wi-Fi off) — the best available proxy for the
  Bluetooth radio. Baseline: **5.2 ± 1.5 mW** absolute (it switches between ≈2 and ≈6 mW plateaus for unknown
  system reasons, and every window has a ~3 s, ~250 mW system burst ≈10 min after Wi-Fi off; A4). So effects
  below ~±2 mW are not resolvable.
- **All rails** = sum of every ODPM rail (CPU, memory, modem, display, …). Baseline 354 ± 64 mW; the window-to-
  window spread (mostly modem and CPU background) is much larger than most MeshLink effects, so the all-rails Δ is
  only meaningful for bulk and GATT CPU cost. CPU Δ = big + mid + little rails.
- **%/h** = Δ / (3,000 mAh × 3.85 V = 11,550 mWh) × 100.
- The wake lock held in every measured window (A1) means the cost of *waking up* for scan results or link events
  is not in these numbers (the overnight run, without the wake lock, is where it shows).

## Android results (Pixel 6, measured, n = 3 each)

### Advertising and scanning (scenarios 2–4)

| Scenario | Shape | `wifi.bt` Δ mW ± SD [min–max] | median-minute Δ mW | All-rails Δ mW | %/h (`wifi.bt`) |
|---|---|---|---|---|---|
| `adv_fast` | legacy connectable `AdvertisingSet` (MESH 32-bit UUID + rotating 128-bit id), `INTERVAL_LOW` = 100 ms, TX HIGH | −0.5 ± 1.4 [−1.5–1.1] | −0.6 | −8 ± 12 | ≈ 0 |
| `adv_slow` | same, `INTERVAL_HIGH` = 1 s | −0.6 ± 0.5 [−1.1–−0.1] | −1.4 | −76 ± 80 | ≈ 0 |
| `scan_low` | one continuous filtered LOW_LATENCY scan (duty 1) | 10.0 ± 5.2 [4.0–13.4] | 12.0 | −6 ± 12 | 0.09 (**degrades**, see below) |
| `scan_med` | 4 s on / 4 s off (duty 0.50) | **27.8 ± 0.3** [27.4–28.0] | 28.1 | 40 ± 7 | 0.24 |
| `scan_high` | 4 s on / 8 s off (duty 0.33) | **18.3 ± 2.6** [15.7–20.8] | 19.0 | 44 ± 24 | 0.16 |
| `scanmode_bal` | continuous, BALANCED | 4.5 ± 3.5 [0.7–7.5] | 6.4 | 112 ± 87 | 0.04 (degrades) |
| `scanmode_lp` | continuous, LOW_POWER | 0.5 ± 1.3 [−0.6–2.0] | 1.1 | −18 ± 24 | ≈ 0 |
| `comb_low` | `scan_low` + fast adv | 11.6 ± 5.0 [8.5–17.4] | 10.1 | −2 ± 33 | 0.10 (degrades) |
| `comb_med` | `scan_med` + fast adv | 24.6 ± 7.9 [15.4–29.5] | 25.1 | 54 ± 56 | 0.21 |
| `comb_high` | `scan_high` + slow adv | 18.5 ± 2.2 [16.3–20.7] | 19.8 | 49 ± 183 | 0.16 |

Findings:

1. **Advertising is not measurable** on this rail at either speed (|Δ| < 1 mW, inside the baseline noise).
2. **Duty-cycled LOW_LATENCY scanning scales with duty: ≈ 55 mW × scan duty** (27.8 / 0.50 = 55.5;
   18.3 / 0.33 = 55.0). Per-minute power is flat across the window (e.g. `e3-scan_med`: 30–34 mW absolute every
   minute).
3. **A continuous scan does not stay at full duty with the screen off.** Per-minute `wifi.bt` (absolute, mW; the
   last minute contains the A4 system burst):

   | Window | min 0–4 | min 5–8 | scan results (600 s) |
   |---|---|---|---|
   | `e1-scan_low` | 23, 18, 4, 4, 5 | 4, 5, 4, 5 | 1,497 |
   | `e2-scan_low` | 23, 33, 29, 33, 25 | 6, 6, 6, 6 | 4,492 |
   | `e3-scan_low` | 24, 32, 29, 26, 23 | 6, 6, 6, 8 | 4,431 |
   | `e1-comb_low` | 24, 33, 29, 14, 5 | 5, 6, 6, 6 | 2,859 |
   | `e2-comb_low` | 24, 40, 32, 31, 24 | 9, 13, 12, 12 | 4,613 |
   | `e3-comb_low` | 26, 34, 22, 4, 7 | 5, 6, 5, 5 | 2,491 |
   | `e1/e3-scanmode_bal` | 12, 12–15, 12, 11–12, 11–12 | 6, 6–7, 6–7, 6 | 1,701 / 1,827 |

   The scan runs at ~25–33 mW for 2–5 min, then drops to baseline level and the scan-result rate falls ~15×
   (e.g. `e1-scan_low` ticks: +694 results in minute 1, ~+45/min after the drop). Scans that are restarted every
   8 or 12 s (`scan_med`, `scan_high`) did not show this (one exception: `e3-comb_med` dropped from ~31 to ~14 mW
   at minute 4 with fewer results, 2,284 vs ~3,600). The mechanism is **not identified** (the filters are set, so
   it is not the documented 5-min unfiltered→opportunistic downgrade; a screen-off scan-mode downgrade by the
   Bluetooth stack is the likely candidate). Consequence: "scan duty 1" (low density) is **not** a steady state on
   Android with the screen off; the pre-drop level (~20–28 mW Δ) is the cost while it lasts.
4. **Simulation index check.** The simulation assumes index = 0.6 × scan duty + 0.4 × adv duty (slow adv = 0.4),
   i.e. low 1.0 / medium 0.7 / high 0.36, relative 1 : 0.7 : 0.36. Measured `comb_*` Δ: medium 24.6, high 18.5 mW
   (ratio 1 : 0.75); low is not steady (11.6 averaged, ~20–28 while undegraded). Measured cost is ≈ **55 mW ×
   scan duty + ~0 × advertising**; advertising weight 0.4 is not supported by this device.
5. **Throttling** (`results/throttle/`, 60 s functional, no energy): literal 2 s-of-4 s scheduling ran 16 bursts,
   5 of them empty (AOSP 5 starts / 30 s limit); 2-of-6, 4-of-8, 4-of-12 had no empty bursts.

### Idle links (scenario 5)

DUT is GATT central to N peers, 1M|2M initiating, MTU 517, no connection-priority request (Android default
interval), no traffic. Connection interval not recorded.

| Scenario | `wifi.bt` Δ mW ± SD [min–max] | median-minute Δ | `wifi.bt` mWh per link-hour | All-rails Δ mW | %/h (`wifi.bt`) |
|---|---|---|---|---|---|
| `idle_gatt_1` | −1.8 ± 0.4 [−2.1–−1.3] | −1.4 | ≈ 0 | 12 ± 13 | ≈ 0 |
| `idle_gatt_3` | 0.9 ± 2.1 [−1.3–2.8] | 1.1 | 0.3 | 40 ± 90 | 0.01 |
| `idle_gatt_5` | 2.8 ± 3.8 [0.5–7.3] | 2.9 | 0.6 | 50 ± 84 | 0.02 |
| `idle_coc_1` | 0.8 ± 2.3 [−1.7–2.6] | 1.2 | 0.8 | 22 ± 35 | 0.01 |
| `idle_coc_3` | 1.4 ± 2.0 [−0.4–3.5] | 2.2 | 0.5 | −21 ± 36 | 0.01 |
| `idle_coc_5` | 3.6 ± 1.8 [1.6–5.0] | 3.0 | 0.7 | −10 ± 13 | 0.03 |

An idle link costs **≲ 1 mW (≲ 1 mWh per link-hour) on `wifi.bt`**, at the noise floor; 5 links ≈ 3–4 mW
(≈ 0.03 %/h). GATT and CoC are indistinguishable. The all-rails per-link numbers are noise (negative values) and
should not be used.

### Bulk transfer (scenario 6)

DUT sends to one peer (Nothing Phone (2)), CONNECTION_PRIORITY_HIGH, messages of 4 KiB or 128 KiB back-to-back for
600 s; KiB counted only when acknowledged. GATT uses `write_ack_every=256` (A5). Throughput is the achieved,
acknowledged rate.

| Scenario | KiB/s | `wifi.bt` Δ mW ± SD | **mJ/KiB `wifi.bt`** | All-rails Δ mW ± SD | CPU Δ mW | mJ/KiB all rails | %/h (`wifi.bt` / all) |
|---|---|---|---|---|---|---|---|
| `bulk_coc_1m_4k` | 96.4 | 73.5 ± 0.8 | 0.76 | 117 ± 12 | 29 | 1.21 | 0.64 / 1.0 |
| `bulk_coc_1m_128k` | 104.1 | 73.0 ± 2.8 | 0.70 | 220 ± 113 | 33 | 2.10 | 0.63 / 1.9 |
| `bulk_coc_2m_4k` | 96.5 | 72.8 ± 1.7 | 0.76 | 124 ± 1 | 34 | 1.28 | 0.63 / 1.1 |
| `bulk_coc_2m_128k` | 100.3 | 71.1 ± 1.5 | 0.71 | 75 ± 125 | 39 | 0.73 | 0.62 / 0.7 |
| `bulk_gatt_1m_4k` | 80.2 | 66.6 ± 2.3 | 0.83 | 230 ± 67 | 111 | 2.86 | 0.58 / 2.0 |
| `bulk_gatt_1m_128k` | 81.3 | 66.9 ± 1.2 | 0.82 | 165 ± 8 | 72 | 2.02 | 0.58 / 1.4 |
| `bulk_gatt_2m_4k` | 80.7 | 66.8 ± 1.1 | 0.83 | 179 ± 23 | 75 | 2.23 | 0.58 / 1.6 |
| `bulk_gatt_2m_128k` | 82.1 | 68.0 ± 1.5 | 0.83 | 222 ± 98 | 73 | 2.71 | 0.59 / 1.9 |

- Radio cost at saturation is **≈ 67–73 mW**, i.e. **≈ 0.7 mJ/KiB (CoC) and ≈ 0.83 mJ/KiB (GATT)** on `wifi.bt`
  (≈ 85 nJ/bit; ~0.6 %/h of a 3,000 mAh battery while saturating).
- **1M vs 2M made no difference** here: throughput was the same (~100 KiB/s CoC, ~81 KiB/s GATT), so the link
  was limited by the peer or connection events, not air time; this does not show 2M's per-bit saving.
- **GATT costs ~2× the CPU of CoC** (+72–111 mW vs +29–39 mW CPU rails) for ~20 % less throughput: per KiB the
  whole-device cost of GATT is ≈ 2–2.9 mJ vs ≈ 0.7–2.1 mJ for CoC.
- Message size (4 vs 128 KiB) has no consistent effect.

### Relaying (scenario 7)

Stand-in (no relay path in the bake-off app): the DUT receives ~200 B messages from peer A on one CoC link and
re-sends each on a second CoC link to peer B (receive → re-send, no routing table or crypto). Rates are offered by
peer A; all messages were forwarded within the window.

| Scenario | Forwarded | `wifi.bt` Δ mW ± SD | median-minute Δ | CPU Δ mW | All-rails Δ mW ± SD | mJ/msg `wifi.bt` | %/h (`wifi.bt`) |
|---|---|---|---|---|---|---|---|
| `relay_1` (1 msg/s) | 600 | 3.4 ± 1.7 | 3.4 | 6.2 | 38 ± 68 | 3.4 | 0.03 |
| `relay_5` (5 msg/s) | 3,000 | 3.3 ± 1.3 | 3.1 | 4.2 | 27 ± 31 | 0.66 | 0.03 |
| `relay_20` (20 msg/s) | 12,001 | 7.0 ± 1.3 | 6.3 | 11.9 | 25 ± 5 | 0.35 | 0.06 |

Relaying small messages is cheap: ~3 mW for two links carrying 1–5 msg/s (indistinguishable from the links
themselves), ~7 mW radio + ~12 mW CPU at 20 msg/s. Marginal radio cost from 5 to 20 msg/s: (7.0 − 3.3) mW /
15 msg/s ≈ **0.25 mJ per forwarded 200 B message**. Real relaying adds routing and crypto CPU that the stand-in
does not do.

## Cross-check: Bluetooth controller time (batterystats, measured)

One 600 s window each, Wi-Fi on, simulated unplug (A2). Seconds in the window (Idle / Rx / Tx); not energy.

| Phone (OS) | Window | Idle s | Rx s | Tx s | Notes |
|---|---|---|---|---|---|
| Galaxy A16 (Android 17) | `base` | 541.9 | 85.3 | 1.8 | screen on during these windows |
| Galaxy A16 | `scan_low` | 298.6 | 326.6 | 4.5 | |
| Galaxy A16 | `idle_gatt_3` (reset before link setup) | 403.0 | 300.0 | 8.2 | includes connect scan |
| Galaxy A16 | `idle_gatt_3` (reset at `window_start`) | 61.1 | 540.5 | 8.1 | valid, 3/3 links; GMS continuous scan dominates Rx |
| moto edge 30 fusion (Android 14) | `base` | 0.1 | 11.4 | 13.2 | controller does not report Idle |
| moto edge 30 fusion | `scan_low` | 177.3 | 127.6 | 29.1 | |
| moto edge 30 fusion | `idle_gatt_3` (reset before link setup) | 0.0 | 57.9 | 55.9 | rerun invalid (1/3 links) |
| Galaxy S10e (Android 12) | `base` | 19.8 | 23.4 | 0.4 | |
| Galaxy S10e | `scan_low` | 265.2 | 348.7 | 2.9 | 0 scan results |
| Galaxy S10e | `idle_gatt_3` (reset before link setup) | 492.5 | 21.7 | 3.2 | rerun invalid (1/3 links) |

Consistent with the Pixel: a continuous scan keeps the controller receiving ~50–60 % of the time on the A16 and
S10e, and three idle links add only ~5–8 s of Tx per 10 min. The controller counters are confounded by system
scans (GMS) and missing Idle reporting (moto), so they cross-check direction, not magnitude.

## Overnight (scenario 8)

Pixel 6, 8 h, screen off, simulated unplug (Doze allowed), **no wake lock**, medium-density duty (4 s on / 4 s
off scan + fast advertising) and 2 idle CoC links (Nothing Phone (2), Galaxy Z Flip4), Perfetto poll 10 s; Nothing
Phone (1) was meant to scan for the DUT's adverts. Ran 2026-11-04 21:42 → 2026-11-05 05:48 CET (`night-24911`,
window 29,037 s = 8.07 h, valid; `results/overnight/`, batterystats/deviceidle/trace in `files/energy-raw/`).

| Measured | Value |
|---|---|
| Doze | deep idle (`DEVICE_IDLE_MODE_CHANGED` true) **2 min** after window start; maintenance exits at +1.0 h, +3.0 h, +7.0 h (~1 min each), otherwise idle all night |
| Links | both CoC links (2M) stayed up all 8 h (0 failures) |
| Scanning | app asked for 4 s on / 4 s off; it ran **623 bursts** (3,630 expected), each still ~4.0 s, but gaps averaged **30.2 s** (the coroutine timer only advances when something wakes the CPU): logged duty **0.12**; batterystats: app scan time 42 min 55 s / 623 scans (**8.8 %**). Scan results kept arriving all night (23,178 total, ~2,500–3,400/h). |
| Advertising | rotation (planned every 300 s) happened every ~30 min (17 `adv_start`); the advert set itself is controller-run. **Not independently verified on air**: the Nothing Phone (1) scanner was offline (`lab-wifi:5555` not in `adb devices`), so its log is empty. |
| `wifi.bt` mean | **12.9 mW** absolute (vs 5.2 mW awake baseline with wake lock, Wi-Fi off in both); per-minute floor 7–9 mW (11–15 mW in the first 3 h) with ~45–57 mW spikes about every 10–20 min |
| CPU rails | 13.0 mW (awake baseline with wake lock: 38 mW) |
| All rails | 382.7 mW (modem 245 mW; display 40 mW with screen off) |
| batterystats (whole phone, 8 h 5 min) | BT controller Idle 1 h 48 min (22 %), Rx 39 min 36 s (8.2 %), Tx 9 min 58 s (2.1 %); BT scan time 2 h 33 min, of which 2 h 3 min is another app (uid u0a128, 8 long scans) and 43 min is MeshLink; discharge 0 mAh (phone on the charger, held at 80 % / "not charging"; `dumpsys battery unplug` only simulated unplug) |

Reading: with the screen off and no wake lock, Android 17 on the Pixel throttled the app's medium-density duty
cycle to ~⅙ of the requested scan duty by stretching the gaps, while links and the controller-run advert stayed
up. The radio rail cost of this "medium density, 2 links, overnight" state is **≈ 8 mW above the awake idle
baseline (≈ 0.07 %/h of a 3,000 mAh battery)**, i.e. ~0.6 % per 8 h night from Bluetooth; the cost of waking the
CPU is included here (CPU rails were 13 mW total). The battery gauge cannot confirm it (the phone stayed on the
charger).

## iOS

Not run (needs a person; README "iOS"). Prepared and tested while plugged: both apps installed and launched;
gauge sampler parses both phones; `UpdateTime` advances every ~20 s (A3); `idle_gatt_3` with three Android
centrals linked 2/3 in a 60 s plugged test (OnePlus 6 failed with scan error 2 and is excluded from the default
centrals).

Human checklist (iPhone 15 first; SE 2016 optional, same steps with `se2016`). Signing profiles expire around
**2026-11-07**: run before then or rebuild/re-sign first.

1. Do not run any Pixel energy run at the same time (the iOS `idle_gatt_3` centrals are the Pixel's peers
   Nothing Phone (2), Galaxy Z Flip4, Gigaset GX6).
2. On the iPhone: Settings → Display & Brightness → Auto-Lock **Never**; brightness **minimum**; Low Power Mode
   **off**; Bluetooth on; Wi-Fi **on** (the sampler uses Wi-Fi). Note the battery % (needs ≥ 60 %).
3. Unplug the iPhone (cable and any MagSafe/Qi), leave it unlocked, screen on, on the desk.
4. Sign check (2 min): `cd prototypes/energy && python3 host/energy.py ios-sample ip15 --seconds 120` → in the
   printed file, `ExternalConnected` false and `InstantAmperage` non-zero (records the sign while discharging).
5. Three repetitions, ~1.5 h each (8 windows × ~11 min; the host launches the app each window; keep the phone
   unlocked): `python3 host/energy.py ios-run ip15 --rep 1`, then `--rep 2`, `--rep 3`. Any sample with
   `ExternalConnected`/`IsCharging` true voids that window. Results: `results/ios-windows.jsonl`, `results/ios/`.
6. Plug back in when done or if the battery drops below 20 %; recharge to ≥ 80 % before the overnight.
7. Overnight (8 h): unplug, unlock, run `python3 host/energy.py ios-overnight ip15 --hours 8`; once it prints
   `app launched; LOCK THE SCREEN NOW`, press the side button to lock the screen and leave the
   phone untouched. Plug back in the next morning.
8. Reset Auto-Lock to its previous value.

Then the parent computes window − bracketing baseline per scenario from `results/ios-windows.jsonl` (the iOS
summary step is not written yet; `power_mw` per window is |InstantAmperage| × Voltage averaged over gauge
updates, ~every 20 s).

## Limitations

- **Whole-rail, not app attribution.** `wifi.bt` includes every Bluetooth user (system scans with
  `ble_scan_always_enabled=1`, GMS) and the Wi-Fi chip's idle draw; all-rails includes everything. Δ removes
  what is constant, not what fluctuates (baseline plateau shifts, the ~10-min system burst).
- **Combined Wi-Fi/BT rail**; Wi-Fi was off, but the chip is shared.
- **Single reference device** (one Pixel 6, one OS build) and fixed peers; other controllers (Samsung, Qualcomm,
  MediaTek) and iOS may differ by multiples. Cross-checks only give controller time.
- **Wake lock** in measured windows (A1) hides the CPU wake-up cost of scan results and link events; idle-link and
  low-duty numbers are therefore lower bounds for the whole device.
- **Charging:** the Pixel sat on a wireless charger, held at 80 % ("not charging", status 4) the whole time (rails
  are valid, but charger heat and PMIC state differ from running on battery; battery-gauge discharge is 0). Battery temperature 33.7–38.5 °C across windows, not controlled.
- **Peer-limited throughput:** bulk throughput was the same at 1M and 2M, so per-KiB costs are for ~80–100 KiB/s
  links; GATT used a flow-control knob (`write_ack_every=256`, A5) not specified by any ADR.
- **Relay stand-in** without routing/crypto; DUT only ever central.
- **Environment:** nearby Apple and other BLE devices advertise continuously (the filtered scans still wake on
  Apple overflow adverts, per ADR 0010's screen-off rule).
- **Continuous-scan degradation** mechanism unidentified (finding 3).
