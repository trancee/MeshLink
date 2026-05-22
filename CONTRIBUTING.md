# How to contribute to MeshLink

This guide takes you from a fresh checkout to a review-ready MeshLink change.

**Audience:** engineers changing this repository, especially the `:meshlink`
library.

**After reading this guide, you should be able to:** prepare a compatible
workstation, build the library, run the expected verification bundle, and know
which supporting documents to use when you need more detail.

If you are integrating MeshLink into a host app rather than changing MeshLink
itself, start with the [documentation map](docs/README.md).

## 1. Prepare your workstation

For the full contributor workflow, use a macOS machine with:

- JDK 17
- Android SDK for API 36 builds
- Xcode
- Python 3

Install `xcodegen` only if you need to regenerate the committed iOS project
after project-spec changes.

For the exact workstation matrix, task list, and repository rules, use the
[Contributor build, test, and verification reference](docs/reference/contributor-reference.md).

## 2. Warm the checkout

From the repository root, run:

```bash
./gradlew help
```

This confirms the Gradle wrapper works and downloads the required plugins and
toolchains.

Then install the repository Git hook suite:

```bash
./scripts/install-git-hooks.sh
```

The suite includes:

- `pre-commit` — formats touched Kotlin modules, runs `yamllint` for staged
  YAML files, and runs the relevant Gradle verification for the staged paths
- `commit-msg` — enforces Conventional Commits
- `pre-push` — scans outgoing commit subjects for Conventional Commit
  compliance, blocks direct pushes to `main`, and runs the heavier
  verification bundle for the affected paths, including benchmark smoke runs
  for benchmark-sensitive MeshLink changes

If formatting changes a staged file, the commit stops so you can review and
re-stage the result.

## 3. Build the library

For a normal first build, run:

```bash
./gradlew :meshlink:build
```

If you only want a compile pass while shaping the change, run:

```bash
./gradlew :meshlink:assemble
```

## 4. Use the local edit loop

Start with formatting:

```bash
./gradlew :meshlink:ktfmtFormat
```

Then run the narrowest tests that still cover your change:

```bash
./gradlew :meshlink:jvmTest
./gradlew :meshlink:testDebugUnitTest
./gradlew :meshlink:iosSimulatorArm64Test
```

When a change crosses shared logic, public API behavior, or Android/iOS parity,
run the full library test bundle:

```bash
./gradlew :meshlink:allTests
```

For `meshlink-reference` changes, prefer the faster reference-app bundle:

```bash
./scripts/run-reference-local-check.sh
```

That wrapper runs `:meshlink-reference:localCheck` directly on the current AGP 9
toolchain.

If you touch Gradle, Android plugin wiring, or post-migration module shape, run
the AGP 9 build-surface bundle:

```bash
./scripts/run-agp9-verification.sh
```

For a narrower loop, you can still run just the invariant guard:

```bash
./gradlew checkAgp9Invariants
```

Use `:meshlink-reference:build` only when you need release APK or iOS framework
artifacts, because it also links release frameworks for all iOS targets.

For the full test-surface matrix, use the
[Contributor build, test, and verification reference](docs/reference/contributor-reference.md).

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

For a `meshlink-reference` review bundle, run:

```bash
./scripts/run-reference-local-check.sh
```

If your change touches performance-sensitive code or physical transport
behavior, add the benchmark or proof validation described in the contributor
reference and benchmark guides.

If your change touches Gradle, Android plugin wiring, or module layout, include
`./scripts/run-agp9-verification.sh` in the verification bundle as well.

## 6. If the public surface changes

A public API change requires all of the following in the same change set:

- updated KDoc
- refreshed checked-in API dump
- regenerated human-readable API appendix
- matching Android and iOS documentation updates
- `verifyDocs` rerun after the doc and appendix updates
- PR rationale for the API diff and version impact

The exact commands and required artifacts are listed in the
[Contributor build, test, and verification reference](docs/reference/contributor-reference.md).

## 7. Open the PR

Before you push:

- work from a feature branch, not `main`
- use Conventional Commit messages
- include the verification commands you ran in the PR description or handoff
- include benchmark or proof evidence when the change needs retained
  performance or physical validation

## 8. Use the right supporting docs

Use this guide for the contributor workflow.

Use these documents when you need more detail:

- [Contributor build, test, and verification reference](docs/reference/contributor-reference.md)
- [MeshLink Constitution](constitution.md)
- [MeshLink documentation map](docs/README.md)
- [Benchmark and validation baselines](benchmarks/README.md)
- [How to use the MeshLink reference app](meshlink-reference/README.md)
- [How to run the Android proof app](meshlink-proof/android/README.md)
- [How to build and run the iOS proof app](meshlink-proof/ios/README.md)
