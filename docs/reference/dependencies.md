# Dependency register

Every dependency and tool MeshLink uses, with its Constitution E3 rationale. The decision behind this baseline is [ADR 0005](../adr/0005-toolchain-and-dependency-baseline.md).

Rules:
- `gradle/libs.versions.toml` is the only source of versions; this page never overrides it. A CI check fails if any catalog entry has no row here.
- Native and Rust entries have no catalog entry, so their pins live in the build scripts and `tools/reference/`, and they are listed here by hand.
- Adding or removing a dependency needs user approval and a row here in the same change.

Scope legend:
- `runtime`: ships inside a published artifact.
- `build`: Gradle or compiler tooling.
- `test`: test sources only.
- `reference`: test-only Rust, never published.

Facts verified 2026-11-01.

## Runtime (ships in artifacts)

| Dependency | Version | Scope | Need | Maintenance | License | Security | Size | Transitives | Why stdlib or existing fails |
|---|---|---|---|---|---|---|---|---|---|
| Kotlin stdlib | 2.4.21 | runtime | Language runtime | JetBrains, stable | Apache-2.0 | JetBrains security process | Platform-provided per target | None | Required by Kotlin |
| kotlinx-coroutines-core | 1.11.0 | runtime | Structured concurrency, `Flow` for the public API and bearer I/O | JetBrains, stable | Apache-2.0 | JetBrains security process | Shared with most KMP host apps | atomicfu (compile-time) | The stdlib has no coroutine library |
| libsodium | 1.0.22 | runtime (native, static, hidden symbols) | X25519, Ed25519, ChaCha20-Poly1305, SHA-256/HKDF, ML-KEM-768, X-Wing | See ADR 0004 | ISC | ADR 0004; sha256 + minisign verified in CI | Shim at `-Os`: arm64 194 KB, armv7 306 KB, x86_64 289 KB, iOS arm64 280 KB | None | ADR 0004 |

## Build tooling

| Dependency | Version | Scope | Need | Maintenance | License | Security | Size | Transitives | Why stdlib or existing fails |
|---|---|---|---|---|---|---|---|---|---|
| Gradle | 9.7.1 | build | Build system that KMP requires | Gradle Inc., stable | Apache-2.0 | Wrapper checksum validated | n/a | n/a | Required by KGP |
| Kotlin Gradle plugin | 2.4.21 | build | KMP compilation, XCFramework, built-in ABI validation (E2 hold) | JetBrains, stable | Apache-2.0 | n/a | n/a | n/a | Required |
| AGP + `com.android.kotlin.multiplatform.library` | 9.3.3 | build | Android library target in KMP | Google, stable | Apache-2.0 | n/a | n/a | n/a | Required for the Android target |
| Android NDK | 30.0.16248370 | build | Builds libsodium and the JNI shim | Google, stable | Apache-2.0 and others | n/a | n/a | n/a | Native crypto needs it (ADR 0004) |
| SKIE | 0.10.16 | build (compiler plugin) | Swift-friendly suspend, Flow, sealed and enum interop | Touchlab, releases follow Kotlin; 0.10.16 adds 2.4.21 | Apache-2.0 | Analytics upload disabled | Adds Swift glue to the framework | None at runtime | Plain Objective-C export loses suspend, Flow and sealed types |
| Kover | 0.9.11 | build | T3 coverage for common and Android | JetBrains; Beta (E2 hold) | Apache-2.0 | n/a | n/a | n/a | No built-in coverage tool |
| Spotless + ktlint | 8.10.4 + 1.8.0 | build | Formatting check (`spotlessCheck`) | DiffPlug and Pinterest, active | Apache-2.0 / MIT | n/a | n/a | n/a | No built-in formatter gate |
| detekt | 2.0.0-alpha.6 | build | Static analysis | Active; alpha (E2 hold until 2.0 stable) | Apache-2.0 | n/a | n/a | n/a | 1.23.8 is built against Kotlin 2.0.21; ktlint is formatting only |
| Dokka | 2.2.0 | build | API reference docs | JetBrains, stable | Apache-2.0 | n/a | n/a | n/a | Required for generated API docs |
| vanniktech maven-publish | 0.37.0 | build | Maven Central publishing for KMP | Active | Apache-2.0 | Signing configured at release | n/a | n/a | Plain `maven-publish` lacks Central Portal upload and KMP defaults |
| Xcode | 16.4 | build (host) | iOS SDK 18.5 and the Swift consumer | Apple | Apple EULA | n/a | n/a | n/a | Hard constraint |

## Test only

| Dependency | Version | Scope | Need | Maintenance | License | Security | Size | Transitives | Why stdlib or existing fails |
|---|---|---|---|---|---|---|---|---|---|
| kotlin-test | 2.4.21 | test | Multiplatform assertions | JetBrains, stable | Apache-2.0 | n/a | n/a | n/a | Required |
| kotlinx-coroutines-test | 1.11.0 | test | Virtual time for deterministic tests (T2, T8) | JetBrains, stable | Apache-2.0 | n/a | n/a | n/a | Required for deterministic coroutine tests |
| Robolectric | 4.17 | test | Android framework on the JVM, SDK 26 and 37 | Google-backed, active | MIT | n/a | n/a | Large (test only) | Instrumented tests can't run in default CI (T2) |
| JUnit 4 | 4.13.2 | test | Robolectric test runner | Maintenance mode, stable | EPL-1.0 | n/a | n/a | hamcrest-core | Robolectric needs a JUnit 4 runner |
| androidx.test core | 1.7.0 | test | `ApplicationProvider` for Robolectric | Google, stable | Apache-2.0 | n/a | n/a | n/a | Standard Robolectric companion |

## Reference (test-only Rust, `tools/reference/`, never published)

Pinned by `rust-toolchain.toml` (Rust 1.99.0) and a committed `Cargo.lock`. Purpose: an independent second vector set for ADR 0002 and ADR 0004.

| Crate | Version | License | Need |
|---|---|---|---|
| hpke (rust-hpke) | 0.15.1 | MIT/Apache-2.0 | HPKE reference |
| ml-kem | 0.3.4 | Apache-2.0 OR MIT | ML-KEM-768 reference |
| x25519-dalek | 3.0.0 | BSD-3-Clause | X25519 reference |
| ed25519-dalek | 3.0.0 | BSD-3-Clause | Ed25519 / identity-certificate reference |
| chacha20poly1305 | 0.11.0 | Apache-2.0 OR MIT | AEAD reference |
| sha2 | 0.11.1 | MIT OR Apache-2.0 | SHA-256 reference |
| hkdf | 0.13.0 | MIT OR Apache-2.0 | HKDF reference |
| snow | git `dc18c06c28fbdeaa6ffae7347e650c805e9b932e` (snow#210) | Apache-2.0 OR MIT | Independent Noise `XXhfs` with ML-KEM-768. Moves to a crates.io release once #210 merges |
