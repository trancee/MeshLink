# Complete Noise `XXhfs` and `XXhfspsk3` handshakes

Status: ready-for-agent
Type: AFK
Blocked by: 20, 22

## Behavior

The Noise code from ticket 22 gains the `hfs` extension with ML-KEM-768, for the post-quantum link layer:

- `Noise_XXhfs_25519+ML-KEM-768_ChaChaPoly_SHA256`;
- `Noise_XXhfspsk3_25519+ML-KEM-768_ChaChaPoly_SHA256`.

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

- [ ] The Rust-generated tier 2 `XXhfs` and `XXhfspsk3` vectors from ticket 20 pass byte for byte in the one runner, on the host and the iOS simulator, including transport messages after `Split`. The runner no longer lists them as pending.
- [ ] A seeded test runs both suites end to end and checks equal handshake hashes and cipher states.
- [ ] The measured message sizes are recorded in the test: message 1 carries the 1,184 B ML-KEM-768 public key in `e1`, and no message exceeds `handshake-message-max` (4,096 B) with a post-quantum certificate payload.
- [ ] T10: a flipped bit in `e1`, in `ekem1`, in the encrypted static key and in the payload each fail the handshake with a typed error. A wrong psk fails `XXhfspsk3`. A truncated or over-long KEM field fails before any KEM call.
- [ ] The classical `XX` and `XXpsk3` cacophony vectors still pass.
- [ ] `./gradlew check` passes with Kover at 100%, and `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- [Vectors README](../../../docs/spec/vectors/README.md): mark the `XXhfs` and `XXhfspsk3` sets as cross-checked in Kotlin and Rust.

## Devices needed

None. Host and simulator only. Ticket 26 measures speed on the oldest phones.
