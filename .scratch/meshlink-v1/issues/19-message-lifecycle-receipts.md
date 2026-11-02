# What are the message lifecycle and receipt semantics?

Type: grilling
Status: resolved

## Question

Define the states of an outgoing and incoming message, when each delivery status of ADR 0007 (`Queued`, `Sent`, `Held`, `Delivered`, `Read`, `Failed`, `Expired`) is reached, what delivery and read receipts mean and carry, who may request them, how they are authenticated (end-to-end in secured profiles, forgeable in `unsecured`), their privacy (a read receipt reveals reading time), and their interaction with routing acks (ADR 0008) and broadcasts.

## Context

- ADR 0007 decision 8 fixes the status names; ADR 0011 makes receipts a preset default with per-send override.
- ADR 0008: the destination's delivery ack refreshes routes; decide whether the delivery receipt is that ack.

## Answer

Resolved 2026-11-02 with the user in three grilling rounds; recorded in [ADR 0013](../../../docs/adr/0013-message-lifecycle-and-receipts.md), with amendments to ADRs 0007, 0008 and 0011.

- The routing ack is the delivery receipt: always sent once the app takes the message, end-to-end in secured profiles, batched, duplicates acknowledged again. Broadcasts get no receipts.
- Read receipts need the sender's request and the recipient's permission; no timestamp, no delay; best effort with their own lifetime.
- Statuses move forward only; `Failed` is local-only; `Expired` at expiry plus 30 s; late receipts still count until then; at most 1,000 messages tracked for `Read`.
- Status lives in memory; `stop()` fails unfinished messages.
- Config: `readReceipts.requestByDefault` and `readReceipts.send`; on for `OfflineChat` and `FieldTeams`.
