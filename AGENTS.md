# Agent Instructions

## Code Coverage

Every change to production code (`src/commonMain/`, `src/androidMain/`, `src/iosMain/`) **must** maintain 100% line and branch coverage.

Before committing, run:

```sh
./gradlew :meshlink:koverVerify
```

If coverage drops below 100%, fix it before committing. Common causes:
- New code paths missing tests (add tests in `src/commonTest/` or `src/androidUnitTest/`)
- Dead code left after refactoring (remove it)
- `require()` with string interpolation creating uncoverable bytecode branches (use explicit `if (...) throw IllegalArgumentException(...)` instead)

## KMP Conventions

### Targets

```kotlin
kotlin {
    androidTarget()
    iosArm64()
    iosSimulatorArm64()

    // JVM is test/build infrastructure only — not a shipping target.
    // commonTest runs on JVM; Kover and benchmarks use JVM.
    jvm()
}
```

Two shipping targets (Android, iOS). JVM exists solely for fast test execution, coverage, and benchmarks.

### Source Set Hierarchy

```
commonMain/          Shared protocol logic (~85% of code)
├── androidMain/     Android BLE, storage, foreground service
├── iosMain/         iOS BLE (CoreBluetooth), Keychain, state preservation
│   ├── iosArm64Main/      (usually empty — iosMain covers both)
│   └── iosSimulatorArm64Main/  (usually empty)
└── jvmMain/         (empty or minimal — JVM is test-only)

commonTest/          Shared tests (run on JVM target)
├── androidUnitTest/ Android-specific unit tests
└── iosTest/         iOS-specific tests (run on simulator)
```

### `expect`/`actual` Declarations

Platform abstraction uses Kotlin's `expect`/`actual` mechanism. Three interfaces cross the platform boundary:
- `BleTransport` — BLE hardware (advertising, scanning, GATT, L2CAP)
- `CryptoProvider` — libsodium bindings (Ed25519, X25519, ChaCha20-Poly1305)
- `SecureStorage` — key-value encrypted storage (Android Keystore, iOS Keychain)

Pattern:
```kotlin
// commonMain
expect fun createCryptoProvider(): CryptoProvider

// androidMain
actual fun createCryptoProvider(): CryptoProvider = AndroidCryptoProvider()

// iosMain
actual fun createCryptoProvider(): CryptoProvider = IosCryptoProvider()
```

### Naming Conventions

| Convention | Example | Avoid |
|-----------|---------|-------|
| Platform prefix for `actual` classes | `AndroidBleTransport`, `IosBleTransport` | `BleTransportAndroid`, `IOSBleTransport` |
| `Ios` prefix (not `IOS` or `iOS`) | `IosSecureStorage`, `IosCryptoProvider` | `IOSSecureStorage`, `iOSCryptoProvider` |
| Source set matches target name | `androidMain/`, `iosMain/` | `android/`, `apple/` |
| Shared code in `commonMain` by default | Move to platform only when `expect`/`actual` needed | Duplicating logic across platform source sets |
| Test source sets | `commonTest/`, `androidUnitTest/`, `iosTest/` | `test/`, `androidTest/` (reserved for instrumented) |

### Dependencies

- `commonMain` dependencies: `kotlinx-coroutines-core`, `flatbuffers-kotlin`
- Platform dependencies go in the matching source set (`androidMain`, `iosMain`)
- Use version catalogs (`libs.versions.toml`) for all dependency versions
- No dependency may appear in both `commonMain` and a platform source set

## Identity & ID Comparison

All IDs and UUIDs are byte arrays and **must** be compared as raw bytes — never convert to `String` and then compare. This applies to Key Hashes (12 bytes), Message IDs (16 bytes), public keys (32/64 bytes), and any other binary identifier.

- Use `ConstantTimeEquals` for cryptographic identifiers (Key Hashes, public keys)
- Use `ByteArray.contentEquals()` for non-secret identifiers (Message IDs, Transfer IDs)
- String conversion (hex, Base64, UUID formatting) is for diagnostics, logging, and display only

Rationale: string conversion allocates, can introduce locale-sensitive case folding bugs, and destroys constant-time guarantees needed for cryptographic identifiers.

## Commit Messages

Use [Conventional Commits](https://www.conventionalcommits.org/) for all commits.

Format: `<type>(<optional scope>): <description>`

Types:
- **feat** — new feature or capability
- **fix** — bug fix
- **refactor** — code change that neither fixes a bug nor adds a feature
- **perf** — performance improvement
- **test** — adding or updating tests
- **docs** — documentation only
- **build** — build system or dependency changes
- **chore** — maintenance tasks that don't modify src or test files
- **ci** — CI/CD configuration changes

Rules:
- Subject line: imperative mood, lowercase, no period, max 72 chars
- Body (optional): wrap at 72 chars, explain *what* and *why*, not *how*
- Breaking changes: add `!` after type/scope (e.g. `feat!: remove legacy API`) or a `BREAKING CHANGE:` footer

Examples:
```
feat(crypto): add Poly1305 message authentication
fix(x25519): mask high bit of scalar to prevent sign-extension
perf(sha256): inline round functions into processBlock
test(hmac): cover long-key branch in slice overload
build: bump AGP 8.9.0 → 8.9.3
docs: add initial README with project overview
refactor(chacha20): reuse keyStream buffer across blocks
```
