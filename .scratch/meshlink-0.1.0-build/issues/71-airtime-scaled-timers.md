# Decide whether large-message timers scale with airtime

Status: needs-triage
Type: HITL
Blocked by: 50

## Behavior

Found in ticket 50 (D-57). The origin's retry and resend timers and the end-to-end session lapse (second retry without a receipt) are fixed times. A 131,072 B frame takes seconds per hop, so a retry often fires while the first copy is still being written or relayed. D-57 stops a second copy at the origin while one is being written, but relays still forward each retry's copy, and a session can lapse while its receipt is on its way. Decide whether these timers scale with a message's size or the measured airtime per hop, and how.

Questions for the owner:

- Which timers scale: `resend-base` + `resend-per-hop`, the retry schedule, the lapse count, or a combination?
- What is the measure: frame size × hops ÷ an assumed rate, or a per-link rate measured from writes?
- Does a slower first retry cost delivery latency for large messages that relays dropped?

## Spec and ADR references

- [routing.md, Origin behavior](../../../docs/spec/routing.md#origin-behavior); [D-57](../../../docs/spec/decisions.md#d-57-one-copy-of-a-large-message-at-a-time), [D-58](../../../docs/spec/decisions.md#d-58-fresh-routes-for-large-messages).
- [end-to-end.md, Sessions](../../../docs/spec/end-to-end.md#sessions) ("Failure").
- [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md) (retry schedule); [ADR 0015](../../../docs/adr/0015-message-size-and-compression.md) decision 6.

## Acceptance criteria

- [ ] Seeded `VirtualAir` runs with `airtimeBytesPerSecond` set measure copies per link, delivery latency and lapses for 131,072 B messages on the 5-node line, with fixed and with scaled timers.
- [ ] The owner decides; the decision is recorded as a D-nn entry and, if the timers change, in the spec and an ADR 0008 amendment.
- [ ] If the timers change, an AFK ticket implements them.

## Proof commands

```sh
./gradlew :meshlink-testing:testAndroidHostTest --tests '*LargeMessage*'
```

## Docs to update

- `docs/spec/decisions.md`, `docs/spec/routing.md`, `docs/spec/end-to-end.md` and ADR 0008 if the timers change.

## Devices needed

None for the decision. Ticket 62's 128 KiB device pairs confirm real airtime.
