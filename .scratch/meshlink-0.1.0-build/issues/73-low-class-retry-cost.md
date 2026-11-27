# Bound the cost of low-class message retries

Status: done
Type: HITL
Blocked by: 42b

## Behavior

Found in ticket 42b (D-61). With the `attempt` byte and low-class delivery receipts on a route or wide flood, every density cell met its bands in a local run of seeds 1–5 except n200-low, whose busiest node wrote 5,187 B/s against 4,700 B/s while delivering every message. The remaining load is low-class `MESSAGE` retries: [origin behavior](../../../docs/spec/routing.md#origin-behavior) retries on a doubling timer from 2 s up to 30 s until a receipt arrives, and on each new link at most once per `link-retry-gap`. In the low class every retry floods the whole mesh with `hop_limit` 255, so in a 200-node low-density mesh one retry costs about as much as the first send. Clamping relayed receipts to the flood radius cut receipt writes but raised message retries to 682,000 relay writes and lost one message, so it was reverted.

Questions for the owner:

- Should a low-class retry reuse an active route (learned from the receipt's or another frame's source tag) before flooding again?
- Should the low class cap retries per message, or lengthen its retry schedule by mesh size or measured airtime (ticket 71)?
- Is a retry on a new link still needed in the low class once a wide flood has reached every connected node?

## Triage decisions (owner, 2026-11-26)

Prototype (throwaway, never merged): branch `prototype/low-retry-cost`, commit `c6b1f6e`, results in `prototype-73/README.md`. n200-low, seeds 1–5, worst seed's busiest node (band 4,700 B/s); relay writes summed over the seeds:

| Rule | Busiest B/s | Delivered | Message retry relays | Receipt relays | All relays |
|---|---|---|---|---|---|
| D-61 (main) | 4,755 | 2,032/2,034 | 1.90 M | 2.85 M | 6.17 M |
| route: resend on an active route, else flood | 4,971 | 2,033/2,034 | 1.94 M | 2.95 M | 6.32 M |
| slow: 5 s to 60 s | 4,596 | 2,032/2,034 | 1.73 M | 2.66 M | 5.83 M |
| nolink: no new-link retries | 4,896 | 2,060/2,062 | 1.24 M | 3.35 M | 6.04 M |
| cap3: at most 3 timed retries | 4,735 | 2,008/2,008 | 1.89 M | 2.58 M | 5.86 M |
| routeslow: route and slow | 5,005 | 2,010/2,012 | 1.80 M | 2.86 M | 6.05 M |

1. Adopt `slow`: the low class retries 5 s doubling to 60 s; the medium class keeps 2 s to 30 s (D-62, ADR 0008 amendment of 2026-11-26).
2. Reject `route`, `nolink`, `cap3` and `routeslow`; their numbers are recorded in D-62.
3. Delivery receipts are about 46 % of relay writes: ticket 77 (needs-triage).
4. The implementation (ticket 76) passes one `density.yml` run of all 8 cells with 5 seeds.
5. The prototype is kept on its branch and linked here.

## Spec and ADR references

- [routing.md, Origin behavior](../../../docs/spec/routing.md#origin-behavior); [D-61](../../../docs/spec/decisions.md#d-61-unicast-retry-attempt).
- [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md), amendment 2026-11-25 "unicast retry attempt"; [ADR 0017](../../../docs/adr/0017-testing-architecture.md), amendment "density suites".
- Ticket 71 (airtime-scaled timers).

## Acceptance criteria

- [x] A seeded `VirtualAir` comparison at n200-low, seeds 1–5, of at least two retry rules against D-61: busiest-node bytes, delivery and relay writes by frame type.
- [x] The owner decides; the decision is recorded as a D-nn entry with an ADR 0008 amendment.
- [x] If adopted, an AFK ticket implements it and one `density.yml` run of all 8 cells with 5 seeds passes every band.

## Proof commands

```sh
./gradlew :meshlink-testing:densityTest -Pdensity.cells=n200-low
gh workflow run density.yml
```

## Devices needed

None.
