# Complete Noise `XXhfs` and `XXhfspsk3` handshakes

Status: ready-for-agent
Type: AFK
Blocked by: 20, 22

## Behavior

The Noise code from ticket 22 gains the `hfs` extension with ML-KEM-768, for the post-quantum link layer:

- `Noise_XXhfs_25519+MLKEM768_ChaChaPoly_SHA256`;
- `Noise_XXhfs+psk3_25519+MLKEM768_ChaChaPoly_SHA256`.

The `hfs` patterns are byte-compatible at the Noise layer with the libp2p proposal and the Noise HFS specification: same protocol name, same `e1`/`ekem1` token placement, same mixing order. If the two diverge, the Noise HFS specification wins.

The KEM key pair and encapsulation come from `CryptoProvider` (ticket 18). Their seeds come from the injected `Random`, or are fixed in tests. Decapsulation of a tampered `ekem1` gives a pseudo-random secret through implicit rejection, so the handshake fails at the next authenticated decrypt.

The state machine stays independent of links, frames and the radio. Ticket 25 uses it for links. `IKhfs` comes with ticket 46.

## Spec and ADR references

- [ADR 0002, Decision](../../../docs/adr/0002-noise-suites-and-handshake-patterns.md#decision) (post-quantum link layer, conformance vectors) and its [amendments](../../../docs/adr/0002-noise-suites-and-handshake-patterns.md#amendments).
- [ADR 0004, Decision](../../../docs/adr/0004-crypto-provider-libsodium.md#decision) (Noise in Kotlin common, two-implementation vectors).
- [link.md, Patterns](../../../docs/spec/link.md#patterns) and [Handshake limits](../../../docs/spec/link.md#handshake-limits).
- [Vectors README, Tier 2, pending](../../../docs/spec/vectors/README.md#tier-2-pending).
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decision 10.

## Acceptance criteria

- [x] The Rust-generated tier 2 `XXhfs` and `XXhfspsk3` vectors from ticket 20 pass byte for byte in the one runner, on the host and the iOS simulator, including transport messages after `Split`. The runner no longer lists them as pending.
- [x] A seeded test runs both suites end to end and checks equal handshake hashes and cipher states.
- [x] The measured message sizes are recorded in the test: message 1 carries the 1,184 B ML-KEM-768 public key in `e1`, and no message exceeds `handshake-message-max` (4,096 B) with a post-quantum certificate payload.
- [x] T10: a flipped bit in `e1`, in `ekem1`, in the encrypted static key and in the payload each fail the handshake with a typed error. A wrong psk fails `XXhfspsk3`. A truncated or over-long KEM field fails before any KEM call.
- [x] The classical `XX` and `XXpsk3` cacophony vectors still pass.
- [x] `./gradlew check` passes with Kover at 100%, and `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- [Vectors README](../../../docs/spec/vectors/README.md): mark the `XXhfs` and `XXhfspsk3` sets as cross-checked in Kotlin and Rust.

## Devices needed

None. Host and simulator only. Ticket 26 measures speed on the oldest phones.

## Outcome

- **Spec check:** the token placement and mixing follow the Noise HFS specification (`noiseprotocol/noise_hfs_spec` at `025f0f60cb3b94ad75b68e3a4158b9aac234f8cb`, sections 4 and 5): `e1` directly after the first `e` (XX message 1 has no DH), `ekem1` directly after `ee`, both sent with `EncryptAndHash`, and `MixKey(kem_output)` after `ekem1`. `e1` gets no psk `MixKey`. snow at the pinned commit does the same, so the two don't diverge.
- **KEM inputs:** as with the ephemerals in ticket 22, production draws the KEM key pair and encapsulation from `CryptoProvider` (ADR 0017 decision 1), not from the injected `RandomSource`. `NoiseKeys` takes an optional fixed `kemKeyPair` (initiator) and 32-byte `encapsulationSeed` (responder) for tests and vectors.
- **Code:** `NoisePattern.XX_HFS` and `XX_HFS_PSK3` with the `E1` and `EKEM1` tokens in `Noise.kt`. A peer KEM key that fails the FIPS 203 modulus check fails the responder's message 2 with `INVALID_PUBLIC_KEY`.
- **Field lengths:** Noise fields have no length prefix, so "over-long" means bytes inserted into a field. A message cut inside `e1` or `ekem1` fails with `MESSAGE_TOO_SHORT` before any KEM call. A byte inserted into or removed from `ekem1` fails its tag before decapsulation. In `XXhfs` message 1, `e1` travels before any key exists, so the responder can't detect a change to it. The change surfaces when the initiator reads message 2, as `handshake-hfs.json` test 2 also expects.
- **Measured sizes** with the 1,357 B post-quantum certificate payload of the vectors: `XXhfs` 1,216, 2,557 and 1,421 B; `XXhfs+psk3` 1,248, 2,557 and 1,421 B. All are under 4,096 B.
- **Vectors:** `handshake-hfs.json`: 2 valid, 5 invalid, 0 skipped; cacophony is unchanged at 2 valid, 942 skipped.
