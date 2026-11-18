# Keep active and passive neighbor views with shuffles

Status: ready-for-agent
Type: AFK
Blocked by: 35

## Behavior

Split from ticket 35 (Q1). Each node keeps a HyParView-style active view (its live links) and a passive view (a peer table of advertisement identifiers), filled by `SHUFFLE` / `SHUFFLE_REPLY` exchanges. This replaces dialing every candidate.

- **Active view:** link budget d = 6, the low-class value. Ticket 36 switches d per density class. One slot stays free for newcomers.
- **Shuffle:** every 10 s on one random link, with 1–5 passive-view entries. The receiver answers on the same link with 0–5 entries and merges what it received.
- **Passive view:** 4·d entries, random replacement when full. Only peers heard in the last 4 s are dialed.

Entries are the 8 B on-air advertisement identifiers, never NodeIds or tags.

## Spec and ADR references

- [link.md, Neighbor list](../../../docs/spec/link.md#neighbor-list) and [Shuffle](../../../docs/spec/link.md#shuffle); [D-24](../../../docs/spec/decisions.md#d-24-neighbor-list) and [C-10](../../../docs/spec/decisions.md#c-10).
- [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md) decision 1 and the 2026-11-02 bridge-rule amendment.
- [ADR 0016](../../../docs/adr/0016-routing-metadata-exposure.md) decision 3 (identifiers, direct neighbors only).
- [v1 parameters, Neighbor selection and topology](../../../docs/evidence/2026-11-routing-simulation/v1-parameters.md#neighbor-selection-and-topology) and [Bridge rule](../../../docs/evidence/2026-11-routing-simulation/v1-parameters.md#bridge-rule) (passive view size, eviction, diversity interval).
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decisions 2, 9 and 10.

## Acceptance criteria

- [ ] A seeded 12-node test shows one shuffle every 10 s on one link, replies on the same link, a passive view bounded at 4·d, and a free newcomer slot.
- [ ] A node never holds more than d links, and existing multi-node tests keep passing under the link budget.
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
