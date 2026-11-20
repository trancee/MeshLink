# Flood `Unsecured` mesh broadcasts

Status: done
Type: AFK
Blocked by: 21, 36

## Behavior

Split into tickets 39 and 39a (Q1). This ticket floods `Unsecured` broadcasts and decodes every `BROADCAST` frame; ticket 39a signs and verifies them in `Classical` and `PostQuantum`. Flooding runs in every density class; ticket 40 switches the medium and high classes to Plumtree.

- **API (Q3):** `broadcast(payload, hopLimit = 255)` returns a `SendResult`, like `send`. `BroadcastOptions` comes with its first field. `OutgoingMessage.to` becomes `NodeId?`: `null` for a broadcast.
- **Receiving (Q2):** broadcasts arrive on the one `messages` flow, inbox bounds included; `ReceivedMessage.broadcast` is `true` for them. The sender is the NodeId the container claims, unauthenticated in `Unsecured`.
- **Frame:** `BROADCAST` (`0x80`) with the 18 B flood header. The codec decodes both containers: kind `0x04` (the claimed sender NodeId and the payload, unpadded) and kind `0x05` (D-40: the 32 B origin key, `u16` payload length, the payload, the 64 B signature). A payload above 1,024 B is malformed in either.
- **Unknown flood types** (`0x82`–`0xBF` but `0x80`): relayed by the flood header alone, never processed, as unknown unicast types are.
- **Flood forwarding:** a node drops a frame that doesn't decode, isn't timely, or whose `message_id` it has seen before that ID's expiry plus `clock-skew`. It delivers a new broadcast its profile accepts, and forwards it on every other link if the decremented hop limit is at least 1. An `Unsecured` node accepts only kind `0x04`. Until ticket 39a, a secured node accepts no `BROADCAST`, so it neither delivers nor relays one.
- **Origin:** writes the broadcast to every current link. With no link, it waits and goes out on the first link that opens (Q5). It remembers its own `message_id`, so echoes are dropped.
- **Validation (Q4):** `NotRunning` as for `send`; a `hopLimit` outside 1–255 returns the new `Rejected(InvalidHopLimit)`; a payload above 1,024 B returns `Rejected(TooLarge)` with no frame on air; the payload counts against the 1 MiB send queue until the broadcast ends, so `QueueFull` applies. Lowering the cap by config is ticket 53.
- **Status:** `Queued`, then `Sent` at the first written copy, then `Expired` at the expiry plus `clock-skew`, as for unicast. No receipts. In a secured profile, until ticket 39a, the broadcast stays `Queued` and expires.
- **Not here:** signing and verification (ticket 39a), Plumtree (ticket 40), holding broadcasts (ticket 49), catch-up (ticket 41) and the broadcast-origination toggle (ticket 53).

## Spec and ADR references

- [routing.md](../../../docs/spec/routing.md): [Routing headers](../../../docs/spec/routing.md#routing-headers) (flood header), [Flood forwarding](../../../docs/spec/routing.md#flood-forwarding), [BROADCAST](../../../docs/spec/routing.md#broadcast), [Size classes](../../../docs/spec/routing.md#size-classes).
- [end-to-end.md, Containers](../../../docs/spec/end-to-end.md#containers) (kind `0x04` in `Unsecured`, kind `0x05` per frame in secured profiles).
- [D-29](../../../docs/spec/decisions.md#d-29-duplicate-suppression), [D-40](../../../docs/spec/decisions.md#d-40-broadcast-containers-and-signing).
- [ADR 0007](../../../docs/adr/0007-public-api-shape.md) decisions 7 and 8; [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md) decision 3; [ADR 0013](../../../docs/adr/0013-message-lifecycle-and-receipts.md) decisions 1 and 7; [ADR 0015](../../../docs/adr/0015-message-size-and-compression.md) decisions 2 and 3; [ADR 0016](../../../docs/adr/0016-routing-metadata-exposure.md) decision 1.

## Acceptance criteria

- [x] The `BROADCAST` codec and the flood frame codec for unknown types pass every matching `routed-frames.json` vector (`0x80`, `0xbf`); both containers decode as specified. Each new decoder has a deterministic mutation test.
- [x] A seeded `VirtualAir` test, 12 `Unsecured` nodes: one broadcast reaches every connected node exactly once, intact, with the origin's NodeId and `broadcast` true, and no node forwards a `message_id` twice.
- [x] A seeded test shows `hopLimit` 2 reaching exactly the nodes within 2 hops.
- [x] A 1,024 B payload is sent; a 1,025 B payload returns `Rejected(TooLarge)` and `hopLimit` 0 or 256 returns `Rejected(InvalidHopLimit)`, each with no frame on air.
- [x] Status goes `Queued` → `Sent` → `Expired` in virtual time; a broadcast sent with no link goes out on the first link.
- [x] T10: `hop_limit` 0, expired frames, a payload length above 1,024, truncated frames and kind `0x05` or `0x01` at an `Unsecured` node are dropped without state change; a secured node relays no `BROADCAST`; an unknown flood type is relayed with one hop less and not delivered.
- [x] `./gradlew check` passes with Kover at 100% and the ABI dump updated; `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- KDoc of `broadcast`, `OutgoingMessage.to`, `ReceivedMessage.broadcast` and `RejectionReason.InvalidHopLimit`: the 1 KiB cap, flooding, the unauthenticated `Unsecured` sender.
- [routing.md](../../../docs/spec/routing.md), [Flood forwarding](../../../docs/spec/routing.md#flood-forwarding): unknown flood types relayed by header, if not yet stated.
- [ADR 0007](../../../docs/adr/0007-public-api-shape.md) amendment: the broadcast API shape (Q2 to Q4).
- `CHANGELOG.md`: `broadcast` entry.

## Devices needed

None. Host and simulator only.

## Outcome

- **Owner decisions:**
  1. Signed broadcasts move to ticket 39a; this ticket floods `Unsecured` broadcasts only.
  2. Broadcasts arrive on `messages` with `ReceivedMessage.broadcast` set.
  3. `broadcast(payload, hopLimit = 255)`; `OutgoingMessage.to` is `null` for a broadcast.
  4. A `hopLimit` outside 1–255 returns `Rejected(InvalidHopLimit)`.
  5. With no link, a broadcast waits `Queued` and goes out on the first link that opens.
- **Secured profiles:** they neither deliver nor relay `BROADCAST`, and their own broadcasts stay `Queued` until they expire (until 39a).
- **API:** `radioFailure()` became the property `radioFailure` to stay within detekt `TooManyFunctions`.
- **Code:** `BroadcastFrame`, `FloodFrame` and `Container.SignedBroadcast` with vectors and fuzz targets; `Floods`, `Broadcasts`, `Endpoint.carries`; `Router`, `Post`, `UnsecuredEnd` and `MeshLink.broadcast` wiring.
- **Tests:** unit tests for each part; `BroadcastTest` in `VirtualAir`. 11 decoder mutants and 24 meshlink mutants were all killed; Kover is at 100%. Shipped in #95.
