# MeshLink Integration Guide

Version: 0.1.0  
Platforms: Android (API 26+), iOS 15+, JVM (for testing)

---

## Quick Start

### Android (Gradle)

Add the dependency to your module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("ch.trancee:meshlink:0.1.0")
}
```

> For JVM-only projects and unit tests use the `-jvm` classifier or rely on the KMP metadata
> artifact — Gradle resolves the correct variant automatically.

### iOS (Swift Package Manager)

Add the package in Xcode → **File › Add Package Dependencies** and paste:

```
https://github.com/trancee/meshlink
```

Or add it directly to your `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/trancee/meshlink", from: "0.1.0")
],
targets: [
    .target(name: "MyApp", dependencies: ["MeshLink"])
]
```

The SPM package resolves to a pre-built XCFramework — no Kotlin toolchain required on the iOS
developer's machine.

---

## Platform Setup

### Android

#### Manifest permissions

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />

<!-- Required for Android 11 and below -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

#### Runtime permission request (Android 12+)

```kotlin
// In your Activity or Fragment
val blePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
    )
} else {
    arrayOf(
        Manifest.permission.BLUETOOTH,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )
}
ActivityCompat.requestPermissions(this, blePermissions, REQUEST_CODE_BLE)
```

Only call `MeshLink.createAndroid()` after permissions are granted.

#### Creating a MeshLink instance

```kotlin
import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.api.createAndroid
import ch.trancee.meshlink.api.meshLinkConfig

val config = meshLinkConfig("com.example.myapp") {
    security { trustMode = TrustMode.STRICT }
    diagnostics { redactPeerIds = false }
}

// context: android.content.Context (Application or Activity context)
val mesh: MeshLink = MeshLink.createAndroid(context, config)
```

`createAndroid` wires `AndroidBleTransport`, Keystore-backed `AndroidSecureStorage`, and a real
`CryptoProvider`. Identity is loaded from secure storage on first call and reused on subsequent
calls — no extra key management needed.

#### Background operation (Foreground Service)

For reliable background BLE operation on Android 8+, wrap your `MeshLink` instance inside a
foreground service with `foregroundServiceType="connectedDevice"`. The reference app
(`meshlink-sample/`) demonstrates the pattern in `MeshLinkService.kt`.

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />

<service android:name=".MeshLinkService"
    android:foregroundServiceType="connectedDevice"
    android:exported="false" />
```

---

### iOS

#### Info.plist

```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>MeshLink uses Bluetooth to connect with nearby peers without internet.</string>

<key>UIBackgroundModes</key>
<array>
    <string>bluetooth-central</string>
    <string>bluetooth-peripheral</string>
</array>

<!-- Required for App Store review — strong encryption declaration -->
<key>ITSAppUsesNonExemptEncryption</key>
<true/>
```

> ⚠️ `ITSAppUsesNonExemptEncryption = YES` is required because MeshLink uses libsodium
> (ChaCha20-Poly1305, Ed25519, X25519). Your app must provide the ERN number during App Store
> Connect export compliance review.

#### Creating a MeshLink instance

```swift
import MeshLink

let config = MeshLinkConfigKt.meshLinkConfig(appId: "com.example.myapp") { builder in
    builder.security { $0.trustMode = .strict }
}
let mesh = MeshLink.companion.createIos(
    config: config,
    restorationIdentifier: "com.example.myapp.meshlink"  // matches your bundle ID
)
```

`createIos` wires `IosBleTransport`, Keychain-backed `IosSecureStorage`, and a real `CryptoProvider`.
The `restorationIdentifier` is passed to CoreBluetooth's state-restoration mechanism — use your
app's bundle ID to ensure pending connections are delivered to the correct process after a background
launch.

All `suspend fun` methods on `MeshLinkApi` are exposed as `async` Swift functions by SKIE.
All `Flow<T>` and `StateFlow<T>` properties are exposed as `AsyncStream<T>`.

---

### JVM (unit tests and server-side)

```kotlin
import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.api.meshLinkConfig

val config = meshLinkConfig("com.example.myapp") {}
val mesh = MeshLink(config)  // Uses NoOpBleTransport — no BLE hardware required
```

The JVM factory is suitable for unit tests and headless server-side usage. It uses
`InMemorySecureStorage` (keys are not persisted) and a no-op BLE transport.

For integration tests with linked virtual transports:

```kotlin
import ch.trancee.meshlink.testing.VirtualMeshTransport
import ch.trancee.meshlink.testing.linkedTransports

val (transportA, transportB) = linkedTransports()
val meshA = MeshLink.createForTest(config, transport = transportA)
val meshB = MeshLink.createForTest(config, transport = transportB)
// transportA and transportB exchange frames as if on real BLE
```

---

## API Overview

### Lifecycle

```kotlin
val mesh = MeshLink.createAndroid(context, config)  // UNINITIALIZED

mesh.start()    // UNINITIALIZED → RUNNING
mesh.pause()    // RUNNING → PAUSED
mesh.resume()   // PAUSED → RUNNING
mesh.stop()     // RUNNING → STOPPED (immediate)
mesh.stop(timeout = 5.seconds)  // drains in-flight transfers first
```

States: `UNINITIALIZED`, `RUNNING`, `PAUSED`, `STOPPED`, `RECOVERABLE`, `TERMINAL`.
`RECOVERABLE` means the engine hit a transient failure and can be restarted via `start()`.
`TERMINAL` is unrecoverable — create a new instance.

```kotlin
mesh.state.collect { state ->
    when (state) {
        MeshLinkState.RUNNING    -> showConnected()
        MeshLinkState.PAUSED     -> showPaused()
        MeshLinkState.RECOVERABLE -> promptRetry()
        else                     -> showOffline()
    }
}
```

### Sending messages

```kotlin
// Unicast — buffered for store-and-forward if peer is temporarily offline
mesh.send(
    recipient = peerId,   // ByteArray (12-byte key hash)
    payload = "hello".encodeToByteArray(),
    priority = MessagePriority.NORMAL,
)

// Broadcast — signed Ed25519, deduplicated, TTL flood-fill
mesh.broadcast(
    payload = "ping".encodeToByteArray(),
    maxHops = 3,
)
```

### Receiving messages

```kotlin
// Collect in a coroutine scope tied to your ViewModel or Service lifecycle
lifecycleScope.launch {
    mesh.messages.collect { msg ->
        val text = msg.payload.decodeToString()
        val sender = msg.senderId.toHex()
        showMessage(sender, text)
    }
}

// Delivery confirmations
lifecycleScope.launch {
    mesh.deliveryConfirmations.collect { msgId ->
        markDelivered(msgId)
    }
}
```

### Peer events

```kotlin
lifecycleScope.launch {
    mesh.peers.collect { event ->
        when (event) {
            is PeerEvent.Found -> addPeer(event.id, event.detail)
            is PeerEvent.Lost  -> removePeer(event.id)
        }
    }
}
```

### Health and diagnostics

```kotlin
// Current snapshot (instant, no suspend)
val health = mesh.meshHealth()
println("${health.connectedPeers} peers, ${health.routingTableSize} routes")

// Periodic snapshots (emitted every DiagnosticsConfig.healthSnapshotIntervalMillis)
lifecycleScope.launch {
    mesh.meshHealthFlow.collect { snapshot ->
        updateHealthBar(snapshot)
    }
}

// Structured diagnostic events (all 27 DiagnosticCode values)
lifecycleScope.launch {
    mesh.diagnosticEvents.collect { event ->
        log("${event.code} [${event.severity}] ${event.payload}")
    }
}
```

### Power management

```kotlin
// Report battery state — drives automatic tier selection
mesh.updateBattery(percent = 0.45f, isCharging = false)

// Pin to a fixed tier (pass null to restore automatic mode)
mesh.setCustomPowerMode(PowerTier.POWER_SAVER)
mesh.setCustomPowerMode(null)  // restore automatic
```

### Identity and trust

```kotlin
// Local identity (available after start())
val fingerprint = mesh.localPublicKey.toHex()

// Key-change events (TOFU/STRICT resolution)
lifecycleScope.launch {
    mesh.keyChanges.collect { event ->
        // Show user: peer event.peerId changed its key
        if (userConfirms()) mesh.acceptKeyChange(event.peerId)
        else                mesh.rejectKeyChange(event.peerId)
    }
}

// Rotate local identity
mesh.rotateIdentity()  // issues signed RotationAnnouncement to all peers
```

### GDPR

```kotlin
// Erase all stored data for a peer
mesh.forgetPeer(peerId)

// Full wipe — engine must be stopped first
mesh.stop()
mesh.factoryReset()
```

---

## Configuration Reference

```kotlin
val config = meshLinkConfig("com.example.myapp") {
    messaging {
        maxMessageSize    = 102_400    // bytes; default 100 KB
        bufferCapacity    = 1_048_576  // bytes; default 1 MB
        broadcastTtl      = 2          // default hop count for broadcasts
        maxBroadcastSize  = 10_000     // bytes
    }
    transport {
        mtu               = 517        // BLE ATT MTU; default 517
        l2capEnabled      = true       // prefer L2CAP CoC over GATT; default true
        forceL2cap        = false      // never fall back to GATT
        forceGatt         = false      // always use GATT
        bootstrapDurationMillis = 30_000L  // stay in PERFORMANCE tier on start
        l2capRetryAttempts = 3         // L2CAP connection retries before GATT fallback
        advertisementIntervalMillis = 250L  // BLE ad interval (ms); subject to region clamping
        scanDutyCyclePercent = 100     // scan duty cycle %; subject to region clamping
    }
    security {
        requireEncryption = true       // reject plaintext; default true
        trustMode         = TrustMode.STRICT  // reject unknown keys; PROMPT prompts
        requireBroadcastSignatures = true
        keyChangeTimeoutMillis = 30_000L  // timeout for PROMPT trust mode resolution
        ackJitterMaxMillis = 500L      // max random jitter added to ACK timing
    }
    power {
        evictionGracePeriodMillis = 30_000L
        powerModeThresholds {
            performanceThreshold = 0.80f   // above this → PERFORMANCE
            powerSaverThreshold  = 0.30f   // below this → POWER_SAVER
        }
    }
    routing {
        maxHops           = 10         // max relay hops; clamped to [1, 20]
        routeCacheTtlMillis = 210_000L
        dedupCapacity     = 25_000     // clamped to [1000, 50000]
        maxMessageAgeMillis = 2_700_000L  // discard messages older than this
    }
    diagnostics {
        enabled           = true
        bufferCapacity    = 1_000      // max buffered diagnostic events
        redactPeerIds     = false      // set true for GDPR: truncates peer IDs to 8 chars
        healthSnapshotIntervalMillis = 5_000L
    }
    rateLimiting {
        maxSends          = 60         // unicast messages/minute
        broadcastLimit    = 10         // broadcasts/minute
        handshakeLimit    = 1          // concurrent handshakes/peer/minute
    }
    transfer {
        chunkInactivityTimeout    = 30_000L  // ms before transfer times out
        maxConcurrentTransfers    = 4
        ackTimeoutMultiplier      = 1.0f   // multiplier for adaptive ACK timeout
        degradationThreshold      = 0.5f   // link quality below this triggers degradation
    }
    region(RegulatoryRegion.EU)  // ETSI EN 300 328: ad interval ≥ 300 ms, scan duty ≤ 70%
}
```

**Built-in presets:**

```kotlin
MeshLinkConfig.smallPayloadLowLatency("com.example.chat")     // text chat
MeshLinkConfig.largePayloadHighThroughput("com.example.files") // file transfer
MeshLinkConfig.minimalResourceUsage("com.example.iot")         // IoT/wearable
MeshLinkConfig.sensorTelemetry("com.example.sensors")          // tiny payloads
```

---

## Reference App

`meshlink-sample/` contains a complete Compose Multiplatform reference application with four
screens: **Chat**, **Mesh Visualizer**, **Diagnostics**, and **Settings**. It uses
`MeshLink.createAndroid` / `MeshLink.createIos` via the `createPlatformMeshLink` expect/actual
pattern and demonstrates the full integration pattern including Android runtime BLE permission
requests and iOS Info.plist setup.

Build and install on Android:

```bash
./gradlew :meshlink-sample:assembleDebug
adb install meshlink-sample/build/outputs/apk/debug/*.apk
```

Build for iOS: open `iosApp/MeshLinkSample.xcodeproj` in Xcode 15+, select your device, and run.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| `UNINITIALIZED` state on Settings screen (Android) | BLE permissions not granted | Request `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` at runtime before calling `MeshLink.createAndroid` |
| No peers discovered after 60 s | App-ID mismatch | Both devices must use the same `appId` string in `meshLinkConfig` |
| iOS app shows no peers | Background modes missing | Add `bluetooth-central` + `bluetooth-peripheral` to `UIBackgroundModes` in Info.plist |
| `IllegalStateException: start() called from invalid state` | Called `start()` from wrong state | Check `mesh.state.value` before calling `start()`; only valid from `UNINITIALIZED` or `RECOVERABLE` |
| `CONFIG_CLAMPED` diagnostic events on start | Config field out of allowed range | Check `config.clampWarnings` list after `meshLinkConfig { }` returns |
| Messages not delivered | Peer is offline, or buffer full | Messages are buffered up to `bufferCapacity` bytes; check `MeshHealthSnapshot.bufferUtilizationPercent` |
| Xcode: `MeshLink not found` in SPM resolve | Checksum mismatch | Delete DerivedData, re-resolve SPM package. If building from source, run `./gradlew :meshlink:assembleMeshLinkXCFramework` first |

---

## Architecture & Implementation Notes

This section documents key architectural decisions, patterns, and gotchas discovered during
implementation. It is intended as guidance for greenfield implementors of the same specification.

### Module Structure

```
:meshlink            — Main library module (KMP: commonMain, androidMain, iosMain, jvmMain)
:meshlink-testing    — Published test utilities (re-exports from :meshlink's testing package)
:meshlink-sample     — Compose Multiplatform reference app (androidApp + shared + iosApp)
```

The `:meshlink-testing` module declares `api(project(":meshlink"))` — all public types from
`:meshlink` are transitively available. The testing utilities (`VirtualMeshTransport`,
`linkedTransports()`, `createForTest()`) live in `:meshlink`'s source under
`ch.trancee.meshlink.testing` because they need access to `internal` types (`BleTransport`).
The `:meshlink-testing` module serves only as a separate publication coordinate.

### Internal vs Public API Boundary

`BleTransport` and all transport event types (`AdvertisementEvent`, `IncomingData`, `PeerLostEvent`,
`SendResult`) are `internal`. Platform transports (`AndroidBleTransport`, `IosBleTransport`) are
internal to `:meshlink`. External modules cannot instantiate them directly.

**Consequence for test utilities:** `VirtualMeshTransport` uses a **composition pattern** — a
`public` outer class holding a `private inner class TransportImpl : BleTransport`. The public
class exposes only test-facing methods (`linkTo`, `simulateDiscovery`, `injectRawIncoming`).
The internal `BleTransport` delegate is accessed via an `internal val transport` property,
which `createForTest()` passes to `MeshLink.create()`.

**Why not make BleTransport public?** It leaks low-level BLE semantics (L2CAP frames, GATT
writes, raw advertisements) that consumers should never interact with. The public API surface
is `MeshLinkApi` — lifecycle, messages, peers, health.

### Engine Composition

`MeshEngine` is the internal coordinator. It composes all subsystems:

```
MeshEngine
├── DeliveryPipeline (message routing, relay, store-and-forward)
│   ├── RouteCoordinator (route management, outbound frame emission)
│   │   ├── RoutingEngine (Babel-inspired DV with feasibility)
│   │   └── RoutingTable
│   ├── TransferEngine (chunked transfer, ACK, timeout)
│   │   └── TransferScheduler (priority queue)
│   └── ReplayGuard (sliding-window nonce dedup)
├── NoiseHandshakeManager (Noise XX lifecycle per peer)
├── PresenceTracker (peer lifecycle FSM: Connected → Disconnected → Gone)
├── PowerManager (tier selection, bootstrap, battery)
│   └── PowerTierController
├── PseudonymRotator (HMAC-based BLE address rotation)
├── TrustStore (TOFU/STRICT/PROMPT key pinning)
├── Identity (Ed25519 + X25519 keypair, rotation)
└── DiagnosticSink (ring-buffer event emission)
```

`MeshLink` is a thin shell over `MeshEngine` — it owns the lifecycle FSM (`MeshLinkState`),
maps internal event types to public API types, and guards method calls with state checks.

### Key Architectural Decisions

| Decision | Choice | Why |
|----------|--------|-----|
| Noise handshake | XX only (no IK) | One code path eliminates wrong-key fallback race; 60-80ms reconnect overhead acceptable |
| Wire format | Pure-Kotlin FlatBuffers codec | `flatbuffers-java` is JVM-only — breaks iOS KMP compilation if in commonMain |
| Crypto provider | libsodium via thin JNI (Android) / cinterop (iOS) | No runtime deps; custom bindings are ~200 lines each; platform parity |
| Routing | Babel-inspired distance-vector with feasibility condition | RFC 8966 proven; monotonic seqNo prevents count-to-infinity |
| Cut-through relay | Byte-level FlatBuffer surgery on chunk 0 | Cannot re-encode multi-chunk messages (only chunk 0 available at relay) |
| Power management | 3-tier (PERFORMANCE/BALANCED/POWER_SAVER) + bootstrap override | Automatic tier selection from battery %; bootstrap override prevents thrashing on start |
| Peer lifecycle | 3-state FSM in PresenceTracker (Connected/Disconnected/Gone) | Two-sweep eviction prevents premature removal during brief disconnections |
| Pseudonym rotation | HMAC-SHA256(keyHash, epoch_counter)[0:12] | Platform-agnostic (commonMain); timer/stagger in PseudonymRotator |
| Public API events | `keyChanges` wired via `MutableSharedFlow` with `tryEmit` | `onKeyChange` callback is synchronous (TrustStore context); `tryEmit` is non-suspending |
| Testing module | Composition pattern (public wrapper + internal delegate) | Kotlin `explicitApi()` prevents public class from exposing internal interface types |

### Conventions

| Convention | Rule |
|------------|------|
| Time fields | Use `Millis` suffix, never `Ms`. Example: `keyChangeTimeoutMillis`, not `keyChangeTimeoutMs` |
| Config clamping | Out-of-range values are clamped silently; warnings are collected in `config.clampWarnings` |
| ByteArray equality | All data classes with `ByteArray` fields override `equals`/`hashCode` using `contentEquals`/`contentHashCode` |
| Kover coverage | 100% line + branch on commonMain. Platform source sets excluded (require real hardware). Test infrastructure excluded by package. |
| BCV (Binary Compatibility) | `explicitApi()` enforced. API baseline committed. `apiCheck` runs on every PR. |
| Diagnostic codes | 27 codes: 4 critical, 8 threshold, 15 log. All emitted via `DiagnosticSink.emit(code) { payload }`. |
| Sealed interfaces | All implementing classes must be in the **same package** (not just same module) — Kotlin sealed interface rule. |

### Gotchas for Greenfield Implementors

1. **AGP 9.0 + KMP incompatibility.** AGP 9.0+ blocks `com.android.library` combined with
   `org.jetbrains.kotlin.multiplatform`. Use `com.android.kotlin.multiplatform.library` for
   the library module. For application modules, add `android.builtInKotlin=false` and
   `android.newDsl=false` to the **root** `gradle.properties` (subproject-level doesn't work).

2. **PowerManager launches coroutines immediately on `MeshEngine.create()`.** The battery-poll
   loop (`while(true) { delay(batteryPollIntervalMillis) }`) and bootstrap timer start on the
   engine scope at construction time — not on `start()`. Any `advanceUntilIdle()` in tests while
   the engine is alive will loop forever through virtual time. Always call `engine.stop()` before
   `advanceUntilIdle()`.

3. **flatbuffers-java is JVM-only.** It has no Kotlin/Native variant. Placing it in commonMain
   dependencies breaks iOS compilation. The wire codec must be a pure-Kotlin implementation of
   the FlatBuffers binary format.

4. **Kotlin sealed interfaces require same-package implementations.** Classes in a sub-package
   (e.g. `wire.messages`) cannot implement a sealed interface declared in `wire`. All
   implementations must share the same `package` declaration.

5. **`explicitApi()` and internal interface composition.** A `public` class implementing an
   `internal` interface cannot have its override members be public (Kotlin visibility rules). Use
   the composition pattern: public outer class with a `private inner class` implementing the
   internal interface, exposed via an `internal val`.

6. **CutThroughBuffer performs byte-level FlatBuffer surgery.** For multi-chunk relay, only
   chunk 0 contains the routing header. You cannot re-encode the full `RoutedMessage` — instead,
   decrement `hopLimit` in-place and append to `visitedList` by shifting bytes and updating
   all `uoffset_t` fields after the insertion point.

7. **GitHub Actions tag-push checkout.** When a tag push triggers a workflow, the runner starts
   in detached HEAD state. A job that needs to commit back to `main` must use `fetch-depth: 0`.
   Files modified between tag creation and `git checkout main` must be saved to `/tmp` and
   restored afterward.

8. **ktfmt formatting is non-obvious.** `kotlinLangStyle()` enforces specific blank-line rules
   and trailing-comma placement that differ from hand-formatting. Always run `ktfmtFormat` after
   creating new Kotlin files — don't rely on manual formatting to match.

9. **`stop()` accepts a `Duration` parameter.** `stop()` (zero duration) immediately terminates.
   `stop(timeout = 5.seconds)` attempts graceful drain of in-flight transfers first. The default
   is `Duration.ZERO` — immediate stop.

10. **RegulatoryRegion clamping.** When `region(RegulatoryRegion.EU)` is set, the config builder
    automatically clamps `advertisementIntervalMillis` to ≥ 300 and `scanDutyCyclePercent` to
    ≤ 70 (ETSI EN 300 328). Clamped fields appear in `config.clampWarnings`.

### Flow Architecture

All public reactive surfaces on `MeshLinkApi` follow the same pattern:

```
Internal subsystem (SharedFlow/MutableSharedFlow)
    ↓ exposed as val on MeshEngine
        ↓ .map { internalType → publicType } in MeshLink
            ↓ exposed as Flow<PublicType> on MeshLinkApi
```

Consumers collect cold `Flow` instances. The underlying `SharedFlow` has `extraBufferCapacity`
to avoid backpressure blocking internal subsystems. `keyChanges` uses `tryEmit` (non-suspending)
because the emission site is a synchronous callback from `TrustStore`.

### Testing Strategy

| Layer | How tested |
|-------|-----------|
| Crypto primitives | Wycheproof test vectors (ECDH, Ed25519, AEAD, HMAC, HKDF) |
| Protocol logic (routing, transfer, messaging) | Unit tests with `VirtualMeshTransport` in commonTest |
| Engine integration | Multi-node `MeshTestHarness` with full Noise XX handshake |
| Public API | `MeshLinkTest` verifying state transitions, flow emission, error guards |
| Platform BLE | Excluded from unit tests (require hardware). Proven by S06 two-device UAT |
| Coverage | 100% Kover on commonMain. Platform and testing packages excluded. |

The `MeshTestHarness` builds N-node topologies with configurable link properties (packet loss,
latency, RSSI). It supports full Noise XX handshakes between nodes and virtual-time advancement
for timeout testing.

For published test utilities (consumer-facing):

```kotlin
// In your test:
val (transportA, transportB) = linkedTransports()
val meshA = MeshLink.createForTest(config, transport = transportA)
val meshB = MeshLink.createForTest(config, transport = transportB)
meshA.start()
meshB.start()
// Send from A, receive on B — no BLE hardware needed
```
