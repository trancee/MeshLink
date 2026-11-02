# What is the message size cap, and is there compression?

Type: grilling
Status: open

## Question

Set the maximum application payload for unicast and broadcast per profile, the link record size cap (ADR 0012), and decide no compression vs bounded compression.

## Context

- Inputs: bake-off throughput (ADR 0009), per-hop reassembly cost (ADR 0008), post-quantum overhead (+1,120 B first message, ADR 0002), 32 B padding (ADR 0012).
- Prior art: Bridgefy gzip-bomb and compression side-channel lessons (ticket 01).
