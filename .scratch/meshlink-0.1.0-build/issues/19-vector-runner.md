# Run every vector set from one runner in `commonTest`

Status: ready-for-agent
Type: AFK
Blocked by: 18

## Behavior

One JSON vector runner lives in `commonTest` of `:meshlink-core`. It runs on the JVM (through the Android host tests) and on the iOS simulator. It reads:

- **Tier 1:** every `docs/spec/vectors/*.json` file whose code exists by now. Each later ticket adds its own files to the same runner when it lands its codec or derivation (slicing rule 3).
- **Wycheproof**, at a pinned commit, for X25519, Ed25519, ChaCha20-Poly1305, HKDF-SHA-256 and ML-KEM-768.
- **X-Wing** Appendix A.5 vectors, moved here from ticket 18.

The vectors README defines the format: `testGroups`, `tests`, `result` of `valid`, `invalid` or `acceptable`, and `flags`. Tests flagged `Placeholder` check only the framing.

External sets are vendored into the repository so tests never need the network (T2). Each vendored set records its source, commit and SHA-256. A small test-only JSON reader is the default. A JSON library would be a new dependency, which needs the owner's approval and an E3 row.

cacophony runs from ticket 22, and the hpke-pq vectors from ticket 45, through this same runner.

## Spec and ADR references

- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decisions 4 (real crypto on the host), 10 (one vector runner and its sets) and 12 (CI tiers).
- [ADR 0002, Decision](../../../docs/adr/0002-noise-suites-and-handshake-patterns.md#decision) (Wycheproof primitives) and [ADR 0004, Decision](../../../docs/adr/0004-crypto-provider-libsodium.md#decision) (vector gates).
- [Vectors README](../../../docs/spec/vectors/README.md), [Tier 1, published](../../../docs/spec/vectors/README.md#tier-1-published).
- [ADR 0005](../../../docs/adr/0005-toolchain-and-dependency-baseline.md) dependency register rules.

## Acceptance criteria

- [ ] One runner class in `commonTest` loads each JSON file, dispatches each test group to a handler, and fails with the file, group and test ID on a mismatch.
- [ ] A test with no handler fails the run, so a file cannot be skipped silently. A file is listed as pending only by an explicit list that names the owning ticket.
- [ ] Every tier 1 file whose code exists passes, with its `valid`, `invalid` and `acceptable` counts reported.
- [ ] Wycheproof X25519, Ed25519, ChaCha20-Poly1305, HKDF-SHA-256 and ML-KEM-768 files pass at the pinned commit. If the pinned commit has no ML-KEM-768 file, the ticket records that and reports it to the owner.
- [ ] The X-Wing A.5 vectors pass through the runner.
- [ ] The runner runs in both `./gradlew check` and `./gradlew iosSimulatorArm64Test`, with no network access.
- [ ] T10: a test that corrupts one expected byte in a copy of each set proves the runner fails.
- [ ] Each vendored set has a register row with source URL, commit, SHA-256 and license. `typos` and `lychee` either pass on the vendored files or exclude them with a stated reason.
- [ ] `./gradlew check` passes with Kover at 100%.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
npx --yes markdownlint-cli2@0.24.1 "**/*.md"
typos
lychee --offline .
python3 tools/spec/check_spec.py
```

## Docs to update

- [Dependency register](../../../docs/reference/dependencies.md): one row per vendored vector set, with its pinned commit and checksum.
- [Vectors README](../../../docs/spec/vectors/README.md): name the runner and how a ticket adds a set.

## Devices needed

None. Host and simulator only.
