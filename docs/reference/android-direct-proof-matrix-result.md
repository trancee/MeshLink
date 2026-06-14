# Android direct-proof matrix result

Last verified: 2026-06-13

This page captures the observed Android direct-proof matrix result from the
45s fail-fast rerun across the attached fleet.

## Scope

- 45s capture window
- fail-fast behavior retained
- sender `proof.complete` remains the hard success gate
- passive retained evidence is best-effort, not required for success

## Result summary

- **1 / 7** pairings passed end-to-end
- **2 / 7** pairings failed in preflight because the wireless ADB alias was not ready
- **4 / 7** pairings failed in capture because the sender never reached `proof.complete` in time
- The only passing pairing was **RMX3710 ↔ A063**

## Matrix snapshot

| Bucket | Count | Meaning |
|---|---:|---|
| Pass | 1 | Sender reached `proof.complete` and the pair completed end-to-end |
| Preflight | 2 | Wireless ADB alias or install readiness was not available before capture began |
| Capture | 4 | Discovery or route establishment did not stabilize before the 45s window expired |

## How to read the result

- Treat the preflight bucket as an environment/readiness failure, not a transport proof failure.
- Treat the capture bucket as a route-stability or proof-completion failure.
- Use the RMX3710 ↔ A063 pair as the canonical successful example for this sweep.
- Use the 45s cap as the expected fast-fail boundary when comparing future reruns.

## Retained evidence

Use this digest together with the direct-proof report and the operator guide when reviewing the matrix shape.
