# Retry low-class messages from 5 s to 60 s

Status: ready-for-agent
Type: AFK
Blocked by: 73

## Behavior

Ticket 73 decided (D-62) that a low-class origin retries an unanswered message with doubling intervals from `retry-min-low` 5 s to `retry-max-low` 60 s, the high-class schedule, instead of 2 s to 30 s. The medium class keeps 2 s to 30 s as `retry-min-medium` and `retry-max-medium`. Certificate lookups follow the same per-class schedule (D-37). New-link retries and `link-retry-gap` are unchanged.

## Spec and ADR references

- [routing.md, Origin behavior](../../../docs/spec/routing.md#origin-behavior); [D-62](../../../docs/spec/decisions.md#d-62-low-class-retry-schedule).
- [ADR 0008](../../../docs/adr/0008-routing-topology-and-density-adaptation.md), amendment 2026-11-26 "low-class retry schedule".

## Acceptance criteria

- [ ] `routing.md` and `limits.json` carry the per-class retry parameters, with D-62 and the ADR 0008 amendment.
- [ ] `Outbox` retries the low class 5 s to 60 s and the medium class 2 s to 30 s, proven by `OutboxTest`.
- [ ] One `density.yml` run of all 8 cells with 5 seeds passes every band.

## Proof commands

```sh
./gradlew :meshlink:testAndroidHostTest --tests '*OutboxTest*'
gh workflow run density.yml --ref feat/low-class-retry-schedule
```

## Devices needed

None.
