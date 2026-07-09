# The `:meshlink:detekt` CI gate was a no-op

## Status

Fixed on branch `chore/wire-detekt-ci-gate`. This document explains a gap
found while reviewing `perf/android-ble-hotpath-fixes` (#81): CI's detekt
step never actually analyzed any code, the real scope of pre-existing detekt
findings once it does, and the fix applied.

## The gap

`.github/workflows/ci.yml`'s Linux job ran `:meshlink:detekt`. For a
single-source-set JVM project, that's the detekt Gradle plugin's real,
umbrella analysis task. For this Kotlin Multiplatform module, though, the
plugin instead registers a **separate, type-resolution-enabled task per
Kotlin target and source set** (`detektAndroidMain`, `detektJvmMain`,
`detektMetadataCommonMain`, `detektIosArm64Main`, ...) -- a known limitation
of the detekt-gradle-plugin with KMP projects. The top-level `detekt` task is
never wired to depend on any of them:

```
$ ./gradlew :meshlink:detekt --rerun
> Task :meshlink:detekt NO-SOURCE
Skipping task ':meshlink:detekt' as it has no source files and no previous output files.
```

It has no source files configured at all, so it always succeeds trivially.
**CI's detekt gate has never run a real analysis on this module.**

This was found while running the `code-review` skill's Standards axis
against `constitution.md` for #81 -- `constitution.md` Principle I requires
"All production code MUST pass Detekt with zero suppressed issues," and
spot-checking the real per-variant tasks locally (`detektAndroidMain`,
`detektAndroidHostTest`) immediately surfaced hundreds of findings that had
clearly never been addressed, which is what prompted digging into why CI
had stayed green.

## Real scope, once the real tasks run

| Task | Weighted issues (before this fix) |
|---|---|
| `detektAndroidMain` | 508 |
| `detektAndroidHostTest` | 244 |
| `detektAndroidDeviceTest` | ~10 |
| `detektMetadataCommonMain` | 178 |
| `detektMetadataMain` | 178 (duplicate coverage of the same commonMain-rooted source as `detektMetadataCommonMain`, see below) |
| `detektMetadataIosMain` | 16 |
| `detektJvmMain` | 24 |
| `detektJvmTest` | 8 |

Total real, pre-existing findings: ~1,166 across those eight tasks (the
`detektMetadataCommonMain`/`detektMetadataMain` overlap means the true
distinct-issue count is somewhat lower than the raw sum). Dominated by
`MagicNumber` (~750+, mostly wire-format/crypto/BLE protocol constants and
test literals -- low novel-bug value for this domain, but still a documented
standard), with smaller but real counts of `ReturnCount`, `LongMethod`,
`TooManyFunctions`, `MaxLineLength`, and `UnreachableCode`.

`detektIosArm64Main/Test`, `detektIosSimulatorArm64Main/Test`,
`detektMetadataAppleMain`, and `detektMetadataNativeMain` are `NO-SOURCE` on
the Linux CI runner and local Linux dev machines (no Xcode/Kotlin-Native
Apple toolchain available) -- they were not evaluated as part of this fix
and carry no baseline file. See "What happens on macOS" below.

### The `UnreachableCode` findings are false positives, not real dead code

Before baselining anything, the 28 `UnreachableCode` findings (spread across
`detektAndroidMain`, `detektAndroidHostTest`, `detektJvmMain`, and
`detektJvmTest`) were inspected individually, since that rule can indicate a
genuine logic bug rather than a style nit. Every instance found has the same
shape:

```kotlin
internal actual fun wireCompatibilityResourceTextOrNull(fileName: String): String? {
    val classLoader = WireCompatibilitySupportLoaderAnchor::class.java.classLoader ?: return null
    return classLoader.getResourceAsStream(fileName)?.bufferedReader()?.use { reader ->
        reader.readText()
    }
}
```

and

```kotlin
private fun BenchmarkTrustRecord.toInternal(): TrustRecord {
    val resolvedFingerprintBytes =
        identityFingerprintBytes
            ?: identityFingerprint?.toBytes()
            ?: error("identity fingerprint is required")
    return TrustRecord(/* ... */)
}
```

Detekt 1.23.8's `UnreachableCode` rule flags the lines of the multi-line
`return ...?.use { ... }` expression (or the line following a `?: error(...)`
elvis fallback) as unreachable, even though these are ordinary, correct
Kotlin control flow -- a null-checked early return followed by the function's
only remaining statement, and an elvis chain ending in a `Nothing`-returning
fallback. This is a rule false-positive on this specific
early-return-then-multi-line-trailing-lambda-return shape, not a real defect.
All 28 instances were confirmed to have this identical shape. They are
included in the baseline files like every other pre-existing finding, but
recorded here explicitly so a future contributor doesn't spend time
re-investigating them as real bugs, and so nobody "fixes" them by actually
restructuring correct code to appease a rule limitation.

## Fix applied

**1. Per-task baseline files, keyed by task name.** The detekt Gradle
plugin's default baseline-file naming keys only off the Kotlin *source set*
name (e.g. both `detektAndroidMain` and `detektJvmMain` default to
`detekt-baseline-main.xml`), so two targets sharing a source-set name would
silently clobber each other's baseline. `meshlink/build.gradle.kts` now
derives a unique file per task from the task name itself
(`config/detekt/baseline-androidMain.xml`, `baseline-jvmMain.xml`, etc.) for
every `Detekt` and `DetektCreateBaselineTask` task.

**2. Baseline files generated for every task with real source on Linux.**
`config/detekt/baseline-*.xml` now exist for `androidMain`,
`androidHostTest`, `androidDeviceTest`, `jvmMain`, `jvmTest`,
`metadataCommonMain`, `metadataMain`, and `metadataIosMain`, capturing every
finding enumerated above. The baseline assignment is conditional on the file
already existing (checked at configuration time) rather than unconditional,
so a target that is `NO-SOURCE` on the machine currently running Gradle
(the iOS/Apple/Native targets on Linux) doesn't fail Gradle's file-existence
input validation for a baseline it doesn't have and doesn't need yet.

**3. `detektAll` aggregate task**, registered in `meshlink/build.gradle.kts`,
depending on every task of type `Detekt` -- this is the real, CI-facing
target now. `.github/workflows/ci.yml`'s Linux job invokes
`:meshlink:detektAll` in place of the no-op `:meshlink:detekt`.

Verified the new wiring actually gates real changes by temporarily adding an
unused, constant-returning private function to a file with an existing
baseline and confirming `detektAndroidMain` correctly failed on the two new
findings (`FunctionOnlyReturningConstant`, `UnusedPrivateMember`) while every
pre-existing baselined finding in that same file stayed silent -- then
reverted the canary change.

## What happens on macOS

CI's macOS job (`meshlink-sdk-macos`) only runs
`:meshlink:iosSimulatorArm64Test`, not `detektAll`, so the gap in iOS/Apple/
Native baseline coverage does not currently affect any CI job. A local
developer running `:meshlink:detektAll` on a Mac, however, would cause the
iOS/Apple/Native `Detekt` tasks to actually have source and run for the
first time (unlike on Linux, where they stay `NO-SOURCE`). Since those
targets have no baseline file yet, they would run with zero pre-existing
issues suppressed -- the honest default (finding real issues rather than
silently passing) rather than a hard failure of the whole `detektAll`
aggregate from a missing file. If and when iOS-side detekt analysis needs to
be added to CI (e.g. a future `meshlink-sdk-macos` step running
`:meshlink:detektAll`), baselines for those four targets should be generated
from a Mac first, following the same process used here.

## Why this wasn't a full pre-existing-issue cleanup

`constitution.md` Principle I's "zero suppressed issues" is now honestly in
tension with baselining ~1,166 pre-existing findings: a baseline is itself a
form of suppression. Fixing all of them in the same change as wiring CI to
find them was judged out of scope -- it would be a large, higher-risk diff
touching dozens of files unrelated to any specific feature or bug fix (e.g.
`Ed25519Fallback.kt`'s `MagicNumber` findings), better done as its own
dedicated, reviewable effort (or several, split by module/ruleset) now that
CI will actually flag any *regression* against this baseline going forward.
This mirrors the project's own prior history of doing exactly this kind of
cleanup deliberately (see `git log --oneline -- meshlink/build.gradle.kts`
for the "finish detekt cleanup" / "finalize engine detekt cleanup" commits
that got specific modules to zero previously, before drifting again).

## Testing

- `./gradlew :meshlink:detektAll` -- passes with baselines applied.
- Canary verification (see "Fix applied" above) that a genuinely new
  violation still fails the gate.
- `./gradlew :meshlink:jvmTest :meshlink:testAndroidHostTest :meshlink:apiCheck :meshlink:koverVerify` --
  unaffected, still green.
- `yamllint .github/workflows/ci.yml` -- clean.

## Related documentation

- [ble-android-performance-pass.md](ble-android-performance-pass.md) -- the
  review this gap was found during.
