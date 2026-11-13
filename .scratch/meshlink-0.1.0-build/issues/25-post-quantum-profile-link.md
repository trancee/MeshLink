# Open `PostQuantum` links

Status: ready-for-agent
Type: AFK
Blocked by: 23, 24, 28

## Behavior

Two nodes configured with `SecurityProfile.PostQuantum` open a secured link on `VirtualAir` through the public API. Everything from ticket 24 applies, with these changes:

- **Patterns:** `XXhfs` in an open mesh and `XXhfspsk3` in a gated mesh (ticket 23).
- **Certificate:** the post-quantum layout (profile `0x02`, 1,354 B) with the 1,216 B X-Wing static key. Each node generates its X-Wing static key pair and issues the certificate (ticket 21).
- **Fragmentation:** `XXhfs` message 1 is about 1,216 B and message 2 about 2.6 KB, more than one cleartext record carries, so they travel as `FRAGMENT` frames carrying `HANDSHAKE`, in cleartext, using the fragment codec and reassembly of ticket 28. Reassembly stops at `handshake-message-max` (4,096 B) of Noise message.
- **Prologue** carries profile `0x02`, so a `Classical` peer and a `PostQuantum` peer fail the handshake.

## Spec and ADR references

- [link.md: Patterns](../../../docs/spec/link.md#patterns), [HANDSHAKE frame](../../../docs/spec/link.md#handshake-frame), [Handshake limits](../../../docs/spec/link.md#handshake-limits), [Fragments](../../../docs/spec/link.md#fragments).
- [identity.md, Post-quantum layout](../../../docs/spec/identity.md#post-quantum-layout-profile-0x02).
- [D-19](../../../docs/spec/decisions.md#d-19-record-and-frame-size-thresholds), [D-20](../../../docs/spec/decisions.md#d-20-handshake-denial-of-service-bounds).
- [ADR 0001, Decision](../../../docs/adr/0001-threat-model-and-profile-guarantees.md#decision) and [ADR 0002, Decision](../../../docs/adr/0002-noise-suites-and-handshake-patterns.md#decision) (post-quantum link layer, about 2.5 KB per establishment).

## Acceptance criteria

- [ ] A public-API `VirtualAir` test brings up two `PostQuantum` nodes in an open mesh and in a gated mesh and exchanges a direct message both ways, encrypted on air.
- [ ] The test records each handshake message size and the number of records per message. Message 2 with the post-quantum certificate stays at most 4,096 B.
- [ ] Tier 2 `XXhfs` and `XXhfspsk3` link transcripts with real post-quantum certificates are added through the ticket 20 reference and pass in Kotlin.
- [ ] T10: a `Classical` peer, a certificate with a flipped X-Wing key byte, a classical-layout certificate, a fragmented Noise message over 4,096 B, and a non-`HANDSHAKE` frame inside a handshake `FRAGMENT` stream each fail the handshake with the spec's `CLOSE` reason.
- [ ] Every ticket 24 T10 case also passes for `PostQuantum`.
- [ ] `./gradlew check` passes with Kover at 100%, and `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- [Security profile guarantees](../../../docs/reference/security-profile-guarantees.md): confirm the `PostQuantum` row matches the behavior, or fix it in this PR.
- [Vectors README](../../../docs/spec/vectors/README.md): mark the post-quantum link transcripts as covered.

## Devices needed

None. `VirtualAir` only. Ticket 26 proves the profiles on phones.
