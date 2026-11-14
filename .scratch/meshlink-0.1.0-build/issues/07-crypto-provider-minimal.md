# Hash, generate identities and draw randomness through a minimal `CryptoProvider`

Status: claimed
Type: AFK
Blocked by: 06

## Behavior

`:meshlink-crypto-sodium` implements an internal `CryptoProvider` with only what the tracer bullet needs: BLAKE2b (unkeyed and keyed, variable output length), Ed25519 key-pair generation, and secure random bytes. A JNI shim serves Android and the JVM host tests; cinterop serves iOS. Common code derives NodeId, MeshIdDigest, the epoch and the mesh tag, and those match the published vectors on every test target. Other primitives arrive in ticket 18.

## Spec and ADR references

- [ADR 0004](../../../docs/adr/0004-crypto-provider-libsodium.md) decision ("Binding": expose only the functions MeshLink calls; "Build": static, hidden symbols; "Gates": at most 1 MB per ABI) and the 2026-11-03 amendment (export allow-list, `nm` gate on `sodium_` and `crypto_` exports).
- [ADR 0005](../../../docs/adr/0005-toolchain-and-dependency-baseline.md) "libsodium build" (the AES invariant: CI fails if the shim's exported or imported symbols include an `aes`, `aegis` or `ipcrypt` function) and "Coverage" (`iosMain` thin adapters listed in a CI-named file).
- [ADR 0011](../../../docs/adr/0011-modules-toggles-and-presets.md) decision 7 (BLAKE2b in every profile) and decision 2 (`:meshlink-crypto-sodium` depends only on `:meshlink-core`).
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decision 4 (real crypto on the host).
- Spec: [derivation rules](../../../docs/spec/README.md#derivation-rules), [epochs](../../../docs/spec/README.md#epochs), [NodeId](../../../docs/spec/identity.md#nodeid), [mesh identifier](../../../docs/spec/discovery.md#mesh-identifier), [mesh tag](../../../docs/spec/discovery.md#mesh-tag), [vector file format](../../../docs/spec/vectors/README.md#file-format).

## Acceptance criteria

- [x] `CryptoProvider` lives in `:meshlink-core` under `ch.trancee.meshlink.internal` and has exactly three capabilities: `blake2b(out length, input, optional key)`, Ed25519 key-pair generation (from a fresh seed and from a given 32 B seed, for vectors), and `randomBytes(n)`. No other primitive is exposed (ADR 0004 "Binding").
- [x] The JNI shim and the cinterop definition call only the libsodium functions behind those capabilities. The PR lists them.
- [x] Android `.so` files link libsodium statically and export only the JNI entry points through a version script; Apple builds use `-exported_symbols_list`. A CI step runs `nm` on every built binary and fails on any exported `sodium_` or `crypto_` symbol, and on any exported or imported symbol matching `aes`, `aegis` or `ipcrypt`. A test proves each gate fails on a seeded violation.
- [x] A CI step fails if native code in any Android ABI or iOS slice exceeds 1 MB. The PR records each size.
- [x] Verify that the AGP KMP library plugin packages the shim's `.so` files into the AAR for the three ABIs; if it cannot, stop and report the blocker rather than switch plugins (E1).
- [x] Android host tests load the test-only host shim (macOS arm64, Linux x86_64) from ticket 06 and use real libsodium; no fake crypto in these tests.
- [x] Common derivation code computes `H`, `trunc_n`, NodeId, MeshIdDigest, epoch and mesh tag. A shared `commonTest` vector runner reads `derivations.json` and passes every `epoch`, `node-id`, `mesh-id-digest` and `mesh-tag` case on the Android host and on `iosSimulatorArm64`. Ed25519 seed-to-public-key matches RFC 8032 section 7.1 TEST 1.
- [x] The `iosMain` adapter files are listed in the CI-named thin-adapter file from ADR 0005 "Coverage"; Kover stays at 100% for `commonMain` and `androidMain`.
- [x] `./gradlew check` and `./gradlew iosSimulatorArm64Test` pass.

## Proof commands

```sh
./gradlew :meshlink-crypto-sodium:testAndroidHostTest --tests '*Derivation*'
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- `PROJECT.md`: proof-matrix rows for the vector runner (Android host, iOS simulator) and the native gates.
- `docs/reference/dependencies.md`: none unless the shim build adds a tool.
- `GLOSSARY.md`: add `CryptoProvider` if it has no entry.

## Devices needed

None.
