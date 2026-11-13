# Complete Noise `XX` and `XXpsk3` handshakes

Status: ready-for-agent
Type: AFK
Blocked by: 19

## Behavior

Noise is written once in Kotlin common in `:meshlink-core`, over `CryptoProvider`. This ticket delivers the handshake state machine, the symmetric state and the transport cipher states for two suites:

- `Noise_XX_25519_ChaChaPoly_SHA256`;
- `Noise_XXpsk3_25519_ChaChaPoly_SHA256`, with the psk supplied by the caller.

It follows the Noise Protocol Framework specification. It covers: `MixHash`, `MixKey`, `MixKeyAndHash`, `EncryptAndHash`, `DecryptAndHash`, `Split`, the implicit 64-bit nonce counter, and `Rekey()`. `HKDF` is HKDF-SHA-256 with empty `info`. A transport nonce of `2^64 − 1` is never used.

Ephemerals come from the injected `Random`, or from fixed keys in tests. The state machine knows nothing about links, frames or the radio. Ticket 24 uses it for links. `IK` comes with ticket 46.

## Spec and ADR references

- [ADR 0002, Decision](../../../docs/adr/0002-noise-suites-and-handshake-patterns.md#decision) (link layer XX, conformance vectors) and [ADR 0004, Decision](../../../docs/adr/0004-crypto-provider-libsodium.md#decision) (Noise in Kotlin common).
- [link.md, Patterns](../../../docs/spec/link.md#patterns), [Pre-shared key](../../../docs/spec/link.md#pre-shared-key) and [Rekey](../../../docs/spec/link.md#rekey).
- [D-06](../../../docs/spec/decisions.md#d-06-mesh-psk).
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decisions 1 and 10.

## Acceptance criteria

- [ ] The cacophony vectors for `Noise_XX_25519_ChaChaPoly_SHA256` and `Noise_XXpsk3_25519_ChaChaPoly_SHA256` pass in the one runner from ticket 19, including the transport messages after `Split`. The cacophony set is vendored with its commit and SHA-256 in the register.
- [ ] A seeded test runs both suites end to end and checks that both sides derive the same handshake hash and cipher states.
- [ ] `Rekey()` matches the Noise definition: a test compares it to `ENCRYPT(k, maxnonce, zerolen, zeros)` truncated to 32 bytes.
- [ ] T10: a flipped bit in any handshake message, a wrong psk, a truncated message, an extra trailing byte, a message out of order, and a reused transport nonce each fail with a typed error and leave no usable cipher state.
- [ ] T10: a peer static key that gives an all-zero X25519 result fails the handshake.
- [ ] The handshake state rejects any call after it fails or completes.
- [ ] `./gradlew check` passes with Kover at 100%, and `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- [Dependency register](../../../docs/reference/dependencies.md): add the vendored cacophony vectors with source, commit, SHA-256 and license.
- [Vectors README](../../../docs/spec/vectors/README.md): mark the XX and XXpsk3 cacophony cross-check as passing in Kotlin.

## Devices needed

None. Host and simulator only.
