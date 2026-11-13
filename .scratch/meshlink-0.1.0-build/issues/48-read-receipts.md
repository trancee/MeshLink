# Report reading with read receipts

Status: ready-for-agent
Type: AFK
Blocked by: 46, 47

## Behavior

In the secured profiles, a read receipt is a transport frame of an open session (kind `0x03`, ticket 46) or a first message (kind `0x01`, ticket 45); in `Unsecured` it is kind `0x04`. Ticket 47 supplies the delivery receipts it pairs with.

When both sides agree, the sender learns that the recipient's app marked a message read, and the status becomes `Read`.

- **Agreement:** the sender asks per message with `READ_RECEIPT_REQUESTED`. The default comes from `readReceipts.requestByDefault`; send options override it. The recipient sends a receipt only if `readReceipts.send` allows and its app calls `markRead()`. `ReceivedMessage.readReceiptRequested` tells the app what was asked. A refusal looks like an unread message.
- **Frame:** `READ_RECEIPT` (`0x42`): unicast header, then a container of kind `0x01`, `0x03` or `0x04`. The plaintext's `MESSAGE_IDS` lists up to 64 IDs.
- **Batching:** a recipient waits at most 2,000 ms to fill a batch.
- **Lifetime:** `expiry` is the recipient's default message lifetime from now. It carries no timestamp and has no artificial delay. Nothing acknowledges it; holders may hold it.
- **Session ended:** the read receipt becomes a first message (kind `0x01`) to the original sender's public tag. `markRead()` works even after the original message's lifetime has passed.
- **Status:** `Read` on arrival. A read receipt without its delivery receipt goes straight to `Read`. If it is lost, the status stays `Delivered`. At most 1,000 sent messages are tracked for `Read`; the oldest drops out with a diagnostic.
- **Config wiring:** this ticket adds the two `readReceipts` values with internal defaults; ticket 53 sets them per preset.

## Spec and ADR references

- [end-to-end.md, Read receipts](../../../docs/spec/end-to-end.md#read-receipts), [Plaintext extensions](../../../docs/spec/end-to-end.md#plaintext-extensions), [First messages](../../../docs/spec/end-to-end.md#first-messages) and [Containers](../../../docs/spec/end-to-end.md#containers).
- [D-31](../../../docs/spec/decisions.md#d-31-plaintext-and-end-to-end-extensions), [D-39](../../../docs/spec/decisions.md#d-39-read-receipt-batching).
- [ADR 0013](../../../docs/adr/0013-message-lifecycle-and-receipts.md) decisions 3, 4, 5, 6 and 10; [ADR 0007](../../../docs/adr/0007-public-api-shape.md) decisions 8 and 9; [ADR 0016](../../../docs/adr/0016-routing-metadata-exposure.md) amendment to ADR 0013.

## Acceptance criteria

- [ ] The `READ_RECEIPT` codec passes every matching `routed-frames.json` vector. Its decoder has a deterministic mutation test.
- [ ] Seeded `VirtualAir` tests, 5-node line, in all three profiles: a requested message marked read reaches `Read`; an unrequested one, or one whose recipient disallows read receipts, stays `Delivered`.
- [ ] A seeded test batches 64 reads into one receipt within 2,000 ms, and 65 into two.
- [ ] A seeded secured test ends the session first; the read receipt goes as a first message to the sender's public tag and still arrives.
- [ ] A seeded test calls `markRead()` after the message's lifetime and the receipt still goes out.
- [ ] A seeded test drops the delivery receipt and shows the read receipt alone moving the status to `Read`.
- [ ] A unit test tracks 1,001 messages and shows the oldest dropped with a diagnostic.
- [ ] T10: a read receipt for an ID never requested, or listing IDs the sender never sent, changes no status. A tampered secured receipt is dropped.
- [ ] `./gradlew check` passes with Kover at 100% and the ABI dump updated; `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- KDoc of `markRead()`, `ReceivedMessage.readReceiptRequested`, `DeliveryStatus.Read` and the `readReceipts` config values.

## Devices needed

None. Host and simulator only.
