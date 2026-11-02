# What are the message lifecycle and receipt semantics?

Type: grilling
Status: open

## Question

Define the states of an outgoing and incoming message, when each delivery status of ADR 0007 (`Queued`, `Sent`, `Held`, `Delivered`, `Read`, `Failed`, `Expired`) is reached, what delivery and read receipts mean and carry, who may request them, how they are authenticated (end-to-end in secured profiles, forgeable in `unsecured`), their privacy (a read receipt reveals reading time), and their interaction with routing acks (ADR 0008) and broadcasts.

## Context

- ADR 0007 decision 8 fixes the status names; ADR 0011 makes receipts a preset default with per-send override.
- ADR 0008: the destination's delivery ack refreshes routes; decide whether the delivery receipt is that ack.
