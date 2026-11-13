# Fuzz every decoder on a schedule with Jazzer

Status: ready-for-agent
Type: AFK
Blocked by: 28

## Behavior

A scheduled, non-required CI job runs coverage-guided Jazzer 0.30.0 against every decoder that exists when this ticket lands. The integration is fixed by ADR 0017 decision 9:

- the standalone `jazzer` driver, with one `fuzzerTestOneInput(ByteArray)` target per decoder;
- launched by a Gradle `JavaExec` task on the Android host-test runtime classpath;
- no JUnit 5 and no `jazzer-junit`.

Decoders expected by now: the advertisement payload, the capability record, link records, the link-local frames (`PLAIN_HELLO`, `HANDSHAKE`, `FRAGMENT`, `BUSY`, `CLOSE` and any other frame already sent), the handshake payload TLVs, the identity certificate, and the direct-message frames of ticket 12. The ticket lists the decoders it finds and covers each one.

Each target asserts that the decoder returns a value or a typed error, and never throws anything else, hangs or allocates without bound. The minimized corpus is committed, seeded from the vector files. Every crash becomes a regression input in the default suite's deterministic mutation tests.

Later decoder tickets add their own target to this job when they land their decoder.

## Spec and ADR references

- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decisions 9, 12 and 13.
- [ADR 0012](../../../docs/adr/0012-wire-format-and-codec.md) (codec rules and typed decode errors).
- [ADR 0005](../../../docs/adr/0005-toolchain-and-dependency-baseline.md) (CI and the scheduled job list).
- [Dependency register](../../../docs/reference/dependencies.md), Jazzer row.
- [Spec README](../../../docs/spec/README.md) and [Vectors README](../../../docs/spec/vectors/README.md) (seed inputs).

## Acceptance criteria

- [ ] `com.code-intelligence:jazzer` 0.30.0 and `jazzer-api` are in `gradle/libs.versions.toml`, used only by the fuzz task, and match the register row.
- [ ] One Gradle `JavaExec` task, `:meshlink-core:fuzz`, runs every target for a bounded time on the Android host-test runtime classpath of `:meshlink-core`, where the decoders live. It is not part of `./gradlew check`. If the decoders turn out to live in another module, the task moves there and this ticket updates the spine and every citing ticket in the same PR (spine rename rule).
- [ ] One target exists per decoder listed in the PR, and a test fails if a decoder in `commonMain` has no target.
- [ ] The committed corpus is seeded from the matching vector files and minimized.
- [ ] A scheduled workflow runs the task on Linux. Actions are the latest stable release, pinned by commit SHA with a version comment. It is not a required check.
- [ ] A crash fails the job and uploads the crashing input. A short local run of every target finds no crash, or each crash found is fixed with a regression input in the default suite.
- [ ] If the standalone driver cannot be wired as decided, the choice returns to the owner (ADR 0017 decision 9) rather than switching tools.
- [ ] `./gradlew check` passes with Kover at 100%; fuzz targets sit outside the Kover-measured sources.

## Proof commands

`./gradlew :meshlink-core:fuzz` is new; this ticket creates it and adds it to the spine's proof-command table.

```sh
./gradlew :meshlink-core:fuzz
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- [Dependency register](../../../docs/reference/dependencies.md): confirm the Jazzer row and add `jazzer-api` if it needs its own row.
- `PROJECT.md`: add `./gradlew :meshlink-core:fuzz` to the verified profile.
- [Spine](../spec.md#proof-commands): the `./gradlew :meshlink-core:fuzz` row, created by 34.

## Devices needed

None. Host only.
