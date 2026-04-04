# Ubiquitous Language

## Actors & Roles

| Term | Definition | Aliases to avoid |
|------|-----------|-----------------|
| **Central** | The BLE role that initiates a GATT connection, determined by the Tie-Breaking Rule. | Initiator, client |
| **Consuming App** | The third-party application that integrates the MeshLink library. | Host app, client app |
| **Neighbor** | A Peer within direct BLE range (1 hop) with an active or possible GATT connection. | Adjacent node, nearby device |
| **Peer** | A device running MeshLink that participates in the mesh network. | Node, device, endpoint |
| **Peripheral** | The BLE role that accepts an inbound GATT connection. | Server, responder |
| **Relay** | A Peer that forwards a Routed Message without being the sender or recipient. | Intermediary, forwarder |

## ⚠️ Terminology Cautions

- **"Peer"** is canonical — avoid "node" (graph-theory only) and "device" (hardware only).
- **"Message"** is overloaded — qualify as Payload (app content), Sealed Message (encrypted envelope), or control message (e.g., "Hello message").
- **"Eviction"** has three meanings — always qualify: Connection Eviction, Buffer Eviction, or Presence Eviction.
- **"ACK"** has two meanings — Chunk ACK (transport-level) vs Delivery ACK (application-level). Always use the full term.
- **"Sequence number"** — chunk sequence (within a Transfer) vs route sequence (Babel freshness). Disambiguate explicitly.
- **"Session"** — refers specifically to a Noise XX Session. Avoid for any other concept.
- **"Broadcast" vs "Advertisement"** — Broadcast = signed mesh message. Advertisement = BLE advertising packet. Never conflate.

## Identity & Trust

| Term | Definition | Aliases to avoid |
|------|-----------|-----------------|
| **Identity** | A static Ed25519 keypair generated on first launch; the public key IS the identity. A Curve25519 key is derived via the Birational Map for Noise handshakes. | Account, user, profile |
| **Key Hash** | 12-byte truncated SHA-256 of a Peer's X25519 public key, used as the wire peer ID in advertisements and all wire messages. | Fingerprint, peer hash |
| **Key Pinning** | Associating a Public Key with a Peer on first contact (TOFU). Strict mode rejects changes; Soft re-pin mode accepts them. Both emit `onKeyChanged`. | Trusting, remembering |
| **Public Key** | The Ed25519 public key uniquely identifying a Peer. The Curve25519 key for Noise handshakes is derived deterministically. | Address, ID |
| **Replay Counter** | Monotonic uint64 per sender inside the Sealed Message, used to reject replayed messages via a sliding Replay Window. | Nonce |
| **Replay Window** | 64-counter sliding window (DTLS-style) per sender for replay protection. Tolerates multi-path reordering. | Anti-replay window |
| **TOFU** | Trust-on-First-Use (RFC 7435) — Public Key pinned on first Noise XX handshake. Default is strict: key changes rejected until explicit re-pin. | TOFI |

## Transport

| Term | Definition | Aliases to avoid |
|------|-----------|-----------------|
| **Advertisement** | 16-byte BLE advertisement payload: protocol version, Power Mode, mesh hash, and Key Hash. | Beacon, broadcast |
| **BleTransport** | Abstraction interface between mesh logic and platform BLE (CoreBluetooth / android.bluetooth). | Transport layer, BLE adapter |
| **Capability Byte** | 1-byte bitmap exchanged during Noise XX handshake. Bit 0 = L2CAP CoC supported. | Feature flags |
| **Control Characteristic** | GATT characteristic pair (write + notify) for handshakes and control messages. Uses write-with-response. | Handshake channel |
| **Data Characteristic** | GATT characteristic pair (write + notify) for chunks and data when L2CAP is unavailable. Uses write-without-response. | Payload channel |
| **GATT Fallback** | Automatic degradation to GATT Data Characteristics when L2CAP is unavailable or fails. | Compatibility mode |
| **L2CAP CoC** | BLE L2CAP Connection-Oriented Channel — stream-oriented data channel with credit-based flow control (iOS 11+ / Android 10+). | L2CAP channel |
| **L2CAP Framing** | Length-prefixed message format on L2CAP streams: 1-byte type + 3-byte LE length + payload. | Stream framing |
| **MTU** | Maximum Transmission Unit — negotiated maximum bytes per BLE GATT write (typically 244–512). | Packet size |
| **PSM** | Protocol/Service Multiplexer — 2-byte identifier assigned by the OS for L2CAP channels. | Channel ID, port |
| **Type Prefix** | 1-byte header on every GATT write identifying message type (`0x00`–`0x0B`). See [wire-format-spec.md](docs/wire-format-spec.md). | Message tag, discriminator |
| **Wire Format** | Custom little-endian binary protocol defining all message layouts. | Protocol, schema |

## Connections

| Term | Definition | Aliases to avoid |
|------|-----------|-----------------|
| **Connection Slot** | One unit of the per-Power Mode connection budget, shared across inbound and outbound connections. | Connection limit |
| **On-demand Connection** | Temporary GATT connection initiated to deliver a message, evicting the lowest-priority Persistent Connection if needed. | Ad-hoc connection |
| **Persistent Connection** | GATT connection maintained to a top-priority Neighbor, filling available connection slots. | Long-lived connection |
| **Priority Score** | Heuristic for eviction: active transfers protected, then LRU with Route Cost tiebreaker. | Connection weight |
| **Tie-Breaking Rule** | Deterministic role assignment: higher Power Mode → Central; same mode → higher Key Hash → Central. | Role negotiation |

## Messaging

| Term | Definition | Aliases to avoid |
|------|-----------|-----------------|
| **Broadcast** | Signed (Ed25519) but unencrypted message delivered to all reachable Peers within `broadcastTtl` hops. | Flood message |
| **Chunk ACK (SACK)** | 64-bit selective ACK bitmask covering chunks `[base_seq, base_seq+63]`. Sender retransmits only missing chunks. | Transport ACK |
| **Delivery ACK** | Signed confirmation that the recipient received and decrypted a Direct Message. Sent via reverse-path unicast. | Read receipt |
| **Direct Message** | 1:1 end-to-end encrypted message sealed with Noise K, with Delivery ACK enabled by default. | Private message, DM |
| **Message ID** | 128-bit random identifier (16 bytes, CSPRNG) for deduplication. Collision probability ~2⁻⁶⁴. | Message hash |
| **NACK** | Negative acknowledgment when a Peer rejects a message (e.g., `bufferFull`, `rateLimitExceeded`). | Rejection |
| **Payload** | Raw bytes the Consuming App provides to `send()` — the application-layer content. | Data, body |

## Transfers (Chunking)

| Term | Definition | Aliases to avoid |
|------|-----------|-----------------|
| **Byte-Offset Resume** | Resume strategy: receiver reports total bytes received, sender resumes at that offset with the new connection's chunk size. | Chunk resume |
| **Chunk** | A BLE-sized byte range of E2E ciphertext carrying a message ID and sequence number. | Fragment, packet |
| **Chunk Inactivity Timeout** | 30-second timer that drops an incomplete Transfer if no new Chunk arrives. | Stall timeout |
| **Transfer** | A single chunked transmission of one message's ciphertext between two adjacent Peers. | Stream, upload |

## Encryption

| Term | Definition | Aliases to avoid |
|------|-----------|-----------------|
| **Birational Map** | RFC 8032 conversion between Ed25519 and Curve25519 keys. One keypair, two uses. | Key conversion |
| **E2E Encryption** | Noise K (`Noise_K_25519_ChaChaPoly_SHA256`) one-shot encryption from sender to final recipient with built-in sender authentication. | Inner encryption |
| **Hop-by-Hop Encryption** | Noise XX (`Noise_XX_25519_ChaChaPoly_SHA256`) session encryption between adjacent Neighbors. | Transport encryption |
| **Key Rotation** | Generating a new Identity keypair via `rotateIdentity()`, signing the announcement with the old key, and broadcasting to all Peers. | Re-keying |
| **Noise XX Session** | Authenticated encrypted channel established via Noise XX handshake between two Neighbors. | Transport session |
| **Sealed Message** | Output of E2E Encryption — ephemeral public key + ciphertext only the recipient can decrypt. | Encrypted envelope |

## Routing

| Term | Definition | Aliases to avoid |
|------|-----------|-----------------|
| **Backup Route** | Second-lowest-cost path to a destination via a different Next-Hop than the Primary Route. | Secondary route |
| **Cut-Through Forwarding** | Relay model where Chunks are forwarded as they arrive without waiting for full reassembly, with a local buffer copy for reliability. | Pipeline forwarding |
| **Link Cost** | Per-hop quality score: linear RSSI cost × loss multiplier × freshness penalty + stability penalty. Lower is better. | Link weight |
| **Next-Hop** | The Neighbor to which a Routed Message is forwarded, per the Routing Table. | Relay target |
| **Primary Route** | The lowest-cost path to a destination in the Routing Table. | Best route |
| **Route Cost** | Cumulative cost from source to destination via Babel Update propagation. Used for Primary Route selection. | Metric, ETX |
| **Route Retraction** | An Update with `metric = 0xFFFF` (infinity) signaling a destination is unreachable. | Link break notification |
| **Routed Message** | Multi-hop envelope containing a Sealed Message + routing metadata (origin, destination, hop count, Visited List). | Relay envelope |
| **Routing Table** | Local structure mapping each known Peer to the best Neighbor (next-hop) for forwarding, built from Babel Hello/Update propagation. | Forwarding table |
| **Visited List** | List of 12-byte peer IDs of every Peer that forwarded a Routed Message, used for loop prevention. | Path list |

## Deduplication & Buffering

| Term | Definition | Aliases to avoid |
|------|-----------|-----------------|
| **Buffer** | Memory region (default 1 MB) for store-and-forward of Routed Messages to unreachable recipients. | Message queue, cache |
| **Buffer Eviction** | 3-tier eviction when full: (1) no-route messages, (2) relay messages, (3) own outbound. Within each tier: priority ascending → TTL ascending → FIFO. | Cache eviction |
| **Buffer TTL** | Per-peer independent countdown (default 15 min) after which a buffered message is evicted. | Message expiry |
| **Dedup Set** | Time-and-count-bounded in-memory set of recently-seen Message IDs (default 100K entries). In-memory only. | Seen set |
| **Relay Cap** | Soft limit (75%) on relay traffic's share of the Buffer pool, reserving 25% for own outbound. | Relay budget |

## Power Management

| Term | Definition | Aliases to avoid |
|------|-----------|-----------------|
| **Connection Eviction** | Removing lowest-priority connections when a Power Mode downgrade reduces the connection budget. | Connection pruning |
| **Custom Power Mode** | Developer override locking to a specific power mode, disabling battery-based transitions. | Fixed mode |
| **Eviction Grace Period** | 30-second window during power mode downgrade where connections with active transfers are not evicted. | Grace window |
| **Hysteresis** | 30-second delay before downward Power Mode transitions; upward transitions are immediate. | Debounce |
| **Power Mode** | One of three tiers (Performance / Balanced / PowerSaver) selected by: `customPowerMode` > charging > battery level. | Power state, energy mode |

## Presence Detection

| Term | Definition | Aliases to avoid |
|------|-----------|-----------------|
| **Adaptive Timeout** | Per-peer presence timeout: `max(own_mode.timeout, peer_mode.timeout)`, preventing false evictions across Power Modes. | Dynamic timeout |
| **Eviction Hardening** | Peer must miss 2 consecutive sweep intervals before being marked Gone. | Double-sweep rule |
| **Peer Lifecycle** | Three-state machine: Connected → Disconnected (GATT drop) → Gone (sweep eviction). | Peer state machine |
| **Presence Eviction** | Marking a Peer as Gone and removing it from the peer table and Routing Table. | Peer removal |
| **Sweep Timer** | Periodic timer (per Power Mode) checking for stale Peers whose last Advertisement exceeds the Adaptive Timeout. | Presence timer |

## Concurrency Model

| Term | Definition | Aliases to avoid |
|------|-----------|-----------------|
| **Engine** | Stateful facade consolidating a domain concern behind sealed result types (SecurityEngine, RoutingEngine, TransferEngine, DeliveryPipeline). | Actor, manager, service |
| **Coordinator** | Stateful component orchestrating multi-step workflows (PowerCoordinator, RouteCoordinator, PeerConnectionCoordinator). | Controller |
| **Policy Chain** | Pure pre-flight evaluator returning a sealed decision type (SendPolicyChain, BroadcastPolicyChain, MessageDispatcher). | Validator, checker |
| **Sealed Result Type** | Pattern where methods return sealed interfaces so callers exhaustively pattern-match on outcomes. | Union type, result enum |
| **DispatchSink** | Callback interface for side effects (flow emissions, transport sends) that MessageDispatcher delegates to MeshLink. | Observer, listener |
| **VirtualMesh** | In-process BLE simulator for testing — creates N simulated Peers with configurable link parameters. | Simulator, mock mesh |

## Relationships

- A **Peer** has exactly one **Identity** (one Ed25519 keypair, one device)
- A **Peer** has 0–N **Neighbors** and a **Routing Table** of reachable Peers beyond neighbors
- A **Direct Message** produces a **Sealed Message** (E2E Encryption), wrapped in a **Routed Message** for multi-hop delivery
- A **Routed Message** is forwarded hop-by-hop using **Cut-Through Forwarding**, protected by **Hop-by-Hop Encryption**
- A **Transfer** is a single-hop chunked delivery of one Routed Message, composed of **Chunks** acknowledged by **Chunk ACKs**
- **Power Mode** governs: scan duty cycle, advertising interval, keepalive interval, Connection Slot budget, concurrent Transfer limits
