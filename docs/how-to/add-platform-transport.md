# How to Add a Platform Transport

## When to use this

You're implementing `BleTransport` for a new platform (e.g., macOS, Linux with BlueZ, or a test double).

## Steps

### 1. Understand the BleTransport interface

Located in `meshlink/src/commonMain/kotlin/ch/trancee/meshlink/transport/BleTransport.kt`:

```kotlin
internal interface BleTransport {
    val incomingData: SharedFlow<IncomingFrame>
    val peerDiscovered: SharedFlow<DiscoveredPeer>
    val peerLost: SharedFlow<LostPeer>
    val connectionStateChanged: SharedFlow<ConnectionStateEvent>

    suspend fun start(advertisementPayload: ByteArray)
    suspend fun stop()
    suspend fun sendFrame(destination: ByteArray, data: ByteArray): Boolean
    fun updateAdvertisementPayload(payload: ByteArray)
}
```

### 2. Create the platform source set

For a new platform (e.g., `macosMain`):

1. Add the target in `meshlink/build.gradle.kts`:
   ```kotlin
   macosArm64("macos")
   ```

2. Create the source directory:
   ```
   meshlink/src/macosMain/kotlin/ch/trancee/meshlink/transport/
   ```

### 3. Implement the transport

```kotlin
internal class MacosBleTransport(
    private val config: BleTransportConfig,
    private val powerTierFlow: StateFlow<PowerTier>,
) : BleTransport {

    private val _incomingData = MutableSharedFlow<IncomingFrame>(extraBufferCapacity = 64)
    override val incomingData: SharedFlow<IncomingFrame> = _incomingData

    // ... implement all 8 members
}
```

### 4. Key implementation requirements

- **Dual-role:** Simultaneously advertise (peripheral) AND scan (central)
- **L2CAP-first:** If the discovered peer's advertisement contains `l2capPsm != 0x00`, connect L2CAP directly. Fall back to GATT only on failure or `psm == 0x00`.
- **Advertisement payload:** 16 bytes. Encode via `AdvertisementCodec.encode()`. PSM at byte offset 3.
- **Connection initiation policy:** When both peers detect each other simultaneously, use `ConnectionInitiationPolicy` (FNV-1a hash tie-breaking from spec §4) to determine who initiates.
- **Frame encoding:** All frames through `L2capFrameCodec` (3-byte header: type + length).

### 5. Wire into MeshEngine

Add a platform factory in your source set:

```kotlin
public fun MeshLink.Companion.createMacos(config: MeshLinkConfig): MeshLink {
    // Internal wiring — creates Identity, CryptoProvider, Transport, Engine
}
```

### 6. Handle platform-specific lifecycle

- **Android:** Foreground service required for background BLE. See `MeshLinkService`.
- **iOS:** State Preservation/Restoration via `willRestoreState`. See `IosBleTransport`.
- **New platforms:** Document background/lifecycle requirements in your implementation.

### 7. Test against VirtualMeshTransport patterns

Your transport should pass the same behavioral tests that VirtualMeshTransport satisfies:
- `start()` → advertising begins + scanning begins
- Discovered peer → `peerDiscovered` emits
- `sendFrame()` → data arrives at peer's `incomingData`
- `stop()` → all connections closed, advertising stops

### 8. Handle Kover exclusions

Platform BLE code that calls native APIs (which can't run on JVM) needs Kover class exclusions:

```kotlin
kover {
    reports {
        filters {
            excludes {
                classes("ch.trancee.meshlink.transport.MacosBleTransport")
            }
        }
    }
}
```

All pure-Kotlin helpers (codec, policy, config) should remain in commonMain and achieve 100% coverage.

## Existing patterns to follow

- `AndroidBleTransport`: GATT server with 5-characteristic layout, L2CAP server socket, dual-role
- `IosBleTransport`: CBCentralManager + CBPeripheralManager, L2CAP channel, State P&R
- `VirtualMeshTransport`: Reference implementation for testing (no real BLE)
- `ConnectionInitiationPolicy`, `WriteLatencyTracker`, `MeshHashFilter`: Pure-Kotlin helpers in commonMain — reuse these
