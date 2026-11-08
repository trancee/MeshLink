# What does MeshLink cost in energy on real phones?

Type: prototype
Status: resolved

## Question

Measure energy per KB and per connection-hour, advertising and scanning duty, and routing work at each density class on lab phones, to replace the simulation's assumed power index and back battery claims. Power policy across presets stays fog until this resolves.

## Context

- Merges the fog items 'routing power on real devices' and 'energy measurements'.
- Long background periods, Doze and overnight behavior are unmeasured (ADR 0010).

## Verdict (grilled 2026-11-07; iOS control night added 2026-11-08)

Prototype branch: `prototype/energy` (`RESULTS.md`, amendments A1–A9).

1. **Android scanning:** never run a continuous scan. Every preset uses start/stop windows with a period of at least
   6 s (AOSP rejects more than 5 starts per 30 s); low density uses the highest duty, 4 s on / 4 s off (0.5,
   measured steady). A continuous LOW_LATENCY scan drops to baseline after 2–5 min with the screen off.
2. **Simulation energy model (ADR 0008):** replace the 0.6 scan / 0.4 advertising index with an absolute component
   model in mW from the Pixel 6 `wifi.bt` rail: about 55 mW × scan duty; advertising about 0; at most about 1 mW
   per idle link; about 0.25 mJ per relayed 200 B message; bulk 0.70–0.76 mJ/KiB (CoC), about 0.83 mJ/KiB (GATT).
3. **Reference device:** the Pixel 6 is the v1 Android reference, labeled as one device; a non-blocking follow-up
   re-measures on a second rail-equipped phone.
4. **iOS:** report bounds, not coefficients (foreground components below about 50 mW; combined medium discovery
   +18 to +53 mW). iOS suspends the app about 30 s after locking and then owns the background duty, so presets
   cannot promise background discovery latency on iOS. Background cost is unresolved: the control night without
   the app drew 39.5 mA (152 mW) against 30.9 mA (119 mW) with it, so night-to-night variation exceeds the app's
   cost; 119 mW stays a whole-phone ceiling. Follow-up (ticket 35): Apple's Power Profiler on the iOS 27 phone, analyzed in Xcode 27 Instruments.
