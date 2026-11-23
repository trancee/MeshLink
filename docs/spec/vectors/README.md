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
| [link-frames.json](link-frames.json) | 75 | Every link-local frame |
| [routed-frames.json](routed-frames.json) | 59 | Every routed frame, containers, unknown routed types |
| [inner-plaintext.json](inner-plaintext.json) | 19 | End-to-end plaintext, extensions and padding |

Total: 265 tests.

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
| `open-failed` | An HPKE payload or a sealed lookup reply fails to open |
| `low-order-key` | An X25519 public key has low order, so the shared secret would be all zero |

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
| hpke-pq | [`third_party/vectors/hpke-pq`](../../../third_party/vectors/hpke-pq/SOURCE.md) | X-Wing, KEM `0x647a`: key derivation, encapsulation, decapsulation; HPKE base mode with HKDF-SHA256 and ChaCha20Poly1305: key schedule, every encryption and every export, from both contexts. The other HPKE suites are not applicable |
| cacophony | [`third_party/vectors/cacophony`](../../../third_party/vectors/cacophony/SOURCE.md) | Noise `XX`, `XXpsk3` and `IK` in `25519_ChaChaPoly_SHA256`: every handshake and transport message and the handshake hash, from both roles. The other patterns and suites are not applicable |

## Tier 2: published

Tier 2 files come from `tools/reference/`, a test-only Rust crate that is never published or linked into an artifact (ADR 0004, ADR 0005). It is the second implementation that ADR 0002 requires; the Kotlin library is the first. Every key, ephemeral, KEM seed and randomness is derived from a fixed label, so the output is deterministic.

<!-- data: vectors -->

| File | Tests | Covers |
|---|---|---|
| [broadcast-signatures.json](broadcast-signatures.json) | 12 | Signed broadcasts: hop limit 64, lowered by a relay, signed at 1 and 255, empty and 1,024 B payloads; flipped payload, signature, `message_id` or expiry bit, swapped `origin_key`, signature by another identity key |
| [certificate-signatures.json](certificate-signatures.json) | 28 | Both certificate profiles: valid, ±30 s skew at both ends, window over 24 h or zero, flipped signature or body bit, wrong signer, wrong NodeId, other profile, truncated, trailing bytes |
| [handshake-xx.json](handshake-xx.json) | 7 | `XX` and `XXpsk3` transcripts with classical certificates in the payload TLVs, the MeshLink prologue, the derived mesh psk, and one transport message each way; each message tampered, prologue mismatch, wrong mesh key |
| [handshake-hfs.json](handshake-hfs.json) | 7 | `XXhfs` and `XXhfs+psk3` transcripts with post-quantum certificates in the payload TLVs (the gated ones also with an unknown non-critical TLV), the MeshLink prologue, the derived mesh psk, and one transport message each way; each message tampered, prologue mismatch, wrong mesh key |
| [link-transport.json](link-transport.json) | 10 | Link records after open `XX` and `XXhfs` handshakes: three frames each way, `REKEY` each way, then two more each way; a record sealed under the key before `REKEY`, a flipped tag bit after it. Three frames each way on GATT, `BEARER_SWITCH` each way, then two more each way on CoC with the nonces carried over; the first CoC record sealed at nonce 0 |
| [first-message.json](first-message.json) | 8 | End-to-end first messages: `IK` and `IKhfs` message 1 under the e2e prologue, carrying a padded `MESSAGE` plaintext with the sender's certificate and a conversation seed, sealed with HPKE for `IKhfs`; a bit flipped in transit, a seal with a flipped `enc` or ciphertext bit, an initiator holding another responder key |
| [e2e-sessions.json](e2e-sessions.json) | 12 | End-to-end sessions after the `first-message.json` message 1: `IK` and `IKhfs` message 2 in a kind `0x02` container whose payload is a padded `DELIVERY_RECEIPT` plaintext listing the first message, then kind `0x03` transport frames each way at explicit nonces, out of order and at the replay-window edge; message 2 with a flipped tag bit or answering another first message, a replayed nonce, a nonce just below the window, a frame with a flipped tag bit followed by the frame as written |
| [hpke-seal.json](hpke-seal.json) | 7 | X-Wing seal with the MeshLink `info`; flipped ciphertext, tag or `enc` bit, wrong recipient NodeId, truncated payload |
| [lookup-reply.json](lookup-reply.json) | 7 | Lookup replies end to end from both ephemeral key pairs: X25519, the reply key and the ChaCha20-Poly1305 seal of a classical and a post-quantum certificate; flipped ciphertext or tag bit, sealed for another salt, a low-order ephemeral on either side |

Total: 98 tests.

- **Regenerate:** `cargo run --manifest-path tools/reference/Cargo.toml` writes each file and its schema. `cargo test --manifest-path tools/reference/Cargo.toml` fails if a committed file differs from the generator, and checks the crate's X-Wing and HPKE against draft-ietf-hpke-pq-05 Appendix A.5 from the vendored hpke-pq vectors (key derivation, `enc`, 10 encryptions, 5 exports). A scheduled workflow, `reference-vectors.yml`, runs both and diffs the directory; it is not a required check.
- **Python check:** `check_spec.py` cannot run Ed25519, ML-KEM or AEADs with the standard library. For tier 2 it recomputes everything else: certificate and broadcast layout, NodeId and the ±30 s validity rules, signing inputs, prologues, psk, payload TLVs and certificates inside handshakes, Noise message sizes, the cause and step of each rejection, the HPKE suite and `info`, seal sizes, lookup tags, lookup-reply keys from the given shared secret and which side refuses a low-order key, the record framing, sizes and `REKEY` or `BEARER_SWITCH` placement of each link stream, and each session's container layouts, receipt `MESSAGE_IDS`, message 2 size and replay-window decisions.
- **Fixed ephemerals in snow.** The reference drives snow at the pinned commit. `Builder::fixed_ephemeral_key_for_testing_only` sets the X25519 ephemeral. snow's built-in ML-KEM uses system randomness, so the crate resolves the KEM through its own resolver: key generation from the 64 B `kem_keygen_seed` (`d ‖ z`), encapsulation from the 32 B `kem_encaps_seed`, both through `ml-kem`. Everything else comes from snow's default resolver, and a crate test shows its KEM interoperates with snow's own.
- **Replay.** A handshake test gives both sides' keys, seeds, prologue and psk, and the payload each side writes. An implementation with the same fixed inputs MUST produce `messages` byte for byte, then `handshake_hash` and `transport`. For an `invalid` test, `messages` are as delivered (with the tampered byte); the receiver of message `rejected_step` MUST fail. A link transport group gives the inputs of the open valid handshake of its pattern; each side seals its `_frames` as link records and MUST produce its `_stream` byte for byte, rekeying its sending direction after `REKEY` and its receiving direction after opening one; after `BEARER_SWITCH` both directions keep their keys and nonces. The responder MUST fail to open record `rejected_record` of `initiator_stream`. A first-message test gives the initiator's keys and KEM seed, the responder key it holds and, for `IKhfs`, the seal inputs: the initiator MUST write `payload` to `message` byte for byte (except a `Tampered` test), and the responder MUST read `message` to `payload` and, for `IKhfs`, open it to `plaintext`; `handshake-failed` means the Noise read fails, `open-failed` that the read succeeds and the seal does not open. A session group gives both sides' inputs, `payload_1` and `message_1`; the responder MUST write message 2 with `receipt_plaintext` to `container_2` byte for byte, and the initiator MUST read it with the state that wrote `message_1` (`handshake-failed` if it cannot). Then each frame's sender MUST seal `plaintext` at `nonce` to `container` byte for byte, and its receiver MUST refuse a nonce with `nonce + 1024 <= highest accepted` (`nonce-below-window`), then an accepted nonce (`replayed-nonce`), before opening, and fail an unopenable frame (`open-failed`); a frame's `error` is its expected outcome, and a refused or failed frame changes no state. A seal test gives the recipient's IKM and the encapsulation randomness: a sender MUST produce `payload` for a `valid` test; a recipient MUST open `payload` to `plaintext`, or fail for an `invalid` test. A lookup test gives both ephemeral key pairs: a responder MUST produce `sealed` for a `valid` test and refuse a low-order `requester_ephemeral`; a requester MUST open `sealed` to `certificate`, or yield nothing for an `invalid` test.
- **Times.** `now` is Unix seconds. Certificate tests use `not_before` 1798761600 (2027-01-01) and a 24 h window.
- **Checked in Kotlin.** The vector runner validates every `certificate-signatures.json` test, reproduces each signature from `ed25519_seed`, and reissues each `valid` certificate byte for byte from its keys with a clock at `not_before`. It checks every `broadcast-signatures.json` test: the signing input, the verify result and the origin's NodeId, and it re-signs each test that has `ed25519_seed` byte for byte. It replays every `handshake-xx.json` and `handshake-hfs.json` test: both sides write each message byte for byte from their keys (and KEM seeds), a `valid` test reaches `handshake_hash` and both transport messages, and the certificates in its messages 2 and 3 validate for the pattern's profile and each side's node id and bind that side's static key; an `invalid` test fails exactly at `rejected_step`. So `XX`, `XXpsk3`, `XXhfs` and `XXhfs+psk3` link transcripts, with their certificates, are cross-checked in Kotlin and Rust. It replays every `link-transport.json` test through `LinkCipher`: each side seals its frames to its stream byte for byte, the other side cuts the stream with `RecordReader` and opens each record to its frame across `REKEY` or `BEARER_SWITCH`, and an `invalid` test fails exactly at `rejected_record`. It replays every `lookup-reply.json` test through `LookupSeal`: both sides derive `shared_secret` and `key` from their private keys, the responder seals `certificate` to `sealed` byte for byte, and the requester opens it to a certificate that validates for `target_node_id`; an `invalid` test yields no certificate, and a low-order key is refused before the AEAD. It checks every `hpke-seal.json` test through `Hpke`: the `info` from its parts, the public key from `recipient_secret_key`, the payload opened to `plaintext` and resealed byte for byte from `encapsulation_randomness`, or refused for an `invalid` test. It replays every `first-message.json` test: the initiator writes `message` from its keys and `responder_key` byte for byte, the responder reads it back to `payload` and, for `IKhfs`, opens the seal to `plaintext`, which reseals to `payload`; an `invalid` test fails at the Noise read or at the seal as its `error` says. It replays every `e2e-sessions.json` test: the initiator writes `message_1` byte for byte, the responder reads it and writes `container_2` from `receipt_plaintext` byte for byte unless the test alters it in transit, and the initiator's read of `container_2` fails exactly for `handshake-failed`. Otherwise both `EndToEndSession`s reach `handshake_hash`; each sender's counter, sealed up to its highest frame nonce, gives each frame's `container` byte for byte unless the frame fails to open, and the receiver opens the frames in order to their `plaintext` or refuses each with its `error`.

## Tier 2: pending

None. Every tier 2 set that ADR 0002 and ADR 0019 decision 4 require before library 1.0.0 is published above.

Primitive conformance uses Wycheproof for X25519, Ed25519, ChaCha20-Poly1305, HKDF and ML-KEM-768 (ADR 0002 decision "Conformance vectors").
