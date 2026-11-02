# How does store-and-forward work?

Type: grilling
Status: open
Blocked by: 19

## Question

Decide quota, retention, custody (who holds for whom, hand-off rules), privacy of held messages, eviction and fairness, and the static-key rotation period that bounds how long held messages stay readable (ADR 0003).

## Context

- Routing-level holding is decided (ADR 0008): 100 messages, 10 s minimum remaining lifetime, 120 s minimum lifetime, ±30 s clock skew.
- Candidate: bounded-lifetime store-and-forward keys for forward secrecy (ADR 0001: none in v1).
- ADR 0011: holding is a preset toggle that never affects relaying.
