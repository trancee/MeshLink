# Copilot Instructions — MeshLink

MeshLink is a Kotlin Multiplatform (KMP) BLE mesh messaging library targeting Android, iOS, macOS, Linux, and JVM. Licensed under The Unlicense.

## Build, Test, and Lint

```bash
# Lint (also runs as pre-commit hook via prek)
./gradlew detekt

# Test — full suite per platform
./gradlew :meshlink:jvmTest --parallel
./gradlew :meshlink:iosSimulatorArm64Test
./gradlew :meshlink:macosArm64Test
./gradlew :meshlink:linuxX64Test

# Test — single test class
./gradlew :meshlink:jvmTest --tests "io.meshlink.wire.WireFormatGoldenVectorsTest"

# Test — single test method
./gradlew :meshlink:jvmTest --tests "io.meshlink.wire.WireFormatGoldenVectorsTest.chunkGoldenVectorEncodes"

# Compile — Android (no device tests without emulator)
./gradlew :meshlink:compileAndroidMain

# Compile — sample app
./gradlew :meshlink-sample:android:compileDebugKotlin

# XCFramework for iOS/macOS
./gradlew :meshlink:assembleMeshLinkXCFramework
```

Detekt enforces: max line length 160, no wildcard imports (except `bluetooth.*`), import ordering with `java/javax/kotlin` last. See `detekt.yml` for full rules.

## Architecture

### Source Sets

~85% of code lives in `commonMain`. Platform-specific code is minimal:

| Source Set | Purpose |
|---|---|
| `commonMain` | All protocol logic, routing, crypto state machines, wire format |
| `androidMain` | BLE transport (`BluetoothGattServer/Client`), `EncryptedSharedPreferences`, `MeshLinkService` foreground service, JCA crypto (API 33+) with PureKotlin fallback |
| `appleMain` | Shared iOS/macOS: `CoreBluetooth`, `CryptoKit`, `Keychain`, State Preservation |
| `iosMain` / `macosMain` | Thin platform-specific overrides |
| `jvmMain` | Java 21 JCA crypto provider |
| `linuxMain` | Raw HCI/L2CAP BLE transport, `pthread`-based locking |
| `commonTest` | 1,125+ tests using `VirtualMeshTransport` (in-memory BLE mock) |

### Main Orchestrator

`MeshLink.kt` (~820 lines) wires together 15+ engines via constructor injection. No DI framework. Key subsystems:

- **RoutingEngine** — Enhanced DSDV with composite link cost, multipath backup, gossip distribution
- **TransferEngine** — Chunking, SACK with AIMD congestion control, byte-offset resume
- **SecurityEngine** — Noise K (E2E) + Noise XX (hop-by-hop), replay guards, TOFI key pinning
- **PowerCoordinator** — 3-tier battery-adaptive (Performance/Balanced/PowerSaver) with hysteresis
- **DeliveryPipeline** — Buffer management with 75/25 relay/own split, 3-tier eviction
- **DiagnosticSink** — 20+ diagnostic codes via `SharedFlow(DROP_OLDEST)`

### Expect/Actual Pattern

Platform-specific functionality uses `expect`/`actual` declarations in `io.meshlink.util` and `io.meshlink.crypto`:

- `currentTimeMillis()` — monotonic clock
- `createPlatformLock()` — reentrant lock (contract: must be reentrant)
- `secureRandomBytes(size)` — CSPRNG
- `createCryptoProvider()` — Ed25519, X25519, ChaCha20-Poly1305, HKDF, SHA-256
- `createBatteryMonitor()` — battery level + charging status

When adding a new `expect` declaration, provide `actual` implementations for all 4 platform groups: `jvmMain`, `androidMain`, `appleMain`, `linuxMain`.

### Sealed Result Types

The codebase uses railway-oriented programming with sealed types instead of exceptions for expected failures:

```kotlin
sealed interface SendDecision {
    data class Direct(val recipientHex: String) : SendDecision
    data class Routed(val nextHopHex: String) : SendDecision
    data object BufferFull : SendDecision
    data object Unreachable : SendDecision
}
```

Public API uses `Result<T>`. Internal engines return sealed types. `IllegalStateException` for misuse only.

## Wire Format

Custom binary protocol — no protobuf. All parsers in `WireCodec.kt`.

- **Type byte** at offset 0: `0x00` Broadcast through `0x0A` Rotation
- **Endianness**: Little-endian for chunk offsets, replay counters, SACK bitmasks. Big-endian for protocol version, timestamps, PSM.
- **Key sizes**: 16-byte peer IDs (SHA-256 truncation), 32-byte Ed25519/X25519 keys, 64-byte signatures
- **Advertisement**: Frozen 17-byte format (`[version:4|power:4][minor:8][keyHash:15]`)

When adding a message type: assign next code in `WireCodec`, add `encode`/`decode` pair, add golden vector test, add fuzz test case.

## Testing Conventions

- Tests use `kotlin.test` assertions — no external test framework
- `VirtualMeshTransport` replaces real BLE for all unit/integration tests
- Deterministic time via `TestCoroutineScheduler` clock injection — no `delay()`-based timing
- Golden test vectors use hex strings with inline comments per field
- Fuzz tests use `Random(seed = 42)` for reproducibility
- Power-assert plugin provides enhanced assertion messages at compile time

## Domain Language

Use the terminology defined in `UBIQUITOUS_LANGUAGE.md`. Key distinctions:

- **Peer** (not "node" or "device") — a device on the mesh
- **Neighbor** — peer within 1-hop BLE range
- **Broadcast** vs **Advertisement** — broadcast is a signed mesh message; advertisement is a BLE packet
- **Chunk ACK** vs **Delivery ACK** — transport-level vs app-level acknowledgment
- **TOFI** (not "TOFU") — Trust-On-First-Discover
- Always qualify "eviction": Connection / Buffer / Presence

## Key Files

| File | What it does |
|---|---|
| `MeshLink.kt` | Main orchestrator — start here to understand the system |
| `MeshLinkApi.kt` | Public API interface (all methods consumers call) |
| `MeshLinkConfig.kt` | 40+ config fields with DSL builder and presets |
| `WireCodec.kt` | All binary encode/decode + message type constants |
| `SecurityEngine.kt` | Noise K/XX consolidation — seal, unseal, sign, verify |
| `RoutingEngine.kt` | DSDV routing table, route learning, next-hop resolution |
| `PowerCoordinator.kt` | Power mode transitions, memory pressure, buffer monitoring |
| `BleTransport.kt` (android) | Android BLE transport with GATT + L2CAP |
| `VirtualMeshTransport.kt` (test) | In-memory BLE mock for testing |
