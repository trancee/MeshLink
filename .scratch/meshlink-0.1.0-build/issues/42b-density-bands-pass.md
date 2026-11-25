# Pass every scheduled density band

Status: ready-for-agent
Type: AFK
Blocked by: 42, 47

## Behavior

Split from ticket 42 (owner decision). Ticket 42's scheduled suite enforces every band of the routing simulation, and one run must pass all of them.

- **Measured miss (seed 1, ticket 42):** every cell delivered at least 99% of unicast and broadcast recipients (n100-high 342 of 343 unicast), but the busiest node wrote 6,756 B/s at n50-low and 6,226 B/s at n100-medium, against a band of 4,700 B/s. n100-high passed at 2,345 B/s.
- **Scheduled run, 5 seeds ([run 45047999300](https://github.com/trancee/MeshLink/actions/runs/45047999300)):** every cell meets the delivery and idle-control bands, and n50-medium (3,720 B/s) and n100-high (2,506 B/s) pass. The busiest node, max over seeds, wrote 6,756 B/s at n50-low, 13,419 at n100-low, 6,763 at n100-medium, 24,132 at n200-low, 13,036 at n200-medium and 4,987 at n200-high.
- **Cause:** 96% of the busiest node's bytes were `MESSAGE` frames, 15,244 frames for 1,517 distinct bodies; one origin wrote one body 228 times. [Origin behavior](../../../docs/spec/routing.md#origin-behavior) resends with backoff until expiry and on every new link, and at low density every resend floods. The routing simulator, whose bands the suite copies, stopped resending at the first receipt.
- **Expected fix:** delivery receipts (ticket 47) end resends. If a run still misses a band after 47, the cause goes to the owner before any rule changes; the bands are never tuned.

## Outcome (2026-11-25, owner decision "E+E2-split")

- **Cause after receipts:** with the 1 s `unicast-seen-window`, late echoes of one send passed relays again and flooded with `hop_limit` 255 (n50-low: 17,002 first forwards, 312,149 echoes).
- **Fix (D-61):** a `u8` `attempt` in the unicast header, a 30 s seen window per `(message_id, attempt)`, and low-class delivery receipts on a route or a wide flood without route requests.
- **Local run, seeds 1–5:** every cell meets every band except n200-low, busiest node 5,187 B/s, every message delivered. Ticket 73 tracks the remaining low-class retry cost; the first acceptance box stays open until it lands.

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
