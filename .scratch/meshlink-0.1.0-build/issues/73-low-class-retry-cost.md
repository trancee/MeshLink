# Bound the cost of low-class message retries

Status: needs-triage
Type: HITL
Blocked by: 42b

## Behavior

Found in ticket 42b (D-61). With the `attempt` byte and low-class delivery receipts on a route or wide flood, every density cell met its bands in a local run of seeds 1–5 except n200-low, whose busiest node wrote 5,187 B/s against 4,700 B/s while delivering every message. The remaining load is low-class `MESSAGE` retries: [origin behavior](../../../docs/spec/routing.md#origin-behavior) retries on a doubling timer from 2 s up to 30 s until a receipt arrives, and on each new link at most once per `link-retry-gap`. In the low class every retry floods the whole mesh with `hop_limit` 255, so in a 200-node low-density mesh one retry costs about as much as the first send. Clamping relayed receipts to the flood radius cut receipt writes but raised message retries to 682,000 relay writes and lost one message, so it was reverted.

Questions for the owner:

- Should a low-class retry reuse an active route (learned from the receipt's or another frame's source tag) before flooding again?
- Should the low class cap retries per message, or lengthen its retry schedule by mesh size or measured airtime (ticket 71)?
- Is a retry on a new link still needed in the low class once a wide flood has reached every connected node?

## Spec and ADR references

- [routing.md, Origin behavior](../../../docs/spec/routing.md#origin-behavior); [D-61](../../../docs/spec/decisions.md#d-61-unicast-retry-attempt).
- [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md), amendment 2026-11-25 "unicast retry attempt"; [ADR 0017](../../../docs/adr/0017-testing-architecture.md), amendment "density suites".
- Ticket 71 (airtime-scaled timers).

## Acceptance criteria

- [ ] A seeded `VirtualAir` comparison at n200-low, seeds 1–5, of at least two retry rules against D-61: busiest-node bytes, delivery and relay writes by frame type.
- [ ] The owner decides; the decision is recorded as a D-nn entry with an ADR 0008 amendment.
- [ ] If adopted, an AFK ticket implements it and one `density.yml` run of all 8 cells with 5 seeds passes every band.

## Proof commands

```sh
./gradlew :meshlink-testing:densityTest -Pdensity.cells=n200-low
gh workflow run density.yml
```

## Devices needed

None.
