# What do members learn about neighbors and routes?

Type: grilling
Status: resolved

## Question

Link encryption hides route messages from outsiders in secured profiles. Decide what neighbor lists, route requests and replies, catch-up digests and Plumtree messages reveal to other members and relays, and what v1 minimizes or documents.

## Context

- Inputs: threat model (A3), ADR 0008 message set, ADR 0012 sender NodeId in `unsecured`.

## Answer

Grilled 2026-11-08; recorded in ADR 0016 and `docs/reference/observer-exposure.md`.

1. Public routing tags derive from the certified static key (not the identity key): recognizable for at most ~25 h by whoever learned the certificate.
2. Neighbor lists and shuffles carry 8 B on-air advertisement identifiers, direct neighbors only, never forwarded.
3. Only first messages use the destination's public tag; afterwards both directions use per-epoch conversation tags from the end-to-end session. Relays, even holding both keys, see only "someone contacted D". Routes become per conversation; ticket 32 must confirm the cost.
4. Catch-up summaries, hop counts and timing, broadcast origin and `unsecured` exposure: accepted and documented.
5. Amended ADRs 0001, 0008, 0012, 0013, 0014; threat model metadata section; guarantees table.
