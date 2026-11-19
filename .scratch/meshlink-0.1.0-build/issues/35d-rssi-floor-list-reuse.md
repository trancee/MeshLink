# Dial only peers above the RSSI floor and reuse former neighbor lists

Status: done
Type: AFK
Blocked by: 35b

## Behavior

Split from ticket 35b (owner decision 3). Two v1 parameters of neighbor selection that 35b left out:

- **RSSI floor for dialing:** a node dials only candidates heard at −85 dBm or stronger; a bridge may be dialed down to −88 dBm (the relaxed floor of the bridge rule).
- **Former neighbor list reuse:** a node keeps a peer's last neighbor list for 120 s after its link closes, and uses it for the overlap and bridge decisions as if still linked.

## Spec and ADR references

- [v1 parameters, Neighbor selection and topology](../../../docs/evidence/2026-11-routing-simulation/v1-parameters.md#neighbor-selection-and-topology): "RSSI floor for dialing" and "Former neighbor list reuse"; [Bridge rule](../../../docs/evidence/2026-11-routing-simulation/v1-parameters.md#bridge-rule): the relaxed floor.
- [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md) decision 1 and the 2026-11-19 amendment.

## Acceptance criteria

- [x] A seeded `VirtualAir` test shows a candidate weaker than −85 dBm is not dialed, and a bridge between −88 and −85 dBm is.
- [x] A seeded test shows a closed peer's list still counts for 120 s and not after.
- [x] `./gradlew check` passes with Kover at 100%, and `./gradlew iosSimulatorArm64Test` passes.

## Outcome

- **Owner decisions:** (1) The floors apply to a per-candidate signal smoothed with α = 0.25, which restarts whenever the candidate's first-heard time does. (2) A former list counts only in the bridge test and the overlap count, not in a link's redundancy or in resolving a peer; it lasts 120 s and a relink replaces it at once.
- **Views:** a bridge needs a smoothed signal of at least −88 dBm and an ordinary or replacement dial at least −85 dBm. **Scanner:** `Candidate.rssi` carries the smoothed signal. **NeighborLists:** `unlinked` keeps the peer's list as a former list; `nearby` and `overlap` count those closed less than 120 s ago.
- **Tests:** `ViewsTest` covers both floors and a former list that counts at 120 s minus one tick, not at 120 s, and stops counting once the peer relinks. `ScannerCandidateTest` covers the smoothing and its restart. `NeighborListTest` shows a pair at −89 dBm never links and a pair at −87 dBm out of reach of the rest links as a bridge. In `VirtualAir` every fresh candidate is first a bridge, so the −85 dBm ordinary floor is proven in `ViewsTest`. All 16 mutations fail a test.
- **VirtualAir:** signals are drawn between −80 and −40 dBm, above the dial floor. A central's link now keeps the handle it dialed: before, a peripheral that restarted advertising during the connect gave it a new one, so the 12-node split-mesh test kept a node one bearer over budget in 20 of 40 runs with former lists (0 of 40 after the fix; `VirtualAirTest` covers it).

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- None expected; both rules are local (spec README, "Purely local choices").
- Updated: the ADR 0008 amendment of 2026-11-19 (Version 12), `CHANGELOG.md` and `docs/agents/lessons.md`.

## Devices needed

None. Host and simulator only.
