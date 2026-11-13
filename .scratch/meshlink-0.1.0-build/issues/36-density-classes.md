# Adapt to low, medium and high local density

Status: ready-for-agent
Type: AFK
Blocked by: 35

## Behavior

Each node estimates its own local density and moves between the low, medium and high classes. Its link budget and scan schedule follow the class. Later routing tickets (37–40, 50) read the class to choose their per-class behavior. Switching never changes the wire format, and every node keeps receiving and relaying every frame type.

- **Estimate:** distinct advertisement identifiers heard in the last 10 s, plus distinct addresses of identifier-less MESH sightings, plus linked peers not heard. It is smoothed with weight 0.3 per scan. The first estimate is unsmoothed.
- **Classes:** low under 10, medium 10–50, high over 50. A node is low until its first estimate.
- **Switching:** up at 12.5 and 62.5, down below 8 and 40, with at least 60 s in a class. The first classification is immediate.
- **Link budget d:** 6 at low, 5 at medium and high (ticket 35's active view).
- **Scan windows, seconds on / off:** `Balanced` 4/4, 4/12, 4/16 and `Responsive` 4/2, 4/8, 4/12 for low, medium and high. This ticket adds an internal policy parameter that tests set. Choosing the policy through the public `power` setting and the presets is ticket 53. Advertising is unchanged.
- **Pinning:** an internal pin fixes the class, for tests and for ticket 53's `Density` toggle. It is not public API in this ticket.
- **Visibility:** the class and switch events are internal. Their diagnostic codes are cataloged in ticket 54 (ADR 0008 decision 8).

## Spec and ADR references

- [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md) decisions 1, 2, 3 and 8; amendments 2026-11-02 (local density), 2026-11-08 (scan and advertise) and stage F (scan schedule table).
- [v1 parameters, Local density estimator and classifier](../../../docs/evidence/2026-11-routing-simulation/v1-parameters.md#local-density-estimator-and-classifier) and [Scan and advertise duty cycle](../../../docs/evidence/2026-11-routing-simulation/v1-parameters.md#scan-and-advertise-duty-cycle).
- [ADR 0011](../../../docs/adr/0011-modules-toggles-and-presets.md) decisions 8 and 10 (the `Density` toggle is local only) and the 2026-11-08 power-policy amendment (`power` picks `Balanced` or `Responsive`).
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decisions 1 and 2 (clock and randomness come in; virtual time).

## Acceptance criteria

- [ ] A unit test drives the estimator with scripted sightings and checks the 10 s window, the 0.3 smoothing, the unsmoothed first estimate and the counting of linked-but-unheard peers and identifier-less sightings.
- [ ] A unit test walks the classifier across 8, 10, 12.5, 40, 50 and 62.5 in both directions and checks the hysteresis and the 60 s dwell.
- [ ] A seeded `VirtualAir` test grows a mesh from 2 to 70 nodes in virtual time and back down. Each node reaches the expected class and changes its link budget to d = 6 / 5 / 5.
- [ ] A seeded test checks the scan windows per class for both policies against the fake radio's scan start and stop calls.
- [ ] A seeded mixed-class mesh test shows nodes in different classes still exchange and relay every link frame type built so far.
- [ ] A pinned class never switches, whatever the estimate.
- [ ] No public API changes in this ticket (the ABI dump is unchanged).
- [ ] `./gradlew check` passes with Kover at 100%, and `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- None. The behavior is internal, and [GLOSSARY.md](../../../GLOSSARY.md) already defines local density. The `power` and `Density` docs land with ticket 53.

## Devices needed

None. Host and simulator only. Real scan timing is ticket 32's.
