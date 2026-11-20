# Catch up missed broadcasts after reconnecting

Status: done
Type: AFK
Blocked by: 40

## Behavior

When a link comes up, each side sends one `CATCH_UP` frame listing the broadcasts it received in the last 60 s. The peer grafts the ones it lacks, so a node that was out of range gets recent broadcasts on reconnecting. This applies in every density class.

- **Frame:** `CATCH_UP` (`0x0A`), link-local, with three `u16`-counted lists: offered message IDs (at most 128), held `{message ID, destination tag}` (at most 100) and tombstones `{message ID, k}` (at most 128). At most 7,127 B.
- **This ticket fills only the offered list.** The held list stays empty until ticket 49; the tombstone list stays empty until ticket 51. The codec handles all three lists now, so later tickets add no new layout.
- **Overflow:** if a list would exceed its maximum, the sender keeps the newest entries.
- **Request:** the receiver sends `GRAFT` (ticket 40) for each offered ID it has not seen. The sender answers with the frame, or with nothing if it no longer holds it.
- **Receiving held and tombstone entries:** decoded and validated. Until tickets 49 and 51, they are ignored.

## Spec and ADR references

- [link.md, Catch-up](../../../docs/spec/link.md#catch-up); [routing.md, Plumtree](../../../docs/spec/routing.md#plumtree) (`GRAFT` for offered IDs).
- [D-25](../../../docs/spec/decisions.md#d-25-catch-up-summary).
- [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md) decision 3 (Catch-up row) and the 2026-11-03 store-and-forward amendment; [ADR 0014](../../../docs/adr/0014-store-and-forward.md) decision 7.
- [Routing simulation, stage F](../../../docs/evidence/2026-11-routing-simulation/README.md#stage-f-store-and-forward-conversation-routes-lookups-and-power) (catch-up within budget).

## Acceptance criteria

- [x] The `CATCH_UP` codec passes every matching `link-frames.json` vector, including over-limit counts and the 7,127 B maximum. Its decoder has a deterministic mutation test.
- [x] A unit test overfills each list and checks the newest entries are kept.
- [x] Seeded `VirtualAir` test, 6 nodes, `Unsecured`: node F is out of range while 5 broadcasts go out, then links again within 60 s. F receives all 5 exactly once, through `GRAFT`.
- [x] The same test with F returning after more than 60 s: broadcasts older than the window are not offered.
- [x] A seeded test shows each side sends exactly one `CATCH_UP` per new link, in every density class.
- [x] T10: counts over the maximum, truncated entries and trailing bytes are dropped without state change. An offered list of 128 unknown IDs costs at most 128 grafts.
- [x] `./gradlew check` passes with Kover at 100%; `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- None. [What each observer learns](../../../docs/reference/observer-exposure.md) already covers catch-up. If the code shows different exposure, update it in this PR.

## Devices needed

None. Host and simulator only.

## Outcome

Shipped in [#101](https://github.com/trancee/MeshLink/pull/101) (`2f03447`).

- **Sending:** each side of every kept link opens it with one `CATCH_UP`, sent directly on that link. `Mail.linked` returns it when the hello completes; `Links.open` sends it only in the kept branch. A link closed as a duplicate sends none. The per-peer lane was rejected because a re-queued write could reach the losing link or be sent twice.
- **Offered list:** the broadcasts `Plumtree` holds for grafts, at most 128, from the last 60 s, oldest first. A broadcast that waited for a link isn't held, so it isn't offered. The held and tombstone lists are sent empty and ignored on receipt.
- **Receiving:** unseen offered IDs reuse the `IHAVE` graft path, bounded by the 128 pending IDs.
- **Code:** core gains the `LinkFrame.CatchUp` codec; `Plumtree.offered()`; `Floods` handles `CATCH_UP`.
- **Tests:**
  - `LinkFrameTest`: overfilled lists keep the newest; wrong sizes and counts are rejected. The `0x0a` vectors are no longer pending.
  - `FloodsTest` and `PlumtreeTest`: the offer window and the 128-grafts bound.
  - `CatchUpLinkTest`: duplicate links send `[1, 0]` and `[0, 1]` catch-ups.
  - `CatchUpMeshTest`: low, medium and high grids recover all 5 broadcasts once each, through `GRAFT`; after more than 60 s nothing is offered.
  - All 38 mutants killed.
- **Docs:** `CHANGELOG.md` has an entry. The spec already described the behavior, and the public ABI didn't change.
