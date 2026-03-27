# Plan: MeshLink — Secure BLE Mesh Messaging Library

> Source PRD: [GitHub Issue #1](https://github.com/trancee/MeshLink/issues/1)

## Architectural decisions

Durable decisions that apply across all phases:

- **Platform strategy**: Kotlin Multiplatform (KMP). `commonMain` holds ~85% of shared protocol/logic code. `androidMain` and `iosMain` hold platform-specific BLE transport and secure storage (~15%). All actors, wire format, crypto state machines, routing, and buffering live in `commonMain`.
- **Concurrency model**: Actor-based. 7 actors (ConnectionActor, CryptoActor, TransferActor, RouterActor, GossipActor, PresenceActor, BufferActor) with bounded typed Kotlin Channels. Strict DAG ordering — downstream actors never send to upstream. Reverse communication via `CompletableDeferred` only. Tiered circuit breaker supervision (Strict/Standard/Lenient).
- **Wire format**: Custom binary protocol. All multi-byte fields unsigned little-endian. 1-byte message type prefix (0x00–0x07, 0x08–0xFF reserved). No protobuf, no TLV. Any wire change requires protocol version bump.
- **GATT service**: Single service UUID `0x7F3A` with 4 characteristics (Control Write/Notify for handshake+gossip; Data Write/Notify for chunks). Control uses write-with-response; Data uses write-without-response.
- **Advertisement format**: Frozen 17-byte payload (1B version+power, 16B SHA-256-128 key hash). Permanent across all protocol versions.
- **Encryption**: Two-tier. Noise K (`Noise_K_25519_ChaChaPoly_SHA256`) for E2E. Noise XX (`Noise_XX_25519_ChaChaPoly_SHA256`) for hop-by-hop. Native platform crypto: Java 21 JCA (JVM/Android), CryptoKit (iOS), pure Kotlin Ed25519/X25519 fallback for Android API 26-32.
- **Identity**: Static Ed25519 keypair generated on first launch. Curve25519 derived via birational map. Public key IS the identity. One key = one device. Keys excluded from platform backup.
- **Secure storage**: Android `EncryptedSharedPreferences` (backed by Keystore). iOS Keychain with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`.
- **Transport abstraction**: `BleTransport` expect/actual interface. Production: `AndroidBleTransport` / `iOSBleTransport`. Tests: `VirtualMeshTransport`.
- **Public API shape**: `MeshLink(context) { config }` constructor. `send(recipient, payload): Result<UUID>`. `.peers: Flow<Peer>`, `.messages: Flow<Message>`, `.diagnostics: Flow<DiagnosticEvent>`. Kotlin suspend + Flow → Swift async + AsyncStream.
- **Config**: Immutable `MeshLinkConfig` built via Kotlin DSL / Swift struct init. Bound at construction. Cross-field validation returns all violations at once.
- **Routing**: Enhanced DSDV with composite link quality metric. Primary + backup routes per destination. Routes expire at 5× gossip interval.
- **Buffering**: Fixed 1MB pool (configurable). 75/25 relay/own split. 3-tier eviction (no-route → relay → own). In-memory dedup set (10K entries, LRU).
- **Distribution**: Maven Central (Android AAR with consumer ProGuard rules). SPM (iOS XCFramework).
- **Minimum versions**: Android API 26 (8.0). iOS 14.
- **License**: Unlicense.

---

## Phase 1: Hello, Neighbor

**User stories**: 1, 2, 3, 4, 6, 12, 14, 31, 32, 33, 36

### What to build

The thinnest possible end-to-end slice: two devices discover each other over BLE and exchange a message through the public API, with no encryption and no routing.

Scaffold the KMP project structure (`commonMain`, `androidMain`, `iosMain`), the actor system skeleton (ConnectionActor and TransferActor with their channels and supervision), and the `BleTransport` interface with platform implementations for advertising, scanning, GATT connection, MTU negotiation, and service discovery.

Implement the wire format for `chunk` (0x03) and `chunk_ack` (0x04) message types with golden byte-level test vectors. Implement basic chunking (split payload into MTU-sized pieces, sequence numbers, message ID) and reassembly with simple in-order ACK.

Expose the public API surface: `MeshLink(context) { config }` with `start()` / `stop()`, `send(recipient, payload)` returning `Result<UUID>`, `.peers` Flow (with replay of current known peers), and `.messages` Flow (forward-only). Implement the config DSL with default values for all parameters (no cross-field validation yet).

Build the VirtualMesh test harness in `commonTest` — in-process simulation of N peers with configurable latency, packet loss, and MTU. Injectable virtual time via `TestCoroutineScheduler`. Verify the tracer bullet: two VirtualMesh peers discover each other and transfer a small payload end-to-end through the public API.

Set up the CI pipeline: build + test shared module on JVM and Native, run protocol conformance tests on every PR.

### Acceptance criteria

- [ ] KMP project builds on both JVM and Native targets with CI green
- [ ] `BleTransport` interface defined with `expect`/`actual` for Android, iOS, and VirtualMesh
- [ ] GATT service advertises UUID `0x7F3A` with 4 characteristics; scanner discovers it
- [ ] `chunk` (0x03) and `chunk_ack` (0x04) wire formats match golden byte-level test vectors
- [ ] Two VirtualMesh peers discover each other via advertisements; `.peers` Flow emits discovery
- [ ] `send(peer, "hello".toByteArray())` returns `Result.Success`; recipient's `.messages` Flow emits the message
- [ ] Payload up to MTU size transfers correctly; payload requiring multiple chunks transfers correctly
- [ ] `start()` / `stop()` lifecycle works; `send()` before `start()` throws `IllegalStateException`
- [ ] Config DSL accepts parameter overrides; defaults applied when omitted
- [ ] `send()` is thread-safe for concurrent calls from multiple threads
- [ ] `send()` returns `Result.Failure(bufferFull)` immediately (non-blocking) when buffer is full
- [ ] VirtualMesh test with virtual time completes in <1 second wall-clock
- [ ] CI pipeline runs `commonTest` on JVM and Native targets on every PR

---

## Phase 2: Encrypted Channel

**User stories**: 8, 9, 17, 18, 20, 48

### What to build

Layer two-tier encryption onto the single-hop transport from Phase 1. This phase adds the CryptoActor and secure storage.

Implement Ed25519 key generation on first launch, Curve25519 derivation via birational map, and storage in platform secure storage (EncryptedSharedPreferences / Keychain). Keys excluded from backup.

Implement the Noise XX handshake state machine (3-message mutual authentication over GATT Control characteristics with 8s timeout). The handshake payload carries protocol version (2B) + capability byte (1B) + L2CAP PSM (2B) — 5 bytes. After handshake, all data on the connection is encrypted with session transport keys.

Implement Noise K seal/unseal for E2E encryption. Sender generates ephemeral Curve25519 keypair per message, encrypts payload with recipient's static key. Sealed payload layout: `[8B replay counter | 1B flags | NB data]`.

Implement replay protection: per-sender uint64 counter with 64-entry sliding window. Pre-increment persist for outbound counter; periodic 1s persist for inbound per-sender window.

Implement basic TOFI key pinning in `strict` mode: pin key after successful Noise XX handshake (not on gossip receipt). `onKeyChanged` callback fires when a previously-pinned peer appears with a different key. Pin persistence in secure storage.

Wire format: `handshake` (0x01) messages. Test vectors for Noise XX transcript and Noise K sealed output using known keypairs.

### Acceptance criteria

- [ ] Ed25519 keypair generated on first `start()`; Curve25519 derived correctly (golden vector)
- [ ] Private keys stored in platform secure storage; excluded from backup
- [ ] Noise XX handshake completes between two VirtualMesh peers within 8s timeout
- [ ] Handshake with wrong static key fails (MITM detection)
- [ ] Noise XX transcript matches golden test vector (known keypairs + known ephemeral keys)
- [ ] Noise K sealed message matches golden test vector on both JVM and Native
- [ ] `send()` E2E encrypts payload; recipient's `.messages` delivers decrypted plaintext
- [ ] Replay counter rejects duplicate message (same counter value)
- [ ] Replay counter accepts out-of-order messages within 64-entry sliding window
- [ ] Outbound replay counter persists across simulated process restart
- [ ] TOFI pins key after first handshake; rejects different key from same peer in strict mode
- [ ] `onKeyChanged` callback fires when peer key changes
- [ ] Key pins survive simulated restart (persisted to secure storage)
- [ ] `SecureStorage` interface defined with `expect`/`actual` for Android, iOS, and mock

---

## Phase 3: Fast Transfer

**User stories**: 14, 15, 16

### What to build

Upgrade the chunking system to handle 100KB payloads with reliability and performance. This phase completes the TransferActor and adds L2CAP support to ConnectionActor.

Implement selective ACK (SACK) with 64-bit bitmask and AIMD congestion control for GATT mode: halve interval on 2 consecutive timeouts, +2 on 4 clean rounds, reset on reconnect. Implement byte-offset resume: on reconnection, receiver reports total bytes received; sender resumes from that offset at the new MTU.

Add L2CAP CoC data plane: capability negotiation via the capability byte already exchanged in Phase 2's Noise XX handshake, PSM exchange via GATT control characteristic, insecure L2CAP channel setup (no BLE-level pairing). Length-prefix framing on L2CAP stream (1-byte type + 3-byte LE length + payload). Power-mode chunk size negotiation (Performance=8192, Balanced=4096, PowerSaver=1024).

Implement L2CAP failure handling: 3 retry attempts with exponential backoff → mark peer GATT-only for session. Backpressure detection (3 consecutive >100ms writes in 7s window → GATT fallback). Nonce desync → immediate L2CAP teardown + GATT fallback. Circuit breaker: 3 desyncs in 5 min → GATT-only for 30 min.

Implement concurrent transfer interleaving (round-robin, per-power-mode limits: 8/4/1). Chunk inactivity timeout (30s, per-transfer). Transfer progress callbacks.

### Acceptance criteria

- [ ] 100KB payload transfers correctly between two peers over GATT with SACK
- [ ] SACK bitmask correctly identifies received chunks and gaps; sender retransmits only gaps
- [ ] AIMD: window halves on 2 consecutive timeouts; increases by 2 on 4 clean rounds; resets on reconnect
- [ ] Connection drop mid-transfer → resume from byte offset on reconnect (no data loss)
- [ ] Both peers support L2CAP → L2CAP channel opened; data transfers at higher throughput
- [ ] One peer lacks L2CAP → GATT fallback; data transfers correctly
- [ ] L2CAP open failure → 3 retries with backoff → peer marked GATT-only for session
- [ ] L2CAP backpressure (3× >100ms writes in 7s) → GATT fallback + `resume_request`
- [ ] L2CAP nonce desync → teardown + GATT fallback; 3 desyncs in 5 min → GATT-only 30 min
- [ ] Transport mode switch resume: transfer starts on GATT, resumes on L2CAP from byte offset
- [ ] 3 concurrent transfers interleaved via round-robin; all 3 complete correctly
- [ ] Concurrent transfer limit reached → NACK with `bufferFull` returned to sender
- [ ] Chunk inactivity timeout fires at 30s; stalled transfer dropped, buffer freed
- [ ] Progress callback fires with accurate progress during multi-chunk transfer
- [ ] L2CAP framing round-trips correctly (length-prefixed messages parsed on both platforms)

---

## Phase 4: Mesh Relay

**User stories**: 7, 43, 44, 45

### What to build

Extend point-to-point to multi-hop mesh. This phase adds RouterActor, GossipActor, PresenceActor, and BufferActor — the four remaining actors in the DAG.

Implement the gossip protocol: `PeerAnnouncement` wire format (0x02) with Ed25519-signed immutable fields (origin pubkey + sequence number). Differential exchange with per-neighbor sequence tracking. Split horizon + poison reverse. Triggered updates on >30% cost change. Rate limiting (1 triggered update per gossip interval per neighbor). Keepalive messages (6 bytes) when topology is stable. Auto-throttle at >100 routing table entries.

Implement Enhanced DSDV routing: composite route cost metric (`RSSI_base × loss_multiplier × freshness_penalty + stability_penalty`), primary + backup routes per destination (different next-hops), route expiry at 5× gossip interval, settling time (1 interval), holddown timer (2× interval). Per-neighbor routing table cap (30%) for Sybil mitigation. Route cost sanity validation.

Implement presence detection: advertisement-based with per-peer adaptive timeout, three-state lifecycle (Connected → Disconnected → Gone), 2-consecutive-miss eviction hardening, atomic sweep evaluation.

Implement BufferActor: fixed 1MB pool with 75/25 relay/own split, 3-tier eviction, in-memory dedup set (10K entries, LRU), store-and-forward with configurable TTL, cut-through relay forwarding with local buffer copy.

Implement routed message forwarding: `routed_message` (0x05) wire format with visited-list loop prevention (SHA-256-128 key hashes). Hop limit enforcement. Relay decrypt → re-encrypt (hop-by-hop Noise XX). Relay-is-also-destination handling for direct messages vs. broadcasts.

### Acceptance criteria

- [ ] A→B→C delivery: device A sends to C through relay B; message arrives decrypted
- [ ] 100KB multi-hop transfer (A→B→C) with simulated packet loss at B→C link completes successfully
- [ ] Gossip converges: all peers learn routes to all reachable destinations within expected interval
- [ ] Gossip signature verification: announcement with invalid Ed25519 signature rejected
- [ ] Split horizon: route not advertised back to neighbor it was learned from
- [ ] Poison reverse: route withdrawal sends cost=∞ to old next-hop
- [ ] Triggered update fires immediately on >30% cost change; batched within 1 interval
- [ ] DSDV: stale route with lower sequence number rejected even if shorter path
- [ ] Multipath failover: primary route fails → instant switch to backup (different next-hop)
- [ ] Visited-list loop detection: message dropped when relay's own hash appears in visited list
- [ ] Hop limit enforced: message dropped at `maxHops`
- [ ] Store-and-forward: message buffered for offline recipient; delivered when recipient reappears within TTL
- [ ] Dedup: same message arriving via two paths delivered once to app
- [ ] Presence: peer stops advertising → evicted after 2 consecutive sweep misses
- [ ] Relay cut-through: chunks forwarded before full reassembly; local buffer retransmits on next-hop drop
- [ ] Buffer eviction order: no-route first → relay → own-outbound; within tier: priority → TTL → FIFO
- [ ] Per-neighbor routing table cap enforced (30%); excess routes from single neighbor rejected
- [ ] 50-peer VirtualMesh gossip convergence completes within 30s

---

## Phase 5: Broadcast, Trust & Delivery

**User stories**: 5, 19, 21, 22, 46, 47

### What to build

Add broadcast messaging, complete the TOFI trust model, implement delivery ACKs, and wire up appId filtering. This phase completes the messaging feature set.

Implement `broadcast` (0x00) wire format: Ed25519-signed envelope with remaining hop count. Broadcast propagation with per-hop TTL clamping. Broadcast rate limiting (10/min per sender, enforced locally on outbound + remotely on inbound). No self-delivery for broadcasts.

Complete TOFI: add `softRepin` mode (auto-accept new key). Implement `rotateIdentity()`: generate new keypair, sign rotation announcement with old key, gossip to neighbors, grace period for old-key sessions (5× gossip interval), teardown + erase old key. Auto-accept signed rotations in both modes.

Implement delivery ACKs: `delivery_ack` (0x06) wire format with recipient Ed25519 signature. Reverse-path unicast routing (2 attempts × 2s timeout). Strict single-signal rule: exactly one terminal callback per messageId (`onDeliveryConfirmed` or `onTransferFailed`). Late ACK tombstoning.

Implement appId topic filtering: `SHA-256-128(appId.toUTF8())` hash in sealed payload flags. Recipient-side silent drop for non-matching appId. Relays forward all messages regardless of appId.

Implement `onTransferFailed` with `failureContext` (NO_ROUTE, HOP_LIMIT, ACK_TIMEOUT, BUFFER_FULL, PEER_OFFLINE). Delivery deadline fires `DELIVERY_TIMEOUT` at `bufferTTL`.

### Acceptance criteria

- [ ] `broadcast(payload)` delivers to all peers within `broadcastTTL` hops; Ed25519 signature valid
- [ ] Broadcast with forged signature rejected at receiver
- [ ] Broadcast rate limit enforced: 11th broadcast in 60s returns `broadcastRateLimitExceeded`
- [ ] No self-delivery: sender's `.messages` does not fire for own broadcast
- [ ] Per-hop TTL clamping: relay with `broadcastTTL=1` limits propagation to immediate neighbors
- [ ] `rotateIdentity()` returns `Result.Success(newPublicKey)`; announcement signed with old key
- [ ] Peers in `softRepin` mode auto-accept rotation; `onKeyChanged` fires (informational)
- [ ] Peers in `strict` mode reject messages from rotated peer until `repinKey()` called
- [ ] Signed rotation auto-accepted in both strict and softRepin modes
- [ ] Delivery ACK with valid Ed25519 signature triggers `onDeliveryConfirmed`
- [ ] Delivery ACK with forged signature rejected
- [ ] Exactly one terminal callback per messageId: either `onDeliveryConfirmed` or `onTransferFailed`
- [ ] Late delivery ACK after tombstone silently dropped + `lateDeliveryAck` diagnostic
- [ ] `appId` set → outbound includes hash; inbound with non-matching hash silently dropped
- [ ] `appId = null` → all messages received (no filtering)
- [ ] `onTransferFailed` includes correct `failureContext` for each failure scenario
- [ ] Delivery deadline: `onTransferFailed(DELIVERY_TIMEOUT)` fires at `bufferTTL`

---

## Phase 6: Power & Platform

**User stories**: 10, 11, 25, 26, 27, 28, 29, 30, 37, 38

### What to build

Implement automatic power management and harden both platform implementations for production background operation.

Implement the 3-tier power mode engine: battery thresholds (>80% Performance, 30–80% Balanced, <30% PowerSaver), charging override, `customPowerMode` override (highest priority). Asymmetric hysteresis: upward transitions immediate, downward transitions require 30s sustained. `BatteryMonitor` expect/actual interface.

Implement scan duty cycle controller: Android hardware-level via `ScanSettings`; iOS foreground software-level start/stop; iOS background carved out (CoreBluetooth controls). Advertising interval adjustment per power mode (250ms/500ms/1000ms).

Implement connection limit enforcement with priority eviction on power mode downgrade. Graceful drain: active transfers get 30s grace period. Memory pressure cancels grace periods. Slot accounting prevents new connections during grace period overflow.

Implement Android foreground service: abstract `MeshLinkService` base class with `createNotification()`, `START_STICKY`, crash loop circuit breaker (3 crashes/5min). Doze mode documentation. Required permissions validation in `start()`.

Implement iOS State Preservation and Restoration: `willRestoreState` handling, fast-path reconnection for restored peripherals, fresh Noise XX handshakes. 30s BLE init timeout.

Implement `pause()` / `resume()` lifecycle: stop scanning/advertising, keep connections alive, queue own-outbound sends, continue relay forwarding. Resume: restart scan, flush queued sends, immediate sweep cycle.

Implement `stop()` with best-effort transfer flush (5s drain timeout). `stopped` is restartable via `start()`. BLE stack auto-recovery (60s silence detection, teardown + re-init, 3 attempts/hour). Low-memory tiered shedding (relay buffers → dedup → connections).

### Acceptance criteria

- [ ] Battery level change triggers correct power mode transition
- [ ] Charging forces Performance mode; `customPowerMode` overrides both battery and charging
- [ ] Hysteresis: battery oscillating near threshold does NOT cause rapid toggling (30s delay on downward)
- [ ] Connection limit enforced per mode; excess idle connections evicted on downgrade
- [ ] Active transfer connections get 30s grace period; OS memory warning cancels grace
- [ ] Scan duty cycle matches target per power mode (Performance 80–100%, Balanced 40–60%, PowerSaver 10–25%)
- [ ] Android foreground service survives notification dismissal; `MeshLinkService` lifecycle works
- [ ] iOS termination → relaunch → peer/routing tables rebuild from gossip within 2 cycles
- [ ] `pause()` → no scanning/advertising; relay forwarding continues; own sends queued
- [ ] `resume()` → scanning restarts, queued sends delivered, immediate sweep runs
- [ ] `stop()` flushes active transfers within 5s; `onTransferFailed` fires for remaining
- [ ] `stop()` → `start()` → library restarts with identity restored; dedup set empty; routing rebuilt
- [ ] BLE stack unresponsive for 60s → auto-recovery triggered; `BLE_STACK_UNRESPONSIVE` diagnostic
- [ ] Low memory → tiered shedding executes in order; `memoryPressure` diagnostics emitted
- [ ] `start()` without BLE permissions → `Result.Failure(permissionDenied(missing))` → recoverable

---

## Phase 7: Production API

**User stories**: 12, 13, 23, 24, 33, 34, 35, 39, 41, 42, 49, 50

### What to build

Complete the production API surface: configuration presets, full diagnostic stream, mesh health API, rate limiting, error model, protocol versioning, and distribution packaging.

Implement configuration presets: `chatOptimized()`, `fileTransferOptimized()`, `powerOptimized()` as factory methods returning `MeshLinkConfig` with adjusted defaults. Individual overrides applied after preset. Cross-field validation rules (all 5 rules) returning all violations at once.

Implement full diagnostic event stream: all diagnostic events from the PRD (l2capFallback, rateLimitHit, routeChanged, peerEvicted, bufferPressure, transportModeChanged, gossipTrafficReport, bleStackUnresponsive, etc.). `DiagnosticEvent` structure with `code`, `severity`, `monotonicMs`, `droppedCount`, `payload`. `MutableSharedFlow(extraBufferCapacity=256, onBufferOverflow=DROP_OLDEST)`.

Implement `meshHealth()` (on-demand, always fresh) and `meshHealthFlow` (reactive, throttled to 2/sec). `MeshHealthSnapshot` with connectedPeers, reachablePeers, avgRouteCost, buffer utilization, activeTransfers, powerMode.

Implement per-sender-per-neighbor rate limiting (20 msg/min default, configurable), per-neighbor aggregate rate limit (100 msg/min), NACK rate limiting (10/sec/neighbor), handshake rate limiting (1/sec for unknown peers, TOFI-pinned exempt).

Implement self-send loopback: `send(ownPublicKey)` delivers via `onMessageReceived` asynchronously; bypasses BLE, dedup, replay counter. Exception isolation and blocking tolerance for all callbacks.

Implement protocol versioning: version exchange in Noise XX handshake, `min(local, remote)` negotiation, N−1 backward compatibility, major mismatch > 1 version → disconnect.

Package for distribution: Maven Central (AAR with consumer ProGuard rules), SPM (XCFramework). Semver for library, integer major.minor for protocol.

### Acceptance criteria

- [ ] Config presets return correct default overrides; individual override applied after preset
- [ ] Cross-field validation catches all 5 rules; returns all violations at once (not just first)
- [ ] Config immutable after construction; no mutation after `start()`
- [ ] Diagnostic stream emits events with correct severity, timestamp, and droppedCount
- [ ] Diagnostic stream has zero cost when no subscriber (SharedFlow)
- [ ] `meshHealth()` returns fresh snapshot (not cached); reflects current state
- [ ] `meshHealthFlow` emits on peer connect/disconnect, transfer start/complete, power mode change
- [ ] Per-sender rate limit: 21st message in 60s from same sender NACKed
- [ ] Per-neighbor aggregate limit: excess beyond 100/min dropped with diagnostic
- [ ] NACK rate limiting: max 10 NACKs/sec/neighbor; excess silently dropped
- [ ] Handshake rate limit: max 1/sec for unknown peers; TOFI-pinned exempt
- [ ] `send(ownPublicKey)` → `onMessageReceived` fires asynchronously; no BLE activity
- [ ] Callback exception in `onMessageReceived` logged as diagnostic; library continues
- [ ] Callback blocking for 10s does not stall actor processing
- [ ] Protocol version negotiation: v1.0 ↔ v1.2 → both use v1.0 feature set
- [ ] Major version gap > 1 → connection torn down
- [ ] AAR builds with consumer ProGuard rules; R8 minified app works without custom keep rules
- [ ] XCFramework builds and integrates via SPM

---

## Phase 8: Reference App & Validation

**User stories**: 40

### What to build

Build the cross-platform reference app, publish documentation, and establish the physical testing pipeline. This phase validates the entire system on real hardware.

Build the Compose Multiplatform reference app with three screens: Chat (demo mode — text/photo messaging with delivery status), Mesh Visualizer (debug mode — live peer graph with link quality, route highlighting), and Diagnostics Log (debug mode — scrollable, filterable DiagnosticEvent viewer with severity filters). Toggle between demo/debug via settings.

Add test mode: hidden intent (Android) / URL scheme (iOS) exposing automated test triggers for Appium (send N messages, rotate key, force power mode, dump mesh state to JSON).

Write developer documentation: integration guide (step-by-step setup for Android and iOS), API reference (generated from KDoc/DocC), architecture guide (mesh/encryption/transport interaction), troubleshooting guide (common BLE issues, iOS background gotchas).

Publish the binary wire format spec and protocol RFC with test vector format documentation.

Set up Firebase Test Lab integration (Tier 2 nightly: Pixel 6a, Samsung S21, iPhone 12). Set up physical test rig (Tier 3 pre-release: 5–7 devices, RF attenuators, shielded enclosure, Appium automation).

Run load and stress tests: 50+ peer VirtualMesh stress test (gossip convergence, memory bounds, sustained load). 100-peer correctness test. Mixed-version interop test (vN ↔ vN−1).

### Acceptance criteria

- [ ] Reference app builds on both Android and iOS from single Compose Multiplatform codebase
- [ ] Chat screen: send/receive text messages; delivery status indicators (sent/delivered/failed)
- [ ] Mesh Visualizer: displays live peer graph with edges color-coded by link quality
- [ ] Diagnostics Log: scrollable, filterable by severity (FATAL/ERROR/WARNING/INFO)
- [ ] Test mode triggers accessible via hidden intent (Android) / URL scheme (iOS)
- [ ] Integration guide: fresh project setup succeeds following the guide on both platforms
- [ ] API reference generated from code (KDoc and DocC)
- [ ] Wire format spec and RFC published in repository
- [ ] Firebase Test Lab nightly: Android↔iOS interop test passes (1:1 + broadcast)
- [ ] Physical test rig: 100KB cross-platform transfer with L2CAP completes with correct data
- [ ] 50-peer VirtualMesh stress test: all routes converge, memory bounded, zero message loss under 5% packet loss
- [ ] 100-peer VirtualMesh: routing convergence, message delivery, gossip stability verified
- [ ] Mixed-version interop: half peers at vN, half at vN−1 → routing and messaging work correctly
- [ ] Battery drain measured on reference device: within ±20% of PRD targets per power mode
