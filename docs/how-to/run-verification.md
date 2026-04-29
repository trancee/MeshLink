# How to Run the Full Verification Suite

## When to use this

Before committing, before a PR, or when you want confidence everything works.

## The one-liner

```bash
./gradlew :meshlink:jvmTest :meshlink:koverVerify :meshlink:apiCheck :meshlink:detekt :meshlink:ktfmtCheck
```

Expected: `BUILD SUCCESSFUL` with 1460+ tests, 100% coverage, no API diff, no lint issues.

## What each gate does

| Gate | Command | What it catches |
|------|---------|-----------------|
| Tests | `:meshlink:jvmTest` | Logic errors, regressions, protocol violations |
| Coverage | `:meshlink:koverVerify` | Untested code paths (100% line + branch required) |
| API compat | `:meshlink:apiCheck` | Accidental public API changes |
| Static analysis | `:meshlink:detekt` | Code smells, complexity, style violations |
| Formatting | `:meshlink:ktfmtCheck` | Inconsistent formatting |

## Running individual gates

```bash
# Just tests
./gradlew :meshlink:jvmTest

# Just coverage (requires tests to have run)
./gradlew :meshlink:koverVerify

# Just API check
./gradlew :meshlink:apiCheck

# Format + fix (auto-fix mode)
./gradlew :meshlink:ktfmtFormat

# Benchmarks (not part of verification — optional)
./gradlew :meshlink:benchmark
```

## Running a single test class

```bash
./gradlew :meshlink:jvmTest --tests "ch.trancee.meshlink.crypto.noise.NoiseXXHandshakeTest"
```

## Running integration tests only

```bash
./gradlew :meshlink:jvmTest --tests "*.integration.*"
```

## Common failures and fixes

### koverVerify fails with < 100%

A code path is untested. Check the HTML report:

```bash
./gradlew :meshlink:koverHtmlReport
open meshlink/build/reports/kover/html/index.html
```

Navigate to the uncovered file and write tests for the missing branches.

**Common causes:**
- `require()` with string interpolation → replace with explicit `if (...) throw`
- `while(isActive)` → replace with `while(true)`
- Safe-call chain on non-nullable result → split into explicit null check
- New data class with ByteArray → override equals/hashCode and test each field difference

### apiCheck fails

You changed the public API surface. If intentional:

```bash
./gradlew :meshlink:apiDump
git diff meshlink/api/
# Review the diff, then commit the new baseline
```

If unintentional: add `internal` visibility to the accidentally-public declaration.

### JVM OOM during integration tests

Add to `meshlink/build.gradle.kts` test config:

```kotlin
jvmArgs("-Xmx1g")
```

And ensure integration test configs use `batteryPollIntervalMillis = 300_000L` to prevent timer explosion under Kover instrumentation.

## Pre-commit hook

The `.githooks/pre-commit` script runs `./gradlew ktfmtCheck detekt` automatically. Install it:

```bash
git config core.hooksPath .githooks
```

## CI runs the same gates

`ci.yml` runs the same verification in parallel:
- Job 1: lint (ktfmt + detekt)
- Job 2: test (allTests)
- Job 3: coverage (koverVerify) — depends on Job 2
- Job 4: benchmark (independent, continue-on-error)
