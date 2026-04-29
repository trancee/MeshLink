# Contributing to MeshLink

## Development Setup

### Prerequisites

- JDK 21 (Zulu recommended)
- Gradle 9.4.1+ (wrapper included)
- macOS for iOS/KLib targets (optional — JVM tests run on any OS)

### Clone and verify

```bash
git clone https://github.com/nicegram/MeshLink.git
cd MeshLink
git config core.hooksPath .githooks
./gradlew :meshlink:jvmTest :meshlink:koverVerify :meshlink:apiCheck :meshlink:detekt :meshlink:ktfmtCheck
```

All gates must pass before you begin.

## Code Style

- **Formatter:** ktfmt (Google style, 100-char line width)
- **Static analysis:** Detekt with the project `detekt.yml` config
- **Auto-fix:** `./gradlew :meshlink:ktfmtFormat`

The pre-commit hook runs `ktfmtCheck` and `detekt` automatically.

## Naming Conventions

- Use `Millis` suffix for all duration fields/parameters: `timeoutMillis`, `intervalMillis`
- Never use `Ms` suffix (see `KNOWLEDGE.md` K001)
- Package names: `ch.trancee.meshlink.<subsystem>`
- Internal classes: `internal` visibility unless part of the public API contract

## Architecture Rules

- **Public API surface** lives in `ch.trancee.meshlink.api` only
- **Internal subsystems** are wired via constructor injection in `MeshEngine.create()`
- **No service locator, no DI framework, no global mutable state**
- **Sealed interface implementations** must be in the same package as the sealed type (not a sub-package)

## Writing Code

### Coverage requirement: 100%

Every line and branch must be covered. This is enforced by `koverVerify` on every commit. No `@CoverageIgnore` annotations are permitted.

Patterns that help:
- Use explicit `if (...) throw` instead of `require()` with string interpolation
- Use `while (true)` instead of `while (isActive)` for coroutine loops
- Override `equals()`/`hashCode()` with `contentEquals()` for any data class with `ByteArray` fields
- Use explicit null checks instead of safe-call chains on non-nullable results

See [Why 100% Coverage](docs/explanation/why-full-coverage.md) for the rationale.

### API compatibility

The public API is tracked by Binary Compatibility Validator (BCV). If you change the public surface:

```bash
./gradlew :meshlink:apiDump
git diff meshlink/api/
```

Review the diff carefully. Removals or signature changes are binary-breaking.

### Tests

- Unit tests in `commonTest` source set
- Integration tests use `MeshTestHarness` (N-node virtual mesh)
- All tests run on JVM via `./gradlew :meshlink:jvmTest`
- Use `runTest` with virtual time — never `Thread.sleep` or wall-clock waits

See [How to Write Integration Tests](docs/how-to/write-integration-test.md).

## Pull Request Workflow

### 1. Branch

```bash
git checkout -b feature/your-change main
```

### 2. Implement

Write code + tests. Run the full verification suite locally:

```bash
./gradlew :meshlink:jvmTest :meshlink:koverVerify :meshlink:apiCheck :meshlink:detekt :meshlink:ktfmtCheck
```

### 3. Commit

Use conventional commit prefixes:

| Prefix | Use for |
|--------|---------|
| `feat:` | New feature or capability |
| `fix:` | Bug fix |
| `refactor:` | Code change that doesn't alter behavior |
| `test:` | Adding or improving tests |
| `docs:` | Documentation only |
| `ci:` | CI/CD changes |
| `perf:` | Performance improvement |
| `chore:` | Build tooling, dependencies |

### 4. Push and open PR

```bash
git push origin feature/your-change
```

CI runs all gates. All must pass before merge.

### 5. Review

- One approval required
- Squash-merge to main
- Delete the branch after merge

## What CI Checks

| Job | What it validates |
|-----|-------------------|
| Lint | ktfmt formatting + Detekt static analysis |
| Test | All 1460+ tests pass |
| Coverage | 100% line + branch (Kover) |
| Benchmark | Performance regression check (warn-only, not blocking) |
| Docs | All relative markdown links resolve |

## Adding a New Feature

1. **Wire message?** → [How to Add a Wire Message Type](docs/how-to/add-wire-message-type.md)
2. **Platform transport?** → [How to Add a Platform Transport](docs/how-to/add-platform-transport.md)
3. **New subsystem?** → Create class, inject into `MeshEngine.create()`, add delegation on `MeshLink`
4. **Public API change?** → Update BCV baseline with `apiDump`, ensure new methods have KDoc

## Gotchas

Read these before you start:

- `MeshEngine.create()` launches timer coroutines immediately — cancel before `advanceUntilIdle()` in tests
- `require()` with string interpolation creates uncoverable Kover branches — use explicit `if/throw`
- ByteArray fields in data classes need manual `equals()`/`hashCode()` with `contentEquals()`
- Sealed interface impls must be in the same package (not a sub-package)
- `testScheduler.runCurrent()` needed after `tryEmit` to flush subscriber continuations
- Integration tests need `batteryPollIntervalMillis = 300_000L` to prevent timer explosion under Kover
- Add `-Xmx1g` JVM args for tests with 3+ nodes under instrumentation

## Documentation

Docs follow the [Diataxis](https://diataxis.fr) framework. When adding a feature:

- **Tutorial** if it's something new users need to learn
- **How-to** if it's a procedure existing users will follow
- **Reference** if it's a new API, config option, or protocol element
- **Explanation** if there's a non-obvious design decision worth recording

See [`docs/README.md`](docs/README.md) for the full index.

## Questions?

Open a GitHub issue with the `question` label.
