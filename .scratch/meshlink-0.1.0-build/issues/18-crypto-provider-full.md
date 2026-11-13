# Complete `CryptoProvider` with every profile's primitives

Status: ready-for-agent
Type: AFK
Blocked by: 07

## Behavior

The internal `CryptoProvider` in `:meshlink-core` gains every primitive that the `Classical` and `PostQuantum` profiles need. The libsodium 1.0.22 binding in `:meshlink-crypto-sodium` implements it: a JNI shim on Android and the host, and cinterop on iOS. The binding exposes only the functions MeshLink calls.

Ticket 07 already delivers BLAKE2b, Ed25519 key generation and randomness. This ticket adds:

- **X25519** (`crypto_scalarmult`). An all-zero or low-order result is an error, never a key.
- **Ed25519** detached sign and verify, pure Ed25519 (`crypto_sign`), plus key pairs from a seed.
- **ChaCha20-Poly1305, IETF** (`crypto_aead_chacha20poly1305_ietf`): encrypt and decrypt with associated data. Decrypt failure is a typed result, never partial plaintext.
- **SHA-256** (`crypto_hash_sha256`) for the Noise hash.
- **HKDF-SHA-256** extract and expand (`crypto_kdf_hkdf_sha256_*`), for the spec `KDF` and the Noise HKDF.
- **ML-KEM-768** key generation, encapsulation and decapsulation (`crypto_kem_mlkem768_*`). Decapsulation of a bad ciphertext uses implicit rejection: it returns a pseudo-random key, not an error.
- **X-Wing** MLKEM768-X25519 (`crypto_kem_xwing_*`), KEM ID `0x647a`.

The sans-IO core takes an injected `Random` (ADR 0017 decision 1). So key generation and encapsulation also have deterministic variants that take a seed. The core uses them with seeds drawn from the injected `Random`, and vectors use them with fixed seeds.

No source sets zeroization or extra constant-time rules beyond libsodium's own. ChaChaPoly was chosen because it is constant-time in software (ADR 0002). This ticket adds no extra rule.

## Spec and ADR references

- [ADR 0004, Decision](../../../docs/adr/0004-crypto-provider-libsodium.md#decision) (binding, X-Wing pin, gates) and its [2026-11-03 amendment](../../../docs/adr/0004-crypto-provider-libsodium.md#amendments) (export allow-list, `nm` gate).
- [ADR 0002, Decision](../../../docs/adr/0002-noise-suites-and-handshake-patterns.md#decision) (primitives and suites).
- [ADR 0011](../../../docs/adr/0011-modules-toggles-and-presets.md) decision 2 (module direction) and [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decisions 1 and 4 (injected `Random`, real crypto on the host).
- [Spec README, Derivation rules](../../../docs/spec/README.md#derivation-rules) (`H`, `KDF`, `DS`).
- Vectors: [draft-ietf-hpke-pq-05](https://datatracker.ietf.org/doc/draft-ietf-hpke-pq/05/) Appendix A.5.

## Acceptance criteria

- [ ] `CryptoProvider` in `:meshlink-core` declares each primitive above, and `:meshlink-crypto-sodium` implements it on Android, the host and iOS.
- [ ] The X-Wing `0x647a` vectors from draft-ietf-hpke-pq-05 Appendix A.5 pass on the host and the iOS simulator. A failure is reported to the owner, not patched around (ADR 0004).
- [ ] Known-answer tests cover each new primitive with at least one published vector (RFC 7748, RFC 8032, RFC 8439, RFC 5869, FIPS 203). Ticket 19 later moves them into the one runner.
- [ ] T10: X25519 with an all-zero or low-order public key returns an error; Ed25519 verify rejects a flipped signature bit, a wrong key and a wrong message; AEAD decrypt rejects a flipped tag, ciphertext or associated-data bit; ML-KEM-768 and X-Wing decapsulation of a tampered ciphertext yield a key different from the sender's; wrong-length keys, nonces and ciphertexts are rejected before any native call.
- [ ] Deterministic key generation and encapsulation give identical output for identical seeds on the host and the iOS simulator.
- [ ] The native code stays at most 1 MB per ABI. The `nm` export gate still finds no `sodium_` or `crypto_` symbol, and the ticket-07 AES symbol check still passes.
- [ ] `./gradlew check` passes with Kover at 100%, and `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- [Dependency register](../../../docs/reference/dependencies.md): extend the libsodium row's list of bound functions if the row lists them.
- `PROJECT.md`: record the native size per ABI if the profile tracks it.

## Devices needed

None. Host and simulator only. Ticket 26 measures speed on the oldest phones.
