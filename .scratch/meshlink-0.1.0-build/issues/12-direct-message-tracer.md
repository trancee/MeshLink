# Send and receive a direct message between two nodes

Status: ready-for-agent
Type: AFK
Blocked by: 11

## Behavior

An app calls `send(to, payload, options)` with the direct-only option for a linked `Unsecured` peer. It gets `Accepted(OutgoingMessage)` whose status goes `Queued` → `Sent` once the `MESSAGE` frame leaves on the link. The peer's app receives the payload with the sender's NodeId from its single-collector `messages` flow. With no receipts yet (ticket 47), a `Sent` message ends `Expired` at its expiry plus 30 s. This completes milestone M1.

## Spec and ADR references

- [ADR 0007](../../../docs/adr/0007-public-api-shape.md) decisions 7 (`send`, `SendResult`), 8 (`DeliveryStatus`), 9 (single collector, bounded inbox, ¼ per-sender share) and the amendments (`awaitSendCapacity()`, 1 MiB send-queue cap, 1 MiB inbox, `UnknownPeer` for an unknown NodeId in `unsecured`).
- [ADR 0013](../../../docs/adr/0013-message-lifecycle-and-receipts.md) decisions 5, 6, 8 (direct-only: `Held` until a link forms or the message expires) and 9 (`stop()` gives `Failed(Stopped)`).
- [ADR 0012](../../../docs/adr/0012-wire-format-and-codec.md) decisions 5, 11 and 12.
- Spec: [routing tags](../../../docs/spec/routing.md#routing-tags), [routing headers](../../../docs/spec/routing.md#routing-headers), [origin behavior](../../../docs/spec/routing.md#origin-behavior) (direct-only uses `hop_limit` 1, D-27), [MESSAGE](../../../docs/spec/routing.md#message), [containers](../../../docs/spec/end-to-end.md#containers), [plaintext](../../../docs/spec/end-to-end.md#plaintext), [tombstones](../../../docs/spec/end-to-end.md#tombstones) (commitment only); decisions D-27, D-30, D-31.
- Vectors: `routed-frames.json` tcIds 3, 19, 20, 47, 48 and 49; `inner-plaintext.json` unsecured message cases 12–17 and 19; `derivations.json` `unsecured-tag` and `tombstone-commitment`.
- [Spine](../spec.md) M1 exit criterion.

## Acceptance criteria

- [ ] The origin builds `MESSAGE` (type 0x40): unicast header with `hop_limit` 1, a random 12 B `message_id`, `expiry` from the preset lifetime (inside 120–3,600 s), the destination's and its own `unsecured_tag` for the current epoch; commitment `trunc_16(H("tombstone", k))` for a random 16 B `k`; container kind 0x04 with the sender NodeId and the unpadded plaintext. All listed vectors pass on the Android host and `iosSimulatorArm64`.
- [ ] The receiver accepts a `MESSAGE` only if its destination tag is one of its own for e − 1, e or e + 1, the kind is 0x04, the plaintext's `message_id` and `expiry` equal the header's, `H(k)` matches the commitment, and the expiry rules hold; otherwise it drops it. It never forwards (`hop_limit` 1). Mutation tests cover the `MESSAGE`, container and plaintext decoders.
- [ ] `SendResult.Rejected`: `NotRunning` before `start()`; `UnknownPeer` for a NodeId never seen in this mesh; `TooLarge` for a payload that does not fit one link record (1,024 − 125 = 899 B in `unsecured`) until ticket 28 adds fragments; `QueueFull` above the 1 MiB send-queue cap, with `awaitSendCapacity()` resuming when room returns.
- [ ] A direct-only send to a known but unlinked peer is `Held`, becomes `Sent` when the link forms, and `Expired` at expiry plus 30 s if it never does. `stop()` turns unfinished messages into `Failed(Stopped)`. Status only moves forward.
- [ ] `messages` allows one collector; a second gets `MeshLinkException.AlreadyCollected`. The inbox holds at most 1,024 messages and at most 1 MiB, and at most ¼ of the bytes per sender (owner decision). A dated ADR 0007 amendment (version bump) records the 1,024-message bound beside decision 9; tests fill the inbox to each bound.
- [ ] M1 exit test in `:meshlink-testing` `commonTest`: two `Unsecured` nodes on `VirtualAir` with real libsodium hashing start, discover each other, link, and node A's direct message reaches node B's `messages` with A's NodeId and the exact payload, while A sees `Queued` then `Sent`. It passes on the Android host and `iosSimulatorArm64` in virtual time.
- [ ] Routed sends (`hop_limit` above 1) are out of scope (ticket 37); `markRead` and `readReceiptRequested` are ticket 48.
- [ ] `./gradlew check`, `./gradlew iosSimulatorArm64Test` and `tools/swiftpm-consumer/test.sh` pass; Kover 100%.

## Proof commands

```sh
./gradlew :meshlink-testing:testAndroidHostTest --tests '*DirectMessage*'
./gradlew check
./gradlew iosSimulatorArm64Test
./gradlew dokkaGenerate
tools/swiftpm-consumer/test.sh
python3 tools/check_adrs.py
```

## Docs to update

- `CHANGELOG.md`: `send`, `messages`, `DeliveryStatus` entries.
- `PROJECT.md`: proof-matrix row for the M1 exit test.
- `docs/adr/0007-public-api-shape.md`: dated amendment with the 1,024-message inbox count bound (version bump).

## Devices needed

None.
