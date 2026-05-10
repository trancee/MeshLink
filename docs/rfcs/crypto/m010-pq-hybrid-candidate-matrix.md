# M010 PQ-Hybrid Candidate Matrix and Wire-Shape Feasibility

Status: S01 design artifact (feasibility contract input for S02)

This document compares candidate PQ-hybrid strategies for MeshLink key establishment,
including wire shape implications, platform constraints, and prototype-selection criteria.

## Candidate Set

### Conservative candidate — X25519 + ML-KEM encapsulation (hybrid KDF input)

- Keep current classical handshake structure as anchor.
- Add PQ KEM contribution and derive final key material from classical+PQ shared inputs.
- Prioritize implementation paths with widest available provider/toolchain support.

### Aggressive candidate — PQ-first handshake path with classical compatibility bind

- Introduce a PQ-dominant message flow and bind classical contribution for compatibility.
- Potentially larger handshake payload and additional negotiation complexity.
- Higher risk of provider/size incompatibility, but stronger future migration posture if viable.

## Wire shape options

### Wire shape A: Inline hybrid payload in existing handshake messages

- Embed PQ material directly in handshake control payload frames.
- Pros: simple sequencing (fewer message types), direct transcript binding.
- Cons: largest per-frame expansion risk; may stress BLE MTU/chunking paths.

### Wire shape B: Staged extension frames (capability + hybrid blob frames)

- Keep baseline handshake frame shape and exchange PQ material in explicit extension frames.
- Pros: clearer parsing/version boundaries, easier fallback isolation.
- Cons: extra message count and timeout/retry complexity.

### Wire shape C: Negotiated compact hybrid profile + optional continuation chunks

- Negotiate a compact profile first; transmit larger PQ artifacts via bounded continuation chunks.
- Pros: better control over BLE frame pressure and fragmentation behavior.
- Cons: most complex state machine; highest implementation complexity for spike.

## BLE and Overhead constraints

Target feasibility constraints for S02 evidence:

- **Handshake message count budget**: prototype should stay within bounded increment over baseline.
- **Per-frame expansion budget**: hybrid additions should remain measurable and attributable per wire shape.
- **Timeout tolerance**: hybrid path must complete within deterministic timeout windows used by current harness tests.
- **Retry pressure visibility**: any increase in retransmit/retry behavior must be captured in machine-readable output.

## Candidate comparison matrix

| Candidate | Strategy class | Wire shape fit | BLE risk | Platform/provider risk | Implementation risk | Notes |
|---|---|---|---|---|---|---|
| C1 | Conservative | A or B | Medium | Medium | Medium | Best initial spike balance between realism and implementation tractability. |
| C2 | Conservative | B | Low-Medium | Medium | Medium | Cleaner versioning/fallback semantics; good observability fit. |
| A1 | Aggressive | A | High | High | High | Payload pressure and provider gaps likely dominate risk. |
| A2 | Aggressive | C | Medium-High | High | Very High | Potential long-term model, but complex for first spike slice. |

## Selection criteria

S02 implementation shortlist must be chosen using explicit criteria:

1. **Compatibility feasibility**: candidate can be exercised on current JVM/Android/iOS build surfaces (or clearly bounded if not).
2. **Deterministic observability**: candidate can emit machine-readable success/failure/fallback/downgrade metrics.
3. **Wire-shape controllability**: candidate overhead can be measured and attributed to specific frames/messages.
4. **Containment**: candidate can remain opt-in and avoid default-path/public-API drift.
5. **Execution cost**: candidate can be implemented and verified within one milestone slice.

## Shortlist for S02 prototype

### Shortlist #1 (primary): C2 — Conservative + staged extension frames (Wire shape B)

Rationale:

- Best trade-off for deterministic instrumentation and fallback separation.
- Lower immediate BLE pressure than fully inline aggressive payloads.
- Keeps complexity bounded for spike while still exercising hybrid integration concerns.

### Shortlist #2 (secondary): C1 — Conservative + inline/staged fallback (Wire shape A/B)

Rationale:

- Useful contrast for measuring payload-size sensitivity versus Shortlist #1.
- Provides direct evidence for whether inline carriage is practical or quickly exceeds budgets.

### Deferred from S02: aggressive candidates (A1/A2)

Rationale:

- Kept as decision-package inputs for S03.
- Require stronger provider maturity and/or broader state-machine investment than S02 should absorb.

## S02 handoff requirements

S02 must produce machine-readable baseline-vs-hybrid evidence for shortlisted candidates,
including latency deltas, frame-size deltas, fallback reasons, downgrade verdicts, and failure classes.
Those fields are frozen by the evaluation contract/validator in S01/T03.

## S03 evidence update

Measured summaries produced under `build/reports/m010-pq-eval/`:

| Candidate | Latency delta | Payload delta | Negotiated mode | Fallback | Downgrade | Failures |
|---|---:|---:|---|---|---|---:|
| C2 (`c2_staged_extension`) | +46 ms | +184 bytes | hybrid | none | none | 0 |
| C1 (`c1_inline_staged`) | +63 ms | +232 bytes | hybrid | none | none | 0 |

Decision implication for M010 closeout: C2 remains the primary follow-up candidate,
while C1 remains comparative control due to higher measured overhead in this spike.