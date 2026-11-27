# Decide how relays rate-limit each neighbor

Status: ready-for-agent
Type: HITL
Blocked by: none

## Behavior

Found in ticket 53 (broadcast priority threat review). The threat model's "Availability" section says that in the secured profiles MeshLink "rate-limits each neighbor". The relay path doesn't: flood frames are bounded only by `message_id` de-duplication until expiry, the hop limit, the payload caps, 32 relayed frames per lane, the holding caps and Plumtree. The route-request rate limit is deferred (ADR 0008 amendment). A member that floods fresh broadcasts, route requests or lookups costs every node in range airtime and lane slots. Decide whether to add per-neighbor rate limits.

Ticket 53 corrected the claim: the threat model now says per-neighbor rate limits are not built and points here.

Questions for the owner:

- Which frame types are rate-limited per neighbor (broadcasts, route requests, lookups, all relayed frames), and what happens above the limit: drop, delay or deprioritize?
- What rate do honest apps need in each density class, and what does the attacker gain at that rate?
- Does `unsecured` get the same limits, given that the claim names only the secured profiles?

## Evidence from ticket 53

Throwaway prototype on branch [`proto/broadcast-flood`](https://github.com/trancee/MeshLink/tree/proto/broadcast-flood), commit `723e83a`.

**Setup.**

- Seeded `VirtualAir`: 30 nodes at 10 kB/s per link, seeds 1–3, low and medium density, `EmergencyBroadcast` preset.
- The attacker is one member sending 900 B broadcasts at 20/s.

**Findings.** p95 is the 95th-percentile delivery time.

- **No defense:** honest unicast p95 is 17–45 s and honest broadcast p95 is 5–17 s. The flood fills the 32 relayed slots per lane.
- **Per-link drop** of relayed broadcasts over budget (rate 1–2/s, burst 5–10):
  - Honest unicast p95 is about 1.3 s, honest broadcast p95 is 0.1–1.8 s, and the attack reaches 0.23–0.76 of nodes.
  - The limit is shared per link, so honest broadcasts pay for it: coverage was 0.84–0.93 in one low-density seed.
  - An honest emergency burst, with every member broadcasting twice in 10 s, lost coverage (lowest 0.908) and had p95 tails up to 79 s.
- **Open:** whether per-origin fairness fixes the honest cost. It needs the origin to be authenticated, and it is exposed to Sybil identities.

## Triage decisions (owner, 2026-11-27)

1. Every relayed flood frame is limited: broadcasts, flooded unicast, `ROUTE_REQUEST` and `LOOKUP_REQUEST`. Each type has its own budget per incoming link.
2. A frame over budget is not relayed. It is still delivered locally if it is addressed to this node. Each drop is reported as a new Debug diagnostic, coalesced per link.
3. Signed broadcasts: per-link keying or per-link plus per-origin fair sharing is decided from a prototype. Its scenarios are an honest burst (every member broadcasts twice in 10 s), one identity flooding at 20/s, a rotating-key flood and a flooded-unicast flood, in low and medium density with seeds 1–3. The rate and burst values come from the honest-burst results.
4. Every profile gets the same per-link limits. Per-origin keying applies only to signed broadcasts in the secured profiles.
5. The values are fixed spec parameters per density class in `limits.json`. There is no public API.
6. The prototype lives on the throwaway branch `prototype/neighbor-rate-limits`. Its results come back to the owner for the value decision, then an AFK ticket implements the D-nn entry, the spec, the threat model and the ADR 0008 amendment.

## Prototype verdict (owner, 2026-11-27)

Prototype (throwaway, never merged): branch [`prototype/neighbor-rate-limits`](https://github.com/trancee/MeshLink/tree/prototype/neighbor-rate-limits), commit `628fd7a`, results in `prototype-74/README.md`.

Setup: 30 nodes at 10 kB/s, low and medium density, seeds 1–3, `EmergencyBroadcast` preset, one attacker sending at 20/s. Each value is the worst of the three seeds.

| Cell | Scenario | Limiter | Unicast p95 s | Honest broadcast coverage | Attack coverage |
|---|---|---|---|---|---|
| low | broadcast flood | none | 30.5 | 1.000 | 0.30–0.32 |
| low | broadcast flood | per link 2/10 | 2.1 | 0.850 | 0.13–0.21 |
| low | broadcast flood | per origin 1/5 + per-link total 4/20 | 15.1 | 1.000 | 0.08–0.16 |
| low | rotating-key flood | per origin 1/5 only | 30.5 | 1.000 | 0.30–0.32 |
| low | rotating-key flood | per origin 1/5 + per-link total 4/20 | 16.2 | 0.955 | 0.27–0.32 |
| low | honest burst | per link 2/10 | 5.1 | 0.976 | – |
| low | honest burst | per origin + per-link total 4/20 | 5.1 | 1.000 | – |
| low | unicast flood | none | 134.7 (0.598 delivered) | 0.695 | – |
| low | unicast flood | unicast 10/40 | 36.9 | 1.000 | – |
| low | unicast flood | unicast 5/20 | 15.1 | 1.000 | – |
| medium | broadcast flood | none | 29.9 | 0.996 | 0.31–0.32 |
| medium | broadcast flood | per origin + per-link total 4/20 | 1.4 | 1.000 | 0.07–0.08 |
| medium | rotating-key flood | per origin + per-link total 4/20 | 4.8 | 1.000 | 0.20–0.28 |
| medium | unicast flood | none | 74.6 (0.978 delivered) | 0.991 | – |
| medium | unicast flood | unicast 10/40 | 1.4 | 1.000 | – |

Decisions:

1. **Broadcasts.**
   - Secured profiles: each signed origin gets 1/s with a burst of 5, inside a per-link total of 4/s with a burst of 20.
   - `unsecured`: only the per-link 4/20 total, because a claimed origin can be spoofed to drain an honest node's budget.
   - Rejected per link 2/10, because honest broadcasts lose coverage (0.850 under attack, 0.976 in a burst).
   - Rejected per origin alone, which is no defense against rotating keys.
2. **Flooded unicast, per link:** low 5/s with a burst of 20; medium and high 10/s with a burst of 40.
3. **`ROUTE_REQUEST` and `LOOKUP_REQUEST`, per link:** 5/s with a burst of 20 in every class. Lookups were not measured.
4. **High density** uses the medium values. The implementation proves them with one `density.yml` run of all 8 cells.
5. Implemented by ticket 78 (D-63).

## Spec and ADR references

- [Threat model, Availability](../../../docs/explanation/threat-model.md#availability); [ADR 0001](../../../docs/adr/0001-threat-model-and-profile-guarantees.md) (adversary A4).
- [routing.md, Flood forwarding](../../../docs/spec/routing.md#flood-forwarding) and [Holding](../../../docs/spec/routing.md#holding).
- [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md) amendment "Route-request rate limit"; [ADR 0011](../../../docs/adr/0011-modules-toggles-and-presets.md) amendment "presets and toggles built" (ticket 53), which only reorders frames and drops nothing.

## Acceptance criteria

- [x] A seeded `VirtualAir` run measures one flooding member's effect on honest unicast and broadcast delivery and airtime, with and without a prototype per-neighbor limit, in a low- and a medium-density cell.
- [x] The owner decides; the decision is recorded as a D-nn entry, and the spec and threat model match it (limits are built, or the open note is replaced by the decision).
- [x] If adopted, an AFK ticket implements it (ticket 78).

## Proof commands

```sh
./gradlew :meshlink-testing:testAndroidHostTest --tests '*DensityCell*'
```

## Docs to update

- `docs/explanation/threat-model.md`, `docs/spec/routing.md`, `docs/spec/decisions.md`; ADR 0008 if adopted.

## Devices needed

None.
