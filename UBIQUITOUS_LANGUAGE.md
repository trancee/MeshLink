# Ubiquitous Language

## Actors & Roles

| Term | Definition | Aliases to avoid |
|------|-----------|-----------------|
| **Central** | The BLE role that initiates a GATT connection. Determined by the **Tie-Breaking Rule**. | Initiator, client |
| **Consuming App** | The third-party application that integrates the MeshLink library. | Library consumer, host app, client app |
| **Neighbor** | A **Peer** within direct BLE range (1 hop away) with which a GATT connection can be established. | Adjacent node, nearby device |
| **Peer** | A device running MeshLink that participates in the mesh network. | Node, device, endpoint |
| **Peripheral** | The BLE role that accepts an inbound GATT connection. | Server, responder |
| **Relay** | A **Peer** that forwards a **Routed Message** on behalf of others without being the sender or recipient. | Intermediary, forwarder, relay node |

## ⚠️ Terminology Cautions

The following terms are commonly confused. Read this before continuing.

- **"Node" vs "Device" vs "Peer"** — **Peer** is canonical. Use "node" only in graph-theory contexts; avoid "device" except for hardware constraints.
- **"Message" is overloaded** — **Payload** = app-level content. **Sealed/Routed Message** = encrypted envelope. Qualify control messages explicitly (e.g., "route request message").
- **"Eviction" has three meanings** — **Connection Eviction** (removing GATT connections), **Buffer Eviction** (removing stored messages), **Presence Eviction** (marking peers as Gone). Always qualify.
- **"ACK" has two meanings** — **Chunk ACK** (transport-level) and **Delivery ACK** (application-level). Always use the full term.
- **"Sequence number" has two meanings** — Chunk sequence (within a Transfer) vs. route sequence (routing freshness). Disambiguate explicitly.
- **"Session"** — Refers specifically to a **Noise XX Session**. Avoid for any other concept.
- **"Broadcast" vs "Advertisement"** — **Broadcast** = signed message to all mesh peers. **Advertisement** = BLE advertising packet. Never conflate.

## Identity & Trust

> See [diagrams.md § TOFU Trust Model](docs/diagrams.md#10-tofu-trust-model) and [§ Key Rotation Sequence](docs/diagrams.md#11-key-rotation-sequence) for visual references.

| Term | Definition | Aliases to avoid |
|------|-----------|-----------------|
| **Backup Exclusion** | Identity keys are explicitly excluded from platform backup (iOS: `ThisDeviceOnly` Keychain attribute; Android: backup exclusion rules). New device = new identity, always. | Backup restore, key migration |
| **Identity** | A static Ed25519 keypair generated on first launch — the Ed25519 public key IS the identity. A Curve25519 keypair is derived from it via the **Birational Map** for use in Noise handshakes. | Account, user, profile |
| **Key Hash** | The 8-byte SHA-256-64 (truncated SHA-256) of a **Peer's** Curve25519 **Public Key** (derived from Ed25519 **Identity**), carried in the **Advertisement** and **Visited List**. | Fingerprint, peer hash, identity hash |
| **Key Pinning** | Associating a discovered **Public Key** with a **Peer** identity on first contact (**TOFU**). Strict mode (default): reject changed keys, require explicit `repinKey()`. Soft re-pin mode: silently accept new keys. Both emit `onKeyChanged`. | Trusting, remembering |
| **Keychain Accessibility Requirement** | iOS: MeshLink keys use `kSecAttrAccessibleAfterFirstUnlock` — available in background after first device unlock post-reboot. Keys are unavailable before first unlock. | Keychain protection, key accessibility |
| **Public Key** | The Ed25519 public key that uniquely identifies a **Peer** on the mesh. The corresponding Curve25519 public key (for Noise handshakes) is derived deterministically. | Address, ID, peer ID |
| **Replay Counter** | A monotonic uint64 counter per sender, included inside the **Sealed Message**, used to reject replayed messages. Recipients maintain a **Replay Window** per sender. | Sequence number (avoid — overloaded with chunk sequence numbers), nonce |
| **Replay Window** | A 64-counter sliding window (DTLS-style) per sender: highest-seen counter N + 64-bit bitmap for [N−63, N]. Tolerates message reordering from **Multipath** routing while rejecting replays. Inbound replay state persisted every 1s. | Anti-replay window, replay bitmap |
| **TOFU** | Trust-on-First-Use (RFC 7435) — the trust model where a **Peer's** **Public Key** is pinned on first encounter. In MeshLink, pinning occurs at peer discovery (Noise XX handshake), not first message exchange. Default mode is **strict**: key changes are rejected until the consuming app explicitly re-pins. | TOFI (previous project name for this concept) |
| **Sybil Attack Mitigation** | Two-mechanism defense: (1) **Handshake Rate Limiting** (1/sec per BLE MAC for unknown peers), (2) **Per-Neighbor Routing Table Cap** (30% limit). See each term for details. | Anti-Sybil, identity flooding defense |
| **Write-Ahead Replay Counter** | The **outbound** Replay Counter, persisted on every increment to prevent counter gaps after unexpected termination. Contrast with **Replay Window** (inbound), which uses periodic persistence. | Eager-persist counter |
| **Persistence Failure Policy** | Criticality-based disk write failure handling: **Identity**/TOFU → `fatalError(retryable: true)` after 3 retries; **Replay Counter** → retry 3×, then `PERSIST_FAILED` diagnostic + continue in-memory; **Dedup Set** → in-memory only in v1 (no persistence). Pre-flight `LOW_DISK_SPACE` diagnostic if <1MB free. | Storage failure, disk full handling |

## Transport

| Term | Definition | Aliases to avoid |
|------|-----------|-----------------|
| **Advertisement** | A BLE advertisement payload (10 of 24 usable bytes): bit-packed protocol version + **Power Mode** (1 byte), version minor (1 byte), and **Key Hash** (8 bytes). | Beacon, broadcast (avoid — "Broadcast" means something specific in messaging) |
| **Base UUID** | The 128-bit UUID template (`7F3Axxxx-8FC5-11EC-B909-0242AC120002`) from which all GATT characteristic UUIDs are derived using 16-bit offsets (`0x0001`–`0x0004`). The 16-bit **Service UUID** (`0x7F3A`) is embedded at the `xxxx` position. | Root UUID, UUID template |
| **BLE Connection Establishment Sequence** | 4-step sequence: BLE connect → MTU negotiate (reject <100) → GATT discovery (10s) → Noise XX handshake (8s). Total budget: 23s. → see design.md §3. | Connection setup, pairing sequence |
| **BLE_STACK_UNRESPONSIVE** | Diagnostic emitted when the library receives zero BLE callbacks for 60 seconds despite being started. Automatic recovery tears down and re-initializes BLE resources (up to 3 attempts/hour). After exhausting retries → `BLE_STACK_FATAL` → all BLE operations stop. | BLE hang, stack timeout |
| **BleTransport** | The abstraction interface between mesh logic and platform BLE (CoreBluetooth / android.bluetooth). Exposes both GATT and **L2CAP CoC** operations. | Transport layer, BLE adapter |
| **Capability Byte** | A 1-byte bitmap exchanged during the **Noise XX Session** handshake indicating supported transport features. Bit 0 = **L2CAP CoC** supported. | Feature flags, capability flags |
| **Control Characteristic** | The GATT characteristic pair (write + notify) carrying Noise XX handshakes and control exchanges. | Handshake channel |
| **Control Plane** | The GATT-based channel for low-bandwidth protocol messages (Noise XX handshakes, ACKs, AODV route discovery). Always GATT, even when L2CAP is active. | Management plane, discovery layer |
| **Credit-Based Flow Control** | Built-in **L2CAP CoC** throttling: receiver issues credits, sender pauses when exhausted. Replaces **SACK** on L2CAP connections. | L2CAP flow control, credit flow |
| **Data Characteristic** | The GATT characteristic pair (write + notify) carrying **Chunks**, **Chunk ACKs**, and **Delivery ACKs** when **L2CAP CoC** is unavailable (**GATT Fallback** mode). | Payload channel |
| **Data Plane** | The communication path for bulk message transfer (**Chunks**, **Routed Messages**, **Broadcasts**). Uses **L2CAP CoC** when both **Peers** support it; falls back to GATT **Data Characteristic** otherwise. | Transfer plane, payload plane, forwarding layer |
| **Frozen Advertisement Format** | The 17-byte BLE **Advertisement** layout is permanently frozen across all protocol versions. Version nibble allows pre-connection version detection. | Fixed advertisement, static ad format |
| **GATT Fallback** | The automatic degradation to GATT **Data Characteristics** when **L2CAP CoC** is unavailable or fails. Always available on all BLE devices. | Fallback mode, compatibility mode |
| **GATT Service** | The BLE GATT service exposed by every **Peer**, identified by the 16-bit **Service UUID** `0x7F3A` (CRC-16/ARC of "MESH"), containing two characteristic pairs (control + data) derived from the **Base UUID**. | BLE service, Bluetooth service |
| **GATT Write Error Handling** | Retry policy for characteristic write failures: 3 attempts with 100ms/200ms/400ms exponential backoff, then disconnect + WRITE_FAILED diagnostic. | Write retry, GATT retry |
| **L2CAP Backpressure** | Condition where L2CAP writes block due to credit exhaustion. Triggers **GATT Fallback** after repeated stalls. L2CAP is not retried until the next fresh connection. | L2CAP stall, credit stall |
| **L2CAP CoC** | BLE L2CAP Connection-Oriented Channel — a stream-oriented data channel on BLE with built-in credit-based flow control, used as the preferred **Data Plane** transport when both **Peers** support it (iOS 11+ / Android 10+). | L2CAP channel, LE CoC, low-energy channel |
| **L2CAP Framing** | Length-prefixed message format on the **L2CAP CoC** stream: 1-byte **Type Prefix** + 3-byte little-endian length + payload. Required because **L2CAP CoC** is stream-oriented (no natural message boundaries like GATT writes). | Stream framing, length-prefix |
| **L2CAP OEM Capability Probe** | A per-`manufacturer|model|SDK_INT` test performed on first connection to determine if a device model supports L2CAP CoC reliably. Result is cached for 30 days. Failure → GATT-only for that device model. | L2CAP test, OEM probe, capability check |
| **MTU** | Maximum Transmission Unit — the negotiated maximum bytes per BLE GATT write (typically 244–512). | Packet size |
| **MTU Negotiation** | The BLE GATT MTU exchange after connection. The OS negotiates the largest MTU both devices support. Connections with effective payload < 100 bytes are rejected with `MTU_TOO_SMALL`. Broadcasts via GATT require MTU ≥ 119. | MTU exchange, MTU handshake |
| **MTU Minimum** | 100 bytes. Connections with effective MTU below this threshold are rejected with MTU_TOO_SMALL diagnostic. | Min MTU, MTU floor |
| **neighborRateLimit** | Per-neighbor aggregate rate limit: maximum total incoming user-level messages from any single **Neighbor** (default: 100/min, configurable). Applied after per-sender `peerRateLimit`. Prevents coordinated denial-of-sleep attacks. Control-plane exempt. | Aggregate rate limit, neighbor cap |
| **Nonce Desync** | An **L2CAP CoC** stream corruption condition where the Noise XX nonce counter between sender and receiver falls out of sync, causing decryption failures. Triggers immediate L2CAP teardown and **GATT Fallback** for that **Peer**. | L2CAP corruption, stream desync |
| **Nonce Desync Circuit Breaker** | Per-peer circuit breaker: 3 L2CAP nonce desyncs within 5 minutes → GATT-only demotion for 30 minutes. Prevents repeated L2CAP failures on peers with corrupted crypto state. | Desync breaker, L2CAP circuit breaker |
| **PSM** | Protocol/Service Multiplexer — a 2-byte identifier assigned by the OS when an **L2CAP CoC** channel is published. The **Peripheral** sends the PSM to the **Central** via the **Control Characteristic** so the **Central** can open the channel. | Channel ID, port |
| **PSM Zero** | L2CAP Protocol/Service Multiplexer value of `0x0000` in the Noise XX handshake payload, signaling "no L2CAP support." Peer falls back to GATT-only data path. | PSM null, no-L2CAP flag |
| **RSSI** | Received Signal Strength Indicator — the power level (in dBm) of a received BLE signal. Used as a linear input to **Link Cost**: cost increases linearly as RSSI decreases (stronger signal = lower cost). | Signal strength, signal level |
| **Service Data (AD type 0x16)** | BLE advertisement data structure keyed by the 16-bit service UUID (0x7F3A). Used instead of Manufacturer-Specific Data to avoid company ID registration. Carries the MeshLink payload (version, power mode, key hash). | Manufacturer data, service advertisement |
| **Type Prefix** | A 1-byte header on every GATT write/notification identifying the message type (`0x00`–`0x0B`). Reserved codes `0x0C`–`0xFF` → drop + UNKNOWN_MESSAGE_TYPE diagnostic. See [wire-format-spec.md](docs/wire-format-spec.md) for the full type table. | Message tag, discriminator |
| **Wire Format** | The custom little-endian binary protocol defining all message layouts as byte-offset tables. | Protocol, serialization format, schema |
| **Write-With-Response** | GATT ATT Write Request used for Control characteristics. Provides per-write confirmation at ~15ms cost. Used for handshakes and control messages. | ATT Write Request, confirmed write |
| **Write-Without-Response** | GATT ATT Write Command used for Data characteristics. Fire-and-forget for throughput; SACK handles reliability. | ATT Write Command, unconfirmed write |

## Connections

| Term | Definition | Aliases to avoid |
|------|-----------|-----------------|
| **BLE Error Mapping** | Platform-specific BLE errors (Android GATT_ERROR, iOS CBATTError) are mapped to 6 abstract MeshLink categories: CONNECTION_FAILED, HANDSHAKE_FAILED, WRITE_FAILED, MTU_TOO_SMALL, DISCOVERY_TIMEOUT, SERVICE_NOT_FOUND. Platform code preserved in diagnostic payload. | Error translation, BLE error codes |
| **Connection Slot** | One unit of the per-**Power Mode** connection budget. Single shared pool for inbound (peripheral) and outbound (central) connections. | Connection limit |
| **Eviction Failure** | When `BLE.disconnect()` fails during **Connection Eviction**; handled by 500ms retry then buffering the message + emitting `EVICTION_FAILED` diagnostic. | Disconnect failure |
| **On-demand Connection** | A temporary GATT connection initiated to a **Neighbor** specifically to deliver a message, evicting the lowest-priority **Persistent Connection** if needed. | Ad-hoc connection, temporary connection |
| **Persistent Connection** | A GATT connection maintained to a top-priority **Neighbor**, filling all available connection slots. | Long-lived connection, always-on connection |
| **Priority Score** | The heuristic scoring a **Connection** for eviction decisions: active transfers are protected from eviction; remaining connections evicted by LRU with **Route Cost** as tiebreaker. | Connection weight, connection rank |
| **Relay Importance** | The count of routing table destinations reachable only through this **Neighbor** (cut vertex approximation). | Cut vertex score, bridge score |
| **Slot Accounting During Grace** | During eviction grace period, grace-period connections count toward `active_connections` but do NOT reserve slots. New connections allowed only if `active_connections < new_mode_limit`. Slots freed immediately on transfer completion. | Grace slot tracking, grace budget |
| **Tie-Breaking Rule** | The deterministic convention for deciding which **Peer** acts as **Central** vs **Peripheral**: higher **Power Mode** → **Central**; same mode → higher **Key Hash** → **Central**. | Role negotiation, connection arbitration |

## Messaging

> See [diagrams.md § Multi-Hop Routed Message Flow](docs/diagrams.md#4-multi-hop-routed-message-flow) for the full sequence diagram.

| Term | Definition | Aliases to avoid |
|------|-----------|-----------------|
| **Broadcast** | A signed (Ed25519) but unencrypted message delivered to all reachable **Peers** in the mesh. | Announcement (acceptable informally), flood message |
| **Broadcast Rate Limit** | Separate rate limit for broadcasts: **10 per minute per sender** (configurable via `broadcastRateLimit`). Enforced both on inbound (remote senders) and outbound (local `broadcast()` calls). Independent of the 20 msg/min direct message quota. | Broadcast throttle, flood limit |
| **Broadcast TTL** | Hop limit for broadcast propagation (default 2, configurable). Each relay decrements; broadcasts dropped at 0. Separate from `maxHops` which applies to routed messages. | Broadcast hop limit, flood TTL |
| **Chunk ACK (SACK)** | A **64-bit selective ACK bitmask** during a **Transfer**, covering chunks `[base_seq, base_seq+63]`. Bits set = received. Sender retransmits only specific missing chunks (gaps). Replaces cumulative ACKs for precise retransmission. | Transport ACK, cumulative ACK |
| **Delivery ACK** | An explicit confirmation that the recipient has received and decrypted a **Direct Message**. Sent via **reverse-path unicast**. | Read receipt (wrong — it confirms decryption, not reading), acknowledgment |
| **Delivery Deadline** | The `bufferTTL` window after `send()` within which `onDeliveryConfirmed` must fire; otherwise `onTransferFailed(DELIVERY_TIMEOUT)` is emitted. Guarantees the sender always receives a definitive callback. | Delivery timeout, send deadline |
| **Direct Message** | A 1:1 end-to-end encrypted message from one **Peer** to a specific recipient, sealed with Noise K. Sender authentication is built into the Noise K handshake. **Delivery ACK** is enabled by default. | Private message, DM, 1:1 message |
| **Message ID** | A 96-bit structured identifier (8-byte peer ID hash + 4-byte monotonic counter), uniquely identifying a message across the mesh for deduplication. | Message hash, packet ID |
| **NACK** | A negative acknowledgment sent when a **Peer** rejects an incoming message or transfer (e.g., `bufferFull`, `rateLimitExceeded`). | Rejection, refusal |
| **NACK Rate Limit** | The per-**Neighbor** cap (10/second) on outgoing NACK responses, preventing amplification attacks from consuming radio time. | Response limiting |
| **Payload** | The raw bytes the **Consuming App** provides to the library for sending — the application-layer content. | Data, body, content |

## Transfers (Chunking)

| Term | Definition | Aliases to avoid |
|------|-----------|-----------------|
| **Byte-Offset Resume** | The resume strategy on reconnection: receiver reports **total bytes received** (not chunk sequence number), sender resumes from that offset at whatever chunk size the new connection negotiates. Enables seamless resume across **GATT**↔**L2CAP CoC** transport mode changes. | Chunk resume, sequence resume |
| **Resume Request** | Message type `0x08` sent on the **Control Characteristic** after an **L2CAP CoC** → **GATT Fallback** transport switch, carrying `{messageId, bytesReceived}` to resume an in-progress **Transfer** from the byte offset. `bytesReceived` = last fully-reassembled chunk boundary (partial frames discarded). Avoids the **Chunk Inactivity Timeout**. | Transport resume, fallback resume |
| **Chunk** | A BLE-sized byte range of E2E ciphertext, carrying a message ID and sequence number. | Fragment, packet, segment |
| **Chunk Inactivity Timeout** | The 30-second timer that drops an incomplete **Transfer** if no new **Chunk** is received (prevents chunk flooding). | Stall timeout, idle timeout |
| **Round-Robin (Weighted)** | The interleaving strategy when multiple **Transfers** are in-flight on one connection: weighted by transfer size — larger transfers get proportionally more send slots. Prevents small messages from starving behind large ones. | Interleaving, multiplexing |
| **Sliding Window (SACK Interval)** | AIMD-based adaptive SACK interval for GATT mode: starts at 8 chunks, grows +2 on success, halves on 2 consecutive timeouts. → see design.md §3. | Flow control, window, congestion window |
| **Transfer** | A single chunked transmission of one message's ciphertext between two adjacent **Peers** over a GATT connection or **L2CAP CoC** channel. | Stream, send, upload |

## Encryption

> See [diagrams.md § Noise XX Handshake](docs/diagrams.md#5-noise-xx-handshake) and [design.md § Security](docs/design.md#5-security) for visual references.

| Term | Definition | Aliases to avoid |
|------|-----------|-----------------|
| **Birational Map** | RFC 8032 conversion between Ed25519 (Edwards) and Curve25519 (Montgomery) keys. MeshLink derives Curve25519 from Ed25519 (the well-supported direction). One keypair, two uses. | Key conversion, curve mapping |
| **E2E Encryption** | The Noise K (`Noise_K_25519_ChaChaPoly_SHA256`) one-shot encryption layer protecting **Payload** from sender to final recipient. Noise K provides built-in sender authentication (recipient must know sender's static key via AODV route discovery). | Inner encryption, message encryption |
| **Hop-by-Hop Encryption** | The Noise XX (`Noise_XX_25519_ChaChaPoly_SHA256`) session encryption layer protecting all data between adjacent **Neighbors**. | Outer encryption, transport encryption, link encryption |
| **Hop-by-Hop Re-encryption** | The per-**Chunk** (or per-**L2CAP Framing** unit) operation at a **Relay**: decrypt from inbound **Noise XX Session**, re-encrypt under outbound **Noise XX Session**. On **L2CAP CoC**, each framed message is an independent encryption unit. | Re-wrapping, transit encryption |
| **Key Rotation** | Generating a new **Identity** keypair via `rotateIdentity()`, signing the announcement with the old key, and broadcasting both old+new keys to all reachable **Peers**. **TOFU** auto-accepts signed rotations in both `strict` and `softRepin` modes. v1: single-device identity only. | Identity rotation, re-keying |
| **Noise XX Session** | The authenticated encrypted transport channel established via Noise XX handshake between two **Neighbors** on a GATT connection. | Transport session, encrypted channel |
| **Recipient Key Unknown** | Error returned when a message targets a peer discovered via route discovery (route exists) but whose Curve25519 public key has not yet been received. Distinct from `noRoute` (no path exists). Transient — resolves when key exchange completes. App can show "establishing secure channel..." | Key not found, key pending |
| **Sealed Message** | The output of **E2E Encryption** — an ephemeral public key + ciphertext blob that only the recipient can decrypt. | Encrypted envelope, sealed envelope |
| **Signature Scope** | Bytes covered by Ed25519 signature. Broadcasts: messageId + senderKey + payloadLen + payload. Delivery ACKs: messageId + senderKey + recipientKey. Sealed Messages: N/A (Noise K authenticates). | Signed data, signing input |

## Routing

> See [diagrams.md § AODV Route Discovery](docs/diagrams.md#8-aodv-route-discovery) for the route discovery sequence diagram.

| Term | Definition | Aliases to avoid |
|------|-----------|-----------------|
| **Backup Route** | The second-lowest-**Route Cost** path to a destination, via a **different Next-Hop** than the **Primary Route**. Enables instant failover. | Secondary route, alternative route |
| **Cut-Through Forwarding** | The **Relay** model where **Chunks** are forwarded to the **Next-Hop** as they arrive, without waiting for full reassembly, while keeping a local buffer copy. | Pipeline forwarding, streaming relay |
| **Freshness Penalty** | Multiplier (1.5×) applied to **Link Cost** when the last loss measurement is stale. Biases routing toward links with recent, proven quality data. Links with recent measurements use 1.0× (no penalty). Precedent: BATMAN-adv V TQ aging, Babel RFC 8966 §3.5.2. | Idle link penalty, staleness penalty |
| **Handshake Rate Limiting** | Sybil mitigation: new **Noise XX Handshakes** with unknown **Peers** are rate-limited to 1 per second. **TOFU**-pinned peers are exempt. Forces Sybil devices to spend real time establishing connections. Configurable via `handshakeRateLimit`. | Connection throttle, handshake throttle |
| **Hop** | A single relay step from one **Peer** to the next in a multi-hop delivery path. | Jump, step |
| **Independent Version Downgrade** | Protocol version negotiation strategy where each peer independently computes `min(local, remote)` version. No coordination needed — both sides arrive at the same result. | Coordinated downgrade, version handshake |
| **Link Cost** | Quality score for a single hop: linear RSSI-to-cost mapping × `loss_multiplier` + `stability_penalty` (lower is better). | Link quality, link weight |
| **Next-Hop** | The **Neighbor** to which a **Routed Message** should be forwarded, as determined by the **Routing Table**. | Next node, relay target |
| **Local Reputation Tracking** | Per-**Relay** delivery success rate tracking used to score neighbor reliability. Each peer maintains local-only delivery success rates for its relays; relays with disproportionate failures are deprioritized in route selection. Never shared across peers (prevents reputation poisoning). Replaces former per-hop ACK design. | Forwarding ACK, relay ACK, per-hop ACK, positional blame |
| **Per-Neighbor Routing Table Cap** | Sybil mitigation: routes learned through any single **Neighbor** are capped at 30% of the **Routing Table** entries (minimum 60 routes). Prevents a Sybil cluster from dominating path selection. Configurable via `routeTablePerNeighborCap`. | Neighbor route quota, per-neighbor limit |
| **Primary Route** | The lowest-**Route Cost** path to a destination in the **Routing Table**. Used for forwarding by default. | Best route, preferred route |
| **Reputation System** | Local-only per-**Relay** delivery success rate tracking. Deprioritizes relays with disproportionate failures. Never shared across peers (prevents reputation poisoning). | Relay trust, behavior scoring |
| **Route Cost** | Cumulative cost from source to destination, derived from per-hop **Link Costs** via AODV route discovery. Includes freshness penalty (1.5× for links without recent measurement). Used for **Primary Route** selection. | Metric, weight, ETX |
| **Route Cost Validation** | Sanity check rejecting routes where `advertised_cost < local_link_cost_to_neighbor`. Prevents trivial blackhole attacks. | Cost sanity check |
| **Route Error (RERR)** | *(Not implemented in v1.)* Routes expire naturally via TTL-based **Route Expiry**. | Route failure, link break notification |
| **Route Expiry** | The soft-deletion of a **Routing Table** entry when its TTL expires (`routeCacheTtlMillis`, default 60s). | Route timeout, stale route cleanup |
| **Routed Message** | The multi-hop envelope containing a **Sealed Message** + routing metadata (sender/recipient **Public Keys**, hop count, TTL, **Visited List**). | Forwarded message, relay envelope |
| **Routing Table** | The local data structure mapping each known **Peer** to the best **Neighbor** (next-hop) for forwarding, built from AODV route discovery. Stores primary + backup routes. | Forwarding table, route map |
| **Visited List** | The list of 8-byte **SHA-256-64 Key Hashes** of every **Peer** that has forwarded a **Routed Message**, used for loop prevention. 32 bytes max at 4 hops. | Visited node list, path list |
| **Visited List Loop Diagnostic** | `VISITED_LIST_LOOP_DETECTED` diagnostic emitted when a message is dropped due to visited-list match. Carries `{messageId, matchedHash, hopCount}`. SHA-256-64 collision probability is ~N²/2⁶⁴ — negligible for meshes ≤10,000 peers; no mitigation implemented. | Loop warning, collision alert |

## Deduplication & Buffering

> See [diagrams.md § Engine & Coordinator Data Flow](docs/diagrams.md#8-engine--coordinator-data-flow) for the crash persistence model.

| Term | Definition | Aliases to avoid |
|------|-----------|-----------------|
| **Buffer** | The memory region (fixed 1MB default) where a **Peer** holds **Routed Messages** destined for currently-unreachable recipients (store-and-forward). | Message queue, store, cache |
| **Buffer Eviction** | 3-tier eviction when memory is full: (1) no-route messages first, (2) relay messages, (3) own outbound messages. Within each tier, evict by priority score (ascending), then remaining TTL, then FIFO. → see design.md §4. | Tiered buffer eviction, cache eviction |
| **Buffer TTL** | The per-peer independent countdown (default 5 minutes) after which a buffered message is evicted. No shared clock — each **Peer** starts its own timer. | Message expiry, time-to-live |
| **Dedup Set** | Time-and-count-bounded in-memory set of recently-seen **Message IDs** for deduplication (max 10k entries, age ≤ `maxHops × bufferTTL`). In-memory only in v1 — lost on crash. | Seen set, message cache |
| **Relay Cap** | The soft limit (75%) on how much of the **Buffer** pool relay-forwarded traffic may consume. Ensures 25% of the pool reserved for own outbound messages. Only activates when both own and relay traffic compete. | Relay budget, relay limit |
| **Relay Buffer Copy** | Per-chunk buffer at a **Relay** during **Cut-Through Forwarding**. Evicted at the **Chunk Inactivity Timeout** (30s) or on final **Chunk ACK** from the **Next-Hop** — whichever first. Counts toward the 75% **Relay Cap**. | Relay cache, forwarding buffer |

## Power Management

| Term | Definition | Aliases to avoid |
|------|-----------|-----------------|
| **Advertising Restart Gap** | The ~100ms interruption during which a **Peer** is invisible to scanners while the BLE advertiser restarts with an updated **Advertisement** payload. | Ad gap, visibility gap |
| **Connection Eviction** | Removing the lowest-**Priority Score** connections when a **Power Mode** downward transition reduces the **Connection Slot** budget. Distinct from **Buffer Eviction** and **Presence Eviction**. | Connection pruning, connection teardown |
| **Custom Power Mode** | Developer override that locks the library to a specific power mode, disabling battery-based transitions and charging override. → see design.md §7. | Fixed mode, manual power |
| **Eviction Grace Period** | 30s window during power mode downgrade where connections with active transfers are NOT evicted. Expires at 30s or transfer completion, whichever first. → see design.md §7. | Graceful drain, grace window, eviction delay |
| **Grace Period Cascading** | Once a 30-second eviction grace period begins during power mode downgrade, further downward transitions do NOT shorten or cancel the timer — it runs to completion from T=0. Upward transitions (e.g., charger plugged in) cancel the grace period immediately since the higher mode provides sufficient connection slots. | Grace stacking, cascading grace |
| **Graceful Drain** | The **Power Mode** transition behavior where connections with active **Transfers** are NOT evicted immediately — they remain until the transfer completes or the **Eviction Grace Period** expires. New transfers and route timers switch to the new mode immediately. Idle connections are evicted at once. | Drain period, transfer drain |
| **Hysteresis** | The 30-second delay before downward **Power Mode** transitions, preventing flapping when battery oscillates near a threshold. Upward transitions are immediate. | Debounce, dampening |
| **Power Mode** | One of three automatic tiers (Performance, Balanced, PowerSaver) selected by the precedence chain: `customPowerMode` > charging state > battery level. PowerSaver activates at <30% battery. Controls scan duty cycle, advertising interval, keepalive interval, connection limits, and concurrent transfer limits. | Power state, energy mode, Ultra-Low |

## Presence Detection

| Term | Definition | Aliases to avoid |
|------|-----------|-----------------|
| **Adaptive Timeout** | The per-peer presence timeout: `max(own_mode.presenceTimeout, peer_mode.presenceTimeout)`, preventing false evictions across mismatched **Power Modes**. | Dynamic timeout, cross-mode timeout |
| **Eviction Hardening** | The requirement that a **Peer** must miss **2 consecutive sweep intervals** before being marked **Gone** (resilience against transient RF interference). | Double-sweep rule, eviction debounce |
| **Peer Lifecycle** | The three-state machine: **Connected** → **Disconnected** (GATT drop) → **Gone** (sweep eviction). | Peer state machine |
| **Presence** | The liveness state of a **Peer**, determined by **Advertisement** timestamps (not GATT connection state). | Availability, reachability |
| **Presence Eviction** | Marking a **Peer** as **Gone** and removing it from the peer table and **Routing Table**. Distinct from **Connection Eviction** and **Buffer Eviction**. | Peer removal, peer pruning |
| **Sweep Timer** | The periodic timer (per **Power Mode**) that checks for stale **Peers** whose last-seen **Advertisement** exceeds the **Adaptive Timeout**. | Presence timer, cleanup timer |

## Concurrency Model

> See [diagrams.md § Architecture Overview](docs/diagrams.md#3-architecture-overview) and [§ Engine & Coordinator Data Flow](docs/diagrams.md#8-engine--coordinator-data-flow) for visual references.

| Term | Definition | Aliases to avoid |
|------|-----------|-----------------|
| **Engine** | A stateful facade that consolidates a domain concern behind sealed result types. Constructed once and injected into MeshLink. Callers pattern-match on sealed results to decide effects. | Actor, manager, service |
| **SecurityEngine** | Engine owning Noise XX/K sessions, trust store (TOFU), and replay guard state. Returns sealed results for handshake steps, seal/unseal, and trust decisions. | CryptoActor, crypto manager |
| **RoutingEngine** | Engine owning the **Routing Table**, AODV route discovery state, and **Dedup Set**. Returns sealed results for route lookups, RREQ/RREP processing, and dedup checks. | RouterActor, routing manager |
| **TransferEngine** | Engine owning in-flight **Transfer** state, chunking, **SACK** windows, and **AIMD** congestion control. Returns sealed results for chunk processing and transfer lifecycle events. | TransferActor, transfer manager |
| **DeliveryPipeline** | Engine owning delivery tracking, confirmation tombstones, and store-and-forward buffering. Returns sealed results for delivery status and buffer operations. | BufferActor, delivery tracker |
| **Coordinator** | A stateful component that orchestrates loops or multi-step decision logic. Coordinators call engines and return sealed actions for MeshLink to execute. | Actor, controller |
| **RouteCoordinator** | Coordinator managing **keepalive probing** of 1-hop neighbors. AODV route discovery handles all multi-hop route establishment on demand. | RouteActor, route manager |
| **PeerConnectionCoordinator** | Coordinator managing BLE connection lifecycle: discovery, **Tie-Breaking Rule** evaluation, **Noise XX Session** initiation, and version negotiation. | ConnectionActor, connection manager |
| **PowerCoordinator** | Coordinator managing **Power Mode** transitions, **Hysteresis** timers, charging override, and memory shedding during mode downgrades. | PresenceActor, power manager |
| **Policy Chain** | A pure pre-flight evaluator that checks conditions in priority order and returns a sealed decision type (e.g., `SendDecision`, `BroadcastDecision`). Testable via function-type dependency injection. | Validator, checker |
| **SendPolicyChain** | Policy chain evaluating unicast send pre-flight: rate limits, circuit breaker, route availability, buffer capacity. Returns `SendDecision` sealed type. | Send validator, send filter |
| **BroadcastPolicyChain** | Policy chain evaluating broadcast pre-flight: rate limits, TTL, signature requirements. Returns `BroadcastDecision` sealed type. | Broadcast validator, broadcast filter |
| **MessageDispatcher** | Policy chain decoding inbound BLE frames, routing them to the appropriate engine, and delegating side effects via **DispatchSink**. | Inbound handler, frame router |
| **Sealed Result Type** | The pattern used across all engines and coordinators: methods return sealed interfaces so callers exhaustively pattern-match on outcomes. Eliminates boolean flags and stringly-typed errors. | Union type, result enum |
| **Function-Type Dependency** | Constructor parameter of type `() -> T` or `(A) -> B` that injects behavior without requiring the full dependency graph. Used by policy chains and coordinators for testability. | Callback, lambda injection |
| **DispatchSink** | Callback interface grouping 8 effect callbacks (flow emissions, transport sends) that `MessageDispatcher` uses to delegate side effects back to MeshLink. | Observer, listener |
| **Monotonic Time** | Uptime-based clock (monotonic, not wall-clock) used for dedup expiry, buffer TTL, replay counter eviction, and circuit breaker windows. Resets to zero on reboot. `System.nanoTime()` (Android) / `ProcessInfo.systemUptime` (iOS). | Uptime clock, elapsed time |
| **Virtual Time** | Injectable clock model used by tests. All internal timers use the injected `clock: () -> Long`. Tests advance time programmatically via `advanceTime()` and `advanceUntilIdle()`. | Fake clock, test clock |
| **VirtualMesh** | The in-process BLE simulator that creates N simulated **Peers** with configurable per-link parameters (latency, loss, RSSI). All **BleTransport** calls route through it. | Simulator, mock mesh |

## Library API

| Term | Definition | Aliases to avoid |
|------|-----------|-----------------|
| **App Filter Hash** | SHA-256-64 hash of the developer-provided appId string. 8 bytes fixed size in the wire format. Zero hash = no filter. NOT included in BLE advertisement; filtering is recipient-side only. | App ID hash, filter hash |
| **appId** | Immutable config parameter (`SHA-256-64` hash in wire format). Inbound messages with non-matching hash silently dropped; relays forward regardless. → see design.md §10. | setAppFilter, app filter, topic filter |
| **Backward Compatibility (Protocol)** | Each major protocol version MUST accept connections from the previous major version and speak the older protocol (downgrade). Prevents mesh partitioning during rollout. | Version downgrade, protocol compat |
| **BLE Connection Parameters** | The BLE connection interval, slave latency, and supervision timeout. MeshLink **requests** optimal values per activity state (active transfer: 15ms/0; idle: 100ms/4) but the OS makes the final decision. Actual negotiated values logged via **Diagnostic Stream**. | Connection interval, BLE params |
| **Config Validation** | Two-phase: per-field bounds at assignment, cross-field rules at build. Returns all violations at once. Config immutable after construction. 7 cross-field rules in v1, extensible. | Validation, bounds checking |
| **Configuration Preset** | Factory method returning `MeshLinkConfig` with tuned defaults for a use case: `smallPayloadLowLatency()` (low latency), `largePayloadHighThroughput()` (large payloads), `minimalResourceUsage()` (battery conservation). All values individually overridable via DSL block. | Config template, preset |
| **Diagnostic Backpressure** | **Diagnostic Stream** uses a bounded buffer (capacity 256, DROP_OLDEST). Each `DiagnosticEvent` carries a `droppedCount` field indicating how many events were lost. Slow subscribers never block the mesh engine. | Diagnostic overflow, event dropping |
| **Diagnostic Stream** | An optional `Stream<DiagnosticEvent>` for transport and mesh observability (L2CAP fallback, rate limits, route changes, evictions). Zero cost when unsubscribed. | Event stream, debug stream, telemetry |
| **DiagnosticEvent** | Structured event: `{code, severity, monotonicMillis, wallClockMillis, droppedCount, payload: Map<String, Any>}`. Four severity levels: `FATAL` (terminal), `ERROR` (app should act), `WARNING` (degraded), `INFO` (observability). | Log event, debug event |
| **Diagnostic Timestamp** | Each `DiagnosticEvent` carries dual timestamps: `monotonicMillis` (millis since boot) for interval calculations, and `wallClockMillis` (epoch millis) for human-readable correlation. | Event time, timestamp |
| **discoveryTimeout** | Config parameter. Maximum time for GATT service/characteristic discovery (default 10s). | Discovery timer, service timeout |
| **Library State Machine** | 6-state lifecycle: uninitialized → running ⇄ paused → stopped. Also: recoverable (retryable failure) and terminal (permanent). `stopped` is restartable; `terminal` is not. `stop()` is idempotent. | Lifecycle, state transitions |
| **MeshLink** | The library/SDK itself — the public interface consumed by the **Consuming App**. | SDK, framework, library |
| **Outbound Broadcast Rate Limit** | The `broadcastRateLimit` is enforced locally on the sending device — `broadcast()` returns `broadcastRateLimitExceeded` error if the consuming app exceeds the configured rate. Prevents a buggy app from flooding the mesh at the source. | Local broadcast throttle, send-side rate limit |
| **Outbox Pattern** | Recommended consuming app pattern: track sent messages in an app-side outbox, remove on `onDeliveryConfirmed`, retry or alert on `onTransferFailed`. See §10 integration guide example. | Send tracking, delivery tracking |
| **perRelayBufferSize** | Config parameter. Per-relay-transfer buffer copy size (default 100KB, range 10KB–1MB). | Relay buffer, copy buffer size |
| **Reactive Connection Limit Discovery** | Android-specific: when BLE connection attempts fail with `GATT_ERROR`, the effective slot budget is reduced. The discovered limit is persisted per device model to compensate for undocumented OEM connection caps. | Auto connection limit, OEM limit discovery |
| **Single-Instance Enforcement** | `MeshLink` is a process-level singleton. `MeshLink.configure()` throws `IllegalStateException` if already configured in the current process. Prevents BLE conflicts and connection slot contention. | Singleton guard, double-init prevention |
| **Thin Overlay (Pause)** | **pause()** suppresses only scanning/advertising and own-outbound sends. All other subsystems (**Buffer Eviction**, memory shedding, power transitions, relay duties) operate normally. | Pause mode, light pause |
| **Two-Phase Shutdown** | After `stop()` returns (5s timeout), lingering callbacks operate on invalidated-but-not-freed state; **LibraryShutdownException** on next library call. Callback threads are daemon threads. | Graceful shutdown, deferred cleanup |
| **Version Negotiation** | Protocol compatibility rules exchanged during Noise XX handshake: same major = full compat, adjacent major = backward compat, gap >1 = disconnect. → see design.md §3. | Protocol negotiation, version check |

## Relationships

- A **Peer** has exactly one **Identity** (one Ed25519 keypair, one device)
- A **Peer** has 0–N **Neighbors** (direct BLE range) and a **Routing Table** of reachable **Peers** beyond neighbors
- A **Persistent Connection** occupies one **Connection Slot**; an **On-demand Connection** evicts the lowest-priority **Persistent Connection** to claim a slot
- A **Direct Message** produces a **Sealed Message** (via **E2E Encryption**), which is wrapped in a **Routed Message** for multi-hop delivery
- A **Routed Message** is forwarded hop-by-hop using **Cut-Through Forwarding**, protected by **Hop-by-Hop Encryption** at each step
- A **Transfer** is a single-hop chunked delivery of one **Routed Message** between two **Neighbors**, composed of many **Chunks** acknowledged by **Chunk ACKs**
- A **Delivery ACK** confirms receipt of a **Direct Message** and is routed independently via reverse-path unicast
- The **Control Plane** (AODV route discovery, keepalives) feeds the **Data Plane** (**Routing Table**) — route discovery establishes routes on demand
- **Power Mode** governs: scan duty cycle, advertising interval, keepalive interval, **Connection Slot** budget, concurrent **Transfer** limits, **Sweep Timer** interval, and **Adaptive Timeout**

## Example dialogue

> **Dev:** "When a **Peer** in Performance **Power Mode** discovers a new **Neighbor** via its **Advertisement**, who initiates the GATT connection?"
>
> **Domain expert:** "The **Tie-Breaking Rule** decides. The **Peer** with the higher **Power Mode** becomes the **Central** — it can afford the polling cost. If both are in the same mode, the higher **Key Hash** wins."
>
> **Dev:** "Once connected, what happens first?"
>
> **Domain expert:** "A Noise XX handshake establishes the **Noise XX Session** — that's the **Hop-by-Hop Encryption**. Then the **Neighbor** is added to the connection table. Routes are discovered on demand via AODV when messages need to be sent — no proactive route exchange is needed."
>
> **Dev:** "If I now send a **Direct Message** to a **Peer** that's 3 hops away, what happens?"
>
> **Domain expert:** "The library creates a **Sealed Message** using **E2E Encryption** (Noise K to the recipient's **Public Key**, with sender authentication built in), wraps it in a **Routed Message**, looks up the **Next-Hop** in the **Routing Table**, and starts a **Transfer**. The **Payload** is encrypted, chunked into **Chunks**, and sent through the **Noise XX Session**. Each **Relay** along the path does **Hop-by-Hop Re-encryption** and **Cut-Through Forwarding** — streaming **Chunks** to the **Next-Hop** while keeping a local **Buffer** copy in case the connection drops."
>
> **Dev:** "What if the **Relay** can't forward because it's at its concurrent **Transfer** limit?"
>
> **Domain expert:** "It sends a **NACK** with `bufferFull`. The sender retries with exponential backoff or tries an alternate route."

## Testing

| Term | Definition | Aliases to avoid |
|------|-----------|-----------------|
| **Confidence Tier** | One of three testing levels: Tier 1 (VirtualMesh, every commit, gates merges), Tier 2 (Firebase 3-device nightly, creates issues), Tier 3 (full matrix pre-release, gates release). | Test level, test tier |
| **Flaky Tolerance** | A BLE hardware test must fail 2-of-3 runs to be flagged. Single failures are logged but not actioned. VirtualMesh (Tier 1) has zero flaky tolerance. | Flaky policy, retry policy |
