# Rotate static keys and drop expired certificates

Status: ready-for-agent
Type: AFK
Blocked by: 44, 49

## Behavior

In `Classical` and `PostQuantum`, a node rotates its static keys so that a stolen device exposes only about the last 25 h of recorded first and held messages. Peers never seal to an expired certificate.

- **Rotation:** every 12 h, a node issues a new certificate with fresh X25519 (and, in `PostQuantum`, X-Wing) static keys. Each is valid 24 h, so two are valid at once. The new certificate goes into every following link handshake.
- **Key retention:** a node keeps a certificate's static private keys until its `not_after` + 3,630 s, then deletes them. Until then it accepts `IK` messages and public tags for every certificate whose keys it holds.
- **Refresh:** a node attaches its current certificate (`SENDER_CERTIFICATE` extension) to `IK` replies and receipts when the peer's copy is past half its validity.
- **Expired certificates:** a sender never seals to an expired certificate. `send` to such a peer returns `Accepted`; the status stays `Queued` while ticket 44's lookup fetches a fresh certificate, then the message is sealed, or it reaches `Expired`. There is no `Rejected(PeerKeyExpired)`.
- **Cache:** cached certificates are dropped at `not_after`; validation rejects a certificate valid for more than 86,400 s.
- **Unsecured:** no certificates, no rotation.

## Spec and ADR references

- [identity.md, Static-key rotation](../../../docs/spec/identity.md#static-key-rotation) and [Validation](../../../docs/spec/identity.md#validation) (rules 2, 5 and 6).
- [end-to-end.md, Plaintext extensions](../../../docs/spec/end-to-end.md#plaintext-extensions); [routing.md, Certificate lookup](../../../docs/spec/routing.md#certificate-lookup).
- [ADR 0014](../../../docs/adr/0014-store-and-forward.md) decisions 9 and 10 and its decision 10 amendment; [ADR 0018](../../../docs/adr/0018-certificate-acquisition.md) decisions 7 and 8; [ADR 0003](../../../docs/adr/0003-installation-identity-key-model.md) amendment; [ADR 0016](../../../docs/adr/0016-routing-metadata-exposure.md) decision 1.

## Acceptance criteria

- [ ] Virtual-time unit tests: a new certificate every 12 h, valid 24 h; static private keys deleted exactly at `not_after` + 3,630 s and not before.
- [ ] Seeded `VirtualAir` test, `Classical` and `PostQuantum`: a first message sealed to the older valid certificate opens after rotation; a held message tagged with the old public tag is claimed and opened before the keys are deleted.
- [ ] A test past `not_after` + 3,630 s: a message to the deleted keys does not open and fails as the spec says.
- [ ] A test lets the peer's copy pass half its validity: the next `IK` reply and receipt carry `SENDER_CERTIFICATE`, and the sender's cache updates.
- [ ] A test with an expired cached certificate: `send` returns `Accepted`, stays `Queued`, a lookup runs, and the message is sealed to the fresh certificate and delivered; without an answer it reaches `Expired`.
- [ ] T10: a certificate valid for more than 86,400 s, an expired one or one with a bad signature is never cached or sealed to.
- [ ] The guarantees row on exposure after key theft (about the last 25 h) matches the code.
- [ ] `./gradlew check` passes with Kover at 100% and the ABI dump updated; `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- [docs/reference/security-profile-guarantees.md](../../../docs/reference/security-profile-guarantees.md) only if the key-theft exposure row disagrees with the code.
- KDoc of `DeliveryStatus.Queued` for the expired-certificate case.

## Devices needed

None. Host and simulator only.
