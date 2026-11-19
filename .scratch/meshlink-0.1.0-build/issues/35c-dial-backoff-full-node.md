# Back off dials and free a slot on a full node

Status: ready-for-agent
Type: AFK
Blocked by: 35a

## Behavior

Split from ticket 35a (Q2). These two rules are coupled: a node that frees a slot without a dial cool-down lets the closed peer redial at once.

- **Dial backoff to one peer:** 3 s after the first failed dial, 60 s after repeated failures, and a 120 s cool-down after the peer rotated or closed the link remotely.
- **Full node:** a node that has held d links for 30 s frees one slot, so newcomers can join it (newcomer reservation N3).

## Spec and ADR references

- [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md) decision 1 (newcomer reservation).
- [v1 parameters, Neighbor selection and topology](../../../docs/evidence/2026-11-routing-simulation/v1-parameters.md#neighbor-selection-and-topology): "Newcomer reservation (N3)" and "Dial backoff to one peer".
- [link.md, Connect retries and link caps](../../../docs/spec/link.md#connect-retries-and-link-caps): connect retries inside one dial stay as they are.

## Acceptance criteria

- [ ] A seeded `VirtualAir` test shows a failed dial is not repeated to the same peer within 3 s, repeated failures within 60 s, and a peer that closed the link or rotated within 120 s.
- [ ] A seeded `VirtualAir` test fills a node to d links and shows it frees one slot after 30 s, which a newcomer then takes.
- [ ] `./gradlew check` passes with Kover at 100%, and `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- None expected; both rules are local (spec README, "Purely local choices").

## Devices needed

None. Host and simulator only.
