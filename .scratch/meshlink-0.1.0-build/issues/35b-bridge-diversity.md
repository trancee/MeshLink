# Repair split meshes and diversify links

Status: ready-for-agent
Type: AFK
Blocked by: 35a

## Behavior

Split from ticket 35 (Q1). Nodes pick and replace links with a diversity preference, and a newly heard peer bridges split meshes. `VirtualAir` gains a range model so tests can build a split mesh.

- **Bridge rule:** a newly heard peer outside the known 1–2-hop neighborhood is dialed first, at most once per 60 s. Sightings that resolve to a known peer through its resolving key are skipped.
- **Diversity replacement:** every 30 s, the most redundant link is replaced if a candidate overlaps at least 2 fewer peers in the 1–2-hop set.

## Spec and ADR references

- [link.md, Neighbor list](../../../docs/spec/link.md#neighbor-list) and [Shuffle](../../../docs/spec/link.md#shuffle); [D-24](../../../docs/spec/decisions.md#d-24-neighbor-list) and [C-10](../../../docs/spec/decisions.md#c-10).
- [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md) decision 1 and the 2026-11-02 bridge-rule amendment.
- [ADR 0016](../../../docs/adr/0016-routing-metadata-exposure.md) decision 3 (identifiers, direct neighbors only).
- [v1 parameters, Neighbor selection and topology](../../../docs/evidence/2026-11-routing-simulation/v1-parameters.md#neighbor-selection-and-topology) and [Bridge rule](../../../docs/evidence/2026-11-routing-simulation/v1-parameters.md#bridge-rule) (passive view size, eviction, diversity interval).
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decisions 2, 9 and 10.

## Acceptance criteria

- [ ] `VirtualAir` can place radios out of range of each other, seeded and deterministic.
- [ ] A seeded split-mesh test repairs through the bridge rule and dials at most once per 60 s.
- [ ] A seeded test shows diversity replacement every 30 s under the overlap rule.
- [ ] `./gradlew check` passes with Kover at 100%, and `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- None expected. [What each observer learns](../../../docs/reference/observer-exposure.md) already covers neighbor lists and shuffles. If the code shows a different exposure, update that page in this PR.

## Devices needed

None. Host and simulator only.
