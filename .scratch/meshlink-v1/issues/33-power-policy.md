# What power policy should each preset and density class use?

Type: prototype
Status: resolved

## Question

Rerun the routing simulation with the measured energy model in place of the power index, and set each density class's scan and advertising schedule and each preset's power policy, so that delivery and latency stay within the ADR 0008 budgets at the lowest energy.

## Context

- Graduated from the map's "Not yet specified" list once ticket 23 resolved.
- Energy model (ADR 0008 amendment of 2026-11-08; [evidence](../../../docs/evidence/2026-11-energy/README.md#4-energy-model-feeds-adr-0008)): scanning 55 mW × scan duty, advertising about 0, 1 mW per idle link, 0.25 mJ per relayed 200 B message, 0.75–0.83 mJ/KiB bulk. One Pixel 6; iOS as bounds only.
- Constraints: start/stop scan windows with a period of at least 6 s (AOSP allows 5 scan starts per 30 s); no continuous scans. Interim schedule: low and medium 4 s on / 4 s off, high 4 s on / 8 s off. Low no longer scans more than medium; decide whether it should, and how.
- Background duty is set by the OS: Doze stretched the 4 s on / 4 s off timer to 30 s gaps (duty 0.12); iOS suspends the app about 30 s after locking. Model background as its own state.
- Can share a simulation rerun with ticket 32. Feeds the preset matrix (ADR 0011).
- Results are labeled simulated (P6).

## Answer

Stage F2 of the routing simulation, with the measured energy model ([evidence](../../../docs/evidence/2026-11-routing-simulation/README.md#stage-f-store-and-forward-conversation-routes-lookups-and-power), simulated).

| Class | `Balanced` | `Responsive` |
|---|---|---|
| low | 4 s on / 4 s off | 4/2 |
| medium | 4/12 | 4/8 |
| high | 4/16 | 4/12 |

- **Low scans more than medium:** newcomers start in the low class, so their join time follows the low schedule. Every slower low schedule raised join p95 by at least 1.45×.
- **Energy:** in dynamic scenarios `Balanced` uses 17–22 mW, down from 24–34 mW at the interim schedule, with join 0.99–1.04×. `Responsive` uses 21–27 mW and joins in 0.82–0.88× the time.
- **Presets (ADR 0011):** `ProximityDiscovery` and `LocalMultiplayer` use `Responsive`; `OfflineChat`, `EmergencyBroadcast` and `FieldTeams` use `Balanced`. Apps can override the setting.
- **Background:** set by the operating system. With half the phones in the background, delivery stays at or above 99.87% and join p95 is 13–17 s; Android background costs about 12 mW. No preset promises background latency.

ADRs 0008 (v9) and 0011 (v7) are amended.
