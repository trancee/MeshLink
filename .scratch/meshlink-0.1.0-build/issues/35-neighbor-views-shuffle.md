# Keep active and passive neighbor views with shuffles

Status: ready-for-agent
Type: AFK
Blocked by: 27

## Behavior

Each node keeps a HyParView-style active view (its live links) and a passive view (a peer table of advertisement identifiers). It picks and replaces links with a diversity preference. Linked peers exchange `NEIGHBOR_LIST` and `SHUFFLE` / `SHUFFLE_REPLY` frames. Nothing in these frames is forwarded.

- **Active view:** link budget d = 6, the low-class value. Ticket 36 switches d per density class. One slot stays free for newcomers.
- **Bridge rule:** a newly heard peer outside the known 1–2-hop neighborhood is dialed first, at most once per 60 s. Sightings that resolve to a known peer through its resolving key are skipped.
- **Neighbor list:** sent after the handshake, when the link set changes and before each own rotation, at most once per 2 s. It carries `own_id`, `next_id` and up to 32 neighbor identifiers. At rotation, the node advertises exactly the `next_id` it announced. Receivers use `next_id` to recognize the rotated peer (ticket 27).
- **Shuffle:** every 10 s on one random link, with 1–5 passive-view entries. The receiver answers on the same link with 0–5 entries and merges what it received.
- **Passive view:** 4·d entries, random replacement when full. Only peers heard in the last 4 s are dialed.
- **Diversity replacement:** every 30 s, the most redundant link is replaced if a candidate overlaps at least 2 fewer peers in the 1–2-hop set.

Entries are the 8 B on-air advertisement identifiers, never NodeIds or tags.

## Spec and ADR references

- [link.md, Neighbor list](../../../docs/spec/link.md#neighbor-list) and [Shuffle](../../../docs/spec/link.md#shuffle); [D-24](../../../docs/spec/decisions.md#d-24-neighbor-list) and [C-10](../../../docs/spec/decisions.md#c-10).
- [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md) decision 1 and the 2026-11-02 bridge-rule amendment.
- [ADR 0016](../../../docs/adr/0016-routing-metadata-exposure.md) decision 3 (identifiers, direct neighbors only).
- [v1 parameters, Neighbor selection and topology](../../../docs/evidence/2026-11-routing-simulation/v1-parameters.md#neighbor-selection-and-topology) and [Bridge rule](../../../docs/evidence/2026-11-routing-simulation/v1-parameters.md#bridge-rule) (passive view size, eviction, diversity interval).
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decisions 2, 9 and 10.

## Acceptance criteria

- [ ] `NEIGHBOR_LIST` (`0x07`), `SHUFFLE` (`0x08`) and `SHUFFLE_REPLY` (`0x09`) codecs pass every matching `link-frames.json` vector in the one vector runner, including the `invalid` count and size cases.
- [ ] Each new decoder has a deterministic mutation test (ADR 0017 decision 9).
- [ ] A seeded 8-node `VirtualAir` test shows: neighbor lists after each handshake, on link-set change and before rotation; never more often than every 2 s; never forwarded.
- [ ] A seeded `VirtualAir` test rotates a node and shows its linked peers recognize the `next_id` it announced. A test that advertises a different identifier fails the MUST.
- [ ] A seeded 12-node test shows one shuffle every 10 s on one link, replies on the same link, a passive view bounded at 4·d, and a free newcomer slot.
- [ ] A seeded split-mesh test repairs through the bridge rule and dials at most once per 60 s.
- [ ] A seeded test shows diversity replacement every 30 s under the overlap rule.
- [ ] T10: a frame with count 33 (neighbor list) or 6 (shuffle), a truncated entry, or trailing bytes is dropped without state change.
- [ ] A test proves no frame carries a NodeId or routing tag in an entry.
- [ ] The spec's "ADR 0016 decision 4" citation for neighbor lists is reported to the owner. ADR 0016 decision 3 is the neighbor-list decision. The fix goes through its own docs change, not this PR.
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
