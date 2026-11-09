# What does MeshLink cost on iOS when measured with Apple's Power Profiler?

Type: prototype
Status: resolved

## Question

Measure MeshLink's Bluetooth and CPU energy on iPhones with Apple's Power Profiler, including screen-off and background states, and replace the iOS bounds in the energy evidence with per-component numbers if the tool resolves them.

## Context

- Ticket 23 could only bound iOS: whole-phone battery-gauge power with screen on had a noise floor of about 50 mW; locked runs needed coulomb counting (iPhone 15 night: 119 mW with the app, control night without it).
- The Power Profiler (introduced in Xcode 26) needs a physical iPhone on iOS 26 or later. In the lab only the 12 mini (iOS 27.0.1, Developer Mode on) qualifies; the iPhone 15 (iOS 17) and SE 2020 (iOS 18) do not.
- Detached recording: Settings > Developer > Performance Trace, mode Power Profiler, with the app under Monitored Apps; up to 10 h without a Mac, so it covers locked and overnight states. Apple lists apps installed by Xcode, TestFlight or enterprise; whether a `devicectl` install appears is unverified.
- Analysis needs Instruments on the Mac. Xcode 26 runs only on macOS 26 and this Mac runs macOS 27 (ticket 06), so the route is Xcode 27 installed next to Xcode 16.4, which stays the build constraint. Installing it needs user approval.
- Output: overall system power as battery %/hour, plus relative CPU, GPU, display and networking impact scores. Scores are not watts. A 60 s connected smoke capture (2026-11-07, Xcode 27.2, 12 mini on USB) confirmed the lanes: per-process CPU, display, GPU and networking impact (Wi-Fi and cellular only) and system %/hour (0 while charging); there is no Bluetooth lane. Bluetooth cost must come from app-on versus app-off system power, unplugged or detached.
- iOS suspends the app about 30 s after locking even with Bluetooth background modes, so background cost is mostly iOS-managed scanning and advertising.
- Apple's Power Profiler documentation lists app lanes for CPU, GPU, display and networking only. To see when iOS actually scans, advertises and holds links while the app is suspended (radio timing, not energy), consider an HCI capture with Apple's Bluetooth logging profile and PacketLogger (Additional Tools for Xcode); both need approval and a person on the phone.
- Apple's "Analyzing your app's battery use" lists Bluetooth as a power-use category in the Xcode Organizer Battery Usage pane (field data from distributed builds) and says Xcode's Energy Impact debug gauge breaks energy down by category; whether the gauge shows Bluetooth for a lab build is unverified. MetricKit has no Bluetooth metric. Organizer data is for host apps, so a troubleshooting how-to could point adopters to it.
- Lane check (2026-11-07, 12 mini, energy build, 60 s each, on USB, n = 1): idle, scan 4/4 + advertising and continuous filtered scan gave the same app lanes (CPU impact 0.016–0.024, networking 0, display about 0.8), although the app log shows scanning, advertising and results. Bluetooth activity does not show in the app's Power Profiler lanes; only unplugged system %/hour can carry it.
- Unplugged attempts (2026-11-07, 12 mini, iOS 27.0.1, Xcode 27.2):
  - Detached Performance Trace (Power Profiler mode, system-only and with Bakeoff monitored) saves no file and shows no alert. On stop, SpringBoard logs `PerformanceTraceError Code=2 "Failed to collect any trace files due to unknown error"`, preceded by a sandbox denial of `hw.uuid` in `ptpassivecollectiond` and "cannot find `device UUID'". A 20 min idle/comb_med schedule was lost this way. Cause is in iOS; not fixable on our side.
  - `xctrace` over Wi-Fi with `--template "Power Profiler" --all-processes` aborts immediately (the Location Energy Model instrument rejects All Processes). `--instrument "Power Profiler" --all-processes` records unplugged system power (1 s samples, for example 15.2 %/hr, fine resolution) but ends with "Device got disconnected" after about 2 s, 2 of 2 tries; a third attempt hung.
  - So on this setup no route yields minutes of unplugged system power. Options: retry after an iOS update, try the Instruments GUI over Wi-Fi, or keep the ticket 23 bounds.
  - Instruments GUI (Xcode 27.2, Blank template plus Power Profiler, All Processes; Record stays disabled until the Power Profiler track is added) recorded 23 min without a disconnect, but the phone was on USB: `DeviceChargingState` is one charging row for the whole trace and `SystemPowerLevel` is 0 %/hr, so no Bluetooth signal. A GUI save of a 23 min trace takes about 1 min to write; `xctrace export` reports "Document Missing Template Error" until it finishes.
- Unplugged result (2026-11-07 22:08–22:32, 12 mini, Instruments GUI over Wi-Fi only, Blank plus Power Profiler, All Processes; 23.7 min without a disconnect; no charging rows; brightness 27 %; screen on, unlocked; trace `~/Downloads/pp-gui2.trace`):
  - Schedule 240 s phases via `devicectl` (Bakeoff `disc=idle` versus comb_med `disc=both filter=mesh scan_on_s=4 scan_off_s=4`), 1 s `SystemPowerLevel` samples, first 15 s and last 5 s of each phase dropped.
  - Medians (%/hr): idle1 5.50, comb2 6.09, idle3 5.08, comb4 6.23, idle5 5.30. comb_med minus idle ≈ +0.8 to +1.2 %/hr.
  - Means are confounded by system bursts of 10–100 s (8–38 %/hr) in idle and comb phases alike (idle1 mean 8.95); they do not follow the 8 s scan cycle. Mean delta (comb versus idle3 and idle5) +2.4 %/hr, not attributable to Bluetooth.
  - In mW the basis matters: Apple states a fraction of total battery energy. Full charge 1,741 mAh (design 2,214) at about 3.85 V gives about 6.7 Wh (8.5 Wh design), so +0.8 to +1.2 %/hr ≈ 54–80 mW (68–102 mW on design capacity). This is the same order as the ticket 23 iOS comb_med gauge deltas on the iPhone 15 and SE 2020 (different models; the 12 mini was not gauge-measured); n = 2 per condition on one phone, so not yet conclusive.

## Verdict (grilled 2026-11-08 and 2026-11-09)

Prototype branch: `prototype/energy` (amendment A10, `host/pp/`, `results/pp/`). Evidence: [energy evidence, iOS Power Profiler](../../../docs/evidence/2026-11-energy/README.md#ios-power-profiler-iphone-12-mini-run).

1. **Method:** the Instruments GUI over Wi-Fi, unplugged, screen on; conditions alternate in 240 s phases in 4 random blocks; per-phase medians of `SystemPowerLevel`. Session 2 (seed 3535, A/B/C) and session 3 (seed 3636, A/C/D, 15 min warm-up, block-invalid rule fixed in advance).
2. **Foreground (accepted round 2):** combined medium discovery costs about 5–80 mW of whole-phone power on the 12 mini (session 2: B − A positive in 4 of 4 blocks, median +26 mW; session 1: 54–80 mW). This replaces "bounds only" in the evidence and ADR 0008.
3. **Background (round 3, Q1):** the pre-registered verdict (C − D positive in at least 3 of 4 blocks) failed: +17, −2, +1, −116 mW. Recorded as not resolvable, median about 0, upper bound about +17 mW; block 4 is reported, not excluded. The Home screen itself costs about +53 mW (D − A), which confounded session 2's C − A.
4. **No fourth session (Q2):** a longer run could narrow the bound but no preset depends on it; iOS sets background duty.
5. **Lessons (Q3):** warm-up, Home-screen confound, empty per-process table, Save As after long recordings.
6. **Prototype captured (Q4):** the `idle` + `bg=1` condition, the phase driver (device from `PP_DEVICE`) and the analysis are committed on `prototype/energy`; raw traces stay outside git. Session 2's trace was overwritten by an accidental save; its deltas come from the analysis at the time.
