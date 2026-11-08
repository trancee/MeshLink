# 0013: Message lifecycle and receipts

Status: accepted (2026-11-02; ticket "What are the message lifecycle and receipt semantics?"). Version: 3 (amended 2026-11-03, ADR 0014; 2026-11-08, ADR 0016).

## Context

- **ADR 0007:** fixes the outgoing statuses `Queued`, `Sent`, `Held`, `Delivered`, `Read`, `Failed(reason)` and `Expired`. `Delivered` means the destination's app took the message from its bounded inbox; a full inbox stops acknowledging. `markRead()` is idempotent and does nothing unless a read receipt was requested and allowed.
- **ADR 0008:** the destination acknowledges every unicast. The ack stops the origin's retries and refreshes the route. Messages carry a 12 B message ID and an absolute expiry, at least 120 s ahead, with ±30 s clock skew tolerated. Relays may hold messages.
- **ADR 0001 and the guarantees table:** receipts are end-to-end authenticated and encrypted in the secured profiles and forgeable in `unsecured`.
- **ADR 0002:** a first end-to-end message rides in `IKhfs` message 1; the destination's reply completes the session.
- **ADR 0011:** receipts were a preset default, with their semantics left to this decision.
- **User requirement:** read receipts are optional.

## Decision

1. **The delivery receipt is the routing ack.** For every unicast, the destination returns one end-to-end delivery receipt once its app takes the message. `Delivered` is always reported and is not opt-in. Mesh broadcasts never get a receipt.
2. **Delivery receipt frame:** a routed frame carrying one or more 12 B message IDs (batched), sent back along the reverse path.
   - It is end-to-end authenticated and encrypted in the secured profiles, and rides in the `IK` reply when one is due. In `unsecured` it is plaintext and forgeable.
   - A duplicate of a message already taken by the app is acknowledged again but never delivered twice.
3. **Read receipts need both sides to agree.**
   - The sender requests one per message; the default comes from config, and per-send options override it.
   - The recipient sends it only if its own config allows, and only when the app calls `markRead()`.
   - The sender cannot tell a refusal from a message not yet read. `ReceivedMessage.readReceiptRequested` tells the receiving app what was asked.
4. **Read receipt frame:** a routed end-to-end frame carrying one or more message IDs (batched). Its lifetime is the configured default message lifetime; it carries no timestamp and has no artificial delay.
   - Delivery is best effort: holders may hold it, but nothing acknowledges it. If it is lost, the sender stays at `Delivered`.
   - `markRead()` still sends it after the original message's lifetime has passed.
5. **Unicast statuses:**
   - **`Queued`:** accepted, nothing on air yet.
   - **`Sent`:** the first frame went to a neighbor. It does not mean `Delivered`.
   - **`Held`:** this node has no route and is waiting for one. It may alternate with `Sent`.
   - **`Delivered`:** the delivery receipt arrived.
   - **`Read`:** the read receipt arrived. A read receipt without its delivery receipt implies delivery and goes straight to `Read`.
   - **`Expired`:** no delivery receipt by the expiry plus 30 s of clock skew.
   - **`Failed(reason)`:** only for local causes, for example `Stopped`. The spec lists the reasons.
6. **Ordering and terminal states:** status only moves forward, except that `Sent` and `Held` may alternate.
   - Terminal: `Read`, `Expired`, `Failed`, and `Delivered` when no read receipt was requested.
   - The origin's retries are bounded (ADR 0008), but running out of them is not a failure: a holder may still deliver, so a late receipt can still turn the message into `Delivered` until it expires.
   - Waiting for `Read` has no time limit, but at most 1,000 messages are tracked; the oldest drops out with a diagnostic.
7. **Mesh broadcasts:** `BroadcastOptions` has no receipt field. Status goes `Queued` → `Sent`, then `Expired` at its lifetime.
8. **Direct-only sends:** same statuses and receipts. If the peer isn't linked, the message waits as `Held` until a link to that peer forms or the message expires. No other node relays or holds it.
9. **Persistence:** outgoing status lives in memory only in v1. `stop()` turns every unfinished message into `Failed(Stopped)`; receipts that arrive after a restart are dropped. Persisting held messages belongs to the store-and-forward decision.
10. **Config:** two values replace the single receipts setting of ADR 0011: `readReceipts.requestByDefault` (sender side) and `readReceipts.send` (recipient allows).

    | Preset | `requestByDefault` | `send` |
    |---|---|---|
    | `OfflineChat` | on | on |
    | `FieldTeams` | on | on |
    | `EmergencyBroadcast` | off | off |
    | `ProximityDiscovery` | off | off |
    | `LocalMultiplayer` | off | off |

## Alternatives

- **A routing ack plus a separate, optional delivery receipt:** two frames per message for no protection, since the ack already proves receipt to the origin.
- **Read receipts controlled only by the sender:** takes the read-time privacy decision away from the reader.
- **A random delay or batching window for read receipts:** worse experience, and coarse timing still leaks.
- **Receipts for mesh broadcasts:** an ack storm that also reveals who heard the broadcast.
- **`Failed(RetriesExhausted)`:** wrong while holders may still deliver; it would also have to be reversible.
- **Acknowledged read receipts:** a receipt for a receipt doubles traffic for a best-effort signal.

## Risks

- **Read-time leak:** a read receipt's arrival reveals roughly when the message was read. The how-to and the guarantees table say so.
- **Late inbox collection:** an app that collects late delays the delivery receipt, and the origin keeps retrying. De-duplication keeps the inbox clean, but the retries cost airtime.
- **Lost read receipts:** best effort, so a chat may show `Delivered` for a message that was read.

## Consequences

- The protocol spec defines the delivery receipt and read receipt frames, their batching limits and the `Failed` reasons.
- `unsecured` receipts stay forgeable (guarantees table, unchanged).
- The store-and-forward ticket is unblocked; it decides whether held messages and their status survive a restart.

## Amendments

2026-11-03, store-and-forward ([ADR 0014](0014-store-and-forward.md)):

- **Delivery receipt (decision 2):** also carries, in clear, one tombstone per acknowledged message so holders can drop their copies.
- **Persistence (decision 9):** held messages also live in memory only in v1.

2026-11-08, routing metadata exposure ([ADR 0016](0016-routing-metadata-exposure.md)):

- **Receipt addressing:** delivery acks and receipts use the conversation tag toward the sender. A read receipt sent after the end-to-end session ended starts a new session and is addressed to the sender's public tag.
