# AGENTS.md

Instructions for AI coding agents working on this repository.

## Project Overview

MeshLink is a **Kotlin Multiplatform (KMP)** BLE mesh messaging library for
Android, iOS, macOS, Linux, and JVM. It provides encrypted multi-hop messaging
over Bluetooth Low Energy with no servers or internet required.

**Key technologies:** Kotlin 2.3.20, Android Gradle Plugin 9.1.0, Gradle 9.4.1,
Java 21, kotlinx-coroutines 1.10.2, Noise protocol (K + XX), Ed25519/X25519,
ChaCha20-Poly1305, detekt 1.23.8.

## Setup

```bash
# Prerequisites: Java 21 (Zulu recommended), Android SDK (compileSdk 36, minSdk 26)
git clone <repo-url> && cd MeshLink
./gradlew detekt                        # Lint — must pass with zero issues
./gradlew :meshlink:jvmTest --parallel  # Fastest test suite (~30s)
```

Gradle wrapper downloads all dependencies automatically. Configuration cache
is enabled (`gradle.properties`).

## Development Workflow

**Always run tests after every code change.** No change is complete until lint
passes and the relevant test suite is green.

```bash
# 1. Lint (zero-issue policy — build fails on any violation)
./gradlew detekt

# 2. Run tests for the platform you changed
./gradlew :meshlink:jvmTest --parallel           # JVM / commonMain changes
./gradlew :meshlink:iosSimulatorArm64Test         # iOS changes
./gradlew :meshlink:macosArm64Test                # macOS changes
./gradlew :meshlink:linuxX64Test                  # Linux changes

# 3. Compile Android (no device tests without emulator)
./gradlew :meshlink:compileAndroidMain

# 4. Compile sample apps if you touched meshlink-sample/
./gradlew :meshlink-sample:android:assembleDebug
./gradlew :meshlink-sample:jvm:build
```

If you changed `commonMain`, run **all** platform test tasks. If you changed a
single platform source set (e.g. `androidMain`), compile that platform at
minimum.

Detekt also runs automatically as a pre-commit hook via
[prek](https://github.com/nicholasgasior/prek). Commits that fail lint are
rejected locally before reaching CI.

## Testing

### Running Tests

```bash
# Full suite per platform
./gradlew :meshlink:jvmTest --parallel
./gradlew :meshlink:iosSimulatorArm64Test
./gradlew :meshlink:macosArm64Test
./gradlew :meshlink:linuxX64Test

# Integration tests only (41 end-to-end scenarios via VirtualMeshTransport)
./gradlew :meshlink:jvmTest --tests "io.meshlink.MeshIntegrationTest" --parallel

# Single test class
./gradlew :meshlink:jvmTest --tests "io.meshlink.wire.WireFormatGoldenVectorsTest"

# Single test method
./gradlew :meshlink:jvmTest --tests "io.meshlink.MeshIntegrationTest.threeHopRoutedMessage"

# XCFramework for iOS/macOS
./gradlew :meshlink:assembleMeshLinkXCFramework

# View HTML test report after a run
open meshlink/build/reports/tests/jvmTest/index.html
```

### Test Structure

89 test files across 20 packages in `meshlink/src/commonTest/`: crypto (17),
transport (9), transfer (8), util (7), power (7), wire (8), model (6),
routing (5), dispatch (3), diagnostics (3), send (2), config (1), delivery (1),
route (1), peer (1), protocol (1), storage (1), plus integration and stress
suites.

#### Integration Test Suite (`MeshIntegrationTest.kt`)

41 end-to-end scenarios exercising full MeshLink flows through the
`VirtualMeshTransport`. Covers discovery, handshake, direct/broadcast/routed
messaging, delivery confirmation, chunking, pause/resume, lifecycle, and
diagnostics.

### Test Conventions

- Use `kotlin.test` assertions — no external test framework
- `VirtualMeshTransport` replaces real BLE in all tests (in-memory mock)
- Deterministic time via `TestCoroutineScheduler` clock injection — never use
  `delay()` for timing
- Golden test vectors use hex strings with inline comments per field
- Fuzz tests use `Random(seed = 42)` for reproducibility
- Power-assert plugin provides enhanced assertion messages at compile time
- Add or update tests for every code change, even if not explicitly requested

### Adding Tests for New Features

- **New wire message type:** add golden vector test + fuzz test case
- **New engine/coordinator:** test with `VirtualMeshTransport` and injected clock
- **New `expect`/`actual`:** add tests in `commonTest` using the expect interface
- **New config field:** add test in `MeshLinkConfigTest`
- **New end-to-end flow:** add scenario to `MeshIntegrationTest.kt`

## Code Style

Follow the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
and the project's detekt configuration (`detekt.yml`):

- **Max line length:** 160 characters
- **No wildcard imports** (exception: `bluetooth.*`)
- **Import ordering:** lexicographic, `java`/`javax`/`kotlin` last, no blank
  lines between imports
- **Argument/parameter wrapping:** each on its own line if they don't all fit
  on one line
- **Trailing commas:** disabled (both call site and declaration site)
- **Indentation:** 4 spaces, no tabs
- **Braces:** required for multi-line `if`/`else`
- **Naming:** `PascalCase` for classes, `camelCase` for functions/properties,
  `UPPER_SNAKE_CASE` for top-level constants

Detekt enforces zero issues (`build: maxIssues: 0`). Run `./gradlew detekt`
before committing. See `detekt.yml` for the full rule set.

## Project Structure

See [README § Project Structure](README.md#project-structure) for the directory
layout. Key points for agents:

### Key Entry Points

| File | Purpose |
|---|---|
| `MeshLink.kt` | Main orchestrator (~820 lines) — start here |
| `MeshLinkApi.kt` | Public API interface (all methods consumers call) |
| `MeshLinkConfig.kt` | 40+ config fields with DSL builder and presets |
| `WireCodec.kt` | Binary encode/decode + message type constants |
| `TlvCodec.kt` | TLV extension area encoder/decoder for wire message extensibility |
| `SecurityEngine.kt` | Noise K (E2E) + Noise XX (hop-by-hop) encryption |
| `RoutingEngine.kt` | Babel routing: Hello/Update propagation, feasibility condition, route cache |
| `PowerCoordinator.kt` | Battery-adaptive power modes with hysteresis |
| `Compressor.kt` | Payload compression expect/actual (raw DEFLATE RFC 1951) |

### Expect/Actual Pattern

Platform-specific code uses `expect`/`actual` declarations in `io.meshlink.util`
and `io.meshlink.crypto`:

- `currentTimeMillis()` — monotonic clock
- `PlatformLock()` — reentrant lock (contract: must be reentrant)
- `secureRandomBytes(size)` — CSPRNG
- `CryptoProvider()` — Ed25519, X25519, ChaCha20-Poly1305, HKDF, SHA-256
- `BatteryMonitor()` — battery level + charging status
- `Compressor()` — zlib payload compression/decompression

When adding a new `expect` declaration, provide `actual` implementations for
**all 4 platform groups**: `jvmMain`, `androidMain`, `appleMain`, `linuxMain`.

## Build and CI

See [README § Building](README.md#building) for full build commands. Quick
reference for the agent workflow:

### CI Pipeline

CI runs on push/PR to `main` (skips markdown-only changes). Seven jobs:

| Job | Runner | Command |
|---|---|---|
| Detekt | ubuntu | `./gradlew detekt` |
| JVM Tests | ubuntu | `./gradlew :meshlink:jvmTest --parallel` |
| Android Compile | ubuntu | `./gradlew :meshlink:compileAndroidMain` |
| iOS Simulator Tests | macos-15 | `./gradlew :meshlink:iosSimulatorArm64Test` |
| macOS Tests | macos-15 | `./gradlew :meshlink:macosArm64Test` |
| Linux Tests | ubuntu | `./gradlew :meshlink:linuxX64Test --parallel` |
| Sample Apps | ubuntu | `:meshlink-sample:android:assembleDebug` + `:meshlink-sample:jvm:build` |

Test reports are uploaded as artifacts (14-day retention). JUnit XML results
are published as check run annotations via `mikepenz/action-junit-report`.

## Error Handling

- Public API returns `Result<T>` — never throw for expected failures
- Internal engines use sealed result types (e.g. `SendDecision`, `UnsealResult`,
  `RouteRequestResult`, `RouteReplyResult`)
- `IllegalStateException` for API misuse only (e.g. calling `send()` before
  `start()`)
- `catch (_: Exception)` in crypto code is intentional — platform crypto throws
  different exception types; Kotlin's `catch(Exception)` does not catch `Error`
  subtypes

## Wire Format

Custom binary protocol — no protobuf. All parsers in `WireCodec.kt`.

- **Type byte** at offset 0: `0x00` Handshake through `0x0B` DeliveryAck
- **Endianness:** little-endian for chunk offsets, replay counters, SACK
  bitmasks; big-endian for protocol version, timestamps, PSM
- **Key sizes:** 8-byte peer IDs, 32-byte Ed25519/X25519 keys, 64-byte
  signatures
- **TLV extensions:** 7 fixed-length message types (Keepalive, ChunkAck, Nack,
  ResumeRequest, RouteRequest, RouteReply, DeliveryAck) support trailing TLV
  extension areas for backward-compatible schema evolution. Tags `0x00`–`0x7F`
  reserved for protocol; `0x80`–`0xFF` for applications. See `TlvCodec.kt`.

When adding a message type: assign next code in `WireCodec`, add `encode`/`decode`
pair, add golden vector test, add fuzz test case.

When adding a TLV extension tag: assign next tag code, update encode/decode to
populate the `extensions` list, add unit test in `TlvCodecTest`, add integration
test in `TlvExtensionWireTest`, update `docs/wire-format-spec.md`.

## Security

- **Encryption:** Two-layer Noise protocol — Noise K (end-to-end) + Noise XX
  (hop-by-hop). All messages encrypted before transmission.
- **Trust model:** TOFU (Trust-On-First-Discover) with key pinning. Key changes
  emit `KeyChangeEvent` for user verification.
- **Replay protection:** Per-peer replay guards with persisted counters.
- **Transitive dependency pinning:** Root `build.gradle.kts` force-pins Netty,
  jose4j, JDOM2, Commons Lang3, and HttpClient to patched versions (see CVE
  comments in the file).

See `docs/threat-model.md` for the full threat analysis.

## Pull Request Guidelines

- Run `./gradlew detekt` and all relevant test tasks before committing
- Detekt runs as a pre-commit hook — commits that fail lint are rejected locally
- Keep commits atomic: one logical change per commit
- Update documentation per the section below when changing APIs or behavior
- CI must pass before merging — seven jobs must all be green

## Domain Language

Use the terminology defined in `UBIQUITOUS_LANGUAGE.md`:

- **Peer** (not "node" or "device") — a device on the mesh
- **Neighbor** — peer within 1-hop BLE range
- **Broadcast** vs **Advertisement** — broadcast is a signed mesh message;
  advertisement is a BLE packet
- **Chunk ACK** vs **Delivery ACK** — transport-level vs app-level
  acknowledgment
- **TOFU** (not "TOFU") — Trust-On-First-Discover
- Always qualify "eviction": Connection / Buffer / Presence

## Documentation

Keep documentation in sync with the implementation:

- **`docs/api-reference.md`** — Must always reflect the current public API. When
  you add, remove, or change any public function, parameter, return type, config
  field, or enum, update the API reference in the same commit.
- **`docs/design.md`** — Authoritative design document (12 sections). Update
  when behaviour or architecture changes.
- **`docs/diagrams.md`** — All Mermaid diagrams live here. Cross-reference
  rather than duplicate content across docs.
- **`docs/wire-format-spec.md`** — Binary protocol specification. Update when
  adding or changing message types.
- **`docs/threat-model.md`** — Security threat analysis. Update when adding new
  attack surfaces or mitigations.
- **`docs/integration-guide.md`** — Consumer-facing integration guide. Update
  when the API surface or configuration changes.

## Debugging Tips

- **Test reports:** After test failures, check
  `meshlink/build/reports/tests/<target>/` for HTML reports.
- **Gradle build scans:** Add `--scan` to any Gradle command for a detailed
  build analysis.
- **Configuration cache:** Enabled by default. If you see stale-cache errors,
  run `./gradlew --no-configuration-cache <task>` to bypass.
- **Kotlin/Native caching:** The `~/.konan` directory caches the K/N compiler.
  Delete it to force a clean download if native compilation fails unexpectedly.
- **Detekt baseline:** There is no baseline file — all issues must be fixed, not
  suppressed.
