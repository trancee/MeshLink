# Test routing at every density

Status: ready-for-agent
Type: AFK
Blocked by: 38, 41

## Behavior

Two seeded `VirtualAir` suites guard routing against regressions. A failure is a regression, not radio truth (T9).

- **Pull-request suite:** meshes of 2–12 nodes in `commonTest`, under virtual time, in the required `./gradlew check` and `./gradlew iosSimulatorArm64Test` runs. It covers what exists when this ticket merges: neighbor views and shuffles, density switching, routed unicast with route discovery, flooded and Plumtree broadcasts, catch-up, and link churn. Ticket 47 adds receipts and ticket 49 adds store-and-forward to this suite.
- **Scheduled suite:** meshes of 50, 100 and 200 nodes at low, medium and high density, in a new non-required scheduled CI job (spine "Scheduled jobs"). It asserts the delivery and airtime bands of the routing simulation:
  - delivery within a connected component of at least 99% at low and medium density and at least 95% at high density; at low density, within 0.5 pp of flooding in the same cell where flooding misses 99%;
  - criteria applied by the sender's true local density at send time;
  - idle control traffic at most 10% of link capacity;
  - the busiest node sends at most 4,700 B/s of routing bytes;
  - a delivery criterion applies to a sub-population only with at least 100 messages pooled over 5 seeds; smaller ones are reported with n.
- The energy band (+3 mW) needs the simulator's energy model, which `VirtualAir` lacks. It stays out; ADR 0017 decision 11 asks for delivery and airtime only.

## Spec and ADR references

- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decisions 2, 3, 11, 12 and 13.
- [Routing simulation, Pre-registered criteria and amendments](../../../docs/evidence/2026-11-routing-simulation/README.md#pre-registered-criteria-and-amendments) (criteria 1 and 2, the stage B and C amendments, 3b, minimum sample) and [Final criteria check](../../../docs/evidence/2026-11-routing-simulation/README.md#final-criteria-check).
- [v1 parameters](../../../docs/evidence/2026-11-routing-simulation/v1-parameters.md) (the parameters the meshes run with).
- [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md) decisions 1–5.

## Acceptance criteria

- [ ] The agent maps the simulator's link capacity, mobility and cell definitions onto `VirtualAir`, picks the scheduled cells and seeds, and records the mapping and any gap in the PR. Bands are copied from the evidence, never tuned to pass.
- [ ] The pull-request suite has at least one seeded scenario per covered behavior, including a line, a star, a 12-node random mesh and a churn run that drops and restores links. Each asserts delivery within lifetime, exactly once and intact.
- [ ] Every test has a timeout; nothing retries (ADR 0017 decision 13). Each test is deterministic: two runs with one seed give identical traces.
- [ ] The PR states the suite's measured wall time and shows the pull-request tier still fits within 15 min.
- [ ] The scheduled job runs `./gradlew :meshlink-testing:densityTest`, a Gradle task outside `check`, on the Android host-test runtime of `:meshlink-testing`. The job is not required and publishes per-cell results with n. The PR adds the command to the spine's proof-command table (Constitution O1).
- [ ] One scheduled run passes every band. Its results are attached to the PR.
- [ ] `./gradlew check` passes with Kover at 100%; `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
./gradlew :meshlink-testing:densityTest
```

`./gradlew :meshlink-testing:densityTest` is new: this ticket creates it and adds it to the spine's proof-command table.

## Docs to update

- [Spine](../spec.md#proof-commands): the `./gradlew :meshlink-testing:densityTest` row, created by 42.
- `PROJECT.md`: the scheduled density command in the verified profile.
- Otherwise none. The suites implement ADR 0017 decision 11 as written. If a simulator cell cannot be matched, the gap goes to the owner, not into a weaker band.

## Devices needed

None. Host, simulator and CI only.
