# Open `PostQuantum` links

Status: done
Type: AFK
Blocked by: 23, 24, 28

## Behavior

Two nodes configured with `SecurityProfile.PostQuantum` open a secured link on `VirtualAir` through the public API. Everything from ticket 24 applies, with these changes:

- **Patterns:** `XXhfs` in an open mesh and `XXhfspsk3` in a gated mesh (ticket 23).
- **Certificate:** the post-quantum layout (profile `0x02`, 1,354 B) with the 1,216 B X-Wing static key. Each node generates its X-Wing static key pair and issues the certificate (ticket 21).
- **Fragmentation:** `XXhfs` message 1 is about 1,216 B and message 2 about 2.6 KB, more than one cleartext record carries, so they travel as `FRAGMENT` frames carrying `HANDSHAKE`, in cleartext, using the fragment codec and reassembly of ticket 28. Reassembly stops at `handshake-message-max` (4,096 B) of Noise message.
- **Prologue** carries profile `0x02`, so a `Classical` peer and a `PostQuantum` peer fail the handshake.

Owner decision (2026-11-16): as in ticket 24, `send()` in `PostQuantum` is accepted but stays `Queued` until the end-to-end containers of ticket 45, so the public-API test proves secured peers and a transcript check proves the records after message 3 are encrypted.

## Spec and ADR references

- [link.md: Patterns](../../../docs/spec/link.md#patterns), [HANDSHAKE frame](../../../docs/spec/link.md#handshake-frame), [Handshake limits](../../../docs/spec/link.md#handshake-limits), [Fragments](../../../docs/spec/link.md#fragments).
- [identity.md, Post-quantum layout](../../../docs/spec/identity.md#post-quantum-layout-profile-0x02).
- [D-19](../../../docs/spec/decisions.md#d-19-record-and-frame-size-thresholds), [D-20](../../../docs/spec/decisions.md#d-20-handshake-denial-of-service-bounds).
- [ADR 0001, Decision](../../../docs/adr/0001-threat-model-and-profile-guarantees.md#decision) and [ADR 0002, Decision](../../../docs/adr/0002-noise-suites-and-handshake-patterns.md#decision) (post-quantum link layer, about 2.5 KB per establishment).

## Acceptance criteria

- [x] A public-API `VirtualAir` test brings up two `PostQuantum` nodes in an open mesh and in a gated mesh, and both list each other as secured peers. A transcript check proves the link records after message 3 are encrypted. A `send()` stays `Queued` until ticket 45 (owner decision above).
- [x] The test records each handshake message size and the number of records per message. Message 2 with the post-quantum certificate stays at most 4,096 B.
- [x] Tier 2 `XXhfs` and `XXhfspsk3` link transcripts with real post-quantum certificates are added through the ticket 20 reference and pass in Kotlin.
- [x] T10: a `Classical` peer, a certificate with a flipped X-Wing key byte, a classical-layout certificate, a fragmented Noise message over 4,096 B, and a non-`HANDSHAKE` frame inside a handshake `FRAGMENT` stream each fail the handshake with the spec's `CLOSE` reason.
- [x] Every ticket 24 T10 case also passes for `PostQuantum`.
- [x] `./gradlew check` passes with Kover at 100%, and `./gradlew iosSimulatorArm64Test` passes.

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

## Outcome

- **API:** `SecurityProfile.PostQuantum`, wire profile `0x02`. The builder's `meshKey` gates it as it does `Classical`.
- **Handshake:** `XXhfs` in an open mesh, `XXhfs+psk3` in a gated mesh. Each run issues a post-quantum certificate (1,354 B) that binds a fresh X-Wing public key; the X-Wing secret key is not kept until ticket 45 seals end-to-end messages to it. Verification is unchanged: a certificate of the classical layout or with a flipped X-Wing byte fails as `CERTIFICATE`.
- **Sizes:** Noise messages are 1,216, 2,557 and 1,421 B in an open mesh, and 1,248, 2,557 and 1,421 B in a gated mesh (`psk3` tags only message 1; messages 2 and 3 are already encrypted after `ee`). As `HANDSHAKE` frames they take 2, 3 and 2 cleartext records. The gated `handshake-hfs.json` messages are 39 B longer because its payloads add an unknown non-critical TLV on purpose.
- **Fragmentation:** `Link.send` now writes a control frame longer than a record body as `FRAGMENT`s of a new stream, sharing the stream counter with messages. Only cleartext handshake messages are that long (message 3 is written before the link is secured), so this overrides ticket 28's "control frames are never fragmented".
- **Tests:** `ClassicalLinkTest` became the abstract `SecuredLinkTest`, which `ClassicalLinkTest` and `PostQuantumLinkTest` run per profile. It gains a node of the other secured profile that never links, a fake of the other profile that fails as `DECRYPT`, a fragmented `PLAIN_HELLO` that fails as `PROTOCOL`, a certificate of the other profile's layout and, for `PostQuantum`, a flipped X-Wing byte. `PostQuantumLinkTest` adds the sizes and record counts.
- **Tier 2:** the vectors already carried post-quantum certificates (ticket 23). The Kotlin runner now validates the certificates in messages 2 and 3 of every valid `handshake-xx.json` and `handshake-hfs.json` test against the pattern's profile, each side's node id and its static key.
