# Prove secured links across `REKEY` and `BEARER_SWITCH`

Status: ready-for-agent
Type: AFK
Blocked by: 46b

## Behavior

Split from ticket 46 (owner decision, 2026-11-23). These proofs moved to ticket 46 from tickets 31 (Q7) and 30 (Q5) because they need several direct messages between two secured peers, which secured sessions (ticket 46b) make possible.

## Spec and ADR references

- [link.md](../../../docs/spec/link.md) `REKEY` and `BEARER_SWITCH`; [D-21](../../../docs/spec/decisions.md#d-21-rekey-schedule).
- Tickets [30](30-rekey.md) and [31](31-coc-bearer-switch.md), owner decisions.

## Acceptance criteria

- [ ] A seeded `VirtualAir` test in `Classical` and `PostQuantum` switches a link to CoC with `BEARER_SWITCH` and shows direct messages before and after the switch delivered once each, in order (moved from ticket 31, Q7).
- [ ] A seeded `VirtualAir` test holds a `Classical` and a `PostQuantum` link for 3 virtual hours and shows direct messages delivered before and after each hourly link `REKEY` (moved from ticket 30, Q5).
- [ ] `./gradlew check` passes with Kover at 100%.

## Proof commands

```sh
./gradlew check
```

## Docs to update

None expected; the tests prove existing spec behavior.

## Devices needed

None. Host only.
