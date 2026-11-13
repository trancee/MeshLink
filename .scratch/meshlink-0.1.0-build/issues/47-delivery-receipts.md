# Report delivery with delivery receipts

Status: ready-for-agent
Type: AFK
Blocked by: 45

## Behavior

The destination of every unicast returns a delivery receipt once its app takes the message. The receipt is the routing ack: it travels the reverse route, refreshes the route on every hop, stops the origin's retries and turns the status into `Delivered`.

- **Frame:** `DELIVERY_RECEIPT` (`0x41`): unicast header, `count` 1–32, then `count` tombstones `{message_id, k}` in clear, then a container. The plaintext's `MESSAGE_IDS` lists the same IDs in the same order; a sender ignores a receipt whose lists differ.
- **Header:** a fresh `message_id` and an `expiry` equal to the latest expiry of the acknowledged messages.
- **Batching:** up to 32 messages from one sender, waiting at most 500 ms to fill a batch.
- **Duplicates:** every later copy of a taken message is acknowledged again but never delivered twice.
- **Profiles:** this ticket runs end to end in `Unsecured` (kind `0x04`). The codec accepts kinds `0x02` and `0x03`, and secured relays forward receipts by header. Secured receipts need `IK` message 2 and transport frames, which ticket 46 adds on top of this frame.
- **Statuses:** `Delivered` on the receipt. Status only moves forward, except that `Sent` and `Held` alternate. A receipt that arrives after the retries ran out still delivers until expiry + 30 s. `stop()` turns unfinished messages into `Failed(Stopped)` if no earlier ticket did.
- **Direct-only sends:** the send option uses `hop_limit` 1. If the peer is not linked, the message waits as `Held` until a link to it forms or it expires. No other node relays or holds it.
- **Density suite:** the pull-request suite of ticket 42 gains receipt scenarios.

Tombstone handling by relays is ticket 51. The routed-unicast toggle is ticket 53.

## Spec and ADR references

- [end-to-end.md, Delivery receipts](../../../docs/spec/end-to-end.md#delivery-receipts), [Containers](../../../docs/spec/end-to-end.md#containers), [Plaintext](../../../docs/spec/end-to-end.md#plaintext) (receipt content) and [Plaintext extensions](../../../docs/spec/end-to-end.md#plaintext-extensions) (`MESSAGE_IDS`).
- [routing.md, Unicast forwarding](../../../docs/spec/routing.md#unicast-forwarding) (receipt refreshes the route) and [Origin behavior](../../../docs/spec/routing.md#origin-behavior) (resend, direct-only `hop_limit` 1).
- [D-27](../../../docs/spec/decisions.md#d-27-routing-header-and-hop-limit), [D-38](../../../docs/spec/decisions.md#d-38-delivery-receipt-batching).
- [ADR 0013](../../../docs/adr/0013-message-lifecycle-and-receipts.md) decisions 1, 2, 5, 6, 8 and 9; [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md) (its 2026-11-02 ADR 0013 amendment); [ADR 0007](../../../docs/adr/0007-public-api-shape.md) decisions 7 and 8.

## Acceptance criteria

- [ ] The `DELIVERY_RECEIPT` codec passes every matching `routed-frames.json` vector; receipt plaintexts pass `inner-plaintext.json`. Its decoder has a deterministic mutation test.
- [ ] Seeded `VirtualAir` test, 6-node line, `Unsecured`: 40 messages from A to F all reach `Delivered`, in batches of at most 32, each within 500 ms of the app taking the message, and A's retries stop.
- [ ] A seeded test duplicates a message and shows a second receipt with no second delivery.
- [ ] A seeded test drops every receipt until the retries run out, then lets a late one through before expiry: status turns `Delivered`. Without it, status turns `Expired` at expiry + 30 s.
- [ ] Seeded direct-only tests: `Held` while unlinked, `Sent` then `Delivered` after linking, `Expired` if no link forms. No relay forwards or holds the frame.
- [ ] A property test checks the status order rules over random event sequences.
- [ ] T10: mismatched `MESSAGE_IDS` and tombstones, `count` 0 or 33, a receipt for an unknown ID, and a receipt with a disallowed kind change no status.
- [ ] Ticket 42's pull-request suite runs the receipt scenarios and still fits its time budget.
- [ ] `./gradlew check` passes with Kover at 100% and the ABI dump updated; `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- KDoc of `DeliveryStatus` (every status and the order rules) and of the direct-only send option.
- The `Failed` reasons are not listed in the spec, although ADR 0013 decision 5 says they are. The PR reports this to the owner rather than inventing a list.

## Devices needed

None. Host and simulator only.
