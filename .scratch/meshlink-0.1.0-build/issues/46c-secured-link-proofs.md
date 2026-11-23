# Prove secured links across `REKEY` and `BEARER_SWITCH`

Status: done
Type: AFK
Blocked by: 46b

## Behavior

Split from ticket 46 (owner decision, 2026-11-23). These proofs moved to ticket 46 from tickets 31 (Q7) and 30 (Q5) because they need several direct messages between two secured peers, which secured sessions (ticket 46b) make possible.

## Outcome

Merged in #134 (`9b4d470`).

- [`secured-links.feature`](../../../meshlink-testing/src/commonTest/resources/features/secured-links.feature) has 4 scenarios: each of the 2 proofs below runs in `classical` and in `post-quantum`.
  Its steps are in `SecuredLinkSteps.kt`, on seeded `VirtualAir`.
- Bearer switch: Alice's L2CAP channel opens 2 s after the link, which leaves a window on GATT.
  Bob receives the first message while the link is still on GATT.
  Alice writes the next 3 on the channel, with no further GATT bytes.
  All 4 are delivered once each, in order, and each ends `Delivered`.
- Rekey: Alice sends one message, then one more a minute after each of the next 3 hourly `REKEY`s.
  All 4 are delivered once each, in order.
  Each node opens 3 `REKEY` frames, and the link stays open.
- Counterexamples were rejected: a 0 s channel delay, 4 rekeys expected, and 5 messages expected.
- The full gate passed (EXIT 0), with Kover at 100%. No docs changed.

## Spec and ADR references

- [link.md](../../../docs/spec/link.md) `REKEY` and `BEARER_SWITCH`; [D-21](../../../docs/spec/decisions.md#d-21-rekey-schedule).
- Tickets [30](30-rekey.md) and [31](31-coc-bearer-switch.md), owner decisions.

## Acceptance criteria

- [x] A seeded `VirtualAir` test in `Classical` and `PostQuantum` switches a link to CoC with `BEARER_SWITCH` and shows direct messages before and after the switch delivered once each, in order (moved from ticket 31, Q7).
- [x] A seeded `VirtualAir` test holds a `Classical` and a `PostQuantum` link for 3 virtual hours and shows direct messages delivered before and after each hourly link `REKEY` (moved from ticket 30, Q5).
- [x] `./gradlew check` passes with Kover at 100%.

## Proof commands

```sh
./gradlew check
```

## Docs to update

None expected; the tests prove existing spec behavior.

## Devices needed

None. Host only.
