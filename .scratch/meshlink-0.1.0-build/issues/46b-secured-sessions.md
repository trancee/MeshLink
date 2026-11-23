# Keep secured end-to-end sessions in the mesh

Status: ready-for-agent
Type: AFK
Blocked by: 45b, 46a, 47

## Behavior

Split from ticket 46 (owner decision, 2026-11-23). It uses ticket 46a's session cryptography.

In `Classical` and `PostQuantum`, a first message opens an end-to-end session. Later messages and receipts between the two peers travel as transport frames to per-conversation tags. Secured unicasts reach `Delivered`.

- **Keeping handshakes:** `FirstMessages` keeps the initiator handshake of each first message it drafts and the responder handshake of each message 1 it opens, so the responder can answer with message 2 and the initiator can adopt it into a session (moved from 46a, owner decision, 2026-11-23).
- **Answering:** the responder processes each message 1 once by `message_id`. It sends message 2 in the `DELIVERY_RECEIPT` for that message, keeps that container and resends it unchanged in the receipt for every later copy.
- **Adopting:** the initiator matches message 2 to its first message by the receipt's tombstone `message_id`, processes the first copy and ignores later ones.
- **Transport:** later messages and receipts in either direction go as kind `0x03` (ticket 46a).
- **Conversation tags:** frames from the initiator go to `tag(0x01)` from `tag(0x02)`, and the reverse. Both ends recognize their sessions' tags for epochs e − 1, e and e + 1.
- **Waiting messages:** after the session opens, messages that waited behind the first message (ticket 45b) go out with the session.
- **Crossed sessions:** both sides keep the session whose initiator has the lower NodeId (bytewise) and drop the other once its outstanding frames are answered.
- **Ending:** after 6 h, after 4,096 frames in either direction, or on eviction from the session cache. The next message is a first message. The cache size is an internal parameter here; ticket 53 sets it per preset.
- **Failure:** a session frame with no receipt by its second retry ends the session and is resent as a first message with a new `message_id`.

## Spec and ADR references

- [end-to-end.md, Sessions](../../../docs/spec/end-to-end.md#sessions), [Conversation tags](../../../docs/spec/end-to-end.md#conversation-tags) and [Delivery receipts](../../../docs/spec/end-to-end.md#delivery-receipts).
- [D-32](../../../docs/spec/decisions.md#d-32-conversation-seed-and-tags), [D-33](../../../docs/spec/decisions.md#d-33-end-to-end-session-limits), [D-34](../../../docs/spec/decisions.md#d-34-session-opening-and-failure).
- [ADR 0002](../../../docs/adr/0002-noise-suites-and-handshake-patterns.md) decision "Sessions"; [ADR 0013](../../../docs/adr/0013-message-lifecycle-and-receipts.md) decision 2 (receipt rides in the `IK` reply); [ADR 0016](../../../docs/adr/0016-routing-metadata-exposure.md) decision 2.

## Acceptance criteria

- [ ] Seeded `VirtualAir` tests, 5-node line, in `Classical` and `PostQuantum`: A sends 10 messages to E. The first opens the session, the other 9 go as kind `0x03` to conversation tags, and all 10 reach `Delivered`, each delivered once.
- [ ] A seeded test duplicates message 1 and shows the cached message 2 resent byte for byte.
- [ ] A seeded test shows crossed sessions settling on the lower-NodeId initiator on both sides.
- [ ] Virtual-time tests end a session at 21,600 s and at 4,096 frames, and the next message is a first message.
- [ ] A seeded test wipes E's session state and shows A's fallback to a first message with a new `message_id` after the second retry.
- [ ] A seeded test across an epoch change keeps delivery through the e − 1 / e + 1 tag window.
- [ ] T10: a replayed nonce, a nonce below the window, a tampered transport frame and a message 2 for an unknown first message, all sent through the mesh, are dropped without delivery or state change.
- [ ] Gherkin scenarios for secured sessions and secured delivery.
- [ ] `./gradlew check` passes with Kover at 100%; `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- KDoc of `DeliveryStatus.Delivered` for the secured profiles.
- [Security profile guarantees](../../../docs/reference/security-profile-guarantees.md) only if a tested row (forward secrecy, replay rejection, conversation unlinkability) differs from the page.

## Devices needed

None. Host and simulator only.
