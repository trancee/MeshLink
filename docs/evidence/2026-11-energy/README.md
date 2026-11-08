# Energy on real phones: evidence

- **Date:** 2026-11-03 21:42 to 2026-11-08 CET.
- **Ticket:** `.scratch/meshlink-v1/issues/23-energy-on-devices.md`
- **Decisions it feeds:** [ADR 0008](../../adr/0008-routing-topology-and-density-adaptation.md) (energy model, scan schedule), [ADR 0010](../../adr/0010-discovery-and-advertising.md) (scanning rules)
- **Code:** throwaway prototype on branch `prototype/energy` (last commit `4b4b835`), under `prototypes/energy/`: pre-registration and amendments A1–A9 in `README.md`, full tables in `RESULTS.md`, runner `host/energy.py`, analysis `host/analyze.py`. The bake-off app (`prototypes/bearer-bakeoff/`) gained an energy mode. It is never merged.

All numbers here were observed on real phones. Simulators were not used. Anything not observed is marked **unverified**.

## Evidence labels

| Label | Meaning |
|---|---|
| **[RUN]** | Measured in the named windows. Raw records (gzipped) are in [`raw/`](raw/). |
| **[LOG]** | Seen in a `dumpsys`, `batterystats` or app log capture. The Perfetto traces (51 MB) are not in this folder. |
| **[UNVERIFIED]** | A likely explanation that no run or log proves. |

## 1. Question

What does MeshLink cost in energy on real phones, per scanning and advertising duty, per idle link, per KiB and per relayed message, and overnight? The answer replaces the routing simulation's assumed power index (0.6 × scan duty + 0.4 × advertising duty, ADR 0008) and backs battery claims.

## 2. Method

The method was pre-registered before the first measurement (`prototypes/energy/README.md`); deviations are amendments A1–A9.

- **Android reference:** Google Pixel 6 (Android 17), the only lab phone with on-device power rails (ODPM; Android Studio documents them on Pixel 6 and later Pixels only). It was read through Perfetto `android.power` with `collect_power_rails`. The `wifi.bt` rail combines Wi-Fi and Bluetooth; Wi-Fi was off in every window. Rails are measured after the battery, so they stay valid on the charger.
- **Windows:** 600 s, screen off, app wake lock held (A1), 3 repeats per scenario, interleaved with baseline windows. **Δ** = window mean − mean of the two bracketing baselines.
- **Android cross-checks:** Bluetooth controller Idle/Rx/Tx time from `batterystats` on the moto edge 30 fusion, Galaxy S10e and Galaxy A16 (time, not energy).
- **iOS:** whole-phone power from the battery gauge (`AppleSmartBattery` over Wi-Fi lockdown: |InstantAmperage| × Voltage, about one update per 20 s), unplugged, screen on at minimum brightness, because a locked iPhone stops answering over Wi-Fi about 4 min after locking. iPhone 15 (iOS 17) and iPhone SE (2020) (iOS 18.7.8), 3 repeats each. The SE 2016 switched itself off with a worn battery after repeat 1 (A8); its data is reported separately.
- **Overnight:** Pixel 6 for 8 h without wake lock (Doze allowed) on the rails; iPhone 15 for 13.4 h locked, measured by coulomb counting (`AppleRawCurrentCapacity` at start and end, read over USB, A9), against a control night without the app.

## 3. Results

### Android, Pixel 6, `wifi.bt` rail [RUN]

Baseline 5.2 ± 1.5 mW absolute; effects under about ±2 mW are not resolvable. n = 3 × 600 s each.

| Component | Measured | Note |
|---|---|---|
| Advertising, 100 ms or 1 s interval | −0.5 ± 1.4 and −0.6 ± 0.5 mW | not measurable |
| Scan 4 s on / 4 s off (duty 0.5) | 27.8 ± 0.3 mW | flat across the window |
| Scan 4 s on / 8 s off (duty 0.33) | 18.3 ± 2.6 mW | |
| Scan cost model | about **55 mW × scan duty** | 27.8 / 0.5 = 55.5; 18.3 / 0.33 = 55.0 |
| Continuous filtered LOW_LATENCY scan | 25–33 mW for 2–5 min, then baseline; results fall about 15× | 6 of 6 windows (`scan_low`, `comb_low`); BALANCED also degrades |
| Idle link, GATT or CoC, 1–5 links | at most about 1 mW per link (5 links: 2.8–3.6 mW) | GATT and CoC are indistinguishable |
| Bulk transfer, CoC | 71–74 mW at 96–104 KiB/s: **0.70–0.76 mJ/KiB** | CPU +29–39 mW |
| Bulk transfer, GATT | 67–68 mW at 80–82 KiB/s: **about 0.83 mJ/KiB** | CPU +72–111 mW, about 2× CoC |
| 1M vs 2M PHY | no difference | throughput was limited by the peer, not airtime |
| Relay, 200 B messages (stand-in, no routing or crypto) | 3.3–3.4 mW at 1–5 msg/s; 7.0 mW at 20 msg/s: **about 0.25 mJ per message** | marginal cost 5 → 20 msg/s |

Scan restarts: a 2 s on / 2 s off schedule ran 5 empty bursts out of 16, because AOSP rejects more than 5 scan starts per 30 s; 4 s on / 4 s off, 4 s on / 8 s off and 4 s on / 12 s off had none [RUN, functional only].

Why a continuous scan degrades is **[UNVERIFIED]**: the filters were set, so it is not the documented 5 min downgrade of unfiltered scans; a screen-off scan-mode downgrade by the stack is the likely cause. The discovery run T9 ([discovery evidence](../2026-11-discovery/README.md)) held a filtered BALANCED scan for 2 h on the same phone without a downgrade, with one advertiser in range; what differs between the two runs is not identified.

### Android cross-checks [LOG]

A continuous scan keeps the controller receiving 50–60% of the time on the A16 and the S10e; three idle links add 5–8 s of transmit time per 10 min. The counters agree with the Pixel in direction only: the moto reports no Idle time, the S10e scan saw 0 results, and the A16 is dominated by Google Play services' own scans.

### Android overnight [RUN]

Pixel 6, 8.07 h (`night-24911`), medium-density schedule (4 s on / 4 s off scan, fast advertising) and 2 idle CoC links, no wake lock.

- Doze started 2 min in; the links stayed up all night.
- The app timer only advanced when something woke the CPU, so scan gaps stretched to 30 s on average: logged scan duty 0.12 (batterystats 8.8%). Scan results kept arriving (about 2,500–3,400 per hour).
- `wifi.bt` was 12.9 mW absolute, about **8 mW above the awake baseline (about 0.07%/h)**, including the cost of waking the CPU.

### iOS, screen on [RUN]

Whole-phone Δ, each window minus its bracketing baselines, 3 repeats, all windows valid, 3/3 links in every link window.

| Scenario | iPhone 15 (iOS 17) Δ mW | SE 2020 (iOS 18.7.8) Δ mW |
|---|---|---|
| Baseline (absolute) | 462 ± 39 (n = 9) | 466 ± 25 (n = 9) |
| Advertising | 1 ± 36 | −14 ± 9 |
| Continuous filtered scan | 0 ± 16 | 11 ± 31 |
| Scan 4 s on / 4 s off + advertising | 53 ± 36 (14, 61, 85) | 18 ± 8 (26, 9, 19) |
| 3 idle GATT links (iPhone as peripheral) | 54 ± 103 | −6 ± 20 |

Baseline drift (SD 25–39 mW) hides every component except combined medium-density discovery, the only scenario positive in all six repeats. These numbers bound iOS costs from above; they do not measure them. SE 2016 repeat 1 only: baselines 559, 495 and 524 mW; advertising 540, 3 links 526, combined 591, scan 516 mW.

### iOS overnight [RUN]

| Night | Window | Charge | Average |
|---|---|---|---|
| MeshLink: scan 4 s on / 4 s off + advertising, background mode | Nov 5 17:46 to Nov 6 07:10 (13.4 h) | 3,037 → 2,623 mAh | 30.9 mA, about 119 mW, 1.05%/h |
| Control: same phone and place, app not running (no Bakeoff log written) | Nov 7 19:28 to Nov 8 09:11 (13.7 h) | 3,091 → 2,549 mAh | 39.5 mA, about 152 mW, 1.24%/h |

iOS suspended the app about 30 s after locking (`background_task_expired` [LOG]); the app's scan schedule stopped and only iOS-managed advertising and filtered scanning continued. The control night used more than the MeshLink night (−8.6 mA, about −33 mW), so night-to-night variation on the same phone exceeds MeshLink's iOS background cost, which this method cannot resolve. 119 mW stays a whole-phone ceiling for a MeshLink night, not an app cost. Resolving the app cost needs alternating app and control nights or an unplugged Power Profiler night (ticket 35).

## 4. Energy model (feeds ADR 0008)

Coefficients for comparing presets, from one Pixel 6 (`wifi.bt` rail, Δ above baseline). They are a single-device reference, not a battery guarantee.

| Component | Coefficient | Source |
|---|---|---|
| Scanning | 55 mW × scan duty (start/stop windows only) | `scan_med`, `scan_high` |
| Advertising | 0 mW (under the ±2 mW noise) | `adv_fast`, `adv_slow` |
| Idle link | 1 mW per link | `idle_*` |
| Relayed message (about 200 B) | 0.25 mJ | `relay_5`, `relay_20` |
| Bulk transfer | 0.75 mJ/KiB CoC, 0.83 mJ/KiB GATT (radio); about 2× CPU on GATT | `bulk_*` |
| Battery for %/h | 11,550 mWh (3,000 mAh × 3.85 V) | convention |

iOS gets bounds instead of coefficients (section 3). Background duty on both platforms is set by the OS: Doze stretched Android's scan gaps to 30 s, and iOS suspends the app.

## 5. Limitations

- **One Android reference device** with one OS build and fixed peers; other controllers may differ by multiples. The ODPM rail combines Wi-Fi and Bluetooth.
- **Whole-rail, not per app:** system scans and Google Play services' scans are included; Δ removes only what is constant.
- **Wake lock** in the measured Android windows (A1) hides the CPU cost of waking up for scan results; the overnight run includes it.
- **Charger:** the Pixel sat on a wireless charger held at 80%; the rails stay valid, but the battery gauge could not confirm them.
- **Bulk throughput was limited by the peer**, so 2M's per-bit saving is not shown.
- **Relay stand-in** without routing or crypto.
- **iOS screen on:** the noise floor is about 50 mW; screen-off foreground windows are impossible because locking suspends the app and stops the Wi-Fi gauge.
- **iOS overnight:** night-to-night variation on one phone was at least 33 mW (the control night drew more than the MeshLink night), so a single pair of nights cannot isolate the app.

## 6. Reproduce

On branch `prototype/energy`, in `prototypes/energy/`:

```sh
python3 host/energy.py run --rep 1                     # Android scenarios, Pixel 6 (Wi-Fi off per window)
python3 host/analyze.py reanalyze && python3 host/analyze.py summary
python3 host/energy.py ios-run ip15 --rep 1            # iOS screen-on windows (phone unplugged, unlocked)
python3 host/energy.py ios-sample se2020 --seconds 60  # gauge check over Wi-Fi
```

The device-lab steps that made iOS gauges readable over Wi-Fi are in [`docs/agents/lessons.md`](../../agents/lessons.md#energy-measurements).
