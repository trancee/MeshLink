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

## Spec and ADR references

- [ADR 0002, Decision](../../../docs/adr/0002-noise-suites-and-handshake-patterns.md#decision) (two-implementation vectors) and [ADR 0004, Decision](../../../docs/adr/0004-crypto-provider-libsodium.md#decision) (Rust test-only).
- [ADR 0005](../../../docs/adr/0005-toolchain-and-dependency-baseline.md) "Test-only Rust reference" and "CI" (pins, scheduled job).
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decisions 10, 12 and 13.
- [Vectors README, Tier 2, pending](../../../docs/spec/vectors/README.md#tier-2-pending); [identity.md, Identity certificate](../../../docs/spec/identity.md#identity-certificate); [link.md, Patterns](../../../docs/spec/link.md#patterns).
- [D-11](../../../docs/spec/decisions.md#d-11-certificate-version-and-signature-input).

## Acceptance criteria

- [ ] `tools/reference/` has `rust-toolchain.toml` with Rust 1.100.0, a committed `Cargo.lock`, and exactly the ADR 0005 crate pins: rust-hpke 0.15.1, `ml-kem` 0.3.4, `x25519-dalek` and `ed25519-dalek` 3.0.0, `chacha20poly1305` 0.11.0, `sha2` 0.11.1, `hkdf` 0.13.0, snow at `dc18c06c28fbdeaa6ffae7347e650c805e9b932e`.
- [ ] No Gradle module, artifact or app depends on `tools/reference/`.
- [ ] The crate generates the three tier 2 sets above. Each file has a schema, is listed in the vectors README tier table, and passes `python3 tools/spec/check_spec.py`.
- [ ] The crate's own tests check its X-Wing against draft-ietf-hpke-pq-05 Appendix A.5 and its HPKE against the hpke-pq vectors.
- [ ] T10 cases are present as `invalid` tests: a flipped certificate signature bit, a wrong NodeId, expiry, a window over 86,400 s, a tampered `hfs` message, and an X-Wing seal with a flipped ciphertext bit.
- [ ] The scheduled workflow regenerates the files and diffs them. Actions are the latest stable release, pinned by commit SHA with a version comment. It is not a required check.
- [ ] `cargo test --manifest-path tools/reference/Cargo.toml` passes locally.
- [ ] `./gradlew check` passes with Kover at 100%, and `./gradlew iosSimulatorArm64Test` passes.

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
