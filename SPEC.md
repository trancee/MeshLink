# MeshLink — Build Specification (Greenfield)

> **Audience:** AI coding agent rebuilding this project from scratch.  
> **What this is:** A single document capturing every architectural decision, implementation pattern, optimization, and lesson learned across 5 milestones of implementation. Follow this as a blueprint.

---

## 1. Project Identity

**MeshLink** is a library-first Kotlin Multiplatform SDK that enables encrypted, serverless, offline-capable messaging over Bluetooth Low Energy mesh networks. No internet, no servers, no user accounts. Two phones near each other find each other via BLE advertising, run a Noise XX mutual-auth handshake, and exchange end-to-end encrypted messages through a multi-hop Babel mesh.

- **Maven coordinates:** `ch.trancee:meshlink:0.1.0`
- **iOS distribution:** XCFramework via SPM (`Package.swift` at repo root)
- **Primary use case:** "Tinder without Internet" — proximity social discovery where users broadcast their profile (≤10KB) as a flood-fill and 1:1 channels open after discovery.

---

## 2. Build System & Toolchain

### 2.1 Gradle Setup

| Tool | Version | Notes |
|------|---------|-------|
| Gradle | 9.4.1+ | Configuration cache enabled |
| Kotlin | 2.3.20 | `-Xexpect-actual-classes` for expect/actual objects |
| AGP | 9.2.0 | **Must use `com.android.kotlin.multiplatform.library`** — old `com.android.library` + KMP is broken |
| Kover | 0.9.8 | 100% line + branch, zero exclusions |
| Detekt | 1.23.8 | Static analysis |
| ktfmt | 0.26.0 | Formatting |
| BCV | 0.18.1 | Binary compatibility validator |
| SKIE | 0.10.11 | Swift AsyncStream wrappers for iOS |
| Dokka | 2.2.0 | KDoc documentation |
| kotlinx-benchmark | 0.4.16 | JMH benchmarks |
| Compose Multiplatform | 1.10.3 | Reference app UI |
| Coroutines | 1.10.2 | Core async primitives |

### 2.2 KMP Target Layout

```kotlin
kotlin {
    explicitApi()
    android { namespace = "ch.trancee.meshlink"; compileSdk = 36; minSdk = 29 }
    iosArm64("ios")  // single iOS target renamed from iosArm64 for consistency
    jvm()            // test/build infrastructure only — no JVM distribution artifact
}
```

**Critical:** `kotlin.mpp.applyDefaultHierarchyTemplate=false` in gradle.properties because the default hierarchy clashes with the renamed `ios` target.

### 2.3 Module Structure

```
:meshlink              — the library (commonMain, androidMain, iosMain, jvmMain)
:meshlink-testing      — publishable test utilities (VirtualMeshTransport)
:meshlink-sample       — root for reference app
:meshlink-sample:shared    — CMP screens, MeshController
:meshlink-sample:androidApp — Android Activity entry point
```

### 2.4 Quality Gates (Enforced Pre-Commit + CI)

1. `./gradlew ktfmtCheck` — formatting
2. `./gradlew detekt` — static analysis
3. `./gradlew :meshlink:koverVerify` — 100% LINE + BRANCH coverage
4. `./gradlew :meshlink:apiCheck` — BCV binary compat
5. `./gradlew :meshlink:jvmTest` — all tests (1550+)

### 2.5 CI Pipeline (GitHub Actions)

- **ci.yml:** 4-job pipeline — lint (ktfmt+detekt) | test (allTests) → coverage (koverVerify) | benchmark (continue-on-error)
- **release.yml:** Triggers on version tag. Publishes to OSSRH (Maven Central) + builds XCFramework + computes SHA-256 + updates Package.swift

### 2.6 Pre-Commit Hook

`.githooks/pre-commit` runs `./gradlew ktfmtCheck detekt`. No external tool dependency.

---

## 3. Architecture Overview

```
meshlink/src/commonMain/kotlin/ch/trancee/meshlink/
  api/           Public surface — MeshLink, MeshLinkApi, MeshLinkConfig, DiagnosticSink
  wire/          FlatBuffers codec — 12 message types, zero-copy
  transport/     BleTransport interface, L2capFrameCodec, BleTransportConfig
  routing/       Babel adaptation — RoutingTable, RoutingEngine, DedupSet, PresenceTracker
  transfer/      SackTracker, TransferSession, TransferEngine
  messaging/     DeliveryPipeline, SlidingWindowRateLimiter, CutThroughBuffer
  power/         PowerManager, PowerModeEngine, ConnectionLimiter
  engine/        MeshEngine (coordinator), MeshStateManager, NoiseHandshakeManager, PseudonymRotator
  crypto/        Noise XX/K, Identity, TrustStore, ReplayGuard
  storage/       SecureStorage interface, InMemorySecureStorage

meshlink/src/androidMain/
  api/           MeshLink.createAndroid(context, config) factory
  transport/     AndroidBleTransport (dual-role), MeshLinkService (foreground service)
  storage/       AndroidSecureStorage (EncryptedSharedPreferences)
  crypto/        AndroidCryptoProvider (JNI → libsodium)

meshlink/src/iosMain/
  api/           MeshLink.createIos(config, restorationIdentifier) factory
  transport/     IosBleTransport (CoreBluetooth, State Preservation/Restoration)
  storage/       IosSecureStorage (Keychain, kSecAttrAccessibleAfterFirstUnlock)
  crypto/        IosCryptoProvider (cinterop → libsodium)

meshlink/src/jvmMain/
  crypto/        JvmCryptoProvider (JDK 17+ built-in crypto — test-only)
```

---

## 4. Cryptographic Stack

### 4.1 CryptoProvider Interface (9 operations)

```kotlin
internal interface CryptoProvider {
    fun generateEd25519KeyPair(): KeyPair
    fun ed25519Sign(message: ByteArray, privateKey: ByteArray): ByteArray
    fun ed25519Verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean
    fun generateX25519KeyPair(): KeyPair
    fun x25519SharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray
    fun chaCha20Poly1305Encrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray
    fun chaCha20Poly1305Decrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray
    fun sha256(input: ByteArray): ByteArray
    fun hkdfSha256(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray
}
```

### 4.2 Platform Implementations

| Platform | Implementation | Notes |
|----------|---------------|-------|
| Android | `AndroidCryptoProvider` via JNI → libsodium 1.0.20 | ~200 line C bridge (`meshlink_sodium.c`) |
| iOS | `IosCryptoProvider` via Kotlin/Native cinterop → libsodium | `.def` file + vendored headers |
| JVM (test) | `JvmCryptoProvider` using JDK 17+ built-in APIs | Ed25519, X25519, ChaCha20-Poly1305, SHA-256, HKDF via HMAC |

### 4.3 Key Format Decisions

- **IETF ChaCha20-Poly1305:** 12-byte nonce (RFC 8439), not original 8-byte
- **Ed25519 private key:** seed(32) + pubKey(32) = 64 bytes (libsodium convention)
- **HKDF:** Manual HMAC loop — libsodium has no direct HKDF function

### 4.4 Noise Protocol

- **Handshake:** `Noise_XX_25519_ChaChaPoly_SHA256` only (Noise IK removed — one code path)
- **E2E encryption:** `Noise_K_25519_ChaChaPoly_SHA256` for unicast messages
- **Classes:** CipherState → SymmetricState → HandshakeState → NoiseXXHandshake → NoiseSession
- **DhCache:** Memoizes DH(s,rs) static-static computation for repeated encryption to same peer
- **Race resolution:** Lexicographic comparison of static public keys — higher key "wins" initiator role

### 4.5 Identity & Trust

- **Identity:** Independent Ed25519 + X25519 keypairs. Key Hash = SHA-256(Ed25519Pub ‖ X25519Pub)[0:12]
- **TOFU:** TrustStore with Strict mode (silent reject) and Prompt mode (callback delegation)
- **Key rotation:** `RotationAnnouncement` signed by old key, propagated to mesh
- **Replay guard:** RFC 9147 §4.5.3 64-bit sliding window. Counter 0 always rejected. Silent drop (no diagnostic).

---

## 5. Wire Format

### 5.1 Codec Strategy

Pure-Kotlin FlatBuffers codec in commonMain (~300 lines ReadBuffer + ~300 lines WriteBuffer). **Not flatc codegen.** `flatbuffers-java` is JVM-only and breaks iOS compilation — it lives in jvmMain for benchmarks only.

### 5.2 Message Types (12)

1. Handshake
2. Keepalive
3. RotationAnnouncementMsg
4. Hello
5. Update
6. Chunk
7. ChunkAck
8. Nack
9. ResumeRequest
10. Broadcast
11. RoutedMessage
12. DeliveryAck

Each message has a 1-byte type prefix for dispatch. InboundValidator performs structural gate before any field access.

### 5.3 L2CAP Frame Codec

3-byte header: `[FrameType(1) | Length(2)]`. FrameType enum for multiplexing on the L2CAP channel.

---

## 6. Transport Layer

### 6.1 BleTransport Interface (8-member contract in commonMain)

Connection model: L2CAP-first (3–10× throughput over GATT). GATT as fallback when `l2capPsm = 0x00` or L2CAP fails.

### 6.2 Advertisement Payload

16 bytes total. PSM at byte offset 3 — available to scanner from first scan result (no GATT round-trip needed). PSM validation: 0x00 (GATT-only) or 128–255 (valid L2CAP dynamic range). Values 1–127 rejected.

### 6.3 Android (`AndroidBleTransport`)

- Dual-role: Central (scanning, GATT client) + Peripheral (advertising, GATT server with 5-characteristic layout)
- `MeshLinkService`: Abstract foreground service with `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE` (API 34+)
- `neverForLocation` flag on `BLUETOOTH_SCAN` permission (API 29+) — no location permission needed
- OEM hardening: `OemSlotTracker` reduces connection slots on repeated failures from specific `manufacturer|model|SDK_INT`

### 6.4 iOS (`IosBleTransport`)

- Dual-role: `CBCentralManager` + `CBPeripheralManager` simultaneously
- State Preservation/Restoration via `willRestoreState` for relaunch after suspension
- `IosSecureStorage` backed by Keychain with `kSecAttrAccessibleAfterFirstUnlock`
- Restoration identifier: constructor parameter (default: `"ch.trancee.meshlink"`)

### 6.5 VirtualMeshTransport (test infrastructure)

In-process BLE simulator: `linkTo` topology, `simulateDiscovery`/`simulatePeerLost`, `simulateWriteFailure`, `sentFrames` capture, `advanceTo(time)` step-mode clock.

### 6.6 Pseudonym Rotation

`PseudonymRotator` class: HMAC-SHA256(keyHash, epochToBytes(epoch))[0:12]. 15-minute epochs. Per-peer stagger prevents fleet-wide rotation bursts. Connected peers verify pseudonyms via HMAC with ±1 epoch tolerance.

---

## 7. Routing (Babel Adaptation — RFC 8966)

### 7.1 Core Components

- `RoutingTable`: FNV-1a route digest, lazy expiry, RFC 8966 modular seqNo
- `RoutingEngine`: Babel feasibility condition (3-step), differential Hello, route retraction
- `RouteCoordinator`: Wires routing + TrustStore pinning + outbound frame emission
- `DedupSet`: TTL+LRU with `List<Byte>` keys (ByteArray can't be Map key)
- `PresenceTracker`: Three-state FSM (Connected → Disconnected → Gone)

### 7.2 Key Decisions

- Feasibility distance stored separately from route entry, cleared only on explicit retraction (never on expiry)
- Per-destination seqNo counter starting at 1u, incremented on reconnect — fixes the differential routing skip bug
- Route entries carry Ed25519 public key; TrustStore stores X25519 key via `pinKey()`
- TTL per priority: HIGH=45min, NORMAL=15min, LOW=5min

### 7.3 Peer Lifecycle

Three-state FSM: Connected → Disconnected → Gone. `MeshStateManager` sweeps every 30s. Two-sweep eviction (sweepCount≥2, 60s grace period). `PeerEvent.Lost` emitted on Gone transition.

---

## 8. Transfer Engine

### 8.1 Components

- `SackTracker`: 64-bit sliding window with UShort.MAX_VALUE sentinel
- `ObservationRateController`: 1→2→4 ACK-observation ramp
- `TransferSession`: Sender/receiver state machines with `Channel<SenderEvent>(UNLIMITED)` senderLoop
- `TransferScheduler`: Weighted Round Robin (HIGH×3 / NORMAL×1 / LOW×1)
- `TransferEngine`: Coordinator, `maxNackRetries=5`

### 8.2 Key Patterns

- Single `Channel<SenderEvent>(UNLIMITED)` into single senderLoop coroutine — serialises all state mutations without Mutex
- `ChunkProgress` emitted on every ACK received (not milestone-based)
- `MemoryPressureListener` fun interface for hook between transport and transfer
- Priority enum in `transfer` package with `fromWire(Byte)` factory and NORMAL fallback

---

## 9. Messaging Pipeline

### 9.1 DeliveryPipeline

Handles full inbound frame dispatch:
- Hello/Update → RouteCoordinator
- Chunk/ChunkAck → TransferEngine
- RoutedMessage → relay or deliver
- Broadcast → flood-fill with TTL decrement + DedupSet

### 9.2 Cut-Through Relay

`CutThroughBuffer` class: parse routing header from chunk 0, begin forwarding re-encrypted chunks to next hop before all inbound chunks arrive. Fallback to full-reassembly on routing header parse failure.

### 9.3 Rate Limiting

`SlidingWindowRateLimiter` + circuit breaker. `send()` suspends and throws on rate-limit violation. `broadcast()` floods with Ed25519 signature.

---

## 10. Power Management

### 10.1 Three Fixed Tiers

Performance / Balanced / PowerSaver — adjusts scan duty cycle, connection count, chunk size.

### 10.2 PowerModeEngine

- ±2% battery hysteresis with 30s downgrade delay
- Charging fast-path to Performance
- No-flap deadband prevents oscillation
- `ConnectionLimiter`: Slot tracking with tier-change `updateMaxConnections()`
- `TieredShedder`: Stateless eviction decision engine

---

## 11. Public API Surface

### 11.1 Entry Point

```kotlin
public class MeshLink : MeshLinkApi {
    public companion object {
        public fun createAndroid(context: Context, config: MeshLinkConfig): MeshLink
        public fun createIos(config: MeshLinkConfig): MeshLink
    }
    public val localPublicKey: ByteArray
    public val state: StateFlow<MeshLinkState>
    public val peers: Flow<PeerEvent>
    public val messages: Flow<ReceivedMessage>
    public val keyChanges: Flow<KeyChangeEvent>
    public val deliveryConfirmations: Flow<MessageId>
    public val transferProgress: Flow<TransferProgress>
    public val transferFailures: Flow<TransferFailure>
    public val meshHealthFlow: Flow<MeshHealthSnapshot>
    public val diagnosticEvents: Flow<DiagnosticEvent>

    public suspend fun start()
    public suspend fun stop(timeout: Duration = Duration.ZERO)
    public suspend fun send(recipient: ByteArray, payload: ByteArray, priority: MessagePriority = NORMAL)
    public suspend fun broadcast(payload: ByteArray, maxHops: Int, priority: MessagePriority = NORMAL)
    public fun routingSnapshot(): RoutingSnapshot
    public fun meshHealth(): MeshHealthSnapshot
    // ... 17 methods total
}
```

### 11.2 MeshLinkConfig

DSL builder with 8 sub-configs and 4 presets. `RegulatoryRegion` enum (DEFAULT, EU) with EU clamping (ad interval ≥ 300ms, scan duty ≤ 70%).

### 11.3 Diagnostics

- 27 `DiagnosticCode` enum values across 4 levels (DEBUG, INFO, WARN, ERROR)
- 28 typed `DiagnosticPayload` subtypes
- `DiagnosticSink`: SharedFlow ring-buffer with DROP_OLDEST, lazy payload evaluation
- `NoOpDiagnosticSink`: Zero-overhead opt-out
- `MeshHealthSnapshot`: 10 fields per spec §11.5 with periodic `meshHealthFlow`
- Emission pattern: `diagnosticSink.emit(code) { payload }`

### 11.4 State Machine

`MeshLinkState` lifecycle FSM with oscillation bounds. States: UNINITIALIZED → RUNNING → PAUSED → STOPPED (or RECOVERABLE / TERMINAL on failure).

### 11.5 Value Classes

`PeerIdHex` for diagnostic payload fields only (internal). Public API uses raw `ByteArray` for peer IDs (12-byte key hash).

---

## 12. Distribution

### 12.1 Maven Central

- `maven-publish` + `signing` + Dokka
- Signing conditional on `SIGNING_KEY` env var — `publishToMavenLocal` works without GPG
- Coordinates: `ch.trancee:meshlink:0.1.0`, `ch.trancee:meshlink-testing:0.1.0`
- 33 artifacts across 5 publication coordinates (verified by `scripts/verify-publish.sh`)

### 12.2 SPM (Swift Package Manager)

- XCFramework: `assembleMeshLinkReleaseXCFramework` task → ios-arm64 slice (no simulator slice needed)
- `Package.swift` at repo root with `binaryTarget`
- release.yml computes SHA-256 and updates Package.swift checksum on tag push

### 12.3 SKIE

Version 0.10.11 generates Swift AsyncStream wrappers for all public Flows (20+ Swift files). Active on Kotlin 2.3.20.

---

## 13. Reference App (meshlink-sample)

Compose Multiplatform 4-screen app: Chat, Mesh Visualizer, Diagnostics, Settings.

- Navigation: `org.jetbrains.androidx.navigation:navigation-compose:2.9.2` with `@Serializable` routes
- `MeshController`: Unidirectional data flow, bridges MeshLink Flows to Compose StateFlows
- Platform wiring: `expect`/`actual` `createPlatformMeshLink()` calls platform factories
- Android: `MainActivity` with runtime BLE permission flow
- iOS: `ComposeUIViewController` + `MeshEngineBridge.swift`

---

## 14. Testing Strategy

### 14.1 Coverage

- **Target:** 100% line + branch coverage with zero `@CoverageIgnore` annotations
- **Tool:** Kover 0.9.8
- **Enforced:** Pre-commit hook + CI

### 14.2 Test Infrastructure

- `VirtualMeshTransport`: In-process BLE simulator with topology, clocks, latency/loss injection
- `MeshTestHarness`: N-node integration framework with real Noise XX handshakes, virtual time (`advanceTimeBy`)
- Standard topology: 3-node linear mesh (A↔B, B↔C, A cannot see C)
- All tests run on JVM — no hardware dependency

### 14.3 Test Count

1550+ JVM tests across 50+ test classes.

### 14.4 Wycheproof Vectors

Used for X25519 and Ed25519 validation. RFC test vectors for HKDF (RFC 5869), ChaCha20-Poly1305 (RFC 8439).

### 14.5 Benchmarks

4 suites: WireFormat, Routing, Transfer, Dedup (17 benchmarks total). Baselines committed to `benchmarks/baselines/jvm.json`.

---

## 15. Critical Implementation Patterns & Gotchas

### 15.1 Kover Compatibility

| Problem | Solution |
|---------|----------|
| `require()` with string interpolation creates uncoverable bytecode branch | Use explicit `if (...) throw IllegalArgumentException(...)` |
| `while(isActive)` creates unreachable Kover branch | Use `while(true)` — compiles to unconditional GOTO |
| Safe-call chain on non-nullable property generates dead null-check | Split into explicit null check |
| `data class` with `ByteArray` fields — custom equals/hashCode needed | Override both using `contentEquals` |
| Timer coroutines with `this` in runTest causes UncompletedCoroutinesError | Pass `backgroundScope` not `this` |
| `testScheduler.runCurrent()` needed after synchronous `tryEmit` | Explicit flush in StandardTestDispatcher tests |
| Sealed interface impls must share exact package of sealed type | Not a sub-package — even if files are in subdirectories |

### 15.2 KMP/AGP Gotchas

| Problem | Solution |
|---------|----------|
| AGP 9.0+ breaks `com.android.library` + KMP | Use `com.android.kotlin.multiplatform.library` plugin |
| `androidUnitTest` source set renamed | Now `androidHostTest` |
| BCV KLib inference fails on Linux | Make KLib validation OS-conditional (macOS only) |
| `flatbuffers-java` in commonMain breaks iOS | Move to jvmMain only; use pure-Kotlin codec in commonMain |
| `kotlin.mpp.applyDefaultHierarchyTemplate=true` clashes with renamed `ios` target | Set to `false` |
| JMH `@Benchmark` can't return inline value classes (UInt) | Cast to Int |
| `allopen` plugin must precede `kotlinx-benchmark` in plugins block | Plugin ordering matters for JMH annotation processing |
| Kover `excludedSourceSets` insufficient for platform-only classes | Use surgical `reports.filters.excludes.classes()` glob patterns |
| NDK r27 requires `NDK_PLATFORM=android-29` for armeabi-v7a | Earlier platform levels fail linker checks |

### 15.3 Naming Convention

Use `Millis` suffix (not `Ms`) for all Long/Int fields representing millisecond durations. Example: `inactivityBaseTimeoutMillis`, `nackBaseBackoffMillis`. Matches Kotlin stdlib (`delay(timeMillis)`).

### 15.4 ByteArray Handling

- Never use ByteArray as Map key (identity-based hashCode) — use `List<Byte>` or a wrapper
- `ConstantTimeEquals` for all cryptographic identifier comparisons
- `contentEquals()` for non-secret identifiers (Message IDs)
- Never compare as strings

### 15.5 Integration Test Config

- `batteryPollIntervalMillis=300_000L` in integration test configs — prevents OOM from timer explosion under Kover instrumentation
- JVM args: `-Xmx1g` for integration tests with multiple MeshEngine instances
- `RouteCoordinator.onPeerConnected()` must use `TrustStore.getPinnedKey()` not `ByteArray(32)` — all-zero key causes InvalidKeyException in Noise K at distant nodes

---

## 16. Build Order (Milestone Sequence)

### M001: Foundation & Crypto
1. KMP scaffold (Gradle, CI, quality gates, BCV, Kover)
2. Android libsodium JNI (3 ABIs)
3. iOS libsodium cinterop
4. Noise XX state machine (3-message handshake)
5. Noise K seal/open + DhCache
6. Identity, TrustStore, SecureStorage, ReplayGuard

### M002: Protocol Engine
1. Wire format (pure-Kotlin FlatBuffers codec, 12 message types)
2. VirtualMeshTransport (BleTransport interface + in-process simulator)
3. Routing engine (Babel feasibility, RoutingTable, RouteCoordinator, DedupSet)
4. Transfer engine (SackTracker, TransferSession, TransferEngine)
5. Messaging pipeline (DeliveryPipeline, send/broadcast, rate limiting)
6. Power management (PowerModeEngine, ConnectionLimiter, TieredShedder)
7. MeshEngine integration + benchmarks (4 suites, 17 benchmarks)

### M003: BLE Transport
1. commonMain BLE contract (AdvertisementCodec, GattConstants, BleTransportConfig)
2. AndroidBleTransport (dual-role, MeshLinkService foreground service)
3. IosBleTransport (CoreBluetooth, State Preservation/Restoration)
4. Cross-platform integration test (wiring + Logger expect/actual)
5. OEM hardening (OemSlotTracker, bootstrap scan mode)

### M004: API, Diagnostics & Distribution
1. Public API surface (MeshLink, MeshLinkConfig, MeshLinkState, explicitApi(), SKIE, BCV baseline)
2. DiagnosticSink + MeshHealthSnapshot (27 codes, 28 payloads)
3. Maven Central distribution (maven-publish, signing, Dokka, release.yml)
4. SPM distribution (XCFramework, Package.swift)
5. Compose Multiplatform reference app (4 screens, MeshController)
6. Two-device integration proof (platform factories, BLE permissions)

### M005: Protocol Hardening & Full API Wiring
1. Remove all @CoverageIgnore, build MeshTestHarness (N-node, full Noise XX)
2. Wire DiagnosticSink emission (18+ emit calls across 5 subsystems)
3. Pseudonym rotation (HMAC-SHA256, 15-min epochs, per-peer stagger)
4. Cut-through relay forwarding (CutThroughBuffer, pipelined chunks)
5. Peer lifecycle FSM (3-state, MeshStateManager 30s sweep, seqNo fix)
6. Stub API wiring (all 12+ methods → real MeshEngine subsystems)
7. Graceful drain, RegulatoryRegion, EXPORT_CONTROL.md

---

## 17. Compliance & Security

### 17.1 Export Control

- ECCN 5D002 classification
- Algorithm inventory in `EXPORT_CONTROL.md`
- TSU §740.13(e) exemption analysis

### 17.2 Regulatory

- `RegulatoryRegion.EU`: advertisement interval ≥ 300ms, scan duty ≤ 70% (ETSI EN 300 328)
- Config clamping happens in `MeshLinkConfigBuilder.build()` with `CONFIG_CLAMPED` diagnostic on start

### 17.3 Android Permissions

- `BLUETOOTH_SCAN` with `neverForLocation` (API 29+)
- `BLUETOOTH_CONNECT`
- `BLUETOOTH_ADVERTISE`
- `FOREGROUND_SERVICE_CONNECTED_DEVICE` (API 34+)
- **No `ACCESS_FINE_LOCATION` needed**

### 17.4 iOS Permissions

- `NSBluetoothAlwaysUsageDescription` in Info.plist
- Background modes: `bluetooth-central`, `bluetooth-peripheral`

---

## 18. What Is Explicitly Out of Scope

- No server component, no internet relay, no cloud sync (R050)
- No multi-party group chats — 1:1 + flood-broadcast only (R051)
- Only Android and iOS ship — JVM is test infrastructure (R052)
- No session ratchet (DH cost acceptable), no Noise IK (one handshake path), no compression (CRIME/BREACH risk) (R053)
- Post-quantum crypto (ML-KEM/ML-DSA) — deferred to post-v1 (R040)
- Wi-Fi Aware transport — deferred to post-v1 (R041)
- OTel bridge for DiagnosticSink — deferred to post-v1 (R042)
- QR code safety number — deferred to post-v1 (R043)

---

## 19. Key Architectural Principles

1. **Library is the product.** No app framework, no platform lock-in. Clean import: `import ch.trancee.meshlink.api.MeshLink`.
2. **MeshEngine is the coordinator.** MeshLink is a thin lifecycle+FSM shell; all domain logic lives in MeshEngine.
3. **Constructor injection everywhere.** No global state, no event bus, no service locator.
4. **VirtualMeshTransport proves correctness.** All protocol logic is verified without BLE hardware. Hardware tests are UAT, not CI.
5. **One handshake path.** Noise XX only. No fallback complexity.
6. **Fail fast on startup.** If SecureStorage/crypto fails → throw immediately. No silent fallback.
7. **Silent drop for replay.** No diagnostic emission to avoid traffic analysis side-channel.
8. **Coverage as correctness tool.** 100% branch coverage is how you prove no dark code paths exist in a crypto protocol.
9. **BCV gates the public API.** Every PR that accidentally changes the public surface fails the build.
10. **Diagnostic emission is lazy.** `diagnosticSink.emit(code) { expensivePayload }` — lambda only evaluated if someone is listening.

---

## 20. File Naming & Organization

- Production packages mirror the architecture: `api/`, `wire/`, `transport/`, `routing/`, `transfer/`, `messaging/`, `power/`, `engine/`, `crypto/`, `storage/`
- Data classes with ByteArray fields always override `equals()`/`hashCode()` using `contentEquals()`
- Internal types stay internal. Platform-specific code in platform source sets. Pure-Kotlin helpers in commonMain even if initially needed for one platform (D028).
- `explicitApi()` enforced — everything is explicitly `public` or `internal`

---

## 21. How to Verify a Complete Build

```bash
# Full verification suite
./gradlew :meshlink:jvmTest :meshlink:koverVerify :meshlink:apiCheck :meshlink:detekt :meshlink:ktfmtCheck

# Expected: BUILD SUCCESSFUL, 1550+ tests pass, 100% coverage, no API diff

# Publish locally
./gradlew publishToMavenLocal
scripts/verify-publish.sh  # 33 artifacts verified

# iOS (macOS only)
./gradlew assembleMeshLinkReleaseXCFramework

# Benchmarks
./gradlew :meshlink:benchmark
```
