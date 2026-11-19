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

- [x] A seeded `VirtualAir` test shows a failed dial is not repeated to the same peer within 3 s, repeated failures within 60 s, and a peer that closed the link or rotated within 120 s.
- [x] A seeded `VirtualAir` test fills a node to d links and shows it frees one slot after 30 s, which a newcomer then takes.
- [x] `./gradlew check` passes with Kover at 100%, and `./gradlew iosSimulatorArm64Test` passes.

## Outcome

- **Owner decisions:** (1) a "rotation" is this node closing a link itself to free a slot; today that is the full-node free, and diversity replacement (35b) will use it too. (2) Backoffs are keyed by radio address (`PeerHandle`); every `CLOSE` received on a kept link counts as a remote close, whatever its reason (spec "Close": reasons are diagnostic only). A peer whose BLE address rotates escapes its backoff early. (3) A full node frees a random kept link, drawn from the seeded `RandomSource`, with `CLOSE` `NORMAL`.
- **Views:** `dialed` records a failed dial (3 s after the first, 60 s after repeated ones) and clears the count on a kept link; `coolDown` keeps a handle undialed for 120 s; `dial` skips backed-off handles and forgets the failures of handles that left the candidates. `held` starts a 30 s timer when the node holds all 6 bearers, cancels it when it drops below, and then frees one random kept link.
- **Links:** a dial reports its result to `Views`; every tracked job's end now triggers the next dial, since a failure can no longer redial in a loop. A received `CLOSE` on a kept link and a freed link both start the 120 s cool-down.
- **Tests:** `ViewsTest` covers backoff, cool-down and the full-node timer. `NeighborListTest` adds a failed and repeated failed dial against an advertiser with an unsupported version, a remote close, a full node freeing a slot that a newcomer takes, a node dropping below budget freeing nothing, and a freed peer not redialed for 120 s. All 19 mutations of the new branches and constants fail a test.
- **Test adjustment:** `eachNodeSendsItsIdentifiers…` asserted a list at the exact rotation instant; churn from the full-node free made a list fall within 2 s before a rotation, which defers the rotation list per `neighbor-list-min-interval`. It now asserts the last list at or before each rotation carries the old `own_id` and the new `next_id`, which is what the spec requires.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- None expected; both rules are local (spec README, "Purely local choices").

## Devices needed

None. Host and simulator only.
