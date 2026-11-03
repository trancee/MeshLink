# How does store-and-forward work?

Type: grilling
Status: resolved
Blocked by: 19

## Question

Decide quota, retention, custody (who holds for whom, hand-off rules), privacy of held messages, eviction and fairness, and the static-key rotation period that bounds how long held messages stay readable (ADR 0003).

## Context

- Routing-level holding is decided (ADR 0008): 100 messages, 10 s minimum remaining lifetime, 120 s minimum lifetime, ±30 s clock skew.
- Candidate: bounded-lifetime store-and-forward keys for forward secrecy (ADR 0001: none in v1).
- ADR 0011: holding is a preset toggle that never affects relaying.

## Answer

Resolved 2026-11-03 with the user in three grilling rounds; recorded in [ADR 0014](../../../docs/adr/0014-store-and-forward.md), with amendments to ADRs 0001, 0003, 0008, 0011, 0012 and 0013, the threat model and the guarantees table.

- Origin keeps its copy; relays hold best-effort copies in memory; no custody transfer, no copy budget.
- Lifetime at most 1 h (user: short-term messaging); presets 30 min, 1 h or 120 s.
- Quota 100 messages plus a byte cap, ¼ per incoming link; eviction by over-share, then soonest expiry.
- Broadcasts are held; catch-up offers held messages and tombstones (routing-simulation rerun needed).
- Delivered copies are purged by tombstones {message ID, k} checked against H(k) in the header (user request).
- Certificates valid 24 h, renewed every 12 h; never seal to an expired certificate.
- Residual tracking risk bounded at 1 h, documented.
- New tickets: certificate acquisition for never-linked nodes; catch-up airtime simulation.
