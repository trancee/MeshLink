# MeshLink Integration Guide

A practical guide for integrating MeshLink into your Android, iOS, or macOS
application.

---

## Quick Start

For the basic create → start → send → stop flow, see the
[README Quick Start](../README.md#quick-start). This guide covers dependency
setup, advanced configuration, and integration patterns.

## Dependencies

**Gradle (Android / KMP)**

```kotlin
// settings.gradle.kts
include(":meshlink")

// app/build.gradle.kts
dependencies {
    implementation(project(":meshlink"))
}
```

**Swift Package Manager (iOS / macOS)** — build the XCFramework, then add the
repository root as a local SPM dependency (`Package.swift` declares a binary
target pointing to the built XCFramework):

```bash
./gradlew :meshlink:assembleMeshLinkXCFramework
```

See the [API Reference § Constructor](api-reference.md#constructor) for the
full `MeshLink` constructor parameter table.

---

## Configuration

MeshLink ships sensible defaults, but every parameter is tunable via
`MeshLinkConfig`. Use the DSL builder or a preset.

### Using the DSL Builder

```kotlin
import io.meshlink.config.meshLinkConfig

val config = meshLinkConfig {
    maxMessageSize = 50_000
    maxHops = 5u
    advanced {
        routeCacheTtlMillis = 120_000L
        keepaliveIntervalMillis = 30_000L
    }
}
```

Public parameters (e.g., `maxMessageSize`, `maxHops`) are set directly on the
builder. Internal protocol parameters live in the `advanced { }` block. For
backward compatibility, internal parameters can also be set directly on the
builder without the `advanced` wrapper.

See [API Reference § Configuration](api-reference.md#fields) for the complete
field list with defaults and descriptions.

### Using a Preset

Four presets cover common scenarios. Each accepts an optional override block.
See the [API Reference § Presets](api-reference.md#presets) for the full
comparison table.

```kotlin
val chatConfig = MeshLinkConfig.smallPayloadLowLatency {
    routeCacheTtlMillis = 30_000L
}
val fileConfig = MeshLinkConfig.largePayloadHighThroughput()
val powerConfig = MeshLinkConfig.minimalResourceUsage()
val sensorConfig = MeshLinkConfig.minimalOverhead()
```

### Key Configuration Fields

The most commonly tuned fields:

- **`maxMessageSize`** — largest expected payload in bytes (default: 10,000; use `largePayloadHighThroughput` preset for 100 KB)
- **`maxHops`** — limit to 3–5 for latency-sensitive apps (default: 10)
- **`broadcastTtl`** — hop limit for broadcast propagation (default: 2, range: 1–`maxHops`)
- **``routeCacheTtlMillis`** — increase for stable meshes, decrease for mobile (default: 300,000)
- **`rateLimitMaxSends`** — outbound rate limit per window (default: 60; `0` = disabled)
- **`l2capEnabled`** — high-throughput L2CAP mode with GATT fallback (default: true)
- **`trustMode`** — key pinning policy: `SOFT_REPIN` (auto-accept key changes, default) or `STRICT` (reject key changes)
- **`deliveryAckEnabled`** — send signed delivery ACKs for received messages (default: true)
- **`diagnosticsEnabled`** — enable the diagnostic event stream (default: true; zero overhead when disabled)
- **`customPowerMode`** — override automatic battery-based power mode (default: null = automatic)
- **`pendingMessageTtlMillis`** — TTL for queued outbound messages (default: 0 = never expire)

#### Compression

MeshLink compresses payloads with raw DEFLATE (RFC 1951) before encryption
(compress-then-encrypt). Each payload gets a 1-byte envelope prefix: `0x00`
(uncompressed, below threshold) or `0x01` + compressed data.

Disable (`compressionEnabled = false`) if payloads are already compressed
(e.g., JPEG, protobuf). Tune `compressionMinBytes` (default: 128) for your
payload profile.

### Validating Configuration

```kotlin
val violations = meshLinkConfig { mtu = 10 }.validate()
violations.forEach { println("⚠️ $it") }  // "mtu must be > 17 (chunk header size)"
```

---

## Sending & Receiving Messages

### Unicast Messages

```kotlin
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
launch { mesh.deliveryConfirmations.collect { println("✅ Delivered: $it") } }

launch {
    mesh.transferProgress.collect { p ->
        println("📤 ${p.messageId}: ${(p.fraction * 100).toInt()}% (${p.chunksAcked}/${p.totalChunks})")
    }
}

launch { mesh.transferFailures.collect { println("❌ ${it.messageId}: ${it.reason}") } }
```

See [API Reference § DeliveryOutcome](api-reference.md#deliveryoutcome) for
specific failure reasons.

---

## Broadcast Messaging

Broadcast messages are sent to all reachable peers within a hop radius.
They are **not encrypted** (by design — any peer can read them).

```kotlin
val result = mesh.broadcast(
    payload = "Emergency alert!".encodeToByteArray(),
    maxHops = 3u,
)
// Broadcasts arrive on the same `messages` flow as unicast messages
```

> **Tip:** Use `broadcastRateLimitPerMin` to prevent broadcast storms.

---

## Multi-Hop Routing

MeshLink uses Babel (RFC 8966, adapted for BLE) for loop-free routing.
Routes are propagated automatically via Hello/Update messages — no
configuration is needed.

### How Route Propagation Works

When a peer discovers a new neighbor (via BLE advertisement):
1. The peer sends a **Hello** message to the neighbor.
2. The neighbor responds with **Update** messages for all known routes.
3. Each Update carries the destination's public key for E2E encryption.
4. Routes are accepted only if they pass the **feasibility condition**
   (metric < feasibility distance), guaranteeing loop-freedom.

If no proactive route exists when `send()` is called, a fallback route
discovery floods a Hello to all neighbors, triggering Update responses
that install the needed route (typically 1–3 seconds for ≤ 5 hops).

### Route Cache Tuning

```kotlin
val config = meshLinkConfig {
    routeCacheTtlMillis = 120_000L            // Cache routes for 2 minutes (stable mesh)
    routeDiscoveryTimeoutMillis = 10_000L     // Allow 10s for discovery in large meshes
    maxHops = 5u                              // Limit message forwarding to 5 hops
}
```

### Manual Route Injection

```kotlin
mesh.addRoute(
    destination = "peer-hex-id",
    nextHop = "neighbor-hex-id",
    cost = 1.5,
)
```

### Route Cost

Routes are ranked by composite cost
(`rssiBase × lossMultiplier × freshnessPenalty + stabilityPenalty`),
considering RSSI, packet loss rate, freshness, and stability.

---

## Power Mode Management

MeshLink automatically adjusts BLE behavior based on battery level.

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
Downward transitions are delayed by 30 seconds (hysteresis) to prevent rapid
mode flapping. Upward transitions and charging are immediate.

### Chunk Size Adaptation

| Mode        | Max Chunk Payload |
|-------------|-------------------|
| PERFORMANCE | 8,192 bytes       |
| BALANCED    | 4,096 bytes       |
| POWER_SAVER | 1,024 bytes       |

The effective chunk size is `min(modeMax, mtu - 17)`.

---

## Encryption & Trust

MeshLink provides two layers of encryption when a `CryptoProvider` is supplied.

### Enabling Encryption

```kotlin
val mesh = MeshLink(
    transport = transport,
    config = config,
    crypto = CryptoProvider(),
    trustStore = TrustStore(mode = TrustMode.STRICT),
)
```

### Encryption Layers

| Layer               | Protocol    | Scope            | Purpose                            |
|---------------------|-------------|------------------|------------------------------------|
| Hop-by-hop          | Noise XX    | Per BLE link     | Session keys, mutual authentication, forward secrecy |
| End-to-end          | Noise K     | Per message      | Sender-authenticated encryption with ephemeral keys  |

**Noise XX** — automatic handshake on peer discovery producing ChaCha20-Poly1305
session keys. **Noise K** — each unicast is sealed with an ephemeral X25519 key
pair; ephemeral keys are discarded after use (per-message forward secrecy).

### Trust Modes

| Mode          | Behavior on Key Change                          |
|---------------|------------------------------------------------|
| `SOFT_REPIN` (default) | Accept new key and re-pin; emit `KeyChangeEvent`|
| `STRICT`      | Reject the peer; emit `KeyChangeEvent`          |

### Monitoring Key Changes

```kotlin
launch {
    mesh.keyChanges.collect { event ->
        println("⚠️ ${event.peerId.toHex()} rotated: ${event.previousKey.toHex()} → ${event.newKey.toHex()}")
    }
}
```

### Identity & Key Management

```kotlin
mesh.rotateIdentity().onSuccess {
    println("New key: ${mesh.localPublicKey?.toHex()}")
}

val myKey = mesh.localPublicKey            // Local Ed25519 public key
val peerKey = mesh.peerPublicKey("abcd...") // Peer's public key from handshake
val broadcastKey = mesh.broadcastPublicKey  // Key for broadcast verification
```

See [Threat Model](threat-model.md) for the security analysis.

---

## Diagnostics & Monitoring

### Real-Time Diagnostic Events

```kotlin
launch {
    mesh.diagnosticEvents.collect { event ->
        println("[${event.severity}] ${event.code}: ${event.payload}")
    }
}
```

See [API Reference § Diagnostics](api-reference.md#diagnostics) for
`DiagnosticEvent` fields and the complete list of 21 diagnostic codes.

### Mesh Health Snapshots

```kotlin
val health = mesh.meshHealth()
println("Peers: ${health.connectedPeers}/${health.reachablePeers}, Buffer: ${health.bufferUtilizationPercent}%")

launch {
    mesh.meshHealthFlow.collect { health -> updateDashboard(health) }
}
```

See [API Reference § MeshHealthSnapshot](api-reference.md#meshhealthsnapshot)
for the full field list.

### Memory Management

```kotlin
mesh.sweep(currentlySeenPeerIds)                     // Remove unseen peers
mesh.sweepStaleTransfers(maxAgeMillis = 60_000L)     // Clean up stale transfers
mesh.sweepStaleReassemblies(maxAgeMillis = 60_000L)  // Clean up stale reassembly buffers
mesh.sweepExpiredPendingMessages()                   // Remove expired pending messages
mesh.shedMemoryPressure()                            // Emergency memory pressure relief
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

- **Validate config** — call `config.validate()` during development.
- **Collect all flows** — launch collectors for `messages`, `peers`,
  `transferFailures`, and `diagnosticEvents` before calling `start()`.
- **Use presets** — start with a preset and override only what you need.

### Performance

- **Set `maxHops` realistically** — limit to 3–5 for chat apps.
- **Tune route cache TTL** — increase for stable meshes, decrease for mobile.
- **Report battery state** — call `updateBattery()` on battery change
  broadcasts so MeshLink can adapt automatically.
- **Sweep periodically** — call `sweepStaleTransfers()` and
  `sweepStaleReassemblies()` on a timer (e.g., every 30–60 s).

### Security

- **Always provide a `CryptoProvider`** in production. Without it, `start()`
  fails by default (`requireEncryption = true`).
- **Use `TrustMode.STRICT`** unless you have a specific need for re-pinning.
- **Monitor `keyChanges`** — alert users when a peer's key changes unexpectedly.
- **Tune rate limiting** — adjust for your use case; set to `0` to disable.

### Resource Management

- **Set `pendingMessageTtlMillis`** — prevents unbounded growth of the pending
  message queue.
- **Monitor `bufferUtilizationPercent`** — react before hitting capacity.
- **Handle `BUFFER_PRESSURE` diagnostics** — shed load or pause sending when
  buffer is above 80%.
- **Configure `circuitBreakerMaxFailures`** — automatically stop sending to
  persistently failing peers.
