# Write the 0.1.0 backlog

Type: task
Status: resolved
Blocked by: 01, 02, 03, 04, 05, 06, 07, 08, 10

## Question

Write the ordered implementation backlog in the form ticket 05 decides, sliced per ticket 07, including release infrastructure (v1 ticket 36), the release workflow, device-lab fixes and the evidence runs of ticket 06. Reaching this ticket's end reaches the destination.

## Context

- Carried from v1: [ticket 36](../../meshlink-v1/issues/36-release-infrastructure.md).
- From ticket 06 ([ADR 0017 amendment](../../../docs/adr/0017-testing-architecture.md#amendments)): the `labapp/` app, the `tools/lab/` scripts, a HITL lab-fault ticket (Bluetooth off on 5 remote phones, nova 9 adb timeout, Pixel 4a address) and the release evidence run itself.
- From ticket 07: milestones M0–M9, the tracer order and the slicing rules ([answer](07-build-order-and-slices.md#answer)).
- From ticket 08 ([ADR 0006 amendment](../../../docs/adr/0006-documentation-architecture.md#amendments)): the Pages workflow publishing Dokka at `/api/` plus the hand-written root `index.html`.

## Answer

The backlog is written: [the 0.1.0 implementation backlog](../../meshlink-0.1.0-build/spec.md) with 64 tickets in [`issues/`](../../meshlink-0.1.0-build/issues/), milestones M0–M9, fixed proof commands and the scheduled jobs. 54 tickets are `ready-for-agent` and 10 `ready-for-human`; none needs triage. Ticket 54 says how to split it by code range if it proves too big.

Decided while writing it, with the owner:

1. **libsodium moves into M1.** NodeIds and mesh tags hash with BLAKE2b in every profile, and ADR 0017 forbids fake crypto, so the `Unsecured` tracer needs a minimal binding (correction in [build order and slices](07-build-order-and-slices.md#answer)).
2. **Signing folds into broadcast.** "Flood and sign mesh broadcasts" (ticket 39) carries D-40 in every profile; the separate signing ticket is gone.
3. **License: the Unlicense.** Ticket 04 adds `LICENSE` and the POM entry, and publishes `:meshlink-core` and `:meshlink-crypto-sodium` as internal, dependency-only artifacts.
4. **Identity storage** (ticket 15): Android Keystore AES-GCM wrapping in no-backup storage, iOS Keychain `AfterFirstUnlockThisDeviceOnly`; never in backups; an unreadable key blocks with `IdentityUnavailable` until `resetIdentity()`.
5. **GATT flow control** (ticket 29): a link-local `CREDIT` frame, window 8 records, reversing C-09 on bake-off rule R14.
6. **`REKEY` on `Unsecured`** is dropped with a diagnostic (ticket 30).
7. **Deferred beyond 0.1.0:** extended advertising and the iOS Live Activity hook (ADR 0010 v6, ADR 0011 v10).
8. **Proximity** (ticket 32): RSSI EWMA, α 0.25; `Immediate` ≥ −55 dBm, `Near` ≥ −75 dBm, else `Far`; `Unknown` after 30 s.
9. **API limits:** one running instance per platform radio, any number on `VirtualAir` (ticket 09); inbox bound 1,024 messages (ticket 12); any well-formed NodeId is sendable in `Unsecured` (ticket 37).
10. **Certificate skew** (ticket 21): ±30 s on both edges, after a threat-model analysis that stops for the owner if a guarantee weakens.
11. **Retries** resend the same container re-tagged for the current epoch, as `routing.md` says; errata in the spec, ADRs 0003, 0013, 0014, 0017 and 0019, and ADR 0021 v2 (ticket 05 adds the required checks).

Each ADR change listed per ticket lands as that ticket's amendment; only the errata and deferrals above are already in `docs/`.
