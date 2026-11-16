# Cross-check tier 2 vectors with the Rust reference

Status: ready-for-agent
Type: AFK
Blocked by: 19

## Behavior

A test-only Rust reference lives in `tools/reference/`. It is never published and never linked into an artifact. It is the second, independent implementation that ADR 0002 requires for the `hfs` patterns, the identity certificate and the nested X-Wing seal.

The reference generates these tier 2 vector files in the vectors README format, from fixed keys and seeds:

- **Certificate signatures:** both layouts; valid, wrong key, wrong NodeId, expired, and a validity window over 24 h. Ticket 21 matches them in Kotlin.
- **`XXhfs` and `XXhfspsk3` handshakes:** full transcripts with fixed static keys, ephemerals and KEM seeds, through snow. Ticket 23 matches them.
- **HPKE X-Wing seal:** KEM `0x647A`, KDF `0x0001`, AEAD `0x0003`, MeshLink `info`. Ticket 45 matches it.

The files are committed in `docs/spec/vectors/`. The Kotlin runner from ticket 19 lists them as pending until the owning ticket lands. Other tier 2 sets are added by the ticket that implements them, using this crate.

A scheduled, non-required CI job builds the reference, regenerates every tier 2 file and fails if any byte differs from the committed file. It also runs the crate's own tests.

The ticket must determine and record in the vectors README:

- how `tools/spec/check_spec.py` treats tier 2 files: it now requires each file in a vectors table and recomputes vectors in Python;
- how snow at the pinned commit takes fixed ephemerals and KEM seeds. If it cannot, report to the owner before choosing another route.

## Outcome

- Snow at the pinned commit takes a fixed X25519 ephemeral through `fixed_ephemeral_key_for_testing_only`, but its ML-KEM draws system randomness. The crate resolves its own `Kem` (key generation from a 64 B seed, encapsulation from a 32 B message, through `ml-kem`) and delegates the rest to snow's default resolver; a crate test shows it interoperates with snow's own ML-KEM. No fork was needed.
- Protocol names (D-45, ADR 0002 2026-11-16 amendment): snow parses only `MLKEM768` (owner decision, option A), and it rejects `XXhfspsk3` with `UnsupportedModifier`: Noise rev 34 section 8.1 separates a second modifier with `+`, so the gated name is `Noise_XXhfs+psk3_25519+MLKEM768_ChaChaPoly_SHA256`. Folded into D-45 as the same rule; ADR prose keeps `XXhfspsk3` as shorthand.
- `check_spec.py` dispatches tier 2 files on the group `type` and recomputes everything the Python standard library can: certificate layout, NodeId and ±30 s rules, signing inputs, prologues, psk, payload TLVs, message sizes, rejection cause and step, HPKE suite, `info` and seal sizes. Signatures, KEMs and AEADs are proven by the Rust staleness test and, later, the Kotlin handlers.
- `chacha20poly1305` 0.11.0 is pinned in `Cargo.lock` through hpke's `chacha` feature, not as a direct dependency. `blake2` 0.11.0 was added (owner decision). ml-kem's `hazmat` feature turned out to gate nothing and was not enabled. snow brings duplicate older `chacha20poly1305`, `blake2` and `curve25519-dalek`; ADR 0005 (2026-11-16) records them.
- The crate's HPKE known-answer test is draft-ietf-hpke-pq-05 Appendix A.5 from the vendored hpke-pq vectors: key derivation, `enc`, 10 encryptions and 5 exports. It covers both the X-Wing and the HPKE criterion.
- Tier 2 files carry `"tier": 2` (owner decision). The Kotlin runner lists them as pending: `CertificateVerify` (21), `NoiseHandshake` (23), `HpkeSeal` (45).

## Spec and ADR references

- [ADR 0002, Decision](../../../docs/adr/0002-noise-suites-and-handshake-patterns.md#decision) (two-implementation vectors) and [ADR 0004, Decision](../../../docs/adr/0004-crypto-provider-libsodium.md#decision) (Rust test-only).
- [ADR 0005](../../../docs/adr/0005-toolchain-and-dependency-baseline.md) "Test-only Rust reference" and "CI" (pins, scheduled job).
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decisions 10, 12 and 13.
- [Vectors README, Tier 2, pending](../../../docs/spec/vectors/README.md#tier-2-pending); [identity.md, Identity certificate](../../../docs/spec/identity.md#identity-certificate); [link.md, Patterns](../../../docs/spec/link.md#patterns).
- [D-11](../../../docs/spec/decisions.md#d-11-certificate-version-and-signature-input).

## Acceptance criteria

- [x] `tools/reference/` has `rust-toolchain.toml` with Rust 1.100.0, a committed `Cargo.lock`, and exactly the ADR 0005 crate pins: rust-hpke 0.15.1, `ml-kem` 0.3.4, `x25519-dalek` and `ed25519-dalek` 3.0.0, `chacha20poly1305` 0.11.0, `sha2` 0.11.1, `hkdf` 0.13.0, snow at `dc18c06c28fbdeaa6ffae7347e650c805e9b932e`.
- [x] No Gradle module, artifact or app depends on `tools/reference/`.
- [x] The crate generates the three tier 2 sets above. Each file has a schema, is listed in the vectors README tier table, and passes `python3 tools/spec/check_spec.py`.
- [x] The crate's own tests check its X-Wing against draft-ietf-hpke-pq-05 Appendix A.5 and its HPKE against the hpke-pq vectors.
- [x] T10 cases are present as `invalid` tests: a flipped certificate signature bit, a wrong NodeId, expiry, a window over 86,400 s, a tampered `hfs` message, and an X-Wing seal with a flipped ciphertext bit.
- [x] The scheduled workflow regenerates the files and diffs them. Actions are the latest stable release, pinned by commit SHA with a version comment. It is not a required check.
- [x] `cargo test --manifest-path tools/reference/Cargo.toml` passes locally.
- [x] `./gradlew check` passes with Kover at 100%, and `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

New command, to be added to the spine table by this ticket:

```sh
cargo test --manifest-path tools/reference/Cargo.toml
./gradlew check
./gradlew iosSimulatorArm64Test
python3 tools/spec/check_spec.py
npx --yes markdownlint-cli2@0.24.1 "**/*.md"
typos
lychee --offline .
```

## Docs to update

- [Vectors README](../../../docs/spec/vectors/README.md): move the three sets from "Tier 2, pending" to a published tier 2 table, with how they are generated and checked.
- [Dependency register](../../../docs/reference/dependencies.md): confirm the Rust rows match `Cargo.lock`.
- `PROJECT.md`: add the Rust command to the verified profile.

## Devices needed

None. Host only.
