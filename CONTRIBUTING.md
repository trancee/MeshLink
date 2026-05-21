# How to build and contribute to MeshLink

This guide shows you how to set up a contributor workstation, build the
MeshLink library, run the expected checks, and prepare a change that is ready
for review.

Use it when you need to:

- get a fresh checkout building locally
- make a change to the `:meshlink` library safely
- choose the right verification for a change
- open a PR that matches the repository rules

If you are integrating MeshLink into a host app instead of changing MeshLink
itself, start with the [documentation map](docs/README.md).

## 1. Start with the right workstation

For the full contributor workflow, use a macOS machine with:

| Need | Tooling |
|---|---|
| Gradle build and tests | JDK 17 |
| Android compilation and unit tests | Android SDK for API 35 builds |
| iOS compilation and tests | Xcode |
| Docs verification and API appendix generation | Python 3 |
| iOS project regeneration after project-spec changes | `xcodegen` only when needed |

You can still do a partial JVM or Android-only loop on another operating
system, but final validation for a library change still needs the iOS targets
on macOS.

## 2. Warm the repository

After cloning your fork and opening the repository root, run:

```bash
./gradlew help
```

That confirms the Gradle wrapper works and downloads the toolchain and project
plugins.

## 3. Build the library

To build the MeshLink library and run its default project tests, use:

```bash
./gradlew :meshlink:build
```

If you only want a quick compilation pass while you are still shaping the
change, use:

```bash
./gradlew :meshlink:assemble
```

## 4. Use the fast local loop while you edit

Run the narrowest command that still tests the code you changed.

### Format first

```bash
./gradlew :meshlink:ktfmtFormat
```

### Run focused automated tests

```bash
./gradlew :meshlink:jvmTest
./gradlew :meshlink:testDebugUnitTest
./gradlew :meshlink:iosSimulatorArm64Test   # Apple Silicon Macs
./gradlew :meshlink:iosX64Test              # Intel Macs
```

When a change crosses `commonMain`, the public API, or Android/iOS parity,
prefer the full library test bundle:

```bash
./gradlew :meshlink:allTests
```

## 5. Run the pre-review verification bundle

Before you ask for review on a library change, run:

```bash
./gradlew \
  :meshlink:allTests \
  :meshlink:detekt \
  :meshlink:apiCheck \
  :meshlink:koverVerify \
  verifyDocs
```

Use these add-ons when the change needs them:

| If your change touches... | Also run... |
|---|---|
| docs only | `./gradlew verifyDocs` |
| crypto, routing, wire codec, transfer, or other performance-sensitive paths | `./gradlew :benchmarks:jvmSmokeBenchmark` during development and `./gradlew :benchmarks:jvmBenchmark` when you need retained evidence |
| Android or iOS transport, discovery, power, or physical proof behavior | the relevant proof-app or reference-app validation flow in the linked guides at the end of this document |
| public API shape | the API update steps in [section 8](#8-if-you-change-the-public-surface) |

## 6. Add the right tests for the change

Use this table to choose the right test surface.

| If you changed... | Add or update... |
|---|---|
| shared protocol logic, routing, or crypto behavior | `commonTest` coverage first |
| multi-node behavior | the virtual mesh integration harness, not ad-hoc real-device tests |
| Android glue | `androidUnitTest`, plus device checks only when platform behavior matters |
| iOS glue | `iosTest`, plus physical proof only when platform behavior matters |
| public API behavior | parity-focused tests that keep Android and iOS behavior aligned |
| benchmark-covered operations | the JVM benchmark suite and retained evidence when required |

Keep these repository rules in mind while you test:

- line and branch coverage must stay at 100%
- Power-assert is the default assertion style for diagnostic value
- multi-node integration work should use the canonical virtual harness instead of real BLE hardware
- physical proof runs are retained evidence, not a substitute for automated tests

For the rationale behind the coverage bar, use [Why MeshLink requires full coverage](docs/explanation/why-full-coverage.md).

## 7. Follow the repository rules while you code

These are the rules contributors trip over most often:

- run ktfmt; do not hand-format Kotlin
- keep production Detekt clean; if a test suppression is unavoidable, justify it inline
- keep shared logic in `commonMain`; use platform source sets for `actual` implementations and platform glue only
- keep public declarations explicit with visibility modifiers and return types
- do not merge `TODO` comments; track unfinished work outside the code
- pin dependency versions exactly
- keep `when` expressions exhaustive over closed sets
- keep Android and iOS public behavior aligned
- update Android and iOS documentation together when a public workflow or API changes
- keep user-facing docs in a single Diátaxis type per page instead of mixing tutorial, how-to, reference, and explanation content

A few code patterns are especially important in this repository:

- prefer explicit condition checks and throws over `require(...) { ... }`
- avoid `while (isActive)` coroutine loops
- prefer explicit null checks when a safe-call chain would create dead coverage branches
- use the project crypto provider abstraction rather than introducing a shipped external crypto library
- do not add a new runtime dependency to `:meshlink` without first changing the governing policy

For the full rule set and hard boundaries, use the [MeshLink Constitution](constitution.md).

## 8. If you change the public surface

When a change intentionally affects the public API:

1. Update the KDoc in the same change set.
2. Refresh the checked-in API dump:

   ```bash
   ./gradlew :meshlink:apiDump
   ```

3. Regenerate the human-readable API appendix:

   ```bash
   python3 scripts/generate_public_api_reference.py
   ```

4. Review the API dump diff and record the version-bump rationale in the PR.
5. Update the matching Android and iOS docs for the changed public workflow or API.
6. Re-run `./gradlew verifyDocs` so the generated appendix and markdown links are checked again.

## 9. Commit and open the PR

Before you push:

- work from a feature branch, not `main`
- use Conventional Commit messages
- include the verification commands you ran in the PR description or handoff
- include benchmark or proof evidence when the change needs retained performance or physical validation

Typical commit message shapes:

- `fix: harden route expiry handling`
- `test: cover ios battery diagnostic parity`
- `docs: add contributor guide`

## 10. Use the right supporting guide when the change goes wider

This guide covers contributing to the library.

Use these guides when your change reaches other surfaces:

- [MeshLink documentation map](docs/README.md)
- [Benchmark and validation baselines](benchmarks/README.md)
- [How to use the MeshLink reference app](meshlink-reference/README.md)
- [How to run the Android proof app](meshlink-proof/android/README.md)
- [How to build and run the iOS proof app](meshlink-proof/ios/README.md)
