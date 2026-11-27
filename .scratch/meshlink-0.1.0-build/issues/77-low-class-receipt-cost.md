# Bound the cost of low-class delivery receipts

Status: needs-triage
Type: HITL
Blocked by: 76

## Behavior

Found in ticket 73. At n200-low, seeds 1–5, delivery receipts made about 46 % of all relay writes: 2.66 M of 5.83 M with the D-62 schedule, 2.85 M of 6.17 M before it. A low-class destination sends its receipt on an active route, else floods it with `hop_limit` 255 (D-61), so a receipt costs about as much as the message it acknowledges. With D-62 the worst seed's busiest node wrote 4,596 B/s against the 4,700 B/s band, so n200-low has little margin left. Clamping receipts to the flood radius at relays cut receipt writes but lost one message and raised message retries (ticket 42b).

Questions for the owner:

- Should a low-class receipt prefer a reverse route learned from the message it acknowledges?
- Should receipts batch longer, or ride on other frames toward the origin, in the low class?
- Is the margin under the band enough, or should this wait for airtime-scaled timers (ticket 71)?

## Spec and ADR references

- [routing.md, Origin behavior](../../../docs/spec/routing.md#origin-behavior); [D-61](../../../docs/spec/decisions.md#d-61-unicast-retry-attempt), [D-62](../../../docs/spec/decisions.md#d-62-low-class-retry-schedule).
- [ADR 0013](../../../docs/adr/0013-message-lifecycle-and-receipts.md); ADR 0008 amendments of 2026-11-25 and 2026-11-26.

## Acceptance criteria

- [ ] A seeded `VirtualAir` comparison at n200-low, seeds 1–5, of at least one receipt rule against D-62: busiest-node bytes, delivery and relay writes by frame type.
- [ ] The owner decides; the decision is recorded as a D-nn entry with an ADR amendment.

## Proof commands

```sh
./gradlew :meshlink-testing:densityTest -Pdensity.cells=n200-low
```

## Devices needed

None.
