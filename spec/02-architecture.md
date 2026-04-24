# 02 — Architecture & Cross-Platform Strategy

> **Covers:** §3 (Architecture & Cross-Platform Strategy)
> **Dependencies:** `01-vision-and-domain-model.md` (terminology)
> **Key exports:** Module layout, engine/coordinator pattern, extension points, project bootstrap sequence, crypto library strategy
> **Changes from original:** A14 (added §5 Project Bootstrap with prek pre-commit hooks as first task)

---

## 1. Why Kotlin Multiplatform (KMP)

~85% shared code in `commonMain`; only BLE hardware + secure storage (~15%) are platform-specific.

**Benefits:**
- Single protocol implementation prevents drift across Android/iOS
- Platform layers are thin — post lightweight value-type events through `BleTransport` (the only FFI surface)
- No cross-FFI exceptions, no retain cycles, no lifecycle coupling
- Libsodium is sole crypto dependency (bundled per platform)
- Constructor injection via `createComponents()` — no DI frameworks

## 2. Module Layout

### KMP Target Declarations

```kotlin
kotlin {
    androidTarget()
    iosArm64()
    iosSimulatorArm64()
    jvm()  // Test/build only — commonTest, Kover, benchmarks
}
```

### Shared Code (`commonMain/`)

```
config/        Configuration data class + builder + presets + validation
crypto/        Noise XX/K state machines, CryptoProvider interface,
               libsodium bindings (expect/actual), TrustStore, ReplayGuard, SecurityEngine
delivery/      DeliveryPipeline — dedup, buffering, store-and-forward
diagnostics/   DiagnosticSink, MeshHealthSnapshot, MeshStateManager
dispatch/      InboundValidator (type dispatch + pre-handshake gate),
               OutboundTracker (delivery timeout tracking)
engine/        MeshEngine (pipeline orchestrator), IdentityManager,
               TransportSender, MeshLinkComponents
model/         Public API types: Message, PeerEvent, SendResult,
               TransferProgress, TransferFailure, KeyChangeEvent, etc.
peer/          PeerLifecycleManager, PeerQueryService
power/         PowerManager, PowerModeEngine, PowerProfile, BatteryMonitor,
               ConnectionLimiter, GracefulDrainManager, TieredShedder
protocol/      ProtocolVersion
routing/       RoutingEngine, RoutingTable, RouteCoordinator,
               RouteCostCalculator, DedupSet, PresenceTracker
send/          SendPolicyChain, BroadcastPolicyChain
storage/       SecureStorage interface, InMemorySecureStorage
transfer/      TransferEngine, TransferSession, TransferScheduler,
               ObservationRateController, ChunkSizePolicy, CutThroughBuffer,
               SackTracker, ResumeCalculator
transport/     BleTransport interface, L2capChannel interface, L2capManager,
               L2capFrameCodec, L2capBackpressureDetector, GattConstants,
               GattPeerIdFraming, BleAutoRecovery, ScanDutyCycleController,
               PermissionChecker
util/          AppIdFilter, BufferEvictionPolicy, CircuitBreaker,
               RateLimiter, PauseManager, DrainController,
               Zeroize, HexUtil, ConstantTimeEquals, etc.
wire/          WireConstants, WireHelpers,
               FlatBuffers codecs (Handshake, Chunk, Broadcast, RoutedMessage,
               DeliveryAck, Nack, ResumeRequest, Hello, Update,
               Keepalive, ChunkAck),
               RotationAnnouncement, AdvertisementCodec
```

### Platform-Specific Source Sets

```
androidMain/   AndroidBleTransport, AndroidL2capChannel, AndroidSecureStorage,
               AndroidCryptoProvider (libsodium JNI), MeshLinkService (foreground service),
               BatteryMonitor

iosMain/       IosBleTransport, IosL2capChannel, IosSecureStorage (Keychain),
               IosCryptoProvider (libsodium cinterop), MeshLinkFactory,
               StatePreservation, BatteryMonitor (UIDevice)
```

`iosArm64Main/` and `iosSimulatorArm64Main/` are typically empty — `iosMain/` intermediate source set covers both targets.

`jvmMain/` is empty or minimal — JVM exists only for test execution, not as a shipping platform.

## 3. Module Interaction Model

Strict **engine/coordinator** pattern with unidirectional data flow:

| Category | Modules | Responsibility |
|----------|---------|----------------|
| **Engines** | SecurityEngine, RoutingEngine, TransferEngine, DeliveryPipeline | Own domain state. Methods return **sealed result types** (exhaustive handling). Hot-path results use `@JvmInline value class`. |
| **Coordinators** | PowerManager, RouteCoordinator, PeerLifecycleManager | Orchestrate multi-step workflows across engines. |
| **Message Pipeline** | MeshEngine (+ SendPolicyChain, BroadcastPolicyChain, InboundValidator) | Pre-flight rules, inbound dispatch, outbound decisions. |
| **Lifecycle Shell** | MeshLink | Thin wiring layer (~420 lines). Connects engines, coordinators, transport. **No business logic.** |

**Rules:**
- No module sends messages directly to another — MeshLink is the sole wiring layer
- All mutable state confined to a single coroutine scope per engine — no locks, no shared mutable state
- CPU-intensive crypto offloaded to a dedicated `cryptoDispatcher`
- **All IDs and UUIDs must be compared as raw bytes — never convert to String for comparison.** Key Hashes (12 bytes), Message IDs (16 bytes), public keys (32/64 bytes), and any other binary identifier are compared using constant-time byte equality (`ConstantTimeEquals`) or plain `ByteArray.contentEquals()`. String conversion (hex, Base64, UUID formatting) is reserved for diagnostics, logging, and human-facing display — never for identity resolution, dedup checks, routing lookups, or equality tests. Rationale: string conversion is allocation-heavy, locale-sensitive (case folding), and destroys constant-time guarantees needed for cryptographic identifiers.

## 4. Extension Points (`expect`/`actual` Boundaries)

Three interfaces are the only FFI surfaces, bridged via Kotlin's `expect`/`actual` mechanism:

### `BleTransport`
BLE hardware abstraction. Platform implementations handle advertising, scanning, GATT server/client, raw byte I/O.
`actual` classes: `AndroidBleTransport`, `IosBleTransport`. Tests use `VirtualMeshTransport` (in-memory simulation). Detail in `03-transport-ble.md`.

### `CryptoProvider`
Cross-platform crypto: Ed25519 sign/verify, X25519 key agreement, ChaCha20-Poly1305 AEAD, SHA-256, HKDF-SHA256.
`actual` classes: `AndroidCryptoProvider` (libsodium JNI), `IosCryptoProvider` (libsodium cinterop). Detail in `05-security-encryption.md`.

### `SecureStorage`
Platform key-value storage for identity keys, trust pins, replay counters.
`actual` classes: `AndroidSecureStorage` (Android Keystore), `IosSecureStorage` (Keychain). Detail in `06-security-identity-trust.md`.

## 5. Project Bootstrap (First Tasks)

Before any feature code, the project skeleton and quality gates must be in place. Implementation should begin with these tasks **in order**:

1. **Gradle KMP project skeleton** — `settings.gradle.kts`, root `build.gradle.kts`, `meshlink/build.gradle.kts` with all four targets (`androidTarget`, `iosArm64`, `iosSimulatorArm64`, `jvm`), version catalog (`libs.versions.toml`), and empty source sets that compile successfully.

2. **Pre-commit hooks via prek** — Install [`prek`](https://prek.j178.dev) and configure `prek.toml` with:
   - **ktfmt** — deterministic Kotlin formatting (`kotlinLangStyle`) via the `com.ncorti.ktfmt.gradle` plugin. Runs `./gradlew :meshlink:ktfmtCheck` on staged `.kt` files.
   - **detekt** — static analysis via `detekt.yml` ruleset. Runs `./gradlew :meshlink:detekt` on staged `.kt` files.
   - Built-in hooks: `trailing-whitespace`, `end-of-file-fixer`, `check-added-large-files`, `check-merge-conflict`.

   ```sh
   prek install          # One-time: installs git shims under .git/hooks/
   prek run --all-files  # Verify all hooks pass
   ```

   **Rationale:** Formatting and lint gates must be established before any code is written. Every commit from the first line of production code onward must pass these checks. Retrofitting formatting onto an existing codebase creates noisy diffs that obscure real changes.

3. **Kover coverage enforcement** — Configure Kover with 100% line and 100% branch coverage thresholds. The first production file and its tests must pass `./gradlew :meshlink:koverVerify` before proceeding.

4. **Utility foundations** — `ConstantTimeEquals`, `HexUtil`, `Zeroize`, `ByteArrayExt`, `AppIdFilter`, `ByteArrayKey`. These are dependencies of nearly every other module.

5. **CryptoProvider interface** + `JvmTestCryptoProvider` — The test crypto provider enables all subsequent work without platform bindings.

6. **Wire format codecs** — All 12 message types + `AdvertisementCodec`. Everything above the wire layer depends on these.

After these six bootstrap tasks, feature implementation can proceed in any topological order consistent with the dependency graph.

## 6. Crypto Library Strategy

**All platforms use libsodium as the sole crypto provider.**

| Platform | `actual` class | Binding | Acceleration | Bundle |
|----------|---------------|---------|-------------|--------|
| Android (API 29+) | `AndroidCryptoProvider` | JNI (precompiled `.so`: arm64-v8a, armeabi-v7a, x86_64) | ARM Crypto Extensions | ~800KB in AAR |
| iOS | `IosCryptoProvider` | Kotlin/Native cinterop (static `.a`) | ARM Crypto Extensions | In XCFramework |

Noise Protocol state machines (XX + K) are in shared Kotlin code on top of `CryptoProvider`.

**Build-time verification:** CI pins libsodium source tarball SHA-256 and verifies before compilation.

**Why libsodium over platform-native APIs:**
- Single audited implementation — identical behavior everywhere
- Constant-time guarantees (verified on real hardware)
- Complete primitive coverage without platform version gating
- `sodium_memzero()` for key material (not reliant on GC)
