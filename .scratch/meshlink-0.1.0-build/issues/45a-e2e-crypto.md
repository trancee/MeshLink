# Add the end-to-end cryptography for first messages

Status: done
Type: AFK
Blocked by: 20, 44

## Behavior

Split from ticket 45 (owner decision). This ticket covers the cryptography and its vectors. Ticket 45b uses them for secured sends. No public behavior changes.

- **HPKE:** RFC 9180 base mode over `CryptoProvider`, with KEM X-Wing (`0x647A`), KDF HKDF-SHA256 (`0x0001`) and AEAD ChaCha20Poly1305 (`0x0003`). It covers `SealBase`, `OpenBase`, the context's sequence numbers and `Export`. The first-message seal is `enc ‖ ct` with `info = DS("hpke-first-message") ‖ initiator x25519 static key ‖ responder NodeId` and empty AAD. It adds 1,136 B.
- **Derivations:** `e2e-prologue`, `hpke-info`, `public-tag`, `conversation-secret` and `conversation-tag`.
- **Noise:** the `IK` and `IKhfs` patterns, with the responder's static key as the initiator's pre-message and the `ss` token. `IKhfs` message 1 is `e, es, e1, s, ss`, and message 2 is `e, ee, ekem1, se`.
- **Message 1:** the `IK` (`Classical`) and `IKhfs` (`PostQuantum`) first-message payloads under the `e2e-prologue`. The `IK` payload is the padded plaintext. The `IKhfs` payload is the HPKE seal of the padded plaintext.

## Spec and ADR references

- [end-to-end.md](../../../docs/spec/end-to-end.md): [Patterns](../../../docs/spec/end-to-end.md#patterns), [First messages](../../../docs/spec/end-to-end.md#first-messages), [Conversation tags](../../../docs/spec/end-to-end.md#conversation-tags).
- [routing.md, Routing tags](../../../docs/spec/routing.md#routing-tags).
- [D-32](../../../docs/spec/decisions.md#d-32-conversation-seed-and-tags), [D-35](../../../docs/spec/decisions.md#d-35-hpke-parameters), [C-06](../../../docs/spec/decisions.md#c-06).
- [ADR 0002](../../../docs/adr/0002-noise-suites-and-handshake-patterns.md) decisions "End-to-end layer", "Post-quantum first and held messages" and "Conformance vectors"; [ADR 0004](../../../docs/adr/0004-crypto-provider-libsodium.md) (HPKE ours, X-Wing pin, hpke-pq gate).

## Outcome

- Shipped in [#123](https://github.com/trancee/MeshLink/pull/123) (`535793e`).
- `first-message.json` has 8 tier 2 cases from the Rust reference. `IK` message 1 is 320 B and `IKhfs` message 1 is 3,872 B. A changed byte in transit or a wrong responder key fails the Noise read (`handshake-failed`). A changed `enc` or `ct` in the seal fails the HPKE open (`open-failed`).
- `check_spec.py` recomputes all 343 vectors, including the new set.
- The end-to-end tag derivations live in `EndToEndTags`, so `Derivations` stays within detekt's function limit without a suppression.
- Hand mutants were all killed. The surviving `<=` mutant on the HPKE overhead check led to `HpkeTest.anEmptyPlaintextSealsToExactlyEncAndTagAndOpens`.

## Acceptance criteria

- [x] HPKE passes the `draft-ietf-hpke-pq-05` Appendix A.5 vectors for `0x647A`: key schedule, every encryption and every export.
- [x] HPKE passes the tier 2 set "HPKE X-Wing seal" (`hpke-seal.json`).
- [x] The `e2e-prologue`, `hpke-info`, `public-tag`, `conversation-secret` and `conversation-tag` derivations pass `derivations.json`.
- [x] `IK` passes cacophony.
- [x] The Rust reference generates the message 1 cases of "`IK` and `IKhfs` sessions". Kotlin writes each message 1 byte for byte, and the responder reads it back to the padded plaintext. Changed bytes are rejected at the right step: a tampered `enc` or `ct`, and a wrong responder key.
- [x] `./gradlew check` passes with Kover at 100%; `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
cargo test --manifest-path tools/reference/Cargo.toml
```

## Docs to update

- [Vectors README](../../../docs/spec/vectors/README.md): the new set, "Checked in Kotlin", cacophony `IK`, and the tier 2 table.

## Devices needed

None. Host and simulator only.
