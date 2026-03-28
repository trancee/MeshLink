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

```
┌─────────────────────────────────────────────────────────────────┐
│                        Application                              │
│                                                                 │
│   peers ◄── messages ◄── deliveryConfirmations ◄── keyChanges   │
│   send() ──► broadcast() ──► meshHealth() ──► updateBattery()   │
├─────────────────────────────────────────────────────────────────┤
│                     MeshLinkApi Interface                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌───────────────┐   │
│  │ Routing   │  │ Transfer │  │ Crypto   │  │ Diagnostics   │   │
│  │          │  │          │  │          │  │               │   │
│  │ DSDV     │  │ Chunking │  │ Noise XX │  │ DiagnosticSink│   │
│  │ Gossip   │  │ SACK     │  │ Noise K  │  │ Health        │   │
│  │ Cost     │  │ AIMD     │  │ Trust    │  │ Snapshots     │   │
│  │ Dedup    │  │ Schedule │  │ Replay   │  │               │   │
│  └──────────┘  └──────────┘  └──────────┘  └───────────────┘   │
│                                                                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌───────────────┐   │
│  │ Power    │  │ Wire     │  │ Util     │  │ Storage       │   │
│  │          │  │          │  │          │  │               │   │
│  │ Modes    │  │ Codec    │  │ Rate     │  │ SecureStorage │   │
│  │ Policy   │  │ Encode   │  │ Limiter  │  │ (Keychain /   │   │
│  │ Shedding │  │ Decode   │  │ Circuit  │  │  Keystore)    │   │
│  │ Chunks   │  │          │  │ Breaker  │  │               │   │
│  └──────────┘  └──────────┘  └──────────┘  └───────────────┘   │
│                                                                 │
├─────────────────────────────────────────────────────────────────┤
│                   BleTransport Interface                         │
├─────────────────────────────────────────────────────────────────┤
│           Android (GATT + L2CAP)  │  iOS (CoreBluetooth)        │
└───────────────────────────────────┴─────────────────────────────┘
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
| `RoutingTable.kt` | Enhanced DSDV routing table with expiry, settling, holddown, and neighbor capacity limits. |
| `RouteCostCalculator.kt` | Composite cost metric from RSSI, packet loss, freshness, and stability. |
| `GossipTracker.kt` | Differential gossip — tracks per-peer route state, computes deltas and withdrawals. |
| `DedupSet.kt` | LRU set for message deduplication (default 10K entries). |
| `PresenceTracker.kt` | Tracks peer liveness for route validity. |
| `DeliveryAckRouter.kt` | Routes delivery ACKs back along the reverse path of routed messages. |

### `io.meshlink.transfer` — Message Transfer

| File | Responsibility |
|------|---------------|
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
| `NoiseXXHandshake.kt` | Noise XX handshake — mutual authentication with forward secrecy. |
| `PeerHandshakeManager.kt` | Multi-peer handshake orchestration and session key storage. |
| `NoiseKSealer.kt` | Per-message end-to-end encryption using Noise K pattern. |
| `ReplayGuard.kt` | 64-entry sliding window replay protection per sender. |
| `TrustStore.kt` | Key pinning with STRICT and SOFT_REPIN modes. |

### `io.meshlink.power` — Power Management

| File | Responsibility |
|------|---------------|
| `PowerModeEngine.kt` | Hysteresis-based mode transitions driven by battery level and charging state. |
| `AdvertisingPolicy.kt` | Maps power mode to BLE advertising interval and scan duty cycle. |
| `TransferScheduler.kt` | Adjusts max concurrent transfers per power mode. |
| `ChunkSizePolicy.kt` | Adjusts chunk payload size per power mode. |
| `TieredShedder.kt` | Three-tier memory shedding strategy (MODERATE → HIGH → CRITICAL). |

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

### `io.meshlink.util` — Utilities

| File | Responsibility |
|------|---------------|
| `RateLimiter.kt` | Sliding window rate limiting (used for sends, broadcasts, handshakes, NACKs, neighbors). |
| `CircuitBreaker.kt` | Fault isolation with closed → open → half-open state machine. |
| `DeliveryTracker.kt` | Per-message delivery outcome tracking (PENDING → RESOLVED). |
| `TombstoneSet.kt` | Tracks recently-delivered message IDs to suppress reordered duplicates. |
| `HexUtil.kt` | Hex encoding/decoding. |
| `PlatformLock.kt` | Multiplatform mutex (`expect`/`actual`). |

---

## Message Flow

### Outbound Unicast

```
Application
    │
    ▼
send(recipient, payload)
    │
    ├─ Rate limiter check ──► FAIL → Result.failure()
    ├─ Circuit breaker check ──► FAIL → Result.failure()
    ├─ Buffer capacity check ──► FAIL → Result.failure()
    │
    ▼
Generate message UUID
    │
    ▼
Noise K seal (if crypto enabled)
    ├─ Generate ephemeral X25519 key pair
    ├─ ECDH: ephemeral private × recipient static public
    ├─ HKDF-SHA256 key derivation
    └─ ChaCha20-Poly1305 encrypt
        Output: [32B ephemeral pub | ciphertext | 16B tag]
    │
    ▼
Chunk into segments
    ├─ Effective chunk size = min(powerModeMax, mtu - 21)
    ├─ Each chunk: type(1) + messageId(16) + seqNum(2) + totalChunks(2) + payload
    └─ Create TransferSession (AIMD window = ackWindowMin)
    │
    ▼
Route lookup (RoutingTable.bestRoute)
    ├─ Direct neighbor → send via BleTransport
    └─ Multi-hop → wrap as TYPE_ROUTED_MESSAGE → send to nextHop
    │
    ▼
BLE Transport
    ├─ L2CAP (preferred, connection-oriented)
    └─ GATT (fallback, connectionless)
    │
    ▼
Wait for Chunk ACKs
    ├─ ACK received → AIMD window increase
    ├─ SACK bitmask → selective retransmit gaps
    ├─ Timeout → AIMD window decrease, retransmit
    └─ All chunks ACKed → await Delivery ACK
    │
    ▼
Delivery ACK received → emit on deliveryConfirmations flow
    or
Timeout → emit TransferFailure(FAILED_DELIVERY_TIMEOUT)
```

### Inbound Unicast

```
BLE Transport
    │
    ▼
incomingData flow
    │
    ▼
WireCodec.decode (parse type byte + header)
    │
    ├─ TYPE_HANDSHAKE → PeerHandshakeManager
    ├─ TYPE_CHUNK → chunk processing pipeline (below)
    ├─ TYPE_CHUNK_ACK → update TransferSession
    ├─ TYPE_ROUTE_UPDATE → RoutingTable.addRoute
    ├─ TYPE_BROADCAST → deduplicate → emit on messages flow
    ├─ TYPE_ROUTED_MESSAGE → forward or deliver (see below)
    ├─ TYPE_DELIVERY_ACK → DeliveryAckRouter
    ├─ TYPE_KEEPALIVE → update presence
    ├─ TYPE_NACK → handle negative ack
    ├─ TYPE_ROTATION → TrustStore update → emit KeyChangeEvent
    └─ TYPE_RESUME_REQUEST → resume interrupted transfer
    │
    ▼
Chunk Processing:
    │
    ├─ Neighbor rate limit check
    ├─ Per-sender rate limit check
    ├─ Dedup check (DedupSet)
    ├─ SackTracker.record(seqNum)
    ├─ Send TYPE_CHUNK_ACK with SACK bitmask
    │
    ▼
All chunks received? (SackTracker.isComplete)
    │
    ▼
Reassemble payload
    │
    ▼
Noise K unseal (if crypto enabled)
    ├─ Extract 32B ephemeral public key
    ├─ ECDH: local static private × ephemeral public
    ├─ HKDF-SHA256 key derivation
    └─ ChaCha20-Poly1305 decrypt
    │
    ▼
App ID filter check
    │
    ▼
Emit Message on messages flow
    │
    ▼
Send TYPE_DELIVERY_ACK back to sender
```

### Routed Message Forwarding

```
Receive TYPE_ROUTED_MESSAGE
    │
    ├─ Am I the destination? → decrypt + deliver locally
    │
    └─ Not for me → relay
        ├─ Hop count check (decrement, discard if 0)
        ├─ Loop detection (have I seen this message ID?)
        ├─ Relay queue capacity check
        ├─ Route lookup for destination
        └─ Forward via BleTransport to nextHop
```

---

## Routing

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
  freshnessPenalty  = 1.0 + ageMs / freshnessHalfLifeMs      1.0 new, 2.0 at half-life
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
   `triggeredUpdateBatchMs` (default 100 ms).

### Deduplication

`DedupSet` is an LRU set (default 10K entries) that tracks message IDs. When a
message arrives, `tryInsert()` returns `true` if the ID is new, `false` if
already seen. On capacity overflow, the least-recently-used entry is evicted.

---

## Security Model

MeshLink implements a two-layer encryption architecture when a `CryptoProvider`
is supplied.

### Layer 1: Hop-by-Hop — Noise XX

**Purpose:** Authenticate neighboring peers, establish session keys, provide
forward secrecy for the BLE link.

**Protocol:** Noise XX (bidirectional, mutual authentication)

```
Initiator (I)                      Responder (R)
─────────────────────────────────────────────────
msg1: I → R    e                   Ephemeral public key
msg2: R → I    e, ee, s, es        Ephemeral, ECDH, encrypted static, ECDH
msg3: I → R    s, se               Encrypted static, ECDH
```

After the three-message handshake:
- Both sides have verified each other's Ed25519 static public key.
- Two symmetric transport keys are derived (one per direction).
- All subsequent hop-by-hop traffic is encrypted with ChaCha20-Poly1305.

**Implementation:** `NoiseXXHandshake` (initiator/responder factory methods)
and `PeerHandshakeManager` (multi-peer orchestration), both managed by `SecurityEngine`.

### Layer 2: End-to-End — Noise K

**Purpose:** Encrypt message payload so only the intended recipient can read
it, even when relayed through intermediate peers.

**Protocol:** Noise K pattern (sender-authenticated, one-way)

```
Seal:
  1. Generate ephemeral X25519 key pair (esk, epk)
  2. Compute shared secret: X25519(esk, recipientStaticPub)
  3. Derive key via HKDF-SHA256
  4. Encrypt with ChaCha20-Poly1305
  5. Output: [32B epk | ciphertext | 16B auth tag]
  6. Discard esk (forward secrecy)

Unseal:
  1. Extract 32B epk from sealed data
  2. Compute shared secret: X25519(recipientStaticPriv, epk)
  3. Derive key via HKDF-SHA256
  4. Decrypt with ChaCha20-Poly1305
```

**Overhead:** 48 bytes per message (32B ephemeral key + 16B auth tag).

### Replay Protection

`ReplayGuard` uses a 64-entry sliding window bitmask per sender. Each message
carries a monotonic counter. The guard accepts a counter only if:
- It is greater than the highest seen counter (advances the window), or
- It falls within the 64-entry window and has not been seen before.

### Trust Model — TOFI (Trust On First Identification)

`TrustStore` implements key pinning:

| Mode | First Contact | Key Change |
|------|---------------|------------|
| `STRICT` | Pin key, return `FirstSeen` | Reject, return `KeyChanged(previousKey)` |
| `SOFT_REPIN` | Pin key, return `FirstSeen` | Accept new key, re-pin, return `FirstSeen` |

### Identity Rotation

`rotateIdentity()` generates a new Ed25519 key pair and broadcasts a
`TYPE_ROTATION` (0x0A) wire message. Peers update their trust store and emit
a `KeyChangeEvent` on the `keyChanges` flow.

### Cryptographic Primitives

All cryptography is implemented in **pure Kotlin** with no native bindings:

| Algorithm | Purpose | Implementation |
|-----------|---------|----------------|
| Ed25519 | Digital signatures (identity) | Pure Kotlin (Edwards25519) |
| X25519 | Key agreement (ECDH) | Pure Kotlin (Curve25519) |
| ChaCha20-Poly1305 | AEAD encryption | Pure Kotlin |
| SHA-256 | Hashing | Pure Kotlin |
| HKDF-SHA256 | Key derivation | Pure Kotlin |

---

## Power Management

MeshLink automatically adapts BLE behavior based on device battery level.

### Three-Tier Power Modes

```
Battery Level:  100%        80%           30%          0%
                 │           │             │            │
                 ├───────────┤─────────────┤────────────┤
                 │PERFORMANCE│  BALANCED   │POWER_SAVER │
                 │           │             │            │
Adv Interval:   250 ms       500 ms        1000 ms
Scan Duty:      90%          50%           15%
Max Transfers:  8            4             1
Chunk Payload:  8192 B       4096 B        1024 B
```

Thresholds are configurable via `powerModeThresholds` (default `[80, 30]`).

### Hysteresis

`PowerModeEngine` prevents rapid mode flapping:

- **Downward transitions** (e.g., BALANCED → POWER_SAVER): delayed by
  `hysteresisMs` (default 30 seconds). The battery must remain below the
  threshold for the full window.
- **Upward transitions** (e.g., POWER_SAVER → BALANCED): immediate.
- **Charging detected**: immediately promotes to PERFORMANCE.

### Advertising & Scan Policy

`AdvertisingPolicy` maps power mode to BLE radio parameters:

| Mode | Advertising Interval | Scan Duty Cycle | Aggressive Discovery |
|------|---------------------|-----------------|---------------------|
| PERFORMANCE | 250 ms | 90% | Yes |
| BALANCED | 500 ms | 50% | No |
| POWER_SAVER | 1000 ms | 15% | No |

### Transfer Scheduling

`TransferScheduler` limits concurrent transfers per power mode:

| Mode | Max Concurrent Transfers |
|------|--------------------------|
| PERFORMANCE | 8 |
| BALANCED | 4 |
| POWER_SAVER | 1 |

On power mode downgrade, excess active transfers are demoted to a waiting
queue. On upgrade, waiting transfers are promoted.

### Chunk Size Adaptation

`ChunkSizePolicy` adjusts payload size per chunk:

```
effectiveChunkSize = min(powerModeMaxPayload, mtu - CHUNK_HEADER_SIZE)
```

where `CHUNK_HEADER_SIZE = 21` bytes.

### Memory Pressure Shedding

`TieredShedder` implements three escalation levels:

| Level | Actions |
|-------|---------|
| MODERATE | Clear relay message buffers |
| HIGH | Also trim deduplication set entries |
| CRITICAL | Also drop BLE connections |

---

## Transfer & Congestion Control

### Chunking

Messages larger than `mtu - 21` bytes are split into chunks. Each chunk
carries a 21-byte header:

```
┌──────┬──────────────┬─────────┬─────────────┬─────────┐
│ Type │  Message ID  │ Seq Num │ Total Chunks│ Payload │
│ 1B   │    16B       │  2B LE  │   2B LE     │ var     │
└──────┴──────────────┴─────────┴─────────────┘─────────┘
```

### Selective Acknowledgement (SACK)

`SackTracker` on the receiver tracks which chunks have arrived:

- `ackSeq`: highest contiguous sequence number received from 0.
- `sackBitmask`: 64-bit bitmask indicating received chunks beyond `ackSeq`.

ACK wire format: `type(1) + messageId(16) + ackSeq(2 LE) + sackBitmask(8 LE)`
= 27 bytes.

The sender uses the SACK bitmask to selectively retransmit only missing
chunks, avoiding unnecessary retransmission of already-received data.

### AIMD Congestion Control

`AimdController` adapts the sending window:

- **Additive Increase:** After 4 consecutive clean ACK rounds, increase
  window by 2 (up to `ackWindowMax`, default 16).
- **Multiplicative Decrease:** After 2 consecutive timeouts, halve the window
  (down to `ackWindowMin`, default 2).
- **Reconnect:** Resets window and counters.

`TransferSession` uses the AIMD window to decide how many chunks to send per
round. It prioritizes retransmitting gaps (from SACK) before sending new
chunks.

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
│   │       ├── diagnostics/          Events, health snapshots
│   │       ├── model/                Message, PeerEvent, etc.
│   │       ├── power/                Power mode engine, policies
│   │       ├── protocol/             Protocol version
│   │       ├── routing/              DSDV, gossip, cost, dedup
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
| Android | API 26 (Android 8.0) | compileSdk 35 |
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
