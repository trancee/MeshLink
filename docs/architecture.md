# MeshLink Architecture

Technical architecture overview of MeshLink — a Kotlin Multiplatform BLE mesh
messaging library for Android and iOS.

---

## Table of Contents

1. [System Overview](#system-overview)
2. [Module Responsibilities](#module-responsibilities)
3. [Message Flow](#message-flow)
4. [Routing](#routing)
5. [Security Model](#security-model)
6. [Power Management](#power-management)
7. [Transfer & Congestion Control](#transfer--congestion-control)
8. [Extension Points](#extension-points)
9. [Wire Protocol](#wire-protocol)
10. [Platform Architecture](#platform-architecture)

---

## System Overview

```mermaid
flowchart TD
    subgraph App["Application Layer"]
        direction LR
        API["MeshLinkApi Interface"]
        Flows["peers · messages · deliveryConfirmations · keyChanges"]
        Methods["send() · broadcast() · meshHealth() · updateBattery()"]
    end

    subgraph Core["Core Engines (commonMain ~85%)"]
        direction TB
        subgraph Row1[" "]
            direction LR
            Routing["🗺️ Routing<br/>DSDV · Gossip<br/>Cost · Dedup"]
            Transfer["📦 Transfer<br/>Chunking · SACK<br/>AIMD · Schedule"]
            Crypto["🔒 Crypto<br/>Noise XX · Noise K<br/>Trust · Replay"]
            Diag["📊 Diagnostics<br/>DiagnosticSink<br/>Health · Snapshots"]
        end
        subgraph Row2[" "]
            direction LR
            Power["⚡ Power<br/>Modes · Policy<br/>Shedding · Chunks"]
            Wire["📡 Wire<br/>Codec · Encode<br/>Decode"]
            Util["🔧 Util<br/>RateLimit · Circuit<br/>Breaker · Pause"]
            Storage["💾 Storage<br/>SecureStorage<br/>Keychain / Keystore"]
        end
    end

    subgraph Transport["BLE Transport Layer"]
        direction LR
        BleAPI["BleTransport Interface"]
        Android["Android<br/>GATT + L2CAP"]
        iOS["iOS<br/>CoreBluetooth"]
    end

    App --> Core --> Transport
```

MeshLink is structured as a layered library with a clean boundary between
platform-agnostic logic (in `commonMain`) and platform-specific I/O (in
`androidMain` / `iosMain`). Approximately 85% of the code is shared.

---

## Module Responsibilities

### `io.meshlink` — Core

| File | Responsibility |
|------|---------------|
| `MeshLinkApi.kt` | Public interface — all application-facing methods and flows. |
| `MeshLink.kt` | Implementation: orchestrates all subsystems, owns coroutine scopes, dispatches events. |

### `io.meshlink.config` — Configuration

| File | Responsibility |
|------|---------------|
| `MeshLinkConfig.kt` | Immutable config data class with 30+ fields, validation, presets (`chatOptimized`, `fileTransferOptimized`, `powerOptimized`), and DSL builder. |

### `io.meshlink.routing` — Mesh Routing

| File | Responsibility |
|------|---------------|
| `RoutingEngine.kt` | Facade consolidating routing table, presence, dedup, gossip preparation (split-horizon/poison-reverse), and adaptive timing behind sealed result types. |
| `RoutingTable.kt` | Enhanced DSDV routing table with expiry, settling, holddown, and neighbor capacity limits. |
| `RouteCostCalculator.kt` | Composite cost metric from RSSI, packet loss, freshness, and stability. |
| `GossipTracker.kt` | Differential gossip — tracks per-peer route state, computes deltas and withdrawals. |
| `DedupSet.kt` | LRU set for message deduplication (default 10K entries). |
| `PresenceTracker.kt` | Tracks peer liveness for route validity. |
| `DeliveryAckRouter.kt` | Routes delivery ACKs back along the reverse path of routed messages. |

### `io.meshlink.transfer` — Message Transfer

| File | Responsibility |
|------|---------------|
| `TransferEngine.kt` | Facade consolidating outbound chunking (AIMD/SACK) and inbound reassembly behind sealed result types. |
| `TransferSession.kt` | Sender-side state machine for multi-chunk transfers. |
| `SackTracker.kt` | Receiver-side selective acknowledgement tracking. |
| `AimdController.kt` | Additive Increase / Multiplicative Decrease congestion control. |
| `TransferScheduler.kt` | Fair round-robin scheduling across concurrent transfers with power-mode limits. |
| `ChunkSizePolicy.kt` | Power-aware chunk payload sizing. |
| `CutThroughBuffer.kt` | Receiver-side reassembly buffer for out-of-order chunks. |

### `io.meshlink.crypto` — Security

| File | Responsibility |
|------|---------------|
| `SecurityEngine.kt` | Facade consolidating E2E encryption, signatures, handshakes, and peer key lifecycle. |
| `CryptoProvider.kt` | Interface for all cryptographic primitives (Ed25519, X25519, ChaCha20-Poly1305, SHA-256, HKDF). |
| `SecureRandom.kt` | `expect/actual` CSPRNG — delegates to `java.security.SecureRandom` (JVM/Android), `SecRandomCopyBytes` (Apple), `/dev/urandom` (Linux). |
| `NoiseXXHandshake.kt` | Noise XX handshake — mutual authentication with forward secrecy. |
| `PeerHandshakeManager.kt` | Multi-peer handshake orchestration and session key storage. |
| `NoiseKSealer.kt` | Per-message end-to-end encryption using Noise K pattern. |
| `ReplayGuard.kt` | 64-entry sliding window replay protection per sender. |
| `TrustStore.kt` | Key pinning with STRICT and SOFT_REPIN modes. |

> **CSPRNG design note:** `kotlin.random.Random.Default` happens to use platform-secure
> sources on current Kotlin versions, but the stdlib docs do not guarantee cryptographic
> security. `secureRandomBytes()` uses canonical platform APIs via `expect/actual`,
> making the security contract explicit and immune to future Kotlin changes.

### `io.meshlink.power` — Power Management

| File | Responsibility |
|------|---------------|
| `PowerCoordinator.kt` | Facade consolidating power-mode transitions, memory-pressure evaluation, and buffer-pressure monitoring behind sealed result types. |
| `PowerProfile.kt` | Single source of truth for all power-mode-dependent constants (advertising interval, scan timing, max connections, gossip multiplier). Replaces scattered `when(mode)` lookups. |
| `PowerModeEngine.kt` | Hysteresis-based mode transitions driven by battery level and charging state. |
| `AdvertisingPolicy.kt` | Maps power mode to BLE advertising interval and scan duty cycle. |
| `TieredShedder.kt` | Three-tier memory shedding strategy (MODERATE → HIGH → CRITICAL). |
| `ConnectionLimiter.kt` | Manages per-mode connection limits with eviction on power mode downgrade. |

### `io.meshlink.send` — Send Policy

| File | Responsibility |
|------|---------------|
| `SendPolicyChain.kt` | Pure pre-flight evaluator for outbound sends; chains buffer, pause, rate-limit, circuit-breaker, routing, and crypto checks into sealed `SendDecision`. |
| `BroadcastPolicyChain.kt` | Pure pre-flight evaluator for broadcast sends; chains buffer and rate-limit checks, then constructs a signed encoded frame via sealed `BroadcastDecision`. |

### `io.meshlink.dispatch` — Message Dispatch

| File | Responsibility |
|------|---------------|
| `MessageDispatcher.kt` | Dispatches inbound BLE frames to typed handlers; owns decode pipeline and delegates effects via `DispatchSink`. |
| `InboundValidator.kt` | Pre-dispatch validation: signatures, replay counters, loop detection, hop limits, rate limiting, app-ID filtering, and decryption. |
| `OutboundTracker.kt` | Tracks outbound message state: recipient mapping, next-hop tracking, and monotonic replay counter. |
| `DispatchSink.kt` | Callback interface grouping 8 effect callbacks for flow emissions and transport sends. |

### `io.meshlink.gossip` — Gossip Coordination

| File | Responsibility |
|------|---------------|
| `GossipCoordinator.kt` | Coordinates routing protocol maintenance: gossip broadcasting, keepalive pings, and triggered route updates with batching and throttling. |

### `io.meshlink.peer` — Peer Connection

| File | Responsibility |
|------|---------------|
| `PeerConnectionCoordinator.kt` | Coordinates BLE peer discovery: version negotiation, routing presence, security key registration, and handshake initiation via sealed `PeerConnectionAction`. |

### `io.meshlink.model` — Domain Model

| File | Responsibility |
|------|---------------|
| `Identifiers.kt` | Inline value classes `PeerId` and `MessageId` wrapping hex strings for compile-time type safety and correct map-key equality. |
| `Message.kt` | Inbound message data class. |
| `PeerEvent.kt` | Sealed interface for peer discovery/loss events. |
| `KeyChangeEvent.kt` | Key rotation notification data class. |
| `TransferFailure.kt` | Transfer failure event data class. |
| `TransferProgress.kt` | Transfer progress event data class. |

### `io.meshlink.wire` — Wire Protocol

| File | Responsibility |
|------|---------------|
| `WireCodec.kt` | Binary encoding/decoding for all 11 message types. Constants for type bytes and header sizes. |

### `io.meshlink.transport` — Transport Abstraction

| File | Responsibility |
|------|---------------|
| `BleTransport.kt` | Interface for platform-specific BLE I/O. |

### `io.meshlink.diagnostics` — Observability

| File | Responsibility |
|------|---------------|
| `DiagnosticSink.kt` | Event emission via `SharedFlow`, 19 diagnostic codes, severity levels. |
| `MeshHealthSnapshot.kt` | Point-in-time mesh state data class. |

### `io.meshlink.storage` — Persistence

| File | Responsibility |
|------|---------------|
| `SecureStorage.kt` | Interface for platform-specific secure key-value storage. |

### `io.meshlink.delivery` — Delivery Pipeline

| File | Responsibility |
|------|---------------|
| `DeliveryPipeline.kt` | Facade consolidating delivery tracking, tombstones, deadline timers, reverse-path relay, replay guards, inbound rate limiting, and store-and-forward buffering behind sealed result types. |
| `DeliveryTracker.kt` | Internal PENDING→RESOLVED state machine for first-terminal-signal delivery tracking. |
| `DeliveryDeadlineTimer.kt` | Internal coroutine-based per-message timeout with diagnostic emission. |
| `TombstoneSet.kt` | Internal time-windowed set for late-ACK detection. |

### `io.meshlink.util` — Utilities

| File | Responsibility |
|------|---------------|
| `DeliveryOutcome.kt` | Enum of delivery outcomes (confirmed, failed variants). |
| `RateLimitPolicy.kt` | Facade consolidating 7 rate limiters and circuit breaker behind sealed `RateLimitResult` type. |
| `PauseManager.kt` | Manages paused state with bounded send and relay queues; returns `PauseSnapshot` on resume for deferred flush. |
| `RateLimiter.kt` | Sliding window rate limiting (per-key token bucket). |
| `CircuitBreaker.kt` | Fault isolation with closed → open → half-open state machine. |
| `HexUtil.kt` | Hex encoding/decoding. |
| `PlatformLock.kt` | Multiplatform mutex (`expect`/`actual`). |

---

## Message Flow

### Outbound Unicast

```mermaid
flowchart TD
    App["Application calls send(recipient, payload)"]
    App --> PolicyChain

    subgraph PolicyChain["SendPolicyChain (pre-flight)"]
        direction TB
        P1{"Buffer full?"} -->|Yes| Fail1["Result.failure(bufferFull)"]
        P1 -->|No| P2{"Loopback?"}
        P2 -->|Yes| Loopback["Deliver to self"]
        P2 -->|No| P3{"Paused?"}
        P3 -->|Yes| Queue["Queue in PauseManager"]
        P3 -->|No| P4{"Rate limited?"}
        P4 -->|Yes| Fail2["Result.failure(rateLimited)"]
        P4 -->|No| P5{"Circuit breaker open?"}
        P5 -->|Yes| Fail3["Result.failure(circuitBreakerOpen)"]
        P5 -->|No| P6{"Route exists?"}
        P6 -->|No| Fail4["Result.failure(unreachable)"]
        P6 -->|Yes| OK["SendDecision.Direct / .Routed"]
    end

    OK --> GenID["Generate message UUID"]
    GenID --> Seal

    subgraph Seal["Noise K Seal (if crypto)"]
        direction TB
        S1["Generate ephemeral X25519 keypair"] --> S2["ECDH: ephemeral × recipient static"]
        S2 --> S3["HKDF-SHA256 key derivation"]
        S3 --> S4["ChaCha20-Poly1305 encrypt"]
    end

    Seal --> Chunk["Chunk into MTU-sized segments"]
    Chunk --> Route{"Direct neighbor?"}
    Route -->|Yes| Direct["Send via BleTransport"]
    Route -->|No| Routed["Wrap as TYPE_ROUTED_MESSAGE → nextHop"]
    Direct --> ACK["Wait for Chunk ACKs (AIMD window)"]
    Routed --> ACK
    ACK --> DeliveryACK["Delivery ACK received → emit confirmation"]
```

### Inbound Unicast

```mermaid
flowchart TD
    BLE["BLE Transport incomingData"] --> Decode["WireCodec.decode (type byte)"]

    Decode --> |"0x01 HANDSHAKE"| Handshake["PeerHandshakeManager"]
    Decode --> |"0x03 CHUNK"| ChunkPipeline
    Decode --> |"0x04 CHUNK_ACK"| AckUpdate["Update TransferSession"]
    Decode --> |"0x02 ROUTE_UPDATE"| RouteLearn["RoutingEngine.learnRoutes"]
    Decode --> |"0x00 BROADCAST"| Broadcast["Dedup → emit on messages"]
    Decode --> |"0x05 ROUTED"| RoutedMsg["Forward or deliver"]
    Decode --> |"0x06 DELIVERY_ACK"| DelAck["DeliveryAckRouter"]
    Decode --> |"0x08 KEEPALIVE"| Keepalive["Update presence"]
    Decode --> |"0x09 NACK"| Nack["Handle negative ack"]
    Decode --> |"0x0A ROTATION"| Rotation["TrustStore → KeyChangeEvent"]

    subgraph ChunkPipeline["Chunk Processing"]
        direction TB
        C1["Rate limit checks"] --> C2["Dedup check"]
        C2 --> C3["SackTracker.record(seqNum)"]
        C3 --> C4["Send CHUNK_ACK + SACK bitmask"]
        C4 --> C5{"All chunks received?"}
        C5 -->|No| Wait["Wait for more chunks"]
        C5 -->|Yes| Reassemble["Reassemble payload"]
    end

    Reassemble --> Unseal["Noise K unseal (if crypto)"]
    Unseal --> Filter["App ID filter check"]
    Filter --> Emit["Emit Message on messages flow"]
    Emit --> SendAck["Send DELIVERY_ACK to sender"]
```

### Routed Message Forwarding

```mermaid
flowchart TD
    Recv["Receive TYPE_ROUTED_MESSAGE"] --> Check{"Am I the destination?"}
    Check -->|Yes| Decrypt["Decrypt + deliver locally"]
    Check -->|No| Relay

    subgraph Relay["Relay Processing"]
        direction TB
        R1{"Hop count > 0?"} -->|No| Drop["Discard (TTL expired)"]
        R1 -->|Yes| R2{"Seen this message ID?"}
        R2 -->|Yes| DropDup["Discard (loop)"]
        R2 -->|No| R3{"Relay queue has capacity?"}
        R3 -->|No| NackFull["Send NACK(bufferFull)"]
        R3 -->|Yes| R4["Route lookup → forward to nextHop"]
    end
```

---

## Routing

`RoutingEngine` is the facade that consolidates routing table management,
peer presence tracking, message deduplication, gossip preparation
(split-horizon / poison-reverse), and adaptive gossip timing behind sealed
result types (`NextHopResult`, `RouteLearnResult`). MeshLink delegates all
routing decisions to `RoutingEngine` and pattern-matches on results.

MeshLink uses an enhanced DSDV (Destination-Sequenced Distance-Vector)
protocol with gossip-based route propagation.

### Routing Table

`RoutingTable` maintains destination → route mappings with:

- **Sequence numbers** — DSDV ordering (higher wins for same destination)
- **Route expiry** — configurable TTL for stale routes
- **Settling delay** — hold new routes briefly before advertising
- **Holddown timer** — suppress route flapping
- **Neighbor cap** — limit routing table entries proportional to neighbor count
- **Primary + backup** — tracks multiple next-hops per destination

### Composite Cost Metric

`RouteCostCalculator` produces a cost value from four inputs:

```
cost = (rssiBase × lossMultiplier × freshnessPenalty) + stabilityPenalty

Where:
  rssiBase         = (rssi − (−20)) / (−100 − (−20))        ∈ [0, 1]
  lossMultiplier   = 1 / (1 − packetLossRate)                1.0 at 0%, 100 at 99%
  freshnessPenalty  = 1.0 + ageMillis / freshnessHalfLifeMillis      1.0 new, 2.0 at half-life
  stabilityPenalty  = flapCount × penaltyPerFlap (default 0.1)
```

Lower cost is better. The result is clamped to `(0, MAX_ROUTE_COST]`.

### Gossip Protocol

`GossipTracker` implements differential gossip to minimize bandwidth:

1. **Per-peer state tracking** — remembers which routes were last sent to each
   peer (destination, sequence number, cost).
2. **Diff computation** — `computeDiff()` returns only routes that are new or
   changed since the last advertisement to that peer.
3. **Withdrawal detection** — `computeWithdrawals()` identifies routes the peer
   knows about but we no longer have (sent with `cost = ∞`).
4. **Triggered updates** — a cost change exceeding `triggeredUpdateThreshold`
   (default 30%) triggers an immediate advertisement, batched within
   `triggeredUpdateBatchMillis` (default 100 ms).

### Deduplication

`DedupSet` is an LRU set (default 10K entries) that tracks message IDs. When a
message arrives, `tryInsert()` returns `true` if the ID is new, `false` if
already seen. On capacity overflow, the least-recently-used entry is evicted.

---

## Security Model

MeshLink implements a two-layer encryption architecture when a `CryptoProvider`
is supplied. For full details, see [design.md §5](design.md#5-security) and
[diagrams.md § Noise XX Handshake](diagrams.md#5-noise-xx-handshake).

| Layer | Protocol | Scope | Purpose |
|-------|----------|-------|---------|
| Hop-by-hop | Noise XX | Per BLE link | Mutual authentication, session keys, forward secrecy |
| End-to-end | Noise K | Per message | Sender-authenticated encryption, relay-opaque |

**Key components:**

- `SecurityEngine` — facade consolidating E2E encryption, signatures, handshakes, and peer key lifecycle
- `NoiseXXHandshake` + `PeerHandshakeManager` — 3-message mutual auth handshake
- `NoiseKSealer` — per-message E2E encryption (48B overhead: 32B ephemeral key + 16B auth tag)
- `ReplayGuard` — 64-entry sliding window bitmask per sender
- `TrustStore` — TOFI key pinning with `STRICT` and `SOFT_REPIN` modes

**Cryptographic primitives** — all pure Kotlin with no native bindings:
Ed25519 (signatures), X25519 (key agreement), ChaCha20-Poly1305 (AEAD),
SHA-256 (hashing), HKDF-SHA256 (key derivation).

---

## Power Management

`PowerCoordinator` is the facade that consolidates power-mode transitions
(with hysteresis), memory-pressure evaluation, and buffer-pressure monitoring
behind sealed result types. For full details and state diagrams, see
[design.md §7](design.md#7-power-management) and
[diagrams.md § Power Mode Transitions](diagrams.md#6-power-mode-transitions).

MeshLink automatically adapts BLE behavior based on battery level across
three tiers:

| Mode | Battery | Adv Interval | Scan Duty | Max Transfers | Chunk Payload |
|------|---------|-------------|-----------|---------------|---------------|
| PERFORMANCE | >80% or charging | 250 ms | 80% | 8 | 8,192 B |
| BALANCED | 30–80% | 500 ms | 50% | 4 | 4,096 B |
| POWER_SAVER | <30% | 1,000 ms | 16% | 1 | 1,024 B |

Thresholds configurable via `powerModeThresholds` (default `[80, 30]`).
Downward transitions delayed by 30s hysteresis; upward transitions and
charging are immediate. Memory pressure shedding escalates through three
levels (MODERATE → HIGH → CRITICAL) based on buffer utilization.

---

## Transfer & Congestion Control

`TransferEngine` consolidates outbound chunking and inbound reassembly behind
sealed result types. For wire format details, see
[wire-format-spec.md](wire-format-spec.md) and
[diagrams.md § GATT Chunking & SACK Flow](diagrams.md#7-gatt-chunking--sack-flow).

- **Chunking** — messages larger than `mtu - 21` bytes are split into chunks
  with a 21-byte header (type + messageId + seqNum + totalChunks).
- **SACK** — receiver tracks arrivals via `SackTracker` (cumulative ackSeq +
  64-bit bitmask). Sender retransmits only missing chunks.
- **AIMD congestion control** — `AimdController` increases the window by 2
  after 4 clean rounds; halves it after 2 consecutive timeouts
  (min: `ackWindowMin`, max: `ackWindowMax`).
- **Transfer scheduling** — `TransferScheduler` limits concurrent transfers
  per power mode and uses weighted round-robin interleaving.

---

## Extension Points

MeshLink is designed for testability and platform flexibility through three
key interfaces:

### BleTransport

**Purpose:** Abstract BLE hardware so `MeshLink` never touches platform APIs
directly.

| Implementation | Platform | Description |
|---------------|----------|-------------|
| `AndroidBleTransport` | Android | GATT server/client + L2CAP CoC |
| `IosBleTransport` | iOS | CoreBluetooth central/peripheral |
| `VirtualMeshTransport` | Tests | In-memory simulated mesh (no hardware) |

### CryptoProvider

**Purpose:** Abstract cryptographic primitives for cross-platform support and
testing.

| Implementation | Description |
|---------------|-------------|
| `PureKotlinCryptoProvider` | Pure Kotlin crypto (used on all platforms) |

Created via the `expect`/`actual` factory: `createCryptoProvider()`.

### SecureStorage

**Purpose:** Abstract platform-specific secure key-value storage.

| Implementation | Platform | Backend |
|---------------|----------|---------|
| `EncryptedSharedPreferences` | Android | Android Keystore |
| Keychain wrapper | iOS | `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` |
| `InMemorySecureStorage` | Tests | In-memory `Map` |

---

## Wire Protocol

MeshLink uses a binary wire protocol (version 1.0) with 11 message types.

### Message Types

| Type Byte | Name | Description |
|-----------|------|-------------|
| `0x00` | `TYPE_BROADCAST` | Unencrypted broadcast message |
| `0x01` | `TYPE_HANDSHAKE` | Noise XX handshake message |
| `0x02` | `TYPE_ROUTE_UPDATE` | DSDV route advertisement (gossip) |
| `0x03` | `TYPE_CHUNK` | Unicast message chunk |
| `0x04` | `TYPE_CHUNK_ACK` | Chunk acknowledgement with SACK |
| `0x05` | `TYPE_ROUTED_MESSAGE` | Multi-hop forwarded message |
| `0x06` | `TYPE_DELIVERY_ACK` | End-to-end delivery confirmation |
| `0x07` | `TYPE_RESUME_REQUEST` | Resume an interrupted transfer |
| `0x08` | `TYPE_KEEPALIVE` | Idle connection keepalive |
| `0x09` | `TYPE_NACK` | Negative acknowledgement |
| `0x0A` | `TYPE_ROTATION` | Identity key rotation announcement |

### BLE Advertisement Payload

17 bytes: `version+power(1B) + keyHash(16B)`

- Bits 0–3 of byte 0: protocol version
- Bits 4–5 of byte 0: power mode (0 = PERFORMANCE, 1 = BALANCED, 2 = POWER_SAVER)
- Bytes 1–16: truncated SHA-256 of the local Ed25519 public key

### Byte Order

All multi-byte integers in chunk headers and ACKs use **little-endian** byte
order. See `docs/wire-format-spec.md` for the complete protocol specification.

---

## Platform Architecture

### Kotlin Multiplatform Structure

```
meshlink/
├── src/
│   ├── commonMain/          ← ~85% of code (platform-agnostic)
│   │   └── kotlin/io/meshlink/
│   │       ├── MeshLink.kt           Core implementation
│   │       ├── MeshLinkApi.kt        Public interface
│   │       ├── config/               Configuration
│   │       ├── crypto/               Noise XX, Noise K, trust, replay
│   │       ├── delivery/             Delivery pipeline, tracking, tombstones
│   │       ├── diagnostics/          Events, health snapshots
│   │       ├── dispatch/             Inbound frame dispatch
│   │       ├── gossip/               Gossip coordination
│   │       ├── model/                Message, PeerEvent, identifiers
│   │       ├── peer/                 Peer connection coordination
│   │       ├── power/                Power mode engine, profiles, policies
│   │       ├── protocol/             Protocol version
│   │       ├── routing/              DSDV, gossip, cost, dedup
│   │       ├── send/                 Send and broadcast policy chains
│   │       ├── storage/              SecureStorage interface
│   │       ├── transfer/             AIMD, SACK, chunking, scheduling
│   │       ├── transport/            BleTransport interface
│   │       ├── util/                 Rate limiter, circuit breaker, etc.
│   │       └── wire/                 Wire codec
│   │
│   ├── androidMain/         ← Android platform bindings
│   │   └── kotlin/io/meshlink/
│   │       ├── crypto/               CryptoProvider (JCA-backed)
│   │       ├── platform/             MeshLinkService
│   │       ├── power/                BatteryMonitor
│   │       ├── storage/              SecureStorage (Keystore)
│   │       ├── transport/            BleTransport, BleConstants
│   │       └── util/                 PlatformLock, Time
│   │
│   ├── iosMain/             ← iOS platform bindings
│   │   └── kotlin/io/meshlink/
│   │       ├── crypto/               CryptoProvider (CryptoKit)
│   │       ├── platform/             StatePreservation
│   │       ├── power/                BatteryMonitor
│   │       ├── storage/              SecureStorage (Keychain)
│   │       ├── transport/            BleTransport
│   │       └── util/                 PlatformLock, Time
│   │
│   └── commonTest/          ← Shared test suite
```

### Build Targets

| Target | Min Version | Notes |
|--------|-------------|-------|
| Android | API 26 (Android 8.0) | compileSdk 36 |
| iOS arm64 | — | Physical devices |
| iOS simulator arm64 | — | Apple Silicon simulators |
| JVM | — | For desktop/server testing |

### Dependencies

- `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2` — the **only**
  runtime dependency.
- All cryptography is pure Kotlin (no BouncyCastle, no native libs).

### expect/actual Pattern

Platform-specific code uses Kotlin's `expect`/`actual` mechanism:

| expect Declaration | Android actual | iOS actual |
|-------------------|----------------|------------|
| `createCryptoProvider()` | `PureKotlinCryptoProvider` | `PureKotlinCryptoProvider` |
| `createPlatformLock()` | JVM `ReentrantLock` wrapper | iOS mutex wrapper |
| `currentTimeMillis()` | `System.currentTimeMillis()` | iOS time function |
