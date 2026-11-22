# Pass every scheduled density band

Status: ready-for-agent
Type: AFK
Blocked by: 42, 47

## Behavior

Split from ticket 42 (owner decision). Ticket 42's scheduled suite enforces every band of the routing simulation, and one run must pass all of them.

- **Measured miss (seed 1, ticket 42):** every cell delivered at least 99% of unicast and broadcast recipients (n100-high 342 of 343 unicast), but the busiest node wrote 6,756 B/s at n50-low and 6,226 B/s at n100-medium, against a band of 4,700 B/s. n100-high passed at 2,345 B/s.
- **Cause:** 96% of the busiest node's bytes were `MESSAGE` frames, 15,244 frames for 1,517 distinct bodies; one origin wrote one body 228 times. [Origin behavior](../../../docs/spec/routing.md#origin-behavior) resends with backoff until expiry and on every new link, and at low density every resend floods. The routing simulator, whose bands the suite copies, stopped resending at the first receipt.
- **Expected fix:** delivery receipts (ticket 47) end resends. If a run still misses a band after 47, the cause goes to the owner before any rule changes; the bands are never tuned.

## Spec and ADR references

- [ADR 0017](../../../docs/adr/0017-testing-architecture.md), 2026-11-22 amendment "density suites".
- [routing.md, Origin behavior](../../../docs/spec/routing.md#origin-behavior).
- [Routing simulation, criterion 3b](../../../docs/evidence/2026-11-routing-simulation/README.md#pre-registered-criteria-and-amendments).

## Acceptance criteria

- [ ] One `density.yml` run of all 8 cells with 5 seeds passes every band. Its per-cell results are attached to the PR.
- [ ] Ticket 42's acceptance box "One scheduled run passes every band" is checked with a link to that run.

## Proof commands

```sh
gh workflow run density.yml
./gradlew :meshlink-testing:densityTest -Pdensity.cells=n50-low
```

## Docs to update

- ADR 0017's density amendment: replace the known-miss bullet with the passing run.
- Spine M5 exit: drop the note that points here.

## Devices needed

None. CI only.
