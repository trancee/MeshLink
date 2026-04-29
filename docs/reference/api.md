# API Reference

## MeshLinkApi

The public interface implemented by `MeshLink`. All methods are safe to call from any thread.

### Lifecycle

| Method | Signature | Description |
|--------|-----------|-------------|
| `start` | `fun start()` | Begin advertising, scanning, and accepting connections. Transitions state to `Starting` → `Running`. |
| `stop` | `fun stop(timeout: Duration = Duration.ZERO)` | Teardown. If `timeout > 0`, blocks new operations and awaits in-flight transfers before teardown. `Duration.ZERO` = immediate. |

### Messaging

| Method | Signature | Description |
|--------|-----------|-------------|
| `send` | `suspend fun send(recipient: PeerIdHex, data: ByteArray): SendResult` | E2E encrypted unicast via Babel routing. Noise K sealed. Chunked if > MTU. |
| `broadcast` | `fun broadcast(data: ByteArray)` | Flood-fill to all reachable peers. Ed25519 signed. TTL-bounded. |

### Peer Management

| Method | Signature | Description |
|--------|-----------|-------------|
| `peerDetail` | `fun peerDetail(peerId: PeerIdHex): PeerDetail?` | Live peer info (state, connection time, route metric). Null if unknown. |
| `allPeerDetails` | `fun allPeerDetails(): List<PeerDetail>` | All known peers with their current state. |
| `peerFingerprint` | `fun peerFingerprint(peerId: PeerIdHex): String` | 12-digit safety number from Ed25519 public key. |
| `forgetPeer` | `fun forgetPeer(peerId: PeerIdHex)` | Clears trust, routing, and delivery state for the peer. |
| `acceptKeyChange` | `fun acceptKeyChange(peerId: PeerIdHex)` | Accept a pending TOFU key change (Prompt mode). |
| `rejectKeyChange` | `fun rejectKeyChange(peerId: PeerIdHex)` | Reject a pending TOFU key change. |

### Identity

| Method | Signature | Description |
|--------|-----------|-------------|
| `rotateIdentity` | `fun rotateIdentity()` | Generate new keypair, broadcast RotationAnnouncement signed by old key. |
| `localPeerId` | `val localPeerId: PeerIdHex` | This device's peer ID (hex-encoded key hash). |

### Observability

| Method | Signature | Description |
|--------|-----------|-------------|
| `peerEvents` | `val peerEvents: Flow<List<PeerEvent>>` | Found, Lost, StateChanged, KeyChangeDetected events. |
| `incomingMessages` | `val incomingMessages: Flow<InboundMessage>` | Decrypted messages from peers. |
| `diagnosticEvents` | `val diagnosticEvents: Flow<DiagnosticEvent>` | Protocol events (27 codes, 3 severity tiers). |
| `meshHealthFlow` | `val meshHealthFlow: Flow<MeshHealthSnapshot>` | Periodic health snapshots. |
| `meshHealth` | `fun meshHealth(): MeshHealthSnapshot` | Point-in-time health read. |
| `routingSnapshot` | `fun routingSnapshot(): RoutingSnapshot` | Current routing table state. |

### Power & System

| Method | Signature | Description |
|--------|-----------|-------------|
| `updateBattery` | `fun updateBattery(level: Float, isCharging: Boolean)` | Report battery state. Drives power tier transitions. |
| `setCustomPowerMode` | `fun setCustomPowerMode(tier: PowerTier?)` | Override automatic tier. Null = return to automatic. |
| `shedMemoryPressure` | `fun shedMemoryPressure()` | Aggressively free internal buffers. |
| `factoryReset` | `fun factoryReset()` | Wipe all state: identity, trust, routes, storage. |

---

## MeshLinkConfig

DSL builder with 8 sub-configs:

```kotlin
val config = MeshLinkConfig {
    appId = "com.example.myapp"           // Required. Mesh isolation key.
    trust { mode = TrustMode.PROMPT }
    power { lowBatteryThreshold = 0.15f }
    transport { maxConnections = 7 }
    routing { helloIntervalMillis = 4000L }
    transfer { maxChunkSize = 512 }
    messaging { rateLimitPerSecond = 10 }
    diagnostics { bufferSize = 256 }
    regulatoryRegion = RegulatoryRegion.DEFAULT  // or .EU
}
```

### Presets

| Preset | Use case |
|--------|----------|
| `MeshLinkConfig.default()` | General purpose |
| `MeshLinkConfig.lowPower()` | Battery-constrained devices |
| `MeshLinkConfig.highThroughput()` | Charging/plugged-in, max performance |
| `MeshLinkConfig.minimal()` | Testing, minimal resource usage |

---

## MeshLinkState

Lifecycle FSM states:

```
Idle → Starting → Running → Stopping → Stopped
                     ↑                      │
                     └──────────────────────┘  (restart)
```

Oscillation bounds prevent rapid start/stop cycling.

---

## SendResult

```kotlin
sealed interface SendResult {
    data class Queued(val messageId: MessageId) : SendResult
    data class Failed(val reason: FailureReason) : SendResult
}
```

---

## PeerEvent

```kotlin
sealed interface PeerEvent {
    data class Found(val peerId: PeerIdHex) : PeerEvent
    data class Lost(val peerId: PeerIdHex) : PeerEvent
    data class StateChanged(val peerId: PeerIdHex, val state: PeerState) : PeerEvent
    data class KeyChangeDetected(val peerId: PeerIdHex, ...) : PeerEvent
}
```

---

## PeerState

```kotlin
enum class PeerState { CONNECTED, DISCONNECTED }
```

Note: `GONE` is not a public state — Gone peers are evicted and emit `PeerEvent.Lost`.

---

## PeerIdHex

Value class wrapping a hex-encoded key hash string. Used throughout the public API instead of raw `ByteArray` or `String`.

```kotlin
@JvmInline
value class PeerIdHex(val hex: String)
```
