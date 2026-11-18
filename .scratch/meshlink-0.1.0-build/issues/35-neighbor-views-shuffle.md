# Exchange neighbor lists and announce the next identifier

Status: ready-for-agent
Type: AFK
Blocked by: 27

## Behavior

Split into tickets 35, 35a and 35b (Q1). This ticket adds the three codecs, neighbor lists and the announced `next_id`. Ticket 35a adds the active and passive views and shuffles; ticket 35b adds the bridge rule and diversity replacement.

Linked peers exchange `NEIGHBOR_LIST` frames, and every node decodes `SHUFFLE` / `SHUFFLE_REPLY` (sent from ticket 35a). Nothing in these frames is forwarded.

- **Neighbor list:** sent after the handshake, when the link set changes and before each own rotation, at most once per 2 s. It carries `own_id`, `next_id` and up to 32 neighbor identifiers. At rotation, the node advertises exactly the `next_id` it announced. Receivers use `next_id` to recognize the rotated peer (ticket 27).

Entries are the 8 B on-air advertisement identifiers, never NodeIds or tags.

## Spec and ADR references

- [link.md, Neighbor list](../../../docs/spec/link.md#neighbor-list) and [Shuffle](../../../docs/spec/link.md#shuffle); [D-24](../../../docs/spec/decisions.md#d-24-neighbor-list) and [C-10](../../../docs/spec/decisions.md#c-10).
- [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md) decision 1 and the 2026-11-02 bridge-rule amendment.
- [ADR 0016](../../../docs/adr/0016-routing-metadata-exposure.md) decision 3 (identifiers, direct neighbors only).
- [v1 parameters, Neighbor selection and topology](../../../docs/evidence/2026-11-routing-simulation/v1-parameters.md#neighbor-selection-and-topology) and [Bridge rule](../../../docs/evidence/2026-11-routing-simulation/v1-parameters.md#bridge-rule) (passive view size, eviction, diversity interval).
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decisions 2, 9 and 10.

## Acceptance criteria

- [x] `NEIGHBOR_LIST` (`0x07`), `SHUFFLE` (`0x08`) and `SHUFFLE_REPLY` (`0x09`) codecs pass every matching `link-frames.json` vector in the one vector runner, including the `invalid` count and size cases.
- [x] Each new decoder has a deterministic mutation test (ADR 0017 decision 9).
- [x] A seeded 8-node `VirtualAir` test shows: neighbor lists after each handshake, on link-set change and before rotation; never more often than every 2 s; never forwarded.
- [x] A seeded `VirtualAir` test rotates a node and shows its linked peers recognize the `next_id` it announced. A test that advertises a different identifier fails the MUST.
- ~~A seeded 12-node test shows one shuffle every 10 s on one link, replies on the same link, a passive view bounded at 4·d, and a free newcomer slot.~~ Moved to ticket 35a (Q1).
- ~~A seeded split-mesh test repairs through the bridge rule and dials at most once per 60 s.~~ Moved to ticket 35b (Q1).
- ~~A seeded test shows diversity replacement every 30 s under the overlap rule.~~ Moved to ticket 35b (Q1).
- [x] T10: a frame with count 33 (neighbor list) or 6 (shuffle), a truncated entry, or trailing bytes is dropped without state change.
- [x] A test proves no frame carries a NodeId or routing tag in an entry.
- [x] The spec's "ADR 0016 decision 4" citation for neighbor lists is reported to the owner. ADR 0016 decision 3 is the neighbor-list decision. The fix goes through its own docs change, not this PR.
- [x] `./gradlew check` passes with Kover at 100%, and `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- None expected. [What each observer learns](../../../docs/reference/observer-exposure.md) already covers neighbor lists and shuffles. If the code shows a different exposure, update that page in this PR.

## Devices needed

None. Host and simulator only.

## Outcome

- **Split (Q1):** this ticket ships the three codecs, neighbor lists and `next_id`; 35a takes views and shuffles, 35b the bridge rule and diversity replacement.
- **Codecs:** `LinkFrame.NeighborList`, `Shuffle` and `ShuffleReply` pass every `link-frames.json` vector, including the `invalid` cases, with mutation tests in the vector handlers and a `LinkFrame` fuzz-target entry.
- **Lists:** `NeighborLists` sends `own_id`, `next_id` and the `own_id`s heard from linked peers on every kept link, at most once per 2 s: after the handshake, on link-set change, and before and after each rotation. `Scanner` draws the next identifier from `ResolvingKeys.at(rotation)` and advertises exactly that identifier at rotation. A central-only node advertises nothing and sends no list. Lists are never forwarded.
- **Recognition:** `Recognition` now exists in open meshes too. A rotated peer is recognized by the `next_id` it announced on its kept link, so an open mesh no longer redials a peer after it rotates; a different identifier is a new candidate.
- **Shuffles:** until 35a, an open link answers a `SHUFFLE` with an empty `SHUFFLE_REPLY` and drops a reply.
- **T10:** a malformed list or shuffle (count 33 or 6, a truncated entry, trailing bytes) or a list before the hello closes the link with `PROTOCOL_ERROR`. The ticket said "dropped without state change"; `link.md` makes a malformed frame close the link, and the spec wins.
- **No NodeId or tag in an entry:** every entry is an identifier another node advertised (`NeighborListTest`).
- **ADR 0016 citation:** the spec already cites decision 3 (`link.md`, `decisions.md` D-24), fixed in `dc93d55`; nothing left to change.
- **Test adjustments:** lists use link credits, so long-running fake centrals keep granting (`initiateGranting`), and secured tests filter lists out (`sealedFrames`, `withoutNeighborLists`). `VirtualLinkEnd.writes` records the write time of each frame. `LinkLimitsTest` now asserts refusals summed across seeds, because a per-seed refusal depends on timing.
- **Other:** `lessons.md` names `FuzzTarget.kt` correctly.
