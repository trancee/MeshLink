# Build libsodium from verified source for the host, Android and iOS

Status: done
Type: AFK
Blocked by: 05

## Behavior

The build produces static libsodium 1.0.22 archives from a pinned, verified upstream tarball for every target MeshLink ships or tests on: the three Android ABIs, the iOS device and simulator slices, and the macOS arm64 and Linux x86_64 hosts that run JVM tests. A tampered tarball fails the build. No Kotlin code uses the archives yet; ticket 07 adds the shim.

## Spec and ADR references

- [ADR 0004](../../../docs/adr/0004-crypto-provider-libsodium.md) decision (libsodium 1.0.22 built from pinned, verified source; static; symbols hidden behind our shim) and its amendments (export allow-list, at most 1 MB of native code per ABI, host build for tests).
- [ADR 0005](../../../docs/adr/0005-toolchain-and-dependency-baseline.md) "libsodium build" (unmodified upstream `dist-build` scripts, `-Os`, `--enable-minimal`, SHA-256 and minisign checks in CI, Apple build of about 16 min cached by tarball SHA-256, `-march=armv8-a+crypto` kept), "Targets" (arm64-v8a, armeabi-v7a, x86_64; `iosArm64`, `iosSimulatorArm64`) and NDK 30.0.16248370.
- [ADR 0017](../../../docs/adr/0017-testing-architecture.md) decision 4 (real crypto on the host) and its amendment (host libsodium is a test-only cached artifact).
- Constitution S1, E3, E5, E7.

## Acceptance criteria

- [ ] One build script (Gradle task in `build-logic/` or a script it calls) downloads the libsodium 1.0.22 release tarball, checks its SHA-256 against a committed value and its minisign signature against the committed upstream public key, and stops before extracting on any mismatch.
- [ ] A test proves the failure path: a tarball with one flipped byte, or a wrong signature, makes the task fail with a message naming the check.
- [ ] The upstream `dist-build` scripts run unmodified with `--enable-minimal` and `-Os`. Any extra flag is passed through the environment, and the PR lists every flag.
- [ ] Outputs: static archives and headers for arm64-v8a, armeabi-v7a and x86_64 built with NDK 30.0.16248370; an Apple XCFramework (or static archives) for `ios-arm64` and `ios-arm64-simulator`; a host archive for macOS arm64 and one for Linux x86_64, marked test-only.
- [ ] Every output is static; the PR shows `file` or `lipo -info` output for each.
- [ ] The PR records the stripped size of each static archive per Android ABI and per iOS slice, and the size each adds to a minimal linked binary. A size above ADR 0004's limit of about 1 MB per Android ABI fails the ticket; the numbers also answer ADR 0011's open question on unsecured-only apps and are recorded in an ADR 0011 amendment.
- [ ] Every CI job that builds Android native code installs NDK 30.0.16248370 explicitly with `sdkmanager` and fails if it is missing (ADR 0005 "CI"; moved here from ticket 05, whose jobs build no native code). As of 2026-11-14 `ubuntu-24.04` preinstalls this NDK, but not as the default, and `macos-26` doesn't have it; the explicit install keeps the job independent of image updates.
- [ ] CI caches each output keyed by the tarball SHA-256, the build flags and the toolchain version. A warm run skips the Apple build; a cold run stays inside the CI time limits of ticket 05.
- [ ] The version, SHA-256 and public key appear once, in the version catalog or one committed file, never duplicated.
- [ ] `docs/reference/dependencies.md` has the libsodium row (runtime, ISC, pinned version and checksum) and a test-only row for the host build.
- [ ] `./gradlew check` stays green with Kover at 100%.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
npx --yes markdownlint-cli2@0.24.1
typos
lychee --offline .
```

## Docs to update

- `docs/reference/dependencies.md`: libsodium runtime row and host test-only row.
- `PROJECT.md`: prerequisites (NDK 30, Xcode, minisign if the build needs it locally), the native build command, and its proof-matrix row.
- `docs/agents/lessons.md`: only if the build hits a new root cause.

## Devices needed

None.
