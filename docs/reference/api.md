# API Reference

## MeshLinkApi

The public interface implemented by `MeshLink`. All methods are thread-safe.

### Lifecycle

| Method | Signature | Description |
|--------|-----------|-------------|
| `start` | `suspend fun start()` | Begin advertising, scanning, and accepting connections. |
| `stop` | `suspend fun stop(timeout: Duration = Duration.ZERO)` | Teardown. If `timeout > 0`, awaits in-flight transfers before closing. |

### Messaging

| Method | Signature | Description |
|--------|-----------|-------------|
| `send` | `suspend fun send(recipient: ByteArray, payload: ByteArray, priority: MessagePriority = NORMAL)` | E2E encrypted unicast via Babel routing. Noise K sealed. Chunked if > MTU. |
| `broadcast` | `suspend fun broadcast(payload: ByteArray, maxHops: Int, priority: MessagePriority = NORMAL)` | Flood-fill to all reachable peers. Ed25519 signed. TTL-bounded by `maxHops`. |

### Peer Management

| Method | Signature | Description |
|--------|-----------|-------------|
| `peerDetail` | `fun peerDetail(id: ByteArray): PeerDetail?` | Live peer info (state, route metric). Null if unknown. |
| `allPeerDetails` | `fun allPeerDetails(): List<PeerDetail>` | All known peers with current state. |
| `peerFingerprint` | `fun peerFingerprint(id: ByteArray): String?` | Safety number from Ed25519 public key. |
| `forgetPeer` | `suspend fun forgetPeer(peerId: ByteArray)` | Clears trust, routing, and delivery state for the peer. |
| `acceptKeyChange` | `suspend fun acceptKeyChange(peerId: ByteArray)` | Accept a pending TOFU key change (Prompt mode). |
| `rejectKeyChange` | `suspend fun rejectKeyChange(peerId: ByteArray)` | Reject a pending TOFU key change. |

### Identity

| Method | Signature | Description |
|--------|-----------|-------------|
| `rotateIdentity` | `suspend fun rotateIdentity()` | Generate new keypair, broadcast RotationAnnouncement signed by old key. |
| `localPublicKey` | `val localPublicKey: ByteArray` | This device's Ed25519 public key. |

### Power & System

| Method | Signature | Description |
|--------|-----------|-------------|
| `updateBattery` | `fun updateBattery(percent: Float, isCharging: Boolean)` | Report battery state. Drives power tier transitions. |
| `setCustomPowerMode` | `fun setCustomPowerMode(mode: PowerTier? = null)` | Override automatic tier. Null = return to automatic. |
| `shedMemoryPressure` | `fun shedMemoryPressure()` | Aggressively free internal buffers. |
| `factoryReset` | `suspend fun factoryReset()` | Wipe all state: identity, trust, routes, storage. |
| `meshHealth` | `fun meshHealth(): MeshHealthSnapshot` | Point-in-time health read. |
| `routingSnapshot` | `fun routingSnapshot(): RoutingSnapshot` | Current routing table state. |

### Flows (Observability)

| Property | Type | Description |
|----------|------|-------------|
| `state` | `StateFlow<MeshLinkState>` | Lifecycle state changes. |
| `peers` | `Flow<PeerEvent>` | Found, Lost, StateChanged events. |
| `messages` | `Flow<ReceivedMessage>` | Decrypted inbound messages. |
| `keyChanges` | `Flow<KeyChangeEvent>` | TOFU key rotation notifications. |
| `deliveryConfirmations` | `Flow<MessageId>` | Delivery ACKs for sent messages. |
| `transferProgress` | `Flow<TransferProgress>` | Chunked transfer progress updates. |
| `transferFailures` | `Flow<TransferFailure>` | Transfer session failures. |
| `meshHealthFlow` | `Flow<MeshHealthSnapshot>` | Periodic health snapshots. |
| `diagnosticEvents` | `Flow<DiagnosticEvent>` | Protocol events (27 codes, 3 severity tiers). |

---

## MeshLinkState

Lifecycle FSM:

```kotlin
enum class MeshLinkState {
    UNINITIALIZED,  // Created but start() not yet called
    RUNNING,        // Active — advertising, scanning, routing
    PAUSED,         // Temporarily suspended (OS background)
    STOPPED,        // Clean shutdown via stop()
    RECOVERABLE,    // Error state that can be recovered from
    TERMINAL,       // Unrecoverable failure
}
```

---

## PeerEvent

```kotlin
sealed class PeerEvent {
    data class Found(val id: ByteArray, val detail: PeerDetail) : PeerEvent()
    data class Lost(val id: ByteArray) : PeerEvent()
    data class StateChanged(val id: ByteArray, val state: PeerState) : PeerEvent()
}
```

---

## PeerState

```kotlin
enum class PeerState { CONNECTED, DISCONNECTED }
```

Peers in the `Gone` internal state emit `PeerEvent.Lost` and disappear from `allPeerDetails()`.

---

## ReceivedMessage

```kotlin
data class ReceivedMessage(
    val id: MessageId,
    val senderId: ByteArray,
    val payload: ByteArray,
    val receivedAtMillis: Long,
)
```

---

## MessagePriority

```kotlin
enum class MessagePriority { LOW, NORMAL, HIGH }
```

Affects TTL and transfer scheduling order.

---

## PeerIdHex

Internal value class used in `DiagnosticPayload` fields only. The public API uses raw `ByteArray` for peer identifiers.

```kotlin
@JvmInline value class PeerIdHex(val hex: String)
```
