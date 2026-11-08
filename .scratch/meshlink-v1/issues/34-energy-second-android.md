# Does the energy model hold on a second rail-equipped Android phone?

Type: prototype
Status: open

## Question

Re-measure the ticket 23 core scenarios (baseline, scan 4/4 and 4/8, advertising, idle links, bulk CoC and GATT, relay) on a Pixel 7 or newer and decide whether the Pixel 6 coefficients in the ADR 0008 energy model hold within a stated tolerance, or need per-device ranges.

## Context

- The v1 energy model comes from one Pixel 6 (Android 17), the only lab phone with per-rail power (ODPM). The Android Studio Power Profiler docs list ODPM on Pixel 6 and later Pixels only; no Pixel 7 or newer is in the lab yet, so this needs a phone to be acquired (human).
- Reuse the throwaway runner on branch `prototype/energy` (`prototypes/energy/host/energy.py`) and its pre-registration; record deviations as amendments.
- Also check whether continuous filtered scans degrade after 2–5 min with the screen off, which conflicts with discovery run T9 (unresolved in ticket 23).
