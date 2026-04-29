# Architecture Map

## Package Layout

```
ch.trancee.meshlink/
├── api/                  ← Public API surface (MeshLinkApi, Config, Events, State)
├── crypto/               ← CryptoProvider interface + platform impls
│   └── noise/            ← Noise XX/K state machines, CipherState, HandshakeState
├── engine/               ← MeshEngine coordinator, MeshEngineConfig
├── identity/             ← Identity generation, persistence, key hash derivation
├── messaging/            ← DeliveryPipeline, BroadcastEngine, DedupSet
├── power/                ← PowerManager, PowerTier, TieredShedder, battery FSM
├── routing/              ← RouteCoordinator, RoutingEngine (Babel), RouteTable
├── storage/              ← SecureStorage interface + platform impls (DataStore, Keychain)
├── transfer/             ← TransferEngine, ChunkAssembler, SACK, CutThroughBuffer
├── transport/            ← BleTransport interface + platform impls + helpers
│   ├── advertisement/    ← AdvertisementCodec, PseudonymRotator, MeshHashFilter
│   └── connection/       ← ConnectionInitiationPolicy, ConnectionLimiter, OemSlotTracker
├── trust/                ← TrustStore, TOFU verification, key rotation
└── wire/                 ← WireCodec, ReadBuffer, WriteBuffer, InboundValidator
    └── messages/         ← 12 message data classes (sealed interface WireMessage)
```

---

## Component Responsibilities

| Component | Single Responsibility |
|-----------|----------------------|
| **MeshLink** | Public lifecycle shell. Holds identity + engine + transport. Delegates all logic. |
| **MeshEngine** | Internal coordinator. Composes all subsystems, routes events between them. |
| **BleTransport** | Platform BLE abstraction. Dual-role (central + peripheral). L2CAP-first, GATT fallback. |
| **NoiseHandshakeManager** | Manages in-progress Noise XX handshakes. Produces `NoiseSession` on completion. |
| **RouteCoordinator** | Babel routing wrapper. Installs/retracts routes, handles Hello/Update. |
| **RoutingEngine** | Pure Babel implementation. Feasibility condition, seqNo, differential updates. |
| **DeliveryPipeline** | Inbound frame dispatch. Routes messages to local delivery or relay. |
| **TransferEngine** | Chunked transfer sessions. SACK, retransmission, timeout, resume. |
| **CutThroughBuffer** | Relay optimization. Forwards chunks before full message received. |
| **PowerManager** | Battery-driven tier transitions. Hysteresis, delay, bootstrap. |
| **TieredShedder** | Enforces per-tier connection limits. Evicts lowest-priority peers. |
| **PresenceTracker** | Peer lifecycle FSM. Connected → Disconnected → Gone (2-sweep eviction). |
| **MeshStateManager** | Periodic sweep (30s). Drives eviction, emits health snapshots. |
| **PseudonymRotator** | Rotates advertisement pseudonym every 15 minutes (HMAC-based). |
| **TrustStore** | Pinned key database. TOFU + Strict/Prompt modes. |
| **DiagnosticSink** | Event aggregator. Lazy payload evaluation, SharedFlow emission. |
| **WireCodec** | Pure-Kotlin FlatBuffers encode/decode for all 12 message types. |
| **InboundValidator** | Structural frame validation before decode. Rejects malformed data. |
| **DedupSet** | TTL + LRU deduplication for broadcast flood-fill. |
| **SecureStorage** | Platform key persistence (Android DataStore, iOS Keychain). |

---

## Data Flow: Sending a Unicast Message

```
App calls meshLink.send(peerId, data)
  │
  ▼
MeshEngine.send()
  │
  ├─ Route lookup: RouteCoordinator.nextHop(destination)
  │    └─ Returns next-hop peer ID + metric
  │
  ├─ E2E seal: NoiseK.encrypt(recipientStaticKey, data)
  │    └─ Returns sealed ciphertext
  │
  ├─ Size check: if sealed.size > MTU
  │    ├─ YES: TransferEngine.startTransfer(sealed, route)
  │    │         └─ Chunks message, sends via DeliveryPipeline
  │    └─ NO:  DeliveryPipeline.sendDirect(RoutedMessage, nextHop)
  │
  ▼
BleTransport.sendFrame(nextHop, frameBytes)
  │
  ▼
Physical BLE (L2CAP or GATT)
```

## Data Flow: Receiving a Frame

```
BleTransport.incomingData emits IncomingFrame
  │
  ▼
InboundValidator.validate(frame)
  │ REJECT → emit MALFORMED_DATA diagnostic, drop
  │
  ▼
Noise transport decrypt (CipherState.decryptWithAd)
  │ FAIL → emit DECRYPTION_FAILED diagnostic, drop
  │
  ▼
WireCodec.decode(decryptedPayload)
  │
  ▼
DeliveryPipeline.dispatch(message, source)
  │
  ├─ Hello/Update → RouteCoordinator (routing table update)
  ├─ Chunk/ChunkAck/Nack → TransferEngine (transfer session)
  ├─ RoutedMessage (destination == self) → local delivery → App
  ├─ RoutedMessage (destination != self) → relay via next hop
  ├─ Broadcast → DedupSet check → local delivery + flood to neighbors
  ├─ Handshake → NoiseHandshakeManager (XX state machine)
  └─ DeliveryAck → TransferEngine (confirm delivery)
```

## Data Flow: Relay (Cut-Through)

```
IncomingFrame (chunk 0 of transfer, destination ≠ self)
  │
  ▼
DeliveryPipeline: "I'm a relay node"
  │
  ├─ Parse routing header from chunk 0
  ├─ Look up next hop in RouteCoordinator
  ├─ Create CutThroughBuffer for this transferId
  │
  ▼
For each chunk arriving from sender:
  ├─ Decrypt with inbound transport key
  ├─ Re-encrypt with outbound transport key (to next hop)
  ├─ Forward immediately (don't wait for all chunks)
  └─ Buffer for local retransmission if next-hop NACKs
```

---

## Dependency Graph (simplified)

```
MeshLink
  └─ MeshEngine
       ├─ BleTransport (platform)
       ├─ NoiseHandshakeManager
       │    └─ CryptoProvider (platform)
       ├─ RouteCoordinator
       │    └─ RoutingEngine
       ├─ DeliveryPipeline
       │    └─ CutThroughBuffer
       ├─ TransferEngine
       ├─ PowerManager
       │    └─ TieredShedder
       ├─ PresenceTracker
       ├─ MeshStateManager
       ├─ PseudonymRotator
       ├─ TrustStore
       ├─ DedupSet
       ├─ DiagnosticSink
       └─ SecureStorage (platform)
```

All subsystems receive dependencies via constructor injection. No service locator, no DI framework.
