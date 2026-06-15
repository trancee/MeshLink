# Android direct-proof matrix result

Last verified: 2026-06-15

This page captures the observed Android direct-proof matrix result from the
132-pair fail-fast sweep across the attached Android fleet.

## Scope

- 45s capture window
- install warm-up capped separately so one slow device cannot block the sweep
- fail-fast behavior retained
- sender `proof.complete` remains the hard success gate
- passive retained evidence is best-effort, not required for success

## Result summary

- **15 / 132** pairings passed end-to-end
- **117 / 132** pairings failed
- **42** pairings failed in preflight
- **9** pairings failed in launch
- **66** pairings failed in capture because `proof.complete` never arrived before timeout
- Install warm-up surfaced two device-specific issues before the matrix began: **Mi Note 3** install/uninstall failure and **OnePlus 7T** install timeout
- Every successful pairing used **L2CAP**; **GATT** produced no passes
- The latest attached-fleet rerun split the remaining failures into reproducible preflight/install and launch clusters, but still produced no pass on either transport path
- The next transport work needs to fix the app-side primary-transport contract before the retained summary can report a true GATT-primary pass

## Timing summary

| Metric | Value |
|---|---:|
| Fastest pair elapsed | 0.1s |
| Slowest pair elapsed | 80.5s |
| Average pair elapsed | 30.3s |

## Zero-pass devices

| Device | Failed pairings | Note |
|---|---:|---|
| Mi Note 3 | 22 | Never cleared preflight in this sweep. |
| Huawei Nova 9 | 22 | Never cleared preflight in this sweep. |
| OnePlus 7T | 22 | Install warm-up timed out and no pair passed. |
| Xiaomi Pocophone F1 | 22 | Never cleared preflight in this sweep. |
| Samsung Galaxy XCover 4 | 22 | Never cleared preflight in this sweep. |

## Matrix snapshot

| Bucket | Count | Meaning |
|---|---:|---|
| Pass | 15 | Sender reached `proof.complete` and the pair completed end-to-end |
| Preflight | 42 | Runtime permissions or install readiness were not available before capture began |
| Launch | 9 | The app launched but never reached the route-finding phase quickly enough |
| Capture | 66 | Discovery or route establishment did not stabilize before the 45s window expired |

## Transport note

- Every successful pairing used **L2CAP**.
- **GATT** did not produce any passes.
- The pass set clustered around Nothing Phone (2), OPPO Reno8 5G, motorola edge 30 fusion, OnePlus Nord 2 5G, Realme C55, Gigaset GX6, Samsung Galaxy Z Flip4, and Nothing Phone (1).
- See also:
  - `docs/explanation/android-direct-proof-gatt-path.md` for the retained-transport interpretation
  - `docs/explanation/android-direct-proof-gatt-transport-refactor-plan.md` for the app-side transport refactor plan

## How to read the result

- Treat preflight as an environment or readiness failure, not a transport proof failure.
- Treat launch as a startup or visibility failure.
- Treat capture as a route-stability or proof-completion failure.
- Prioritize the zero-pass devices for startup, permission, and pairing-path debugging.
- Use the 45s cap as the expected fast-fail boundary when comparing future reruns.

## 2026-06-15 attached-fleet rerun

The latest attached-fleet sweep completed all 30 directed pairs and landed in the
same failure-only shape as the previous baseline, but with a smaller 6-device
matrix.

- **0 / 30** pairings passed end-to-end
- **30 / 30** pairings failed
- **18** pairings failed in preflight
- **12** pairings failed in launch
- The sweep checkpoint was written under `/tmp/meshlink_android_matrix_20260615T191228/`
- The pass set remains empty, so this rerun did not surface a successful GATT or L2CAP combination on the current attached fleet
