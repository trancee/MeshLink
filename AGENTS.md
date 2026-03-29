# AGENTS.md — MeshLink

Instructions for AI coding agents working on this repository.

## Golden Rule

**Always run tests after every code change.** No change is complete until the
relevant test suite passes. Run lint first, then tests:

```bash
# 1. Lint (zero-issue policy — build fails on any violation)
./gradlew detekt

# 2. Run tests for the platform you changed
./gradlew :meshlink:jvmTest --parallel          # JVM / commonMain
./gradlew :meshlink:iosSimulatorArm64Test        # iOS
./gradlew :meshlink:macosArm64Test               # macOS
./gradlew :meshlink:linuxX64Test                 # Linux

# 3. Compile Android (no device tests without emulator)
./gradlew :meshlink:compileAndroidMain

# 4. Compile sample app if you touched meshlink-sample/
./gradlew :meshlink-sample:android:assembleDebug
./gradlew :meshlink-sample:jvm:build
```

If you changed `commonMain`, run **all** platform test tasks. If you changed a
single platform source set (e.g. `androidMain`), compile that platform at
minimum.

## Kotlin Code Style

Follow the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
and the project's detekt configuration (`detekt.yml`). Key rules:

- **Max line length**: 160 characters
- **No wildcard imports** (exception: `bluetooth.*`)
- **Import ordering**: lexicographic, `java`/`javax`/`kotlin` last, no blank lines between imports
- **Argument/parameter wrapping**: each on its own line if they don't all fit on one line
- **Trailing commas**: disabled
- **Indentation**: 4 spaces, no tabs
- **Braces**: required for multi-line `if`/`else`

Detekt runs as a pre-commit hook (via [prek](https://github.com/nicholasgasior/prek))
and in CI. Zero issues tolerated — `build: maxIssues: 0`.

## Project Structure

MeshLink is a **Kotlin Multiplatform (KMP)** BLE mesh messaging library.

```
meshlink/src/
  commonMain/     — ~85% of code: protocol, routing, crypto, wire format
  commonTest/     — 1,125+ tests using VirtualMeshTransport (in-memory BLE mock)
  androidMain/    — BLE transport, EncryptedSharedPreferences, JCA crypto
  appleMain/      — Shared iOS/macOS: CoreBluetooth, CryptoKit, Keychain
  iosMain/        — iOS-specific overrides
  macosMain/      — macOS-specific overrides
  jvmMain/        — Java 21 JCA crypto provider
  linuxMain/      — Raw HCI/L2CAP transport, pthread locking
meshlink-sample/  — Sample apps (android, ios, macos, jvm, linux)
docs/             — Design docs, API reference, threat model, diagrams
```

### Key Entry Points

| File | Purpose |
|---|---|
| `MeshLink.kt` | Main orchestrator (~820 lines) — start here |
| `MeshLinkApi.kt` | Public API interface |
| `MeshLinkConfig.kt` | 40+ config fields with DSL builder and presets |
| `WireCodec.kt` | Binary encode/decode + message type constants |
| `SecurityEngine.kt` | Noise K (E2E) + Noise XX (hop-by-hop) |
| `RoutingEngine.kt` | DSDV routing table and next-hop resolution |
| `PowerCoordinator.kt` | Battery-adaptive power modes with hysteresis |

### Expect/Actual Pattern

Platform-specific code uses `expect`/`actual` in `io.meshlink.util` and
`io.meshlink.crypto`. When adding a new `expect` declaration, provide `actual`
implementations for **all 4 platform groups**: `jvmMain`, `androidMain`,
`appleMain`, `linuxMain`.

## Testing Conventions

- Use `kotlin.test` assertions — no external test framework
- `VirtualMeshTransport` replaces real BLE in all unit/integration tests
- Deterministic time via `TestCoroutineScheduler` clock injection — no `delay()`-based timing
- Golden test vectors use hex strings with inline comments per field
- Fuzz tests use `Random(seed = 42)` for reproducibility
- Power-assert plugin provides enhanced assertion messages at compile time

### Running a Single Test

```bash
# Single test class
./gradlew :meshlink:jvmTest --tests "io.meshlink.wire.WireFormatGoldenVectorsTest"

# Single test method
./gradlew :meshlink:jvmTest --tests "io.meshlink.wire.WireFormatGoldenVectorsTest.chunkGoldenVectorEncodes"
```

## Error Handling

- Public API returns `Result<T>` — never throw for expected failures
- Internal engines use sealed result types (e.g. `SendDecision`, `UnsealResult`)
- `IllegalStateException` for API misuse only (e.g. calling `send()` before `start()`)
- `catch (_: Exception)` in crypto code is intentional — platform crypto throws
  different exception types; Kotlin's `catch(Exception)` does not catch `Error` subtypes

## Wire Format

Custom binary protocol — no protobuf. All parsers in `WireCodec.kt`.

- **Type byte** at offset 0: `0x00` Broadcast through `0x0A` Rotation
- **Endianness**: little-endian for chunk offsets, replay counters, SACK bitmasks;
  big-endian for protocol version, timestamps, PSM
- **Key sizes**: 16-byte peer IDs, 32-byte Ed25519/X25519 keys, 64-byte signatures

When adding a message type: assign next code in `WireCodec`, add `encode`/`decode`
pair, add golden vector test, add fuzz test case.

## Domain Language

Use the terminology defined in `UBIQUITOUS_LANGUAGE.md`:

- **Peer** (not "node" or "device") — a device on the mesh
- **Neighbor** — peer within 1-hop BLE range
- **Broadcast** vs **Advertisement** — broadcast is a signed mesh message; advertisement is a BLE packet
- **Chunk ACK** vs **Delivery ACK** — transport-level vs app-level acknowledgment
- **TOFI** (not "TOFU") — Trust-On-First-Discover
- Always qualify "eviction": Connection / Buffer / Presence

## Documentation

- **API reference** (`docs/api-reference.md`): Must always reflect the current
  implementation. When you add, remove, or change any public API (functions,
  parameters, return types, config fields, enums), update the API reference in
  the same commit.
- **Design doc** (`docs/design.md`): Authoritative design details (12 sections).
  Update when behaviour or architecture changes.
- **Diagrams** (`docs/diagrams.md`): All Mermaid diagrams live here.
  Cross-reference rather than duplicate content across docs.
