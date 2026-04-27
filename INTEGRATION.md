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
mesh.stop()     // RUNNING → STOPPED
mesh.stop(gracefulDrainTimeout)  // drains in-flight transfers first
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

// Periodic snapshots (emitted every DiagnosticsConfig.healthSnapshotIntervalMs)
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
        l2capEnabled      = true       // prefer L2CAP CoC over GATT; default true
        forceL2cap        = false      // never fall back to GATT
        forceGatt         = false      // always use GATT
        bootstrapDurationMs = 30_000L  // stay in PERFORMANCE tier on start
    }
    security {
        requireEncryption = true       // reject plaintext; default true
        trustMode         = TrustMode.STRICT  // reject unknown keys; PROMPT prompts
        requireBroadcastSignatures = true
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
    }
    diagnostics {
        enabled           = true
        redactPeerIds     = false      // set true for GDPR: truncates peer IDs to 8 chars
        healthSnapshotIntervalMs = 5_000L
    }
    rateLimiting {
        maxSends          = 60         // unicast messages/minute
        broadcastLimit    = 10         // broadcasts/minute
    }
    transfer {
        chunkInactivityTimeout    = 30_000L  // ms before transfer times out
        maxConcurrentTransfers    = 4
    }
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

Build for iOS: open `MeshLinkSample/MeshLinkSample.xcodeproj` in Xcode 15+, select your device, and run.

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
