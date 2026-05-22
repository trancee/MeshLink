# M010 PQ-hybrid candidate matrix and wire-shape feasibility

Status: S01 design artifact

This note compares PQ-hybrid candidate strategies for MeshLink key
establishment, with a focus on wire shape, platform constraints, and prototype
selection.

## Candidate set

### Conservative candidate — X25519 + ML-KEM encapsulation

- Keep the current classical handshake as the anchor.
- Add a PQ KEM contribution and derive final key material from the combined
  classical and PQ inputs.
- Prefer implementation paths with the widest near-term provider support.

### Aggressive candidate — PQ-first handshake path with classical bind

- Introduce a PQ-dominant message flow.
- Bind the classical contribution for compatibility and migration.
- Accept larger payloads and more negotiation complexity in exchange for a more
  forward-leaning posture.

## Wire-shape options

### A. Inline hybrid payload in existing handshake messages

- Embed PQ material directly in handshake control payloads.
- Best for simple sequencing.
- Highest risk for frame expansion and BLE pressure.

### B. Staged extension frames

- Keep the baseline handshake frame shape.
- Exchange PQ material in explicit extension frames.
- Better for parsing and fallback isolation, but costs extra messages.

### C. Negotiated compact profile plus continuation chunks

- Negotiate a compact profile first.
- Move larger PQ artifacts into bounded continuation chunks.
- Best BLE-pressure control, but highest state-machine complexity.

## Feasibility constraints for the next prototype

Any shortlisted candidate should stay inside these bounds:

- bounded handshake message-count growth over baseline
- measurable and attributable per-frame expansion
- deterministic timeout behavior under the current harness
- machine-readable visibility into retry and fallback behavior

## Comparison matrix

| Candidate | Strategy class | Wire-shape fit | BLE risk | Provider risk | Implementation risk | Notes |
|---|---|---|---|---|---|---|
| `C1` | Conservative | A or B | Medium | Medium | Medium | Good realism-to-complexity balance |
| `C2` | Conservative | B | Low-Medium | Medium | Medium | Cleanest fallback and observability story |
| `A1` | Aggressive | A | High | High | High | Payload pressure likely dominates |
| `A2` | Aggressive | C | Medium-High | High | Very high | Better long-term shape, too complex for the first spike |

## Selection criteria

Choose the S02 shortlist with explicit criteria:

1. compatibility feasibility on current JVM/Android/iOS build surfaces
2. deterministic observability for success, fallback, downgrade, and failure
3. measurable wire-shape overhead
4. containment behind an opt-in path without public-API drift
5. implementation cost that fits one milestone slice

## Recommended shortlist for S02

### Primary: `C2` — conservative + staged extension frames

Why:

- strongest observability and fallback separation
- lower immediate BLE pressure than inline aggressive payloads
- good realism without overloading the first spike

### Secondary: `C1` — conservative + inline/staged contrast

Why:

- gives a useful comparison point for payload-size sensitivity
- tests whether inline carriage is practical before the project commits to it

### Deferred: aggressive candidates

The aggressive paths stay relevant as future design inputs, but they require
more provider maturity and state-machine work than S02 should absorb.

## Handoff requirement for S02

S02 should produce machine-readable baseline-vs-hybrid evidence for the
shortlisted candidates, including:

- latency deltas
- frame-size deltas
- fallback reasons
- downgrade verdicts
- failure classes

## S03 evidence summary

Measured summaries produced under `build/reports/m010-pq-eval/`:

| Candidate | Latency delta | Payload delta | Negotiated mode | Fallback | Downgrade | Failures |
|---|---:|---:|---|---|---|---:|
| `C2` (`c2_staged_extension`) | +46 ms | +184 bytes | hybrid | none | none | 0 |
| `C1` (`c1_inline_staged`) | +63 ms | +232 bytes | hybrid | none | none | 0 |

Current implication: `C2` remains the stronger follow-up candidate, while `C1`
remains the comparison control because it carries higher measured overhead.
