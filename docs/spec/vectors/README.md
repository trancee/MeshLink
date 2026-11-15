# Test vectors

Status: Draft. Part of [MeshLink protocol 1](../README.md).

Vectors are required by Constitution rule C10 and ADR 0019 decision 4. They come in two tiers.

## Tier 1: published

Tier 1 covers every byte layout (encode and decode) and every hash-only derivation. `tools/spec/gen_vectors.py` generates the files with the Python 3 standard library only; the output is deterministic. `tools/spec/check_spec.py` recomputes every vector.

<!-- data: vectors -->

| File | Tests | Covers |
|---|---|---|
| [derivations.json](derivations.json) | 51 | Epoch, NodeId, mesh identifier digest, mesh tag and key, psk, resolvable, public, unsecured, conversation and lookup tags, `H(k)`, lookup-reply key, prologues, HPKE info, signing inputs |
| [records.json](records.json) | 8 | Link record framing |
| [advertisement.json](advertisement.json) | 10 | 16 B advertisement payload |
| [capability.json](capability.json) | 14 | Capability record and TLVs |
| [certificate.json](certificate.json) | 13 | Certificate layouts |
| [handshake-payload.json](handshake-payload.json) | 8 | Link handshake payload TLVs |
| [link-frames.json](link-frames.json) | 71 | Every link-local frame |
| [routed-frames.json](routed-frames.json) | 59 | Every routed frame, containers, unknown routed types |
| [inner-plaintext.json](inner-plaintext.json) | 19 | End-to-end plaintext, extensions and padding |

Total: 253 tests.

Layouts that contain signatures, keys, Noise messages or ciphertexts use fixed placeholder bytes. Those tests carry the flag `Placeholder` and test only the framing.

### File format

The format follows Wycheproof. Each file has a JSON Schema next to it (`*.schema.json`, draft 2020-12).

| Field | Meaning |
|---|---|
| `algorithm` | What the file covers |
| `schema` | Name of the schema file |
| `generatorVersion` | Version of `gen_vectors.py` |
| `numberOfTests` | Number of tests in the file |
| `notes` | Meaning of each flag used |
| `testGroups[]` | Groups. A layout group has `layout` (and `secured` for plaintexts); a derivation group has `derivation` and `domain` |
| `tests[].tcId` | Test number, unique in the file, from 1 |
| `tests[].comment` | What the test shows |
| `tests[].flags` | Flags, explained in `notes` |
| `tests[].result` | `valid`, `invalid` or `acceptable` |
| `tests[].encoded` | Layout tests: the bytes, hex |
| `tests[].decoded` | Layout tests with result `valid` or `acceptable`: the fields; byte strings in hex |
| `tests[].error` | Layout tests with result `invalid`: the error code |
| `tests[].inputs`, `tests[].output` | Derivation tests: inputs and expected output, hex |

- A decoder MUST reject every `invalid` test. The error code names the first rule broken; an implementation need not report the same code but SHOULD map to an equivalent typed error.
- An `acceptable` test is well formed but handled specially: a reserved advertisement flag (ignored) or an unknown routed type (relayed, not delivered).
- An encoder given `decoded` MUST produce `encoded` for every `valid` test.

### Error codes

| Code | Meaning |
|---|---|
| `truncated` | The input ends inside a field |
| `trailing-bytes` | Bytes remain after the last field |
| `out-of-range` | A value, length or count is outside its allowed range |
| `too-long` | The whole structure exceeds its maximum size |
| `bad-length` | A fixed-size structure has the wrong size |
| `unknown-version` | Unsupported layout, certificate or protocol version |
| `unknown-kind` | Unknown or disallowed profile or container kind |
| `unknown-type` | Unknown link-local frame type |
| `reserved-type` | Frame type `0x00` or `0xC0`–`0xFF` |
| `version-order` | Capability version list not strictly descending |
| `tlv-order` | TLV types not strictly ascending |
| `unknown-critical-tlv` | Unknown TLV with bit 7 set |
| `tlv-length` | Known TLV with the wrong fixed length |
| `bad-padding` | Secured plaintext padding not zero, too long or misaligned; or padding in `unsecured` |

## Vector runner

One runner executes every tier 1 file and every vendored external set: `VectorRunner` in `:meshlink-crypto-sodium` `commonTest` (package `ch.trancee.meshlink.vectors`). It runs in the Android host tests (`./gradlew check`) and on the iOS simulator (`./gradlew iosSimulatorArm64Test`), with no network access. It lives in the crypto module, not in `:meshlink-core`, because derivation and Wycheproof handlers need the real `CryptoProvider` (ADR 0017 decision 10).

- **Embedding.** The `meshlink.spec-vectors` build plugin copies each JSON file into generated test sources as minified text chunks. A vendored file must match its line in the `SHA256SUMS` next to it, or the build fails.
- **Dispatch.** `VectorSets.kt` gives each file a `VectorSet`: a key function, such as the group's `layout` or `derivation`, a frame's first byte or a Wycheproof group `type`, and one handler per key. A handler checks one test against its `result`.
- **No silent gaps.** The run fails if a file has no set, if `numberOfTests` is wrong, if a test has neither a handler nor a skip, or if a skip or handler matches no test. A mismatch fails with `<file> group <key> tcId <n>`.
- **Skips.** `vectorSkips` lists every key with no handler yet. `Skip.Pending` names the ticket that lands the code; `Skip.NotApplicable` states why MeshLink never runs those tests, such as HPKE KEMs other than X-Wing.
- **Report.** `everyVectorFilePasses` prints the `valid`, `invalid`, `acceptable` and skipped counts of each file.

To add a codec or derivation: write its handler in `LayoutHandlers.kt` or `CryptoHandlers.kt`, add it to the file's set, and delete the matching `Skip.Pending`. The run fails while the skip and the handler both exist.

To add a file: a tier 1 file is picked up from this directory automatically and needs a set. An external set goes under `third_party/vectors/<set>/` with unchanged files, its license, a `SHA256SUMS` and a `SOURCE.md`, plus a row in the [dependency register](../../reference/dependencies.md#vendored-test-vectors).

| Set | Directory | Covers |
|---|---|---|
| Wycheproof | [`third_party/vectors/wycheproof`](../../../third_party/vectors/wycheproof/SOURCE.md) | X25519, Ed25519, ChaCha20-Poly1305, HKDF-SHA-256, ML-KEM-768 (key generation, encapsulation, decapsulation) |
| hpke-pq | [`third_party/vectors/hpke-pq`](../../../third_party/vectors/hpke-pq/SOURCE.md) | X-Wing, KEM `0x647a`: key derivation, encapsulation, decapsulation |

## Tier 2: pending

These vectors need X25519, Ed25519, AEAD, ML-KEM, X-Wing, HPKE or Noise. Status for each: **pending — two implementations (Kotlin + Rust reference, ADR 0002/0004)**. They are required before library 1.0.0 (ADR 0019 decision 4).

| Vector set | Covers |
|---|---|
| Certificate signatures | Ed25519 over `DS("certificate")` ‖ body; valid, wrong key, wrong NodeId, expired, validity over 24 h |
| Signed broadcasts | Ed25519 over the broadcast signing input; hop-limit changes keep the signature valid |
| `XX` and `XXpsk3` link handshakes | Full transcripts with the MeshLink prologue and payload TLVs, cross-checked with cacophony |
| `XXhfs` and `XXhfspsk3` link handshakes | Full transcripts with ML-KEM-768, cross-checked with snow `hfs` and libp2p if published |
| Link transport and rekey | Record encryption, `REKEY` and nonce continuity across `BEARER_SWITCH` |
| `IK` and `IKhfs` sessions | Message 1 with plaintext, message 2 in a receipt, transport frames with explicit nonces and replay rejection |
| HPKE X-Wing seal | RFC 9180 base mode, KEM `0x647A`, KDF `0x0001`, AEAD `0x0003`, with the MeshLink `info` |
| Sealed lookup replies | X25519, HKDF and ChaCha20-Poly1305 seal of a certificate; all-zero shared secret rejected |
| Lookup-reply key from X25519 | End-to-end from the two ephemeral key pairs (tier 1 covers only the HKDF step) |
| Mesh psk use | `XXpsk3` with the derived psk |

Primitive conformance uses Wycheproof for X25519, Ed25519, ChaCha20-Poly1305, HKDF and ML-KEM-768 (ADR 0002 decision "Conformance vectors").
