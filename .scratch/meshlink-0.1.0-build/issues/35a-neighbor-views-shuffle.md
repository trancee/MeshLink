# Keep active and passive neighbor views with shuffles

Status: done
Type: AFK
Blocked by: 35

## Behavior

Split from ticket 35 (Q1). Each node keeps a HyParView-style active view (its live links) and a passive view (a peer table of advertisement identifiers), filled by `SHUFFLE` / `SHUFFLE_REPLY` exchanges. This replaces dialing every candidate.

- **Active view:** link budget d = 6, the low-class value. Ticket 36 switches d per density class. d counts kept links and every bearer still opening, in both roles (Q1). A node dials only while it holds fewer than d − 1, so one slot stays free for newcomers. It accepts an inbound bearer while it holds fewer than d, and otherwise refuses it with `CLOSE` `RESOURCE_LIMIT` before any frame, as an iPhone at its peripheral cap does; it keeps advertising.
- **Shuffle:** every 10 s on one random link, with min(5, size) random passive-view entries; skipped while the view is empty. The receiver answers on the same link with 0–5 random entries chosen before it merges what it received (Q3).
- **Passive view:** 4·d entries: identifiers of sighted, unlinked candidates and entries received in shuffles, never the node's own. Random replacement when full (Q3).
- **Dial pool:** candidates heard in the last 4 s, identifier-less ones included, picked at random; rechecked on every sighting and whenever a link closes (Q4). One dial is in flight at a time: the next follows once the link opens or the dial fails (owner decision 2026-11-19, amending Q4's "up to the free slots"; see Outcome). Bridge-rule ordering is ticket 35b.
- **Moved to ticket 35c (Q2):** dial backoff and freeing a slot on a node full for 30 s.
- **Handshake bound:** with d counting opening bearers, inbound bearers can no longer reach the 8 half-open handshakes of D-20. The check stays; a `Handshakes` unit test covers it (Q1).

Entries are the 8 B on-air advertisement identifiers, never NodeIds or tags.

## Spec and ADR references

- [link.md, Neighbor list](../../../docs/spec/link.md#neighbor-list) and [Shuffle](../../../docs/spec/link.md#shuffle); [D-24](../../../docs/spec/decisions.md#d-24-neighbor-list) and [C-10](../../../docs/spec/decisions.md#c-10).
- [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md) decision 1 and the 2026-11-02 bridge-rule amendment.
- [ADR 0016](../../../docs/adr/0016-routing-metadata-exposure.md) decision 3 (identifiers, direct neighbors only).
- [v1 parameters, Neighbor selection and topology](../../../docs/evidence/2026-11-routing-simulation/v1-parameters.md#neighbor-selection-and-topology) and [Bridge rule](../../../docs/evidence/2026-11-routing-simulation/v1-parameters.md#bridge-rule) (passive view size, eviction, diversity interval).
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decisions 2, 9 and 10.

## Acceptance criteria

- [x] A seeded 12-node test shows one shuffle every 10 s on one link, replies on the same link, a passive view bounded at 4·d, and a free newcomer slot.
- [x] A node never holds more than d links, and existing multi-node tests keep passing under the link budget.
- [x] A node holding d links refuses the next inbound bearer with `CLOSE` `RESOURCE_LIMIT` before any other frame.
- [x] A candidate last heard more than 4 s ago is not dialed.
- [x] `./gradlew check` passes with Kover at 100%, and `./gradlew iosSimulatorArm64Test` passes.

## Outcome

- **Views:** `Views` holds the passive view (24 entries, random replacement, never the node's own identifiers), samples shuffles, picks the next dial and checks acceptance. `Links` counts every tracked job against the budget, kept or opening, in both roles; a bearer beyond 6 gets `CLOSE` `RESOURCE_LIMIT` before any frame and `ML3004 LinkCapReached(6)`, which reuses the existing diagnostic (ADR 0020), so the ABI is unchanged.
- **Deadlock found and fixed:** with several dials in flight (Q4 as first written), two nodes that dialed each other filled their budgets with dials and refused each other's bearers. Spec "Connect retries and link caps" blocks redial after `RESOURCE_LIMIT` while the peer stays a candidate, so they never linked; the 6-node `LinkLimitsTest` mesh failed on several seeds. The owner chose one dial in flight over accept-then-evict and over a refusal cool-down. A failed dial moves to the other candidates at once; an opened link triggers the next dial.
- **Freed slots:** a closed kept link redials from `track`, after its job leaves the budget. A redial launched from `release` ran while `serve` still waited for its cancelled children, so it saw a full budget and never dialed.
- **Tests:** `ViewsTest` (passive-view bound, own skip and replacement; sample size; the 4 s dial filter and slots), `HandshakesTest` (the D-20 bound, now unreachable through inbound bearers), `LinkLimitsTest` (the 7th bearer refused before any frame), and in `NeighborListTest`: a shuffle answered with a sample taken before merging and its entries shuffled onward, a seeded 12-node mesh (at most 6 links, one `SHUFFLE` per 10 s per node with 1–5 entries, each answered on the same link with 0–5, a 13th node linking), a closing peripheral or central link freeing its slot for a fresh candidate, and a stale candidate dialed only at its next sighting. The passive-view bound of 4·d is proved in `ViewsTest`; the view isn't observable from the 12-node test. Mutations of the budget check, the dial slot, the 4 s filter, the own-identifier skip, one-in-flight and each redial path all fail a test.
- **Test adjustments:** `LinkLimitsTest`'s capped mesh now settles for 10 s (a second scan window, since dials are serialized) and caps at 2, because cap 3 was never exceeded once dials were serialized. The half-open tests moved from `SecuredLinkTest` to `HandshakesTest`. `FragmentLinkTest` fills the reassembly budget over 8 links in sequence. `BearerSwitchTest` checks the first link, since the freed slot redials the peer. `VirtualLinkEnd.opposite` pairs a shuffle with its reply.
- **Unreachable through links:** the 1 MiB large-reassembly budget (6 × 139,264 B ≈ 816 KiB); `ReassemblyTest` in `meshlink-core` covers it.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- None expected. [What each observer learns](../../../docs/reference/observer-exposure.md) already covers neighbor lists and shuffles. If the code shows a different exposure, update that page in this PR.

## Devices needed

None. Host and simulator only.
