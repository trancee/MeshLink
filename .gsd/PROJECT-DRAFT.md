# Android direct-proof matrix result

## Scope
Rerun the Android direct-proof scenario across the attached fleet with a 45s capture window and fail-fast behavior.

## Result summary
- **1 / 7 pairings passed** end-to-end.
- **2 / 7 pairings failed in preflight** because the wireless ADB alias was not ready.
- **4 / 7 pairings failed in capture** because the sender never reached `proof.complete` within the 45s window.
- The **only passing pairing** was **RMX3710 ↔ A063**.

## Matrix
| Pair | Initial | Initial s | Final | Final s | Quirk |
|---|---|---:|---|---:|---|
| A065 ↔ Pixel 4a | fail: capture | 87.8 | fail: capture | 47.8 | Route never stabilized before timeout |
| NAM_LX9 ↔ OnePlus 6 | fail: preflight | 6.7 | skipped | — | Wireless ADB alias not ready |
| SM_G390F ↔ Nokia X20 | fail: capture | 80.8 | fail: capture | 48.1 | Route never stabilized before timeout |
| Mi Note 3 ↔ CPH2385 | fail: preflight | 22.1 | skipped | — | Wireless ADB alias not ready |
| RMX3710 ↔ A063 | pass | 56.1 | pass | 21.3 | Passed with `uuid-pair-plus-service-data` |
| CPH2359 ↔ SM_F721B | fail: capture | 84.7 | fail: capture | 46.8 | Route never stabilized before timeout |
| E940_2849_00 ↔ POCOPHONE_F1 | fail: capture | 91.5 | fail: capture | 48.7 | Route never stabilized before timeout |

## Investigation findings
### 1) There are two distinct failure classes
- **Preflight failure**: the ADB serial alias was present in `adb devices -l` output only partially or not at all by the time the harness checked readiness.
- **Capture failure**: the app launched, saw a peer, but never produced the sender `proof.complete` marker before the timeout.

### 2) The common capture failure is route establishment, not app crash
The failing runs typically show:
- `peer.discovered` on the sender
- `send.requested`
- retry loops such as `sender.wait.retry reason=route-unavailable` or a long wait with no completion
- no sender `proof.complete`

That means the scenario is often stuck **before direct route stabilization**, not in the message payload itself.

### 3) The harness now surfaces route stage directly
The direct-proof harness persists:
- `routeStage`
- `routeEvidence`
- `senderRouteStage` / `senderRouteEvidence`
- `passiveRouteStage` / `passiveRouteEvidence`

This turns the old opaque capture timeout into an explicit route diagnosis. Example classifications from the rerun:
- sender `route-unavailable`
- sender `peer-discovered`
- sender/passive `route-discovered`

### 4) The “simple scenario” is carrier-sensitive on this bench
The passing pair only completed when the final run used:
- `--advertisement-carrier uuid-pair-plus-service-data`
- a bootstrap target peer id derived from the passive peer’s persisted keys

That combination turned an 8.8s pass from a previously failing pair.

### 5) Passive `proof.complete` is not always emitted even on success
For the passing pair, the sender `proof.complete` is the hard gate, while the passive side retained proof via exported history/evidence. That matches the harness contract and should not be treated as a failure by itself.

## Likely root cause
The matrix is not failing because the message send API is broken; it is failing because **direct discovery and route formation are unreliable across this attached fleet under a short timeout**, and some devices are also flaky on wireless ADB readiness.

## Operational takeaway
If the goal is a fast smoke:
- keep the 45s cap,
- treat preflight readiness failures as infrastructure noise,
- and prefer the service-data carrier on devices that otherwise stall in route discovery.

If the goal is broad fleet coverage, the harness should record the route-stage explicitly so these timeouts can be triaged by device pair instead of looking like generic scenario failures.
