# Decide how relays rate-limit each neighbor

Status: needs-triage
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

- **No defence:** honest unicast p95 is 17–45 s and honest broadcast p95 is 5–17 s. The flood fills the 32 relayed slots per lane.
- **Per-link drop** of relayed broadcasts over budget (rate 1–2/s, burst 5–10):
  - Honest unicast p95 is about 1.3 s, honest broadcast p95 is 0.1–1.8 s, and the attack reaches 0.23–0.76 of nodes.
  - The limit is shared per link, so honest broadcasts pay for it: coverage was 0.84–0.93 in one low-density seed.
  - An honest emergency burst, with every member broadcasting twice in 10 s, lost coverage (lowest 0.908) and had p95 tails up to 79 s.
- **Open:** whether per-origin fairness fixes the honest cost. It needs the origin to be authenticated, and it is exposed to Sybil identities.

## Spec and ADR references

- [Threat model, Availability](../../../docs/explanation/threat-model.md#availability); [ADR 0001](../../../docs/adr/0001-threat-model-and-profile-guarantees.md) (adversary A4).
- [routing.md, Flood forwarding](../../../docs/spec/routing.md#flood-forwarding) and [Holding](../../../docs/spec/routing.md#holding).
- [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md) amendment "Route-request rate limit"; [ADR 0011](../../../docs/adr/0011-modules-toggles-and-presets.md) amendment "presets and toggles built" (ticket 53), which only reorders frames and drops nothing.

## Acceptance criteria

- [ ] A seeded `VirtualAir` run measures one flooding member's effect on honest unicast and broadcast delivery and airtime, with and without a prototype per-neighbor limit, in a low- and a medium-density cell.
- [ ] The owner decides; the decision is recorded as a D-nn entry, and the spec and threat model match it (limits are built, or the open note is replaced by the decision).
- [ ] If adopted, an AFK ticket implements it.

## Proof commands

```sh
./gradlew :meshlink-testing:testAndroidHostTest --tests '*DensityCell*'
```

## Docs to update

- `docs/explanation/threat-model.md`, `docs/spec/routing.md`, `docs/spec/decisions.md`; ADR 0008 if adopted.

## Devices needed

None.
