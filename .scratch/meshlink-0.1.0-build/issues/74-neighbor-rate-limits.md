# Decide how relays rate-limit each neighbor

Status: needs-triage
Type: HITL
Blocked by: none

## Behavior

Found in ticket 53 (broadcast priority threat review). The threat model's "Availability" section says that in the secured profiles MeshLink "rate-limits each neighbor". The relay path doesn't: flood frames are bounded only by `message_id` de-duplication until expiry, the hop limit, the payload caps, 32 relayed frames per lane, the holding caps and Plumtree. The route-request rate limit is deferred (ADR 0008 amendment). A member that floods fresh broadcasts, route requests or lookups costs every node in range airtime and lane slots. Decide whether to add per-neighbor rate limits, or correct the claim.

Questions for the owner:

- Which frame types are rate-limited per neighbor (broadcasts, route requests, lookups, all relayed frames), and what happens above the limit: drop, delay or deprioritize?
- What rate do honest apps need in each density class, and what does the attacker gain at that rate?
- Does `unsecured` get the same limits, given that the claim names only the secured profiles?

## Spec and ADR references

- [Threat model, Availability](../../../docs/explanation/threat-model.md#availability); [ADR 0001](../../../docs/adr/0001-threat-model-and-profile-guarantees.md) (adversary A4).
- [routing.md, Flood forwarding](../../../docs/spec/routing.md#flood-forwarding) and [Holding](../../../docs/spec/routing.md#holding).
- [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md) amendment "Route-request rate limit"; [ADR 0011](../../../docs/adr/0011-modules-toggles-and-presets.md) amendment "Broadcast priority" (ticket 53), whose per-link priority budget covers only scheduling.

## Acceptance criteria

- [ ] A seeded `VirtualAir` run measures one flooding member's effect on honest unicast and broadcast delivery and airtime, with and without a prototype per-neighbor limit, in a low- and a medium-density cell.
- [ ] The owner decides; the decision is recorded as a D-nn entry, and the spec and threat model match it (the claim is either implemented or removed).
- [ ] If adopted, an AFK ticket implements it.

## Proof commands

```sh
./gradlew :meshlink-testing:testAndroidHostTest --tests '*DensityCell*'
```

## Docs to update

- `docs/explanation/threat-model.md`, `docs/spec/routing.md`, `docs/spec/decisions.md`; ADR 0008 if adopted.

## Devices needed

None.
