# Repair split meshes and diversify links

Status: done
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

- [x] `VirtualAir` can place radios out of range of each other, seeded and deterministic.
- [x] A seeded split-mesh test repairs through the bridge rule and dials at most once per 60 s.
- [x] A seeded test shows diversity replacement every 30 s under the overlap rule.
- [x] `./gradlew check` passes with Kover at 100%, and `./gradlew iosSimulatorArm64Test` passes.

## Outcome

- **Owner decisions:** (1) Stop rotation churn with all three of: a list resend when a linked peer's first list arrives or names a new `own_id`; a bridge window of 5 to 13 s after first sighting; and a 5 s settle for replacement candidates. (2) Resolve crossed bridge or replacement dials with crossed-dial slack: while its own such dial is in flight, a node accepts one bearer beyond its budget. (3) The RSSI floors and former-list reuse move to ticket 35d.
- **Views:** `dial` picks a bridge (an identifier-less candidate, or one first heard 5 to 13 s ago that the node doesn't know and no linked peer's list names) first, at most once per 60 s. A full node frees its most redundant link, if one has redundancy, and dials the bridge into that slot. Every 30 s `diversify` lets a node at its last free slot dial a settled candidate whose overlap is at least 2 below its most redundant link's redundancy. `kept` frees the most redundant other link once a bridge or replacement link is kept at the budget. `accepts` allows the slack. To keep each class within detekt's function limit, the passive view moved to `PassiveView` and dial backoff to `Backoff`, unchanged in behavior.
- **NeighborLists:** `received` resends on a new or renamed peer; `nearby`, `overlap` and `redundancy` feed the rules. **Scanner:** candidates carry `since` (first heard, reset by a new identifier or 60 s unheard) and `known` (resolved to a peer). **VirtualAir:** radios have positions and a 30 m default range; `move` drops links that leave range.
- **Tests:** `ViewsTest` covers the bridge window, eviction, `kept`, replacement and slack. `ScannerCandidateTest` covers `since` and `known`. `NeighborListTest` adds the resend, an 8-node mesh that keeps its links for 20 min after settling, a 12-node split mesh repaired within the bridge window with at most one cross dial per node, and replacement on the 30 s tick. 32 of 33 mutations fail a test; the survivor was the `advertises` term in `nearby`, removed as dead because a node never sights its own identifier. A mutant of the rewritten `redundancy` early return replaces it, and it fails a test. A further `ViewsTest` case covers a bridge kept at the budget with no other link to free. The `GAP_MILLIS` `<` to `<=` mutant is equivalent at the 8 s scan period.
- **Evidence:** before the settle and resend, an 8-node mesh churned to about 110 link ends per node in 1 h (about 7 on `main`); with both it stays at 5 to 7. In 100 split-mesh runs before the slack, 9 had not repaired 40 s after the move and 2 took 31 s; with it, all 100 repaired 13 s after the move.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- None expected. [What each observer learns](../../../docs/reference/observer-exposure.md) already covers neighbor lists and shuffles. If the code shows a different exposure, update that page in this PR.
- Updated: spec "Neighbor list" (resend on a renamed peer), the ADR 0008 amendment of 2026-11-19, `CHANGELOG.md` and `docs/agents/lessons.md`. The observer page is unchanged: the resend sends the same frame to the same linked peers, a little more often.

## Devices needed

None. Host and simulator only.
