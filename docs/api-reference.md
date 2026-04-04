# MeshLink API Reference

Complete API reference for the MeshLink Kotlin Multiplatform BLE mesh messaging
library.

---

## Table of Contents

1. [MeshLinkApi Interface](#meshlinkapi-interface)
2. [MeshLink Class](#meshlink-class)
3. [MeshLinkConfig](#meshlinkconfig)
4. [Model Classes](#model-classes)
5. [Diagnostics](#diagnostics)
6. [Extension Points](#extension-points)
7. [Supporting Types](#supporting-types)

---

## MeshLinkApi Interface

`io.meshlink.MeshLinkApi`

The primary interface for all mesh networking operations. Implemented by
`MeshLink`.

### Lifecycle

| Signature | Description |
|-----------|-------------|
| `fun start(): Result<Unit>` | Starts BLE advertising, scanning, and background processing. Valid from `UNINITIALIZED`, `STOPPED`, or `RECOVERABLE` states. Returns failure on init error (state → `RECOVERABLE` or `TERMINAL`). Throws `IllegalStateException` from `TERMINAL` state. |
| `fun stop()` | Stops all networking activity and releases resources. Always safe to call (no-op from `UNINITIALIZED`, `STOPPED`, `TERMINAL`). Idempotent. |
| `fun pause()` | Pauses scanning/advertising while retaining connections. No-op if not `RUNNING`. |
| `fun resume()` | Resumes from paused state. No-op if not `PAUSED`. |

### Messaging

| Signature | Description |
|-----------|-------------|
| `fun send(recipient: ByteArray, payload: ByteArray): Result<SendResult>` | Sends an encrypted unicast message. `recipient` is a 12-byte peer ID. Returns `SendResult.Sent` when transmission starts, or `SendResult.Queued` when the message is deferred (paused or route pending). Fails if rate-limited, circuit breaker tripped, or buffer full. |
| `fun broadcast(payload: ByteArray, maxHops: UByte): Result<MessageId>` | Broadcasts an unencrypted message to all peers within `maxHops` radius. Returns the `MessageId`. |

### Event Streams

All event streams are Kotlin `Flow` instances. Collect them in a coroutine
scope before calling `start()`.

| Property | Type | Description |
|----------|------|-------------|
| `peers` | `Flow<PeerEvent>` | Emits `PeerEvent.Found` and `PeerEvent.Lost` events when BLE neighbors appear or disappear. |
| `messages` | `Flow<Message>` | Emits received unicast and broadcast messages with `senderId` and `payload`. |
| `deliveryConfirmations` | `Flow<MessageId>` | Emits the `MessageId` when the recipient acknowledges delivery. |
| `transferProgress` | `Flow<TransferProgress>` | Emits progress updates for chunked transfers: `messageId`, `chunksAcked`, `totalChunks`, `fraction`. |
| `transferFailures` | `Flow<TransferFailure>` | Emits when a message transfer fails, with a `DeliveryOutcome` reason. |
| `meshHealthFlow` | `Flow<MeshHealthSnapshot>` | Emits periodic mesh health snapshots. |
| `keyChanges` | `Flow<KeyChangeEvent>` | Emits when a peer rotates their identity key. Includes `previousKey` and `newKey`. |
| `diagnosticEvents` | `Flow<DiagnosticEvent>` | Real-time stream of diagnostic events (see [Diagnostics](#diagnostics)). |

### Health & Diagnostics

| Signature | Description |
|-----------|-------------|
| `fun meshHealth(): MeshHealthSnapshot` | Returns a point-in-time snapshot of mesh state. |
| `fun drainDiagnostics(): List<DiagnosticEvent>` | *Deprecated.* Drains buffered diagnostic events. Use `diagnosticEvents` flow instead. |

### Memory & State Management

| Signature | Description |
|-----------|-------------|
| `fun sweep(seenPeers: Set<String>): Set<String>` | Removes peers not in `seenPeers`. Returns the set of removed peer ID hex strings. |
| `fun sweepStaleTransfers(maxAgeMillis: Long): Int` | Removes outbound transfers older than `maxAgeMillis`. Returns count removed. |
| `fun sweepStaleReassemblies(maxAgeMillis: Long): Int` | Removes incomplete reassembly buffers older than `maxAgeMillis`. Returns count removed. |
| `fun sweepExpiredPendingMessages(): Int` | Removes pending messages past their TTL. Returns count removed. |
| `fun shedMemoryPressure(): List<String>` | Aggressively frees memory when under pressure. Returns a list of shedding action descriptions. |

### Routing

| Signature | Description |
|-----------|-------------|
| `fun addRoute(destination: String, nextHop: String, cost: Double, sequenceNumber: UInt)` | Manually adds or updates a route. `destination` and `nextHop` are hex peer IDs. `cost` must be > 0. `sequenceNumber` is the Babel sequence number for route freshness. Routes added manually behave like discovered routes and are subject to TTL expiry (`routeCacheTtlMillis`). |

### Power Management

| Signature | Description |
|-----------|-------------|
| `fun updateBattery(batteryPercent: Int, isCharging: Boolean)` | Reports current battery status. MeshLink adjusts advertising interval, scan duty, chunk sizes, and concurrent transfer limits accordingly. |
| `fun setCustomPowerMode(mode: PowerMode?)` | Override automatic power mode. Pass a `PowerMode` to lock the mode, or `null` to resume automatic battery-based transitions. |

### Cryptography & Identity

| Signature | Description |
|-----------|-------------|
| `val localPublicKey: ByteArray?` | The local device's Ed25519 static public key. `null` if crypto is not enabled. |
| `val broadcastPublicKey: ByteArray?` | Static key used for broadcast message verification. |
| `fun peerPublicKey(peerIdHex: String): ByteArray?` | Returns the peer's Ed25519 public key obtained during handshake. `null` if peer is unknown or crypto is disabled. |
| `fun peerDetail(peerIdHex: String): PeerDetail?` | Returns a snapshot of a single peer's connection, routing, and identity state. `null` if the peer is unknown. |
| `fun allPeerDetails(): List<PeerDetail>` | Returns detail snapshots for all known peers. Useful for visualizers and diagnostics UIs. |
| `fun peerFingerprint(peerIdHex: String): String?` | Returns a 12-digit symmetric safety number for out-of-band peer verification. Both sides compute the same number. `null` if either key is unavailable. |
| `fun rotateIdentity(): Result<Unit>` | Generates a new Ed25519 identity key pair and announces the rotation to the mesh via a `ROTATION` wire message. |

---

## MeshLink Class

`io.meshlink.MeshLink`

The concrete implementation of `MeshLinkApi`.

### Constructor

```kotlin
class MeshLink(
    transport: BleTransport,
    config: MeshLinkConfig = MeshLinkConfig(),
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
    clock: () -> Long = { currentTimeMillis() },
    crypto: CryptoProvider? = null,
    trustStore: TrustStore? = null,
) : MeshLinkApi
```

| Parameter          | Type                | Default                | Description |
|--------------------|---------------------|------------------------|-------------|
| `transport`        | `BleTransport`      | —                      | Platform-specific BLE transport. Required. |
| `config`           | `MeshLinkConfig`    | `MeshLinkConfig()`     | Mesh configuration. |
| `coroutineContext` | `CoroutineContext`  | `EmptyCoroutineContext`| Custom dispatcher for mesh coroutines and callback dispatch. Pass `Dispatchers.Main` for UI-driven apps. |
| `clock`            | `() -> Long`        | `currentTimeMillis()`  | Monotonic clock source; override for deterministic testing. |
| `crypto`           | `CryptoProvider?`   | `null`                 | Encryption provider. **Required by default** — `start()` fails if null unless `requireEncryption = false`. Use `CryptoProvider()`. |
| `trustStore`       | `TrustStore?`       | `null`                 | Enables key pinning when non-null. When `null` and `crypto` is provided, a `TrustStore` is created using `config.trustMode`. |
| `cryptoDispatcher` | `CoroutineContext?` | `null` (= `coroutineContext`) | Dispatcher for CPU-intensive crypto operations (Noise K seal/unseal). Defaults to the `coroutineContext` parameter. In production, pass `Dispatchers.Default` to offload crypto to a background thread pool and prevent blocking the main mesh scope. |

### Lifecycle States

```kotlin
enum class LifecycleState {
    UNINITIALIZED,  // Constructed, not yet started
    RUNNING,        // start() succeeded, mesh active
    PAUSED,         // pause() called
    STOPPED,        // stop() completed, clean shutdown
    RECOVERABLE,    // start() failed with retryable error — start() allowed again
    TERMINAL,       // start() failed with non-retryable error — permanently dead
}
```

Access via `val state: LifecycleState`.

**Transition rules:**

| Method | Valid from | Transitions to |
|--------|-----------|----------------|
| `start()` | `UNINITIALIZED`, `STOPPED`, `RECOVERABLE` | `RUNNING`, `RECOVERABLE`, or `TERMINAL` |
| `pause()` | `RUNNING` | `PAUSED` (no-op otherwise) |
| `resume()` | `PAUSED` | `RUNNING` (no-op otherwise) |
| `stop()` | `RUNNING`, `PAUSED`, `RECOVERABLE` | `STOPPED` (no-op from `UNINITIALIZED`, `STOPPED`, `TERMINAL`) |
| `start()` | `TERMINAL` | throws `IllegalStateException` |

---

## MeshLinkConfig

`io.meshlink.config.MeshLinkConfig`

For usage examples and configuration presets, see [Integration Guide § Configuration](integration-guide.md#configuration).

Immutable configuration data class. **Public API** parameters (commonly tuned
by consuming apps) are direct constructor parameters. **Internal** protocol
parameters live in the `advanced: MeshLinkInternalConfig` property and are
accessible via `config.advanced.fieldName` or convenience delegate properties
on `MeshLinkConfig` itself (e.g., `config.keepaliveIntervalMillis`).

### Fields

#### Messaging

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `maxMessageSize` | `Int` | `10_000` | Maximum payload size in bytes. Messages larger than `mtu - 21` are automatically chunked. Use `largePayloadHighThroughput` preset for up to 100 KB. |
| `bufferCapacity` | `Int` | `1_048_576` | Total in-flight data buffer size in bytes. Must be ≥ `maxMessageSize`. |
| `mtu` | `Int` | `185` | BLE link MTU. Effective payload per chunk = `mtu - 21` (first chunk header size). Must be > 21. |
| `maxHops` | `UByte` | `10` | Maximum hop count for routed messages. |
| `broadcastTtl` | `UByte` | `2` | Hop limit for broadcast relay propagation. Must be in range `1..maxHops`. Broadcasts sent via `broadcast()` are clamped to this value. Relays also clamp forwarded broadcasts to their local `broadcastTtl`. |
| `pendingMessageTtlMillis` | `Long` | `0` | TTL for queued outbound messages. `0` = never expire. |
| `pendingMessageCapacity` | `Int` | `100` | Maximum pending outbound messages. |
| `appId` | `String?` | `null` | Optional app identifier. Messages with a non-matching app ID are silently dropped. |

#### Rate Limiting

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `rateLimitMaxSends` | `Int` | `60` | Max outbound unicast sends per window. `0` = disabled. |
| `rateLimitWindowMillis` | `Long` | `60_000` | Sliding window for outbound rate limiting (ms). |
| `broadcastRateLimitPerMin` | `Int` | `10` | Max broadcast sends per minute. `0` = disabled. |
| `inboundRateLimitPerSenderPerMin` | `Int` | `30` | Max inbound messages from a single sender per minute. `0` = disabled. |
| `handshakeRateLimitPerSec` | `Int` | `1` | Max Noise XX handshakes per second. |
| `nackRateLimitPerSec` | `Int` | `10` | Max NACKs per second. |
| `neighborAggregateLimitPerMin` | `Int` | `100` | Max aggregate messages from all BLE neighbors per minute. |
| `senderNeighborLimitPerMin` | `Int` | `20` | Max messages from a single BLE neighbor per minute. |

#### Circuit Breaker

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `circuitBreakerMaxFailures` | `Int` | `0` | Consecutive failures before circuit opens. `0` = disabled. |
| `circuitBreakerWindowMillis` | `Long` | `60_000` | Window for counting failures (ms). |
| `circuitBreakerCooldownMillis` | `Long` | `30_000` | Cooldown before retry after circuit trips (ms). |

#### Routing

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `routeCacheTtlMillis` | `Long` | `300_000` | Time-to-live for cached routes (ms). Routes expire and are refreshed via Babel Updates or on-demand discovery. |
| `routeDiscoveryTimeoutMillis` | `Long` | `5_000` | Maximum time to wait for a Route Reply after flooding a Route Request (ms). Messages are queued during discovery. |
| `relayQueueCapacity` | `Int` | `100` | Max queued relay (forwarded) messages. |

#### Transfer & Congestion

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `ackWindowMin` | `Int` | `2` | Minimum AIMD congestion window size. |
| `ackWindowMax` | `Int` | `16` | Maximum AIMD congestion window size. Must be ≥ `ackWindowMin`. |
| `chunkInactivityTimeoutMillis` | `Long` | `30_000` | Timeout for incomplete transfer assembly (ms). Must be < `bufferTtlMillis`. |
| `bufferTtlMillis` | `Long` | `900_000` | Max time to keep buffered store-and-forward data (ms). Default 15 minutes. |
| `deliveryTimeoutMillis` | `Long` | `30_000` | How long before notifying sender of delivery failure (ms). Must be ≤ `bufferTtlMillis`. `0` = no deadline. |

#### Transport

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `l2capEnabled` | `Boolean` | `true` | Enable L2CAP connection-oriented mode. |
| `l2capRetryAttempts` | `Int` | `3` | L2CAP connection retry count. Must be ≥ 0 when L2CAP is enabled. |
| `l2capBackpressureWindowMillis` | `Long` | `7_000` | Rolling window for L2CAP backpressure detection (ms). 3 consecutive >100ms writes within this window triggers GATT fallback. Range: 3000–15000. |
| `evictionGracePeriodMillis` | `Long` | `30_000` | Maximum time to keep connections with active transfers alive during power mode downgrade (ms). Range: 5000–60000. |
| `keepaliveIntervalMillis` | `Long` | `0` | Idle connection keepalive interval (ms). `0` = disabled. |
| `tombstoneWindowMillis` | `Long` | `120_000` | Window to ignore reordered duplicate messages (ms). |

#### Power Management

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `powerModeThresholds` | `List<Int>` | `[80, 30]` | Battery thresholds for power mode transitions. Must be strictly descending. `[80, 30]` means: >80% = PERFORMANCE, 30–80% = BALANCED, <30% = POWER_SAVER. |
| `customPowerMode` | `PowerMode?` | `null` | Override automatic power mode with a fixed mode. When set, battery-based transitions are disabled. |

#### Diagnostics

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `diagnosticBufferCapacity` | `Int` | `256` | Maximum diagnostic events in the ring buffer. |
| `diagnosticsEnabled` | `Boolean` | `true` | Enable the diagnostic event stream. When `false`, no diagnostic events are generated (zero overhead). |
| `dedupCapacity` | `Int` | `100_000` | Maximum unique message IDs tracked for deduplication (TTL-based, 300 s expiry). |

#### Protocol

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `protocolVersion` | `ProtocolVersion` | `ProtocolVersion(1, 0)` | Wire protocol version (major, minor). |

#### Compression

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `compressionEnabled` | `Boolean` | `true` | Enable raw DEFLATE (RFC 1951) payload compression. Compression is applied before encryption (compress-then-encrypt). When disabled, no envelope prefix is added and payloads are sent raw (backward compatible). |
| `compressionMinBytes` | `Int` | `128` | Minimum payload size in bytes before compression is attempted. Payloads smaller than this threshold are wrapped with a `0x00` (uncompressed) envelope prefix instead. |

#### Security

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `requireEncryption` | `Boolean` | `true` | When `true`, `start()` fails if no `CryptoProvider` is supplied. Set to `false` to allow plaintext operation. |
| `trustMode` | `TrustMode` | `SOFT_REPIN` | Key pinning policy. `SOFT_REPIN`: silently accept new key and re-pin; emit `KeyChangeEvent` for app-level awareness. `STRICT`: reject messages from peers whose key changed, require explicit `repinKey(peer)`. Both modes emit `KeyChangeEvent`. |
| `deliveryAckEnabled` | `Boolean` | `true` | Send signed delivery ACKs for received routed messages. When `false`, recipients do not send delivery ACKs (senders rely on SACK-based transfer completion). |
| `visitedListEnabled` | `Boolean` | `true` | Include per-hop peer ID hashes in routed messages for loop detection. When disabled, loop prevention relies on dedup set + hop counter. Disabling saves 12–120 bytes per routed message. |
| `paddingBlockSize` | `Int` | `0` | Pad payload envelopes to the next multiple of this block size (bytes) before E2E encryption. `0` = disabled. Set to `64` or `128` for traffic analysis resistance. |
| `maxConcurrentInboundSessions` | `Int` | `100` | Maximum concurrent inbound reassembly sessions. Limits memory exhaustion from unauthenticated chunk floods. |

### Validation

```kotlin
fun validate(): List<String>
```

Returns a list of constraint violation messages. An empty list means the
configuration is valid. Enforced constraints:

- `mtu > 21`
- `maxMessageSize ≤ bufferCapacity`
- `maxMessageSize > 0`
- `bufferCapacity > 0`
- `mtu ≤ maxMessageSize`
- `diagnosticBufferCapacity ≥ 0`
- `dedupCapacity > 0`
- `rateLimitWindowMillis > 0` when `rateLimitMaxSends > 0`
- `circuitBreakerCooldownMillis > 0` when `circuitBreakerMaxFailures > 0`
- `ackWindowMax ≥ ackWindowMin`
- `broadcastTtl` in range `1..maxHops`
- `evictionGracePeriodMillis` in range `5000..60000`
- `l2capBackpressureWindowMillis` in range `3000..15000`
- `powerModeThresholds` strictly descending
- `l2capRetryAttempts ≥ 0` when `l2capEnabled`
- `chunkInactivityTimeoutMillis < bufferTtlMillis`
- `deliveryTimeoutMillis ≤ bufferTtlMillis` (when both > 0)
- `deliveryTimeoutMillis ≥ 0`

### Presets

```kotlin
companion object {
    fun smallPayloadLowLatency(overrides: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig
    fun largePayloadHighThroughput(overrides: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig
    fun minimalResourceUsage(overrides: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig
    fun minimalOverhead(overrides: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig
}
```

| Preset | `maxMessageSize` | `bufferCapacity` | Use Case |
|--------|------------------|------------------|----------|
| `smallPayloadLowLatency` | 10,000 | 524,288 (512 KB) | Text chat, small payloads |
| `largePayloadHighThroughput` | 100,000 | 2,097,152 (2 MB) | Images, files, large data |
| `minimalResourceUsage` | 10,000 | 262,144 (256 KB) | IoT, wearables, battery-constrained devices |
| `minimalOverhead` | 1,000 | 65,536 (64 KB) | Sensor telemetry, infrequent small readings |

All presets accept an override block:

```kotlin
val config = MeshLinkConfig.smallPayloadLowLatency {
    keepaliveIntervalMillis = 30_000L
}
```

### DSL Builder

```kotlin
fun meshLinkConfig(block: MeshLinkConfigBuilder.() -> Unit = {}): MeshLinkConfig
```

Constructs a `MeshLinkConfig` using a builder DSL:

```kotlin
val config = meshLinkConfig {
    maxMessageSize = 50_000
    mtu = 512
    advanced {
        routeCacheTtlMillis = 120_000L
    }
}
```

Public parameters are set directly on the builder. Internal protocol
parameters live in the `advanced { }` block. For backward compatibility,
internal parameters can also be set directly (without the `advanced` wrapper).

---

## Model Classes

All model classes are in the `io.meshlink.model` package.

### MessageId

```kotlin
@JvmInline
value class MessageId(val hex: String)
```

A 16-byte random message identifier, represented as a 32-character hex string. Generated from the platform CSPRNG with 128 bits of entropy. Birthday-bound collision probability is ~2⁻⁶⁴ — negligible for any practical message volume.

### Message

```kotlin
data class Message(
    val senderId: ByteArray,
    val payload: ByteArray,
)
```

Represents a received unicast or broadcast message.

| Field | Type | Description |
|-------|------|-------------|
| `senderId` | `ByteArray` | 12-byte peer ID of the sender. |
| `payload` | `ByteArray` | Decrypted message content. |

### PeerEvent

```kotlin
sealed class PeerEvent {
    data class Found(val peerId: ByteArray) : PeerEvent()
    data class Lost(val peerId: ByteArray) : PeerEvent()
}
```

Emitted on the `peers` flow when BLE neighbors are discovered or lost.

| Subclass | Field | Description |
|----------|-------|-------------|
| `Found` | `peerId: ByteArray` | 12-byte ID of the newly found peer. |
| `Lost` | `peerId: ByteArray` | 12-byte ID of the peer that is no longer reachable. |

### TransferProgress

```kotlin
data class TransferProgress(
    val messageId: MessageId,
    val chunksAcked: Int,
    val totalChunks: Int,
) {
    val fraction: Float
        get() = if (totalChunks == 0) 1f else chunksAcked.toFloat() / totalChunks
}
```

Progress update for a chunked message transfer.

| Field | Type | Description |
|-------|------|-------------|
| `messageId` | `MessageId` | Structured message identifier of the message being transferred. |
| `chunksAcked` | `Int` | Number of chunks acknowledged by the recipient. |
| `totalChunks` | `Int` | Total number of chunks in the transfer. |
| `fraction` | `Float` | Computed property: `chunksAcked / totalChunks`. Returns `1f` if `totalChunks` is 0. |

### TransferFailure

```kotlin
data class TransferFailure(
    val messageId: MessageId,
    val reason: DeliveryOutcome,
)
```

Emitted when a message transfer fails.

| Field | Type | Description |
|-------|------|-------------|
| `messageId` | `MessageId` | Structured message identifier of the failed message. |
| `reason` | `DeliveryOutcome` | The failure reason (see [DeliveryOutcome](#deliveryoutcome)). |

### KeyChangeEvent

```kotlin
data class KeyChangeEvent(
    val peerId: ByteArray,
    val previousKey: ByteArray,
    val newKey: ByteArray,
)
```

Emitted when a peer rotates their Ed25519 identity key.

| Field | Type | Description |
|-------|------|-------------|
| `peerId` | `ByteArray` | 12-byte ID of the peer. |
| `previousKey` | `ByteArray` | The peer's previous Ed25519 public key. |
| `newKey` | `ByteArray` | The peer's new Ed25519 public key. |

This class implements custom `equals()` and `hashCode()` using
`contentEquals()` / `contentHashCode()` for correct `ByteArray` comparison.

### PeerDetail

`io.meshlink.model.PeerDetail`

```kotlin
data class PeerDetail(
    val peerIdHex: String,
    val presenceState: PresenceState,
    val isDirectNeighbor: Boolean,
    val routeNextHop: String?,
    val routeCost: Double?,
    val routeSequenceNumber: UInt?,
    val publicKeyHex: String?,
    val nextHopFailureRate: Double,
    val nextHopFailureCount: Int,
)
```

Snapshot of a single peer's connection, routing, and identity state. Returned by
`peerDetail()` and `allPeerDetails()`.

| Field | Type | Description |
|-------|------|-------------|
| `peerIdHex` | `String` | Hex-encoded 12-byte peer ID. |
| `presenceState` | `PresenceState` | `CONNECTED`, `DISCONNECTED`, or `GONE`. |
| `isDirectNeighbor` | `Boolean` | `true` if the peer is within 1-hop BLE range. |
| `routeNextHop` | `String?` | Hex ID of the next-hop peer on the best route. `null` if no route exists. |
| `routeCost` | `Double?` | Composite link quality cost of the best route. `null` if no route. |
| `routeSequenceNumber` | `UInt?` | Babel route sequence number of the best route. |
| `publicKeyHex` | `String?` | Ed25519 public key as hex. `null` if crypto is disabled or peer is unknown. |
| `nextHopFailureRate` | `Double` | Delivery failure rate via this peer as next-hop (0.0–1.0). |
| `nextHopFailureCount` | `Int` | Total recorded delivery failures via this peer. |

### DeliveryOutcome

`io.meshlink.util.DeliveryOutcome`

```kotlin
enum class DeliveryOutcome {
    CONFIRMED,
    FAILED_NO_ROUTE,
    FAILED_HOP_LIMIT,
    FAILED_ACK_TIMEOUT,
    FAILED_BUFFER_FULL,
    FAILED_PEER_OFFLINE,
    FAILED_DELIVERY_TIMEOUT,
}
```

| Value | Description |
|-------|-------------|
| `CONFIRMED` | Message was delivered and acknowledged by the recipient. |
| `FAILED_NO_ROUTE` | No route exists to the destination peer. |
| `FAILED_HOP_LIMIT` | Message exceeded the configured `maxHops`. |
| `FAILED_ACK_TIMEOUT` | Chunk-level ACK was not received within the timeout. |
| `FAILED_BUFFER_FULL` | Outbound buffer capacity was exceeded. |
| `FAILED_PEER_OFFLINE` | Destination peer is no longer reachable. |
| `FAILED_DELIVERY_TIMEOUT` | End-to-end delivery ACK was not received within the deadline. |

---

## Diagnostics

All diagnostic types are in the `io.meshlink.diagnostics` package.

### DiagnosticSink

```kotlin
class DiagnosticSink(
    bufferCapacity: Int = 256,
    clock: () -> Long = { currentTimeMillis() },
    epochClock: () -> Long = { currentTimeMillis() },
    enabled: Boolean = true,
)
```

Manages diagnostic event emission and buffering.

| Member | Description |
|--------|-------------|
| `val enabled: Boolean` | Whether diagnostic events are emitted. Controlled by `MeshLinkConfig.diagnosticsEnabled`. |
| `fun emit(code: DiagnosticCode, severity: Severity, payload: String? = null)` | Emits a diagnostic event to both the `SharedFlow` and the legacy buffer. No-op when `enabled = false`. The `payload` string is wrapped as `mapOf("message" to payload)` in the event. |
| `val events: SharedFlow<DiagnosticEvent>` | Flow of diagnostic events. Uses `DROP_OLDEST` overflow policy. |
| `fun drainTo(out: MutableList<DiagnosticEvent>)` | **Deprecated.** Drains buffered events into `out` and clears the buffer. Use `events` flow instead. |

### DiagnosticEvent

```kotlin
data class DiagnosticEvent(
    val code: DiagnosticCode,
    val severity: Severity,
    val monotonicMillis: Long,
    val wallClockMillis: Long,
    val droppedCount: Int,
    val payload: Map<String, Any>,
)
```

| Field | Type | Description |
|-------|------|-------------|
| `code` | `DiagnosticCode` | The event code identifying what happened. |
| `severity` | `Severity` | `INFO`, `WARN`, `ERROR`, or `FATAL`. |
| `monotonicMillis` | `Long` | Monotonic clock timestamp when the event occurred. |
| `wallClockMillis` | `Long` | Wall-clock (epoch) timestamp when the event occurred. |
| `droppedCount` | `Int` | Number of events dropped due to buffer overflow since the last event. |
| `payload` | `Map<String, Any>` | Event-specific key-value pairs. Typically contains a `"message"` key with a human-readable description. |

### Severity

```kotlin
enum class Severity { INFO, WARN, ERROR, FATAL }
```

| Value | Description |
|-------|-------------|
| `INFO` | Observability, no action needed. |
| `WARN` | Degraded but functional. |
| `ERROR` | Operation failed, app should act. |
| `FATAL` | Library entered terminal state. |

### DiagnosticCode

All 21 diagnostic codes:

| Code | Typical Severity | Description |
|------|-----------------|-------------|
| `L2CAP_FALLBACK` | WARN | BLE transport fell back from L2CAP to connectionless mode. |
| `RATE_LIMIT_HIT` | WARN | Outbound rate limit was triggered; message was dropped. |
| `ROUTE_CHANGED` | INFO | A route to a destination was added, updated, or removed. |
| `PEER_PRESENCE_EVICTED` | WARN | A peer was presence-evicted (removed from the active peer list after 2 consecutive sweep misses). |
| `BUFFER_PRESSURE` | WARN | Buffer utilization exceeded 80%. Consider shedding load. |
| `TRANSPORT_MODE_CHANGED` | INFO | The BLE transport operating mode changed (e.g., L2CAP ↔ connectionless). |
| `GOSSIP_TRAFFIC_REPORT` | INFO | A route exchange event completed. Payload may contain route counts. |
| `BLE_STACK_UNRESPONSIVE` | ERROR | The BLE hardware stack is not responding. Requires recovery action. |
| `MEMORY_PRESSURE` | ERROR | Memory threshold exceeded. Tiered shedding has been initiated. |
| `LATE_DELIVERY_ACK` | WARN | A delivery ACK arrived after the delivery deadline had expired. |
| `DECRYPTION_FAILED` | WARN | AEAD decryption authentication failed. Message was discarded. |
| `HOP_LIMIT_EXCEEDED` | INFO | A routed message reached its maximum hop count and was discarded. |
| `LOOP_DETECTED` | WARN | A message loop was detected (peer re-forwarded a message). |
| `REPLAY_REJECTED` | WARN | The replay guard rejected a message with a duplicate or out-of-window counter. |
| `MALFORMED_DATA` | WARN | Wire format parsing failed. The received data is malformed. |
| `SEND_FAILED` | WARN | An outbound send operation failed at the transport layer. |
| `NEXTHOP_UNRELIABLE` | WARN | A routing next-hop has a delivery failure rate exceeding 50%. Payload includes the next-hop ID and failure rate. |
| `APP_ID_REJECTED` | INFO | A received message's app ID does not match the local filter. Silently dropped. |
| `UNKNOWN_MESSAGE_TYPE` | WARN | A wire message with an unrecognized type byte was received. |
| `DELIVERY_TIMEOUT` | WARN | End-to-end delivery ACK was not received within the configured deadline. |
| `HANDSHAKE_EVENT` | INFO | Noise XX handshake lifecycle event (initiation, message received, completion). |

### MeshHealthSnapshot

```kotlin
data class MeshHealthSnapshot(
    val connectedPeers: Int,
    val reachablePeers: Int,
    val bufferUtilizationPercent: Int,
    val activeTransfers: Int,
    val powerMode: PowerMode,
    val avgRouteCost: Double = 0.0,
    val relayBufferUtilization: Float = 0f,
    val ownBufferUtilization: Float = 0f,
    val relayQueueSize: Int = 0,
)
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `connectedPeers` | `Int` | — | Number of direct BLE neighbors. |
| `reachablePeers` | `Int` | — | Total peers reachable via routing (including direct). |
| `bufferUtilizationPercent` | `Int` | — | Percentage of buffer capacity in use (0–100). |
| `activeTransfers` | `Int` | — | Number of in-flight message transfers. |
| `powerMode` | `PowerMode` | — | Current power mode: `PERFORMANCE`, `BALANCED`, or `POWER_SAVER`. |
| `avgRouteCost` | `Double` | `0.0` | Average composite cost across all routes in the routing table. |
| `relayBufferUtilization` | `Float` | `0f` | Relay (inbound) buffer usage as fraction of total capacity (0.0–1.0). |
| `ownBufferUtilization` | `Float` | `0f` | Own-outbound buffer usage as fraction of total capacity (0.0–1.0). |
| `relayQueueSize` | `Int` | `0` | Number of messages in the relay forwarding queue. |

---

## Extension Points

MeshLink defines three interfaces that serve as platform abstraction
boundaries. Provide custom implementations for testing or alternative
platforms.

### BleTransport

`io.meshlink.transport.BleTransport`

System boundary for BLE hardware.

```kotlin
interface BleTransport {
    val localPeerId: ByteArray
    suspend fun startAdvertisingAndScanning()
    suspend fun stopAll()
    val advertisementEvents: Flow<AdvertisementEvent>
    val peerLostEvents: Flow<PeerLostEvent>
    suspend fun sendToPeer(peerId: ByteArray, data: ByteArray)
    val incomingData: Flow<IncomingData>
}
```

| Member | Description |
|--------|-------------|
| `localPeerId` | 12-byte unique identifier for this device (truncated SHA-256 of X25519 public key). |
| `advertisementServiceData` | Service data payload to include in BLE advertisements (set before starting). |
| `startAdvertisingAndScanning()` | Begin BLE advertising and scanning. |
| `stopAll()` | Stop all BLE activity. |
| `advertisementEvents` | Flow of advertisement payloads from discovered peers. |
| `peerLostEvents` | Flow of events when a peer stops advertising or times out. |
| `sendToPeer(peerId, data)` | Send raw bytes to a connected peer. |
| `incomingData` | Flow of raw bytes received from peers. |

**Associated data classes:**

```kotlin
data class AdvertisementEvent(val peerId: ByteArray, val advertisementPayload: ByteArray)
data class IncomingData(val peerId: ByteArray, val data: ByteArray)
data class PeerLostEvent(val peerId: ByteArray)
```

**Implementations:**
- **Android:** `AndroidBleTransport` (production)
- **iOS:** `IosBleTransport` (production)
- **Tests:** `VirtualMeshTransport` (in-memory simulation)

### CryptoProvider

`io.meshlink.crypto.CryptoProvider`

Platform-agnostic cryptography abstraction.

```kotlin
interface CryptoProvider {
    fun generateEd25519KeyPair(): CryptoKeyPair
    fun sign(privateKey: ByteArray, data: ByteArray): ByteArray
    fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean
    fun generateX25519KeyPair(): CryptoKeyPair
    fun x25519SharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray
    fun aeadEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray = byteArrayOf()): ByteArray
    fun aeadDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray = byteArrayOf()): ByteArray
    fun sha256(data: ByteArray): ByteArray
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray
}
```

Ed25519 for signing/verification, X25519 for key agreement, ChaCha20-Poly1305
for AEAD encryption, SHA-256 for hashing, and HKDF-SHA256 for key derivation.

**Factory:**

```kotlin
expect fun CryptoProvider(): CryptoProvider
```

Returns a `PureKotlinCryptoProvider` on all platforms (no native bindings).

**Associated type:**

```kotlin
data class CryptoKeyPair(val publicKey: ByteArray, val privateKey: ByteArray)
```

### SecureStorage

`io.meshlink.storage.SecureStorage`

Platform-specific secure key-value storage for persisting identity keys and
state across app launches.

```kotlin
interface SecureStorage {
    fun put(key: String, value: ByteArray)
    fun get(key: String): ByteArray?
    fun delete(key: String)
    fun clear()
    fun size(): Int
}
```

| Method | Description |
|--------|-------------|
| `put(key, value)` | Store a byte array under the given key. |
| `get(key)` | Retrieve the byte array for the given key, or `null` if not found. |
| `delete(key)` | Remove the entry for the given key. |
| `clear()` | Remove all entries. |
| `size()` | Return the number of stored entries. |

**Implementations:**
- **Android:** `EncryptedSharedPreferences` (backed by Android Keystore)
- **iOS:** Keychain with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`
- **Tests:** `InMemorySecureStorage`

---

## Supporting Types

### ProtocolVersion

`io.meshlink.protocol.ProtocolVersion`

```kotlin
data class ProtocolVersion(val major: Int, val minor: Int)
```

Wire protocol version. Default: `ProtocolVersion(1, 0)`.

### TrustStore

`io.meshlink.crypto.TrustStore`

```kotlin
class TrustStore(mode: TrustMode = TrustMode.STRICT)

enum class TrustMode { STRICT, SOFT_REPIN }

sealed interface VerifyResult {
    object FirstSeen : VerifyResult
    object Trusted : VerifyResult
    data class KeyChanged(val previousKey: ByteArray) : VerifyResult
}
```

| Method | Description |
|--------|-------------|
| `fun verify(peerId: String, publicKey: ByteArray): VerifyResult` | Verify a peer's public key against the stored pin. |

| Trust Mode | Key Change Behavior |
|------------|---------------------|
| `STRICT` | Rejects the connection; returns `KeyChanged`. |
| `SOFT_REPIN` | Accepts the new key, re-pins it, and returns `FirstSeen`. |

### PowerMode

`io.meshlink.power.PowerMode`

```kotlin
enum class PowerMode { PERFORMANCE, BALANCED, POWER_SAVER }
```

### MeshLinkConfigBuilder

`io.meshlink.config.MeshLinkConfigBuilder`

Mutable builder class with all fields from `MeshLinkConfig`, including
`broadcastTtl`, `trustMode`, `deliveryAckEnabled`, `diagnosticsEnabled`,
`customPowerMode`, `evictionGracePeriodMillis`, and
`l2capBackpressureWindowMillis`. Used by the `meshLinkConfig {}` DSL and
preset `overrides` blocks.

```kotlin
class MeshLinkConfigBuilder(/* all fields with same defaults as MeshLinkConfig */) {
    fun build(): MeshLinkConfig
}
```

### TlvEntry

`io.meshlink.wire.TlvEntry`

A single TLV (Type-Length-Value) extension entry carried in the extension area
of supported wire messages.

```kotlin
data class TlvEntry(
    val tag: UByte,
    val value: ByteArray
)
```

| Field | Type | Description |
|-------|------|-------------|
| `tag` | `UByte` | Extension tag identifier. Tags `0x00`–`0x7F` are reserved for protocol use; tags `0x80`–`0xFF` are available for application use. |
| `value` | `ByteArray` | Extension value (0–65535 bytes). |

Custom `equals()` and `hashCode()` implementations use `contentEquals` /
`contentHashCode` for byte array comparison.

### TlvCodec

`io.meshlink.wire.TlvCodec`

Internal codec for encoding and decoding the TLV extension area appended to
fixed-length wire messages. Not typically called directly by consumers — the
`WireCodec` encode/decode functions handle TLV extensions via the `extensions`
parameter.

```kotlin
internal object TlvCodec {
    fun encode(entries: List<TlvEntry>): ByteArray
    fun decode(data: ByteArray, offset: Int): Pair<List<TlvEntry>, Int>
    fun wireSize(entries: List<TlvEntry>): Int
}
```

| Method | Description |
|--------|-------------|
| `encode(entries)` | Encodes a list of TLV entries into a byte array including the 2-byte extension-length prefix. Returns `[0x00, 0x00]` when entries is empty. |
| `decode(data, offset)` | Decodes TLV entries starting at `offset`. Returns `(entries, totalBytesConsumed)`. Unknown tags are preserved (not dropped). Throws `IllegalArgumentException` if data is truncated. |
| `wireSize(entries)` | Returns the total wire size in bytes including the 2-byte prefix. |

Wire messages that support TLV extensions (Keepalive, ChunkAck, Nack,
ResumeRequest, DeliveryAck) accept an
`extensions: List<TlvEntry> = emptyList()` parameter in their encode functions
and expose an `extensions: List<TlvEntry>` field on their decoded data classes.
See [wire-format-spec.md § TLV Extension Area](wire-format-spec.md#tlv-extension-area)
for the binary layout.
