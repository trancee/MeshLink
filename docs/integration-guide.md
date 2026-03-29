# MeshLink Integration Guide

A practical guide for integrating MeshLink — a Kotlin Multiplatform BLE mesh
messaging library — into your Android or iOS application.

---

## Table of Contents

1. [Quick Start](#quick-start)
2. [Configuration](#configuration)
3. [Sending & Receiving Messages](#sending--receiving-messages)
4. [Broadcast Messaging](#broadcast-messaging)
5. [Multi-Hop Routing](#multi-hop-routing)
6. [Power Mode Management](#power-mode-management)
7. [Encryption & Trust](#encryption--trust)
8. [Diagnostics & Monitoring](#diagnostics--monitoring)
9. [Error Handling](#error-handling)
10. [Best Practices](#best-practices)

---

## Quick Start

Get a BLE mesh network running in five minutes.

### 1. Add the Dependency

**Gradle (Android / KMP)**

```kotlin
// settings.gradle.kts
include(":meshlink")

// app/build.gradle.kts
dependencies {
    implementation(project(":meshlink"))
}
```

MeshLink targets **Android (minSdk 26, compileSdk 36)** and
**iOS (arm64, simulatorArm64)** via Kotlin Multiplatform.

**Swift Package Manager (iOS / macOS)** — build the XCFramework, then open the
pre-configured sample project:

```bash
./gradlew :meshlink:assembleMeshLinkXCFramework
open meshlink-sample/ios/MeshLinkSample.xcodeproj   # or macos/
```

For your own project, add the repository root as a local SPM dependency —
`Package.swift` declares a binary target pointing to the built XCFramework.

### 2. Create a MeshLink Instance

```kotlin
import io.meshlink.MeshLink
import io.meshlink.config.meshLinkConfig
import io.meshlink.crypto.createCryptoProvider

// `transport` is your platform-specific BleTransport implementation
val mesh = MeshLink(
    transport = transport,
    config = meshLinkConfig {
        maxMessageSize = 50_000
        gossipIntervalMillis = 10_000L
    },
    crypto = createCryptoProvider(),
)
```

The `MeshLink` constructor accepts:

| Parameter          | Required | Description                              |
|--------------------|----------|------------------------------------------|
| `transport`        | Yes      | `BleTransport` implementation            |
| `config`           | No       | `MeshLinkConfig` (defaults are sensible) |
| `coroutineContext` | No       | Custom coroutine context                 |
| `clock`            | No       | Clock function (for testing)             |
| `crypto`           | No       | `CryptoProvider` — **required by default** (`requireEncryption = true`). Set `requireEncryption = false` in config to allow plaintext. |
| `trustStore`       | No       | `TrustStore` (enables key pinning)       |

### 3. Start the Mesh

```kotlin
val result = mesh.start()
result.onSuccess {
    println("Mesh is running")
}.onFailure { error ->
    println("Failed to start: ${error.message}")
}
```

### 4. Listen for Peers

```kotlin
import io.meshlink.model.PeerEvent

launch {
    mesh.peers.collect { event ->
        when (event) {
            is PeerEvent.Found -> println("Peer joined: ${event.peerId.toHex()}")
            is PeerEvent.Lost      -> println("Peer left: ${event.peerId.toHex()}")
        }
    }
}
```

### 5. Send & Receive Messages

```kotlin
// Send a message
val messageId = mesh.send(
    recipient = recipientPeerId,       // 16-byte peer ID
    payload = "Hello mesh!".encodeToByteArray(),
)

// Receive messages
launch {
    mesh.messages.collect { message ->
        val text = message.payload.decodeToString()
        println("From ${message.senderId.toHex()}: $text")
    }
}
```

### 6. Stop the Mesh

```kotlin
mesh.stop()
```

---

## Configuration

MeshLink ships sensible defaults, but every parameter is tunable via
`MeshLinkConfig`. Use the DSL builder or a preset.

### Using the DSL Builder

```kotlin
import io.meshlink.config.meshLinkConfig

val config = meshLinkConfig {
    maxMessageSize = 50_000
    bufferCapacity = 1_048_576
    mtu = 185
    gossipIntervalMillis = 10_000L
    keepaliveIntervalMillis = 30_000L
    pendingMessageTtlMillis = 60_000L
}
```

### Using a Preset

Four presets cover common scenarios. Each accepts an optional override block.
See the [API Reference § Presets](api-reference.md#presets) for the full
comparison table.

```kotlin
import io.meshlink.config.MeshLinkConfig

// Chat apps — small messages, moderate buffer
val chatConfig = MeshLinkConfig.chatOptimized {
    gossipIntervalMillis = 5_000L
}

// File/image transfer — large messages, big buffer
val fileConfig = MeshLinkConfig.fileTransferOptimized()

// Battery-constrained devices — small buffers, minimal overhead
val powerConfig = MeshLinkConfig.powerOptimized()

// Sensor telemetry — tiny payloads, minimal resources
val sensorConfig = MeshLinkConfig.sensorOptimized()
```

### Key Configuration Options

#### Messaging

| Field                   | Default   | Recommendation                                  |
|-------------------------|-----------|--------------------------------------------------|
| `maxMessageSize`        | 100,000   | Set to your largest expected payload             |
| `bufferCapacity`        | 1,048,576 | Must be ≥ `maxMessageSize`                       |
| `mtu`                   | 185       | Leave at default unless you negotiate a larger MTU |
| `pendingMessageTtlMillis`   | 0 (never) | Set to 60–300 s for apps with delivery deadlines |
| `pendingMessageCapacity`| 100       | Increase for bursty senders                      |
| `maxHops`               | 10        | Lower to 3–5 for latency-sensitive apps          |

#### Rate Limiting

| Field                               | Default      | Description                        |
|-------------------------------------|--------------|------------------------------------|
| `rateLimitMaxSends`                 | 0 (disabled) | Max outbound unicasts per window   |
| `rateLimitWindowMillis`                 | 60,000       | Window duration in ms              |
| `broadcastRateLimitPerMinute`       | 0 (disabled) | Max broadcasts per minute          |
| `inboundRateLimitPerSenderPerMinute`| 0 (disabled) | Inbound cap per sender per minute  |
| `neighborAggregateLimitPerMin`      | 100          | Total messages from all neighbors  |
| `senderNeighborLimitPerMin`         | 20           | Messages from a single neighbor    |

#### Resilience

| Field                        | Default      | Description                          |
|------------------------------|--------------|--------------------------------------|
| `circuitBreakerMaxFailures`  | 0 (disabled) | Failures before circuit opens        |
| `circuitBreakerWindowMillis`     | 60,000       | Failure counting window              |
| `circuitBreakerCooldownMillis`   | 30,000       | Time before retry after trip         |
| `dedupCapacity`              | 100,000      | TTL-based dedup set size (300 s expiry)  |
| `tombstoneWindowMillis`          | 120,000      | Ignore reordered duplicates window   |

#### Routing

| Field                       | Default | Description                              |
|-----------------------------|---------|------------------------------------------|
| `gossipIntervalMillis`          | 0 (off) | Route advertisement interval             |
| `triggeredUpdateThreshold`  | 0.3     | Cost change % triggering immediate gossip|
| `triggeredUpdateBatchMillis`    | 100     | Batch window for triggered updates       |
| `relayQueueCapacity`        | 100     | Max queued relay messages                |

#### Transport

| Field                       | Default | Description                              |
|-----------------------------|---------|------------------------------------------|
| `l2capEnabled`              | true    | Enable L2CAP connection-oriented mode    |
| `l2capRetryAttempts`        | 3       | Retry count for L2CAP connections        |
| `chunkInactivityTimeoutMillis`  | 30,000  | Timeout for incomplete transfers         |
| `bufferTtlMillis`               | 300,000 | Max time to keep buffered data           |
| `keepaliveIntervalMillis`       | 0 (off) | Idle connection keepalive                |

### Validating Configuration

Call `validate()` to check for constraint violations before use:

```kotlin
val config = meshLinkConfig { mtu = 10 }
val violations = config.validate()
if (violations.isNotEmpty()) {
    violations.forEach { println("⚠️ $it") }
    // Output: "mtu must be > 21 (chunk header size)"
}
```

---

## Sending & Receiving Messages

### Unicast Messages

```kotlin
// Send — returns a Result<Uuid> with the message ID
val result = mesh.send(
    recipient = peerIdBytes,
    payload = data,
)

result.onSuccess { messageId ->
    println("Queued message $messageId")
}.onFailure { error ->
    println("Send failed: ${error.message}")
}
```

### Tracking Delivery

```kotlin
// Confirmation that the recipient received the message
launch {
    mesh.deliveryConfirmations.collect { messageId ->
        println("✅ Delivered: $messageId")
    }
}

// Transfer progress (for chunked messages)
launch {
    mesh.transferProgress.collect { progress ->
        val pct = (progress.fraction * 100).toInt()
        println("📤 ${progress.messageId}: $pct% (${progress.chunksAcked}/${progress.totalChunks})")
    }
}

// Failures
launch {
    mesh.transferFailures.collect { failure ->
        println("❌ ${failure.messageId} failed: ${failure.reason}")
    }
}
```

The `DeliveryOutcome` enum provides specific failure reasons — see the
[API Reference § DeliveryOutcome](api-reference.md#deliveryoutcome) for all
values.

---

## Broadcast Messaging

Broadcast messages are sent to all reachable peers within a hop radius.
They are **not encrypted** (by design — any peer can read them).

```kotlin
// Broadcast with a 3-hop radius
val result = mesh.broadcast(
    payload = "Emergency alert!".encodeToByteArray(),
    maxHops = 3u,
)

// Receive broadcasts on the same `messages` flow
launch {
    mesh.messages.collect { message ->
        println("Message from ${message.senderId.toHex()}: ${message.payload.decodeToString()}")
    }
}
```

> **Tip:** Use `broadcastRateLimitPerMinute` in your config to prevent
> accidental broadcast storms.

---

## Multi-Hop Routing

MeshLink uses an enhanced DSDV (Destination-Sequenced Distance-Vector)
routing protocol with gossip-based advertisement.

### Enabling Gossip

Set `gossipIntervalMillis` to a non-zero value to enable periodic route
advertisements:

```kotlin
val config = meshLinkConfig {
    gossipIntervalMillis = 10_000L            // Advertise routes every 10 s
    triggeredUpdateThreshold = 0.3        // Trigger immediate update on 30% cost change
    maxHops = 5u                          // Limit message forwarding to 5 hops
}
```

### Manual Route Injection

For advanced use cases, inject routes directly:

```kotlin
mesh.addRoute(
    destination = "peer-hex-id",
    nextHop = "neighbor-hex-id",
    cost = 1.5,
    sequenceNumber = 42u,
)
```

### Route Cost

Routes are ranked by a composite cost metric that considers:
- **RSSI** — signal strength (lower is better)
- **Packet loss rate** — reliability factor
- **Freshness** — exponential decay with configurable half-life
- **Stability** — penalty for flapping routes

The formula: `cost = rssiBase × lossMultiplier × freshnessPenalty + stabilityPenalty`

---

## Power Mode Management

MeshLink automatically adjusts BLE behavior based on battery level.

### Reporting Battery Status

```kotlin
mesh.updateBattery(batteryPercent = 72, isCharging = false)
```

### Three Power Tiers

| Mode          | Battery     | Adv Interval | Scan Duty | Max Concurrent Transfers |
|---------------|-------------|--------------|-----------|--------------------------|
| PERFORMANCE   | > 80%       | 250 ms       | 80%       | 8                        |
| BALANCED      | 30% – 80%   | 500 ms       | 50%       | 4                        |
| POWER_SAVER   | < 30%       | 1000 ms      | 17%       | 1                        |

Thresholds are configurable via `powerModeThresholds` (default: `[80, 30]`).

**Hysteresis:** Downward transitions are delayed by 30 seconds to prevent
rapid mode flapping. Upward transitions and charging are immediate.

### Chunk Size Adaptation

Chunk payload size also adapts to power mode:

| Mode        | Max Chunk Payload |
|-------------|-------------------|
| PERFORMANCE | 8,192 bytes       |
| BALANCED    | 4,096 bytes       |
| POWER_SAVER | 1,024 bytes       |

The effective chunk size is `min(modeMax, mtu - 21)`.

---

## Encryption & Trust

MeshLink provides two layers of encryption when a `CryptoProvider` is supplied.

### Enabling Encryption

```kotlin
import io.meshlink.crypto.createCryptoProvider
import io.meshlink.crypto.TrustStore
import io.meshlink.crypto.TrustMode

val mesh = MeshLink(
    transport = transport,
    config = config,
    crypto = createCryptoProvider(),
    trustStore = TrustStore(mode = TrustMode.STRICT),
)
```

### Encryption Layers

| Layer               | Protocol    | Scope            | Purpose                            |
|---------------------|-------------|------------------|------------------------------------|
| Hop-by-hop          | Noise XX    | Per BLE link     | Session keys, mutual authentication, forward secrecy |
| End-to-end          | Noise K     | Per message      | Sender-authenticated encryption with ephemeral keys  |

**Noise XX Handshake** — performed automatically when two peers discover each
other. Produces symmetric session keys (ChaCha20-Poly1305) and verifies each
peer's Ed25519 static public key.

**Noise K Sealing** — each unicast message is sealed with an ephemeral X25519
key pair against the recipient's static public key. Ephemeral keys are
discarded after use, providing per-message forward secrecy.

### Trust Modes

| Mode          | Behavior on Key Change                          |
|---------------|------------------------------------------------|
| `STRICT`      | Reject the peer; emit `KeyChangeEvent`          |
| `SOFT_REPIN`  | Accept new key and re-pin; emit `KeyChangeEvent`|

### Monitoring Key Changes

```kotlin
launch {
    mesh.keyChanges.collect { event ->
        println("⚠️ Peer ${event.peerId.toHex()} rotated key")
        println("  Old: ${event.previousKey.toHex()}")
        println("  New: ${event.newKey.toHex()}")
    }
}
```

### Identity Rotation

```kotlin
// Rotate your own identity key — announces to the mesh via ROTATION message
mesh.rotateIdentity().onSuccess {
    println("Identity rotated. New key: ${mesh.localPublicKey?.toHex()}")
}
```

### Querying Peer Keys

```kotlin
val myKey = mesh.localPublicKey            // Local Ed25519 public key
val peerKey = mesh.peerPublicKey("abcd...") // Peer's public key from handshake
val broadcastKey = mesh.broadcastPublicKey  // Key for broadcast verification
```

See [Threat Model](threat-model.md) for the security analysis and threat categories.

---

## Diagnostics & Monitoring

See [API Reference § Diagnostics](api-reference.md#diagnostics) for the full list of diagnostic codes.

### Real-Time Diagnostic Events

```kotlin
launch {
    mesh.diagnosticEvents.collect { event ->
        println("[${event.severity}] ${event.code}: ${event.payload ?: ""}")
    }
}
```

Each `DiagnosticEvent` contains:

| Field          | Type             | Description                      |
|----------------|------------------|----------------------------------|
| `code`         | `DiagnosticCode` | One of 20 event codes            |
| `severity`     | `Severity`       | `INFO`, `WARN`, or `ERROR`       |
| `monotonicMillis`  | `Long`           | Monotonic clock timestamp        |
| `droppedCount` | `Int`            | Events lost due to buffer overflow|
| `payload`      | `String?`        | Optional diagnostic data         |

### Mesh Health Snapshots

```kotlin
// One-shot
val health = mesh.meshHealth()
println("Connected: ${health.connectedPeers}, Reachable: ${health.reachablePeers}")
println("Buffer: ${health.bufferUtilizationPercent}%, Power: ${health.powerMode}")

// Continuous stream
launch {
    mesh.meshHealthFlow.collect { health ->
        updateDashboard(health)
    }
}
```

`MeshHealthSnapshot` fields:

| Field                      | Type     | Description                      |
|----------------------------|----------|----------------------------------|
| `connectedPeers`           | `Int`    | Direct BLE neighbors             |
| `reachablePeers`           | `Int`    | Peers reachable via routing      |
| `bufferUtilizationPercent` | `Int`    | Buffer usage percentage          |
| `activeTransfers`          | `Int`    | In-flight message transfers      |
| `powerMode`                | `String` | Current power mode               |
| `avgRouteCost`             | `Double` | Average route cost across table  |
| `relayQueueSize`           | `Int`    | Queued relay messages            |
| `effectiveGossipIntervalMillis`| `Long`   | Current gossip interval          |

### Memory Management

```kotlin
// Remove peers not seen recently
val removed = mesh.sweep(currentlySeenPeerIds)

// Clean up stale transfers (older than 60 s)
val cleaned = mesh.sweepStaleTransfers(maxAgeMillis = 60_000L)

// Clean up stale reassembly buffers
mesh.sweepStaleReassemblies(maxAgeMillis = 60_000L)

// Remove expired pending messages
mesh.sweepExpiredPendingMessages()

// Emergency memory pressure relief
val actions = mesh.shedMemoryPressure()
actions.forEach { println("Shed: $it") }
```

---

## Error Handling

MeshLink uses Kotlin `Result` for operations that can fail:

```kotlin
mesh.start().onFailure { error ->
    // Handle startup failure (e.g., BLE unavailable)
}

mesh.send(recipient, payload).onFailure { error ->
    // Rate-limited, circuit breaker tripped, buffer full, etc.
}

mesh.rotateIdentity().onFailure { error ->
    // Crypto not enabled, etc.
}
```

### Lifecycle

| Method     | From State      | To State       | Description                   |
|------------|-----------------|----------------|-------------------------------|
| `start()`  | Stopped         | Running        | Begin advertising & scanning  |
| `pause()`  | Running         | Paused         | Suspend activity, keep state  |
| `resume()` | Paused          | Running        | Resume from pause             |
| `stop()`   | Running/Paused  | Stopped        | Full shutdown                 |

---

## Best Practices

### General

- **Validate config** — always call `config.validate()` during development.
- **Collect all flows** — launch collectors for `messages`, `peers`,
  `transferFailures`, and `diagnosticEvents` before calling `start()`.
- **Use presets** — start with `chatOptimized()`, `fileTransferOptimized()`,
  or `powerOptimized()` and override only what you need.

### Performance

- **Set `maxHops` realistically** — limit to 3–5 for chat apps to reduce
  latency and relay load.
- **Enable gossip** — set `gossipIntervalMillis` to 5–30 seconds for multi-hop
  networks.
- **Report battery state** — call `updateBattery()` on battery change
  broadcasts so MeshLink can adapt automatically.
- **Sweep periodically** — call `sweepStaleTransfers()` and
  `sweepStaleReassemblies()` on a timer (e.g., every 30–60 s).

### Security

- **Always provide a `CryptoProvider`** in production. Encryption is required
  by default (`requireEncryption = true`). Without a `CryptoProvider`,
  `start()` will fail.
- **Use `TrustMode.STRICT`** unless you have a specific need for re-pinning.
- **Monitor `keyChanges`** — alert users when a peer's key changes
  unexpectedly.
- **Enable rate limiting** — set `rateLimitMaxSends`, `broadcastRateLimitPerMinute`,
  and `inboundRateLimitPerSenderPerMinute` to protect against abuse.

### Resource Management

- **Set `pendingMessageTtlMillis`** — prevents unbounded growth of the pending
  message queue.
- **Monitor `bufferUtilizationPercent`** from `meshHealth()` — react before
  hitting capacity.
- **Handle `BUFFER_PRESSURE` diagnostics** — shed load or pause sending when
  buffer is above 80%.
- **Configure `circuitBreakerMaxFailures`** — automatically stop sending to
  persistently failing peers.
