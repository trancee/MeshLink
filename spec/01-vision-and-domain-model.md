# 01 — Vision & Domain Model

> **Covers:** §1 (Vision & Scope) + §2 (Domain Model & Ubiquitous Language)
> **Dependencies:** None — read this first.
> **Key exports:** All canonical terminology used throughout the spec.
> **Changes from original:** S1 (Bloom filter removed from terminology), S2 (ratchet terms removed), S7 (Soft Re-pin removed)

---

## 1. What MeshLink Is

MeshLink is a **library-first SDK** for encrypted, serverless, offline-capable
messaging over **Bluetooth Low Energy (BLE) mesh networks** — no internet, no
servers, no user accounts.

**Core capabilities:**
- Multi-hop mesh routing (Babel-based, proactive route propagation)
- Two-layer encryption — Noise XX hop-by-hop + Noise K end-to-end
- Large message transfer with chunking, SACK, and flow control
- Power-aware operation (auto-adjusts radio behavior by battery state)
- Cross-platform — Android, iOS (~85% shared code via KMP)
- All crypto via libsodium (audited, constant-time, hardware-accelerated)

**What it is NOT:**
- Not a standalone app — it's a library integrated by a **Consuming App**
- Not an internet relay — BLE-proximity only (~30–100m)
- Not group messaging (v1) — 1:1 encrypted + flood-broadcast only
- Not a persistence layer — messages are ephemeral

## 2. Target Platforms

| Platform | Minimum | Notes |
|----------|---------|-------|
| Android  | API 29  | Native L2CAP CoC, no location permission; libsodium via JNI |
| iOS      | 14+     | Stable L2CAP CoC + modern CoreBluetooth |

> **JVM** is used as the build/test/benchmark execution platform (commonTest, Kover/JaCoCo, kotlinx-benchmark/JMH) but is NOT a shipping target. No JVM distribution artifacts.

## 3. Design Principles

1. **Library-first** — SDK ships as a dependency; reference app is a demo, not the product
2. **Minimal deps** — sole runtime dependency: libsodium (bundled per platform)
3. **Single shared implementation** — protocol logic in shared KMP code to prevent drift
4. **Encryption mandatory** — `start()` fails without a crypto provider unless explicitly opted out
5. **Observable, not opaque** — single diagnostic event stream; no console output in release
6. **Deterministic testability** — injectable clocks; in-process virtual transport replaces real BLE

## 4. License & Distribution

- **License:** Unlicense (public domain)
- **Android:** Maven Central (`ch.trancee:meshlink:<version>`)
- **iOS:** SPM via XCFramework (GitHub release tags)
- **Versioning:** Semver (library version decoupled from protocol version)

---

## 5. Domain Model — Canonical Terminology

> These are the **only** terms to use. Aliases listed under "Avoid" cause ambiguity.

### Actors & Roles

| Term | Definition | Avoid |
|------|-----------|-------|
| **Peer** | A device running MeshLink that participates in the mesh. | "node" (graph-theory only), "device" (hardware only) |
| **Neighbor** | A Peer within direct BLE range (1 hop) with an active or possible GATT connection. | "adjacent node" |
| **Relay** | A Peer that forwards a Routed Message without being sender or recipient. | "forwarder" |
| **Central** | BLE role that initiates a GATT connection (determined by Tie-Breaking Rule, see `03-transport-ble.md`). | "initiator" |
| **Peripheral** | BLE role that accepts an inbound GATT connection. | "server" |
| **Consuming App** | The third-party application integrating the MeshLink library. | "host app" |

### Identity & Trust

| Term | Definition |
|------|-----------|
| **Identity** | Two independent static keypairs: Ed25519 (signing) + X25519 (key agreement). Generated on first launch. Independently generated, not derived from each other. |
| **Key Hash** | First 12 bytes of `SHA-256(Ed25519Pub ‖ X25519Pub)`. Wire-level peer identifier. 96-bit collision resistance (~2⁴⁸ birthday bound). |
| **Public Key** | The Ed25519 public key. Used for broadcast signing + delivery ACK authentication. |
| **TOFU** | Trust-On-First-Use — public key pinned on first successful Noise XX handshake. |
| **Key Pinning** | Associating a public key with a peer on first contact. Two modes: **Strict** (reject changes) and **Prompt** (delegate key-change decision to the Consuming App via callback). Both emit a key-change event. |
| **Replay Counter** | Monotonic uint64 per sender inside the Sealed Message + 64-entry sliding window (DTLS-style). |

### Transport

| Term | Definition |
|------|-----------|
| **BLE Transport** | Abstraction interface between mesh logic and platform BLE hardware. The sole FFI/platform boundary. |
| **Advertisement** | 16-byte BLE payload: protocol version (3 bits), power mode (2 bits), mesh hash, L2CAP PSM, Key Hash. L2CAP-first connection when PSM advertised. |
| **L2CAP CoC** | BLE L2CAP Connection-Oriented Channel — stream-oriented, credit-based flow control. Preferred data plane. |
| **GATT Fallback** | Auto degradation to GATT when L2CAP unavailable or fails. |
| **MTU** | Maximum Transmission Unit — negotiated per BLE connection. |
| **PSM** | Protocol/Service Multiplexer — 2-byte L2CAP channel identifier (128–255). |
| **Type Prefix** | 1-byte header on every GATT write identifying message type (`0x00`–`0x0B`). |

### Connections

| Term | Definition |
|------|-----------|
| **Connection Slot** | One unit of per-power-mode connection budget (shared inbound/outbound). |
| **Persistent Connection** | GATT connection maintained to top-priority Neighbors. |
| **On-demand Connection** | Temporary connection for message delivery; evicts lowest-priority persistent if needed. |
| **Tie-Breaking Rule** | Deterministic role: higher Power Mode → Central; same mode → higher Key Hash (lexicographic) → Central. |

### Messaging

| Term | Definition |
|------|-----------|
| **Direct Message** | 1:1 E2E encrypted (Noise K), optional Delivery ACK. |
| **Broadcast** | Ed25519-signed, unencrypted, flood-delivered within `broadcastTtl` hops. |
| **Sealed Message** | E2E encryption output: ephemeral pubkey + ciphertext. |
| **Routed Message** | Multi-hop envelope: Sealed Message + routing metadata (origin, destination, hop count, Visited List). |
| **Message ID** | 128-bit CSPRNG random (16 bytes). Dedup key. |
| **Delivery ACK** | Ed25519-signed confirmation of receipt + decryption. |
| **NACK** | Negative ack with reason: `unknown`, `bufferFull`, `unknownDestination`, `decryptFailed`, `rateLimited`. |

### Transfers (Chunking)

| Term | Definition |
|------|-----------|
| **Chunk** | BLE-sized byte range of E2E ciphertext with Message ID + sequence number. |
| **Transfer** | One chunked transmission of a message between adjacent Peers. |
| **Chunk ACK (SACK)** | 64-bit selective ACK bitmask covering `[base_seq, base_seq+63]`. |
| **Byte-Offset Resume** | On disconnect, receiver reports bytes received; sender resumes at that offset. |
| **Chunk Inactivity Timeout** | Default 30s; drops incomplete Transfer if no new Chunk arrives. |

### Encryption

| Term | Definition |
|------|-----------|
| **E2E Encryption** | Noise K (`Noise_K_25519_ChaChaPoly_SHA256`) — two-DH one-shot from sender to recipient. Full seal per message. |
| **Hop-by-Hop Encryption** | Noise XX (`Noise_XX_25519_ChaChaPoly_SHA256`) session between adjacent Neighbors. |

### Routing

| Term | Definition |
|------|-----------|
| **Routing Table** | Maps known Peers → best Neighbor (next-hop), built from Babel Hello/Update. |
| **Primary Route** | Lowest-cost path. |
| **Backup Route** | Second-lowest cost via a *different* next-hop. |
| **Route Cost** | Composite: `RSSI_base × loss_multiplier × freshness_penalty + stability_penalty`. Lower = better. Stored as Double internally. |
| **Route Retraction** | Update with `metric = 0xFFFF` (infinity) → unreachable. |
| **Visited List** | Variable-length array of 12-byte peer IDs that forwarded a Routed Message. Loop prevention with zero false positives. |
| **Dedup Set** | `LinkedHashMap<MessageId, Long>` — fixed-size (default 25K entries), TTL = max buffer TTL (45 min). Zero false positives. |
| **Cut-Through Forwarding** | Relay pipelines forwarding: parse routing header, re-serialize with modified visited list/hop limit, and begin forwarding re-encrypted chunks to next-hop concurrently with receiving remaining inbound chunks. E2E ciphertext passes through opaque. |

### Power Management

| Term | Definition |
|------|-----------|
| **Power Mode** | Performance / Balanced / Power Saver — three fixed tiers selected by: `customPowerMode` > charging > battery level. |
| **Hysteresis** | ±2% dead zone + 30s delay for downward transitions. Upward = immediate. |
| **Connection Eviction** | Removing lowest-priority connections on mode downgrade. |
| **Eviction Grace Period** | 30s window where active-transfer connections are not evicted. |

### Presence Detection

| Term | Definition |
|------|-----------|
| **Adaptive Timeout** | `max(own_mode.timeout, peer_mode.timeout)` per peer. |
| **Eviction Hardening** | Peer must miss 2 consecutive sweep intervals → Gone. |
| **Peer Lifecycle** | Connected → Disconnected (GATT drop) → Gone (sweep eviction). |

### Buffering

| Term | Definition |
|------|-----------|
| **Buffer** | Fixed-size memory region (configurable, default 1 MB) for store-and-forward of Routed Messages. Allocated at startup. |
| **Buffer TTL** | Fixed per priority: HIGH=45min, NORMAL=15min, LOW=5min. |
| **Relay Cap** | 75% relay / 25% own outbound. |
| **Buffer Eviction** | 3-tier: (1) no-route, (2) relay, (3) own outbound. Within tier: lowest priority evicted first, then shortest TTL remaining first, then oldest first (FIFO). |

### Concurrency Model

| Term | Definition |
|------|-----------|
| **Engine** | Stateful facade: SecurityEngine, RoutingEngine, TransferEngine, DeliveryPipeline. Returns sealed result types. |
| **Coordinator** | Stateful orchestrator: PowerManager, RouteCoordinator, PeerLifecycleManager. |
| **Policy Chain** | Pure pre-flight evaluator: SendPolicyChain, BroadcastPolicyChain. |
