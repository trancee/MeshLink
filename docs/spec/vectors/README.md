# Test vectors

Status: Draft. Part of [MeshLink protocol 1](../README.md).

Vectors are required by Constitution rule C10 and ADR 0019 decision 4. They come in two tiers: tier 1 needs only hashing, tier 2 needs the real cryptography.

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
| `tier` | Tier 2 files only: `2` |
| `generatorVersion` | Version of the generator: `gen_vectors.py` for tier 1, `tools/reference` for tier 2 |
| `numberOfTests` | Number of tests in the file |
| `notes` | Meaning of each flag used |
| `testGroups[]` | Groups. A layout group has `layout` (and `secured` for plaintexts); a derivation group has `derivation` and `domain`; a tier 2 group has `type` |
| `tests[].tcId` | Test number, unique in the file, from 1 |
| `tests[].comment` | What the test shows |
| `tests[].flags` | Flags, explained in `notes` |
| `tests[].result` | `valid`, `invalid` or `acceptable` |
| `tests[].encoded` | Layout tests: the bytes, hex |
| `tests[].decoded` | Layout tests with result `valid` or `acceptable`: the fields; byte strings in hex |
| `tests[].error` | Tests with result `invalid`: the error code |
| `tests[].inputs`, `tests[].output` | Derivation tests: inputs and expected output, hex |
| Other `tests[]` fields | Tier 2 tests: named in the file's schema and in [Tier 2: published](#tier-2-published) |

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
| `bad-signature` | Ed25519 signature does not verify under the certificate's key |
| `node-id-mismatch` | Certificate key does not hash to the expected NodeId |
| `expired` | `now` ≥ `not_after` + 30 s |
| `not-yet-valid` | `now` + 30 s < `not_before` |
| `handshake-failed` | A Noise handshake message fails to decrypt, at `rejected_step` |
| `open-failed` | An HPKE payload fails to open |

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
| cacophony | [`third_party/vectors/cacophony`](../../../third_party/vectors/cacophony/SOURCE.md) | Noise `XX` and `XXpsk3` in `25519_ChaChaPoly_SHA256`: every handshake and transport message and the handshake hash, from both roles. `IK` is pending until ticket 46; the other patterns and suites are not applicable |

## Tier 2: published

Tier 2 files come from `tools/reference/`, a test-only Rust crate that is never published or linked into an artifact (ADR 0004, ADR 0005). It is the second implementation that ADR 0002 requires; the Kotlin library is the first. Every key, ephemeral, KEM seed and randomness is derived from a fixed label, so the output is deterministic.

<!-- data: vectors -->

| File | Tests | Covers |
|---|---|---|
| [certificate-signatures.json](certificate-signatures.json) | 28 | Both certificate profiles: valid, ±30 s skew at both ends, window over 24 h or zero, flipped signature or body bit, wrong signer, wrong NodeId, other profile, truncated, trailing bytes |
| [handshake-hfs.json](handshake-hfs.json) | 7 | `XXhfs` and `XXhfs+psk3` transcripts with payload TLVs and one transport message each way; each message tampered, prologue mismatch, wrong mesh key |
| [hpke-seal.json](hpke-seal.json) | 7 | X-Wing seal with the MeshLink `info`; flipped ciphertext, tag or `enc` bit, wrong recipient NodeId, truncated payload |

Total: 42 tests.

- **Regenerate:** `cargo run --manifest-path tools/reference/Cargo.toml` writes each file and its schema. `cargo test --manifest-path tools/reference/Cargo.toml` fails if a committed file differs from the generator, and checks the crate's X-Wing and HPKE against draft-ietf-hpke-pq-05 Appendix A.5 from the vendored hpke-pq vectors (key derivation, `enc`, 10 encryptions, 5 exports). A scheduled workflow, `reference-vectors.yml`, runs both and diffs the directory; it is not a required check.
- **Python check:** `check_spec.py` cannot run Ed25519, ML-KEM or AEADs with the standard library. For tier 2 it recomputes everything else: certificate layout, NodeId and the ±30 s validity rules, signing inputs, prologues, psk, payload TLVs and certificates inside handshakes, Noise message sizes, the cause and step of each rejection, the HPKE suite and `info`, and seal sizes.
- **Fixed ephemerals in snow.** The reference drives snow at the pinned commit. `Builder::fixed_ephemeral_key_for_testing_only` sets the X25519 ephemeral. snow's built-in ML-KEM uses system randomness, so the crate resolves the KEM through its own resolver: key generation from the 64 B `kem_keygen_seed` (`d ‖ z`), encapsulation from the 32 B `kem_encaps_seed`, both through `ml-kem`. Everything else comes from snow's default resolver, and a crate test shows its KEM interoperates with snow's own.
- **Replay.** A handshake test gives both sides' keys, seeds, prologue and psk, and the payload each side writes. An implementation with the same fixed inputs MUST produce `messages` byte for byte, then `handshake_hash` and `transport`. For an `invalid` test, `messages` are as delivered (with the tampered byte); the receiver of message `rejected_step` MUST fail. A seal test gives the recipient's IKM and the encapsulation randomness: a sender MUST produce `payload` for a `valid` test; a recipient MUST open `payload` to `plaintext`, or fail for an `invalid` test.
- **Times.** `now` is Unix seconds. Certificate tests use `not_before` 1798761600 (2027-01-01) and a 24 h window.
- **Checked in Kotlin.** The vector runner validates every `certificate-signatures.json` test, reproduces each signature from `ed25519_seed`, and reissues each `valid` certificate byte for byte from its keys with a clock at `not_before`. `handshake-hfs.json` and `hpke-seal.json` stay pending until tickets 23 and 45.

## Tier 2: pending

These sets are not generated yet. Status for each: **pending — two implementations (Kotlin + Rust reference, ADR 0002/0004)**. They are required before library 1.0.0 (ADR 0019 decision 4).

| Vector set | Covers |
|---|---|
| Signed broadcasts | Ed25519 over the broadcast signing input; hop-limit changes keep the signature valid |
| `XX` and `XXpsk3` link handshakes | Full transcripts with the MeshLink prologue and payload TLVs. The cacophony cross-check of both patterns already passes in Kotlin |
| Link transport and rekey | Record encryption, `REKEY` and nonce continuity across `BEARER_SWITCH` |
| `IK` and `IKhfs` sessions | Message 1 with plaintext, message 2 in a receipt, transport frames with explicit nonces and replay rejection |
| Sealed lookup replies | X25519, HKDF and ChaCha20-Poly1305 seal of a certificate; all-zero shared secret rejected |
| Lookup-reply key from X25519 | End-to-end from the two ephemeral key pairs (tier 1 covers only the HKDF step) |
| Mesh psk use | `XXpsk3` with the derived psk |

Primitive conformance uses Wycheproof for X25519, Ed25519, ChaCha20-Poly1305, HKDF and ML-KEM-768 (ADR 0002 decision "Conformance vectors").
