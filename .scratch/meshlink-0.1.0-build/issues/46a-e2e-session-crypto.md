# Add the end-to-end session cryptography

Status: ready-for-agent
Type: AFK
Blocked by: 45a, 47

## Behavior

Split from ticket 46 (owner decision, 2026-11-23). This ticket covers the session cryptography and its vectors. Ticket 46b uses them in the mesh. No public behavior changes.

- **Message 2:** a responder handshake that has read message 1 writes `IK` or `IKhfs` message 2 (`e, ee, se` or `e, ee, ekem1, se`) under the `e2e-prologue`. Its payload is a padded `DELIVERY_RECEIPT` plaintext whose `MESSAGE_IDS` lists the first message. The container is kind `0x02`. The initiator reads message 2 with the state of the first message it wrote. Both sides then split into an end-to-end session. Keeping those handshakes in `FirstMessages` (answer and adopt) is ticket 46b (owner decision, 2026-11-23).
- **Transport:** kind `0x03` with an explicit `u64` nonce from a per-session send counter starting at 0, and empty associated data (Noise `SetNonce`). A receiver accepts each nonce once, within a 1,024-nonce window of the highest nonce it has accepted. A replayed nonce, a nonce below the window and a frame that fails to open change no state.
- **Vectors:** the tier 2 set "`IK` and `IKhfs` sessions" moves from pending to published as `e2e-sessions.json`. `tools/reference` generates it from fixed inputs, `check_spec.py` recomputes what it can without AEADs, and the Kotlin vector runner replays it.

## Spec and ADR references

- [end-to-end.md, Sessions](../../../docs/spec/end-to-end.md#sessions), [Containers](../../../docs/spec/end-to-end.md#containers), [Plaintext](../../../docs/spec/end-to-end.md#plaintext) and [Delivery receipts](../../../docs/spec/end-to-end.md#delivery-receipts).
- [D-33](../../../docs/spec/decisions.md#d-33-end-to-end-session-limits), [D-34](../../../docs/spec/decisions.md#d-34-session-opening-and-failure).
- [ADR 0002](../../../docs/adr/0002-noise-suites-and-handshake-patterns.md) decisions "End-to-end layer", "Sessions" and "Conformance vectors"; [ADR 0005](../../../docs/adr/0005-toolchain-and-dependency-baseline.md) (test-only Rust reference).

## Acceptance criteria

- [ ] `cargo run --manifest-path tools/reference/Cargo.toml` writes `e2e-sessions.json` and its schema. For `IK` and `IKhfs` they give message 1, message 2 carrying a receipt, and transport frames each way, some out of order. Invalid tests cover a tampered message 2, a message 2 for another first message, a replayed nonce, a nonce below the window and a tampered transport frame. `cargo test` passes.
- [ ] `python3 tools/spec/check_spec.py` recomputes the set's container layout, sizes, receipt plaintext, nonces and the step of each rejection.
- [ ] The Kotlin vector runner replays every test: each side writes message 2 and the transport frames byte for byte, the other side opens them, and every invalid test fails at its stated step without changing session state.
- [ ] Unit tests (T10) for the replay window: a replayed nonce, a nonce exactly at and just below the window edge, a nonce far ahead, and a tampered frame. Each new decoder has a deterministic mutation test.
- [ ] `./gradlew check` passes with Kover at 100%; `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
cargo test --manifest-path tools/reference/Cargo.toml
python3 tools/spec/check_spec.py
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- [end-to-end.md, Patterns](../../../docs/spec/end-to-end.md#patterns): the vectors line ("Message 2 and transport vectors are pending").
- [vectors/README.md](../../../docs/spec/vectors/README.md): the published table, the replay rules and "Checked in Kotlin"; remove the pending set.

## Devices needed

None. Host and simulator only.
