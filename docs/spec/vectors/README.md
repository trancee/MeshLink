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
