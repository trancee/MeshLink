# MeshLink — Visual Diagrams

Reference diagrams for the MeshLink BLE mesh networking library. All diagrams
use [Mermaid](https://mermaid.js.org/) and render natively on GitHub. If
diagrams don't render in your viewer, paste them into the
[Mermaid Live Editor](https://mermaid.live).

## Contents

1. [Library State Machine](#1-library-state-machine) — 6-state lifecycle
2. [Wire Format — Routed Message](#2-wire-format--routed-message-noise-k-sealed-payload) — Noise K sealed payload byte layout
3. [Architecture Overview](#3-architecture-overview) — Engine/coordinator layers
4. [Multi-Hop Routed Message Flow](#4-multi-hop-routed-message-flow) — Sender → Relay → Recipient sequence
5. [Noise XX Handshake](#5-noise-xx-handshake) — 3-message mutual authentication
6. [Power Mode Transitions](#6-power-mode-transitions) — Battery-driven mode state machine
7. [GATT Chunking & SACK Flow](#7-gatt-chunking--sack-flow) — Selective ACK and resume-on-disconnect
8. [Engine & Coordinator Data Flow](#8-engine--coordinator-data-flow) — Sealed result types, unidirectional flow
9. [AODV Route Discovery](#9-aodv-route-discovery) — On-demand RREQ flood and RREP unicast
10. [TOFU Trust Model](#10-tofu-trust-model) — Key pinning with strict/softRepin modes
11. [Key Rotation Sequence](#11-key-rotation-sequence) — Broadcast announcement, grace period, teardown

---

## 1. Library State Machine

The 6-state lifecycle governing the `MeshLink` instance. `stopped` is restartable; `terminal` is permanent.

```mermaid
---
title: MeshLink Library State Machine
---
stateDiagram-v2
    [*] --> uninitialized : MeshLink(context) { config }

    uninitialized --> running : start() success
    uninitialized --> recoverable : start() retryable failure
    uninitialized --> terminal : start() non-retryable failure

    running --> paused : pause()
    paused --> running : resume()

    running --> stopped : stop()
    paused --> stopped : stop()
    recoverable --> stopped : stop()

    recoverable --> running : start() retry success
    recoverable --> terminal : start() retry non-retryable

    stopped --> running : start() re-init success
    stopped --> recoverable : start() re-init retryable failure
    stopped --> terminal : start() re-init non-retryable failure

    terminal --> [*] : Create new instance

    note right of uninitialized
        Construction is side-effect-free.
        All init happens in start().
    end note

    note right of stopped
        Restartable. start() re-initializes
        with the same config.
    end note

    note right of terminal
        Permanent. start() throws
        IllegalStateException.
    end note

    note left of recoverable
        Retryable failure.
        start() re-attempts init.
    end note
```

---

## 2. Wire Format — Routed Message (Noise K Sealed Payload)

Byte layout of the E2E encrypted payload inside a `routed_message` (0x0A).

```mermaid
---
title: "Noise K Sealed Payload (with appId)"
---
packet
    0-63: "Replay Counter (uint64 LE)"
    64-71: "Flags (bit 0 = 1: appId present)"
    72-135: "App ID Hash (SHA-256-64, 8 bytes)"
    136-199: "Message Data (N bytes, plaintext)"
```

**Variant without appId** (flags bit 0 = 0):

```mermaid
---
title: "Noise K Sealed Payload (no appId)"
---
packet
    0-63: "Replay Counter (uint64 LE)"
    64-71: "Flags (bit 0 = 0: no appId)"
    72-135: "Message Data (N bytes, plaintext)"
```

### Broadcast Envelope Wire Format

```mermaid
---
title: "Broadcast Envelope (header + optional signature + payload)"
---
packet
    0-95: "Message ID (structured, 12 bytes)"
    96-159: "Origin Peer ID (8 bytes)"
    160-167: "Remaining Hop Count (1 byte)"
    168-231: "App ID Hash (8 bytes)"
    232-239: "Flags (1 byte)"
    240-751: "Signature (64B) + Signer Key (32B) [if flags bit 0]"
    752-815: "Payload (remaining bytes)"
```

> **Note:** When signed, the signature covers `messageId + origin + appIdHash + payload`. The signature block (64-byte Ed25519 signature + 32-byte signer public key) is present only when `flags & 0x01`.

### GATT Message Type Prefix

```mermaid
---
title: "MeshLink Message Types (1-byte prefix)"
---
packet
    0-7: "Type"
```

| Code | Type |
|------|------|
| 0x00 | handshake |
| 0x01 | keepalive |
| 0x02 | rotation |
| 0x03 | route_request (RREQ) |
| 0x04 | route_reply (RREP) |
| 0x05 | chunk |
| 0x06 | chunk_ack |
| 0x07 | nack |
| 0x08 | resume_request |
| 0x09 | broadcast |
| 0x0A | routed_message |
| 0x0B | delivery_ack |
| 0x0C–0xFF | reserved |

---

## 3. Architecture Overview

The engine/coordinator architecture with transport, security, and routing layers.

```mermaid
flowchart TB
    subgraph App["Consuming App"]
        API["MeshLink Public API<br/>(send, broadcast, meshHealth)"]
    end

    subgraph Core["MeshLink Core"]
        direction TB

        subgraph Engines["Stateful Engines (sealed result types)"]
            SE["SecurityEngine<br/>Noise XX/K, Trust, Replay"]
            RE["RoutingEngine<br/>AODV, Cost, Dedup"]
            TE["TransferEngine<br/>Chunking, SACK, AIMD"]
            DP["DeliveryPipeline<br/>Confirmation, Timeout"]
        end

        subgraph Coordinators["Orchestrators"]
            PC["PowerCoordinator<br/>Mode transitions, Hysteresis"]
            GC["RouteCoordinator<br/>Keepalive"]
            PCC["PeerConnectionCoordinator<br/>Discovery, Handshake init"]
        end

        subgraph Policies["Policy Chains"]
            SPC["SendPolicyChain<br/>Rate limit, Circuit breaker"]
            BPC["BroadcastPolicyChain<br/>Buffer, Rate limit"]
            MD["MessageDispatcher<br/>Inbound decode + dispatch"]
        end

        subgraph Security["Security Layer"]
            NK["Noise K (E2E)<br/>0-RTT Sender Auth"]
            NXX["Noise XX (Hop-by-Hop)<br/>Mutual Auth + Key Exchange"]
            RC["ReplayGuard<br/>64-entry Sliding Window"]
        end

        subgraph Transport["Transport Layer"]
            L2CAP["L2CAP CoC<br/>Credit-based Flow Control"]
            GATT["GATT Fallback<br/>SACK + Chunking"]
        end
    end

    subgraph BLE["BLE Stack (OS)"]
        SCAN["Scanner"]
        ADV["Advertiser"]
        CONN["Connections"]
    end

    API --> SPC & BPC
    SPC --> SE
    SPC --> RE
    SE --> NXX & NK
    TE --> DP
    MD --> TE
    GC --> RE
    PCC --> SE

    NXX --> L2CAP & GATT
    L2CAP & GATT --> CONN
    PC --> SCAN & ADV
    PCC --> CONN

    style App fill:#e8f5e9,stroke:#388e3c
    style Security fill:#fff3e0,stroke:#f57c00
    style Transport fill:#e3f2fd,stroke:#1976d2
    style BLE fill:#fce4ec,stroke:#c62828
```

---

## 4. Multi-Hop Routed Message Flow

Sequence diagram showing a direct message from Sender → Relay → Recipient with delivery ACK.

```mermaid
sequenceDiagram
    actor Sender as Sender App
    participant SLib as Sender MeshLink
    participant Relay as Relay Peer
    participant RLib as Recipient MeshLink
    actor Recipient as Recipient App

    Sender->>SLib: send(recipientPubKey, payload)
    activate SLib

    Note over SLib: Noise K encrypt (E2E)<br/>Wrap in routed_message (0x0A)

    SLib->>Relay: Noise XX encrypted chunks (0x05)
    activate Relay

    Note over Relay: Decrypt hop-by-hop (Noise XX)<br/>Check visited list (loop prevention)<br/>Decrement TTL, buffer copy

    Relay->>RLib: Re-encrypt + forward chunks (0x05)
    deactivate Relay
    activate RLib

    RLib-->>Relay: chunk_ack (0x06) per SACK interval

    Note over RLib: Reassemble chunks<br/>Noise K decrypt (E2E)<br/>Dedup check (message ID)<br/>appId filter check

    RLib->>Recipient: onMessageReceived(senderId, payload)

    Note over RLib: Sign delivery ACK (Ed25519)

    RLib-->>SLib: delivery_ack (0x0B) reverse-path
    deactivate RLib

    SLib->>Sender: onDeliveryConfirmed(messageId)
    deactivate SLib

    Note over Relay: Evict buffer copy<br/>(delivery ACK or TTL expiry)
```

---

## 5. Noise XX Handshake

The 3-message mutual authentication handshake establishing a hop-by-hop encrypted session.

```mermaid
sequenceDiagram
    participant I as Initiator
    participant R as Responder

    Note over I,R: Phase 1 — Unencrypted

    I->>R: Message 1 (e)<br/>Ephemeral key only (no app payload)

    Note over I,R: Phase 2 — Initiator Authenticated

    R->>I: Message 2 (e, ee, s, es)<br/>Encrypted payload (5B):<br/>version(2) + capability(1) + l2cap_psm(2)

    Note over I,R: Phase 3 — Mutually Authenticated

    I->>R: Message 3 (s, se)<br/>Encrypted payload (5B):<br/>version(2) + capability(1) + l2cap_psm(2)

    Note over I,R: Both peers now know:<br/>Protocol version, L2CAP capability, PSM

    rect rgb(230, 255, 230)
        Note over I,R: Secure Channel Ready
        I->>R: Encrypted application data
        R->>I: Encrypted application data
    end

    Note right of I: Failure handling:<br/>Msg 1 invalid → R disconnects silently<br/>Msg 2 invalid → I discards state + disconnects<br/>Msg 3 invalid → R discards state + disconnects<br/>Timeout (8s) → discard all Noise state<br/>No immediate retry — wait for BLE re-discovery
```

---

## 6. Power Mode Transitions

The 3-tier automatic power management system with battery-driven transitions.

```mermaid
---
title: Power Mode State Machine
---
stateDiagram-v2
    [*] --> Balanced : Default on start()

    state "Performance\n>80% or charging\nScan 80% · Ads 250ms\nKeepalive 5s · Conn 8" as Perf
    state "Balanced\n30–80%\nScan 50% · Ads 500ms\nKeepalive 15s · Conn 4" as Bal
    state "PowerSaver\n<30%\nScan ~17% · Ads 1s\nKeepalive 30s · Conn 1" as PS

    Bal --> Perf : battery ≥ 80% (immediate)
    Perf --> Bal : battery < 80% for 30s
    Bal --> PS : battery < 30% for 30s
    PS --> Bal : battery ≥ 30% (immediate)

    PS --> Perf : Charging detected (override)
    Bal --> Perf : Charging detected (override)

    note right of Perf
        Upward: immediate
        Downward: 30s hysteresis
    end note
```

---

## 7. GATT Chunking & SACK Flow

The chunk transfer sequence with selective acknowledgement, relay buffering, and resume-on-disconnect.

```mermaid
sequenceDiagram
    participant S as Sender
    participant R as Relay
    participant D as Recipient

    Note over S,D: 100KB message → ~244 chunks at MTU 244

    S->>R: chunk 0x05 (seq=0, totalLen=100KB)
    R->>D: Re-encrypt + forward chunk 0
    Note right of R: Buffer copy locally

    S->>R: chunk 0x05 (seq=1)
    R->>D: Forward chunk 1

    S->>R: chunk 0x05 (seq=2..7)
    R->>D: Forward chunks 2–7

    D-->>R: chunk_ack 0x06 (base=0, bitmask=0xFF)
    R-->>S: Forward SACK

    Note over S,D: SACK every 8 chunks (AIMD: min 2, max 16)

    loop Chunks 8..N
        S->>R: chunk 0x05 (seq=8..N)
        R->>D: Forward chunks
    end

    Note over R,D: Link drops mid-transfer

    R-xD: chunk lost — L2CAP disconnects

    Note over R: 30s chunk inactivity timeout
    R->>D: New Noise XX handshake
    D->>R: resume_request 0x08 (messageId, bytesReceived)
    R->>D: Retransmit from byte offset (from local buffer)

    D-->>R: Final SACK (all chunks received)
    R-->>S: Forward final SACK
    Note over R: Evict buffer copies

    S->>S: Transfer complete
```

---

## 8. Engine & Coordinator Data Flow

The engine/coordinator architecture with sealed result types and unidirectional data flow.

```mermaid
flowchart TD
    subgraph Facades["Stateful Engines (sealed result types)"]
        SE["SecurityEngine<br/>🔒 Noise XX/K, Trust, Replay"]
        RE["RoutingEngine<br/>🗺️ AODV, Cost, Dedup"]
        TE["TransferEngine<br/>📦 Chunking, SACK, AIMD"]
        DP["DeliveryPipeline<br/>✅ Confirmation, Timeout"]
    end

    subgraph Orchestrators["Coordinators"]
        PC["PowerCoordinator<br/>⚡ Mode transitions"]
        GC["RouteCoordinator<br/>📡 Keepalive"]
        PCC["PeerConnectionCoordinator<br/>🤝 Discovery, Handshake"]
    end

    subgraph Chains["Policy Chains (pure functions)"]
        SPC["SendPolicyChain<br/>→ SendDecision"]
        BPC["BroadcastPolicyChain<br/>→ BroadcastDecision"]
        MD["MessageDispatcher<br/>→ DispatchSink effects"]
    end

    BLE["BLE Transport"]

    BLE --> MD
    MD -->|"Inbound frames"| TE & SE & RE
    SPC -->|"SendDecision.Direct/Routed"| SE
    SE -->|"Sealed/Handshake bytes"| TE
    TE -->|"TransferUpdate"| DP
    GC -->|"Keepalive"| RE
    PCC -->|"PeerConnectionAction"| SE
    PC -->|"PowerProfile"| BLE

    style Facades fill:#e3f2fd,stroke:#1976d2
    style Orchestrators fill:#fff3e0,stroke:#f57c00
    style Chains fill:#e8f5e9,stroke:#388e3c
```

> **Design principle:** Engines return sealed result types; the caller pattern-matches and dispatches effects. No engine sends messages directly to another engine. `MeshLink` is the sole wiring layer.

---

## 9. AODV Route Discovery

On-demand route discovery using RREQ flood and RREP unicast. Routes are
established only when a message needs to be sent — no proactive routing
overhead.

```mermaid
sequenceDiagram
    participant S as Source
    participant N1 as Neighbor 1
    participant N2 as Neighbor 2
    participant N3 as Neighbor 3
    participant D as Destination

    Note over S: send(msg, dest=D) — no cached route

    S->>N1: RREQ (broadcast flood)
    S->>N2: RREQ (broadcast flood)

    N1->>N1: Record reverse path → S
    N2->>N2: Record reverse path → S

    N1->>N3: RREQ (rebroadcast)
    N3->>N3: Record reverse path → N1

    N3->>D: RREQ (rebroadcast)
    Note over D: I am the destination

    D->>N3: RREP (unicast back)
    N3->>N3: Install forward route to D
    N3->>N1: RREP (unicast back)
    N1->>N1: Install forward route to D
    N1->>S: RREP (unicast back)

    Note over S: Route installed, pending messages drained

    S->>N1: RoutedMessage(dest=D, payload=msg)
    N1->>N3: Forward
    N3->>D: Forward
```

---

## 10. TOFU Trust Model

Trust-on-First-Discover key pinning with strict/softRepin modes and signed rotation handling.

```mermaid
flowchart TD
    Discover["Peer key discovered"] --> Origin{"Source?"}

    Origin -->|"Route discovery"| Routing["Routing candidate only\n(NOT trusted for TOFU)"]
    Routing --> WaitNoise["Wait for Noise XX handshake"]

    Origin -->|"Noise XX handshake"| Authenticated["Mutually authenticated key"]

    WaitNoise --> Authenticated
    Authenticated --> Pinned{"Previously\npinned?"}

    Pinned -->|"No (first time)"| Pin["Pin key in secure storage"]
    Pin --> Ready["Ready to communicate"]

    Pinned -->|"Yes"| Changed{"Key changed?"}
    Changed -->|"No"| Ready

    Changed -->|"Yes"| Signed{"Signed rotation\nannouncement?"}

    Signed -->|"Yes (old key signature)"| AutoAccept["Auto-accept in both modes"]
    AutoAccept --> Callback1["onKeyChanged fires"]
    Callback1 --> Ready

    Signed -->|"No"| Mode{"trustMode?"}

    Mode -->|"strict (default)"| Reject["REJECT messages\nonKeyChanged fires"]
    Reject --> AppDecides{"App calls\nrepinKey(peer)?"}
    AppDecides -->|"Yes"| Accept["Accept new key"]
    AppDecides -->|"No"| Blocked["Messages rejected"]
    Accept --> Ready

    Mode -->|"softRepin"| SilentRepin["Auto-accept + re-pin\nonKeyChanged fires\n(informational)"]
    SilentRepin --> Ready

    Ready --> DupCheck{"Same key from\n2 BLE MACs?"}
    DupCheck -->|"Yes"| Warning["onSecurityWarning\nDUPLICATE_IDENTITY"]
    DupCheck -->|"No"| Safe["Communicating"]

    style Pin fill:#d4edda,stroke:#28a745
    style Reject fill:#f8d7da,stroke:#dc3545
    style AutoAccept fill:#d4edda,stroke:#28a745
    style SilentRepin fill:#d4edda,stroke:#28a745
    style Warning fill:#fff3cd,stroke:#ffc107
```

---

## 11. Key Rotation Sequence

Identity key rotation with broadcast announcement, grace period, and old-key teardown.

```mermaid
sequenceDiagram
    participant App as Consuming App
    participant Dev as Rotating Device
    participant N1 as Neighbor 1
    participant N2 as Neighbor 2

    App->>Dev: rotateIdentity()
    activate Dev

    Note over Dev: 1. Generate new Ed25519 keypair
    Note over Dev: 2. Store new key in secure storage\n(old key retained alongside)
    Note over Dev: 3. Sign announcement with OLD key

    rect rgb(230, 240, 255)
        Note over Dev,N2: 4. Broadcast rotation announcement (132 bytes)
        Dev->>N1: rotation 0x02: oldKey(32) | newKey(32) | seq(4) | sig(64)
        Dev->>N2: rotation 0x02: rotation announcement
        N1-->>Dev: Write-with-response ACK
        N2-->>Dev: Write-with-response ACK
        Note over N1: Verify sig against pinned old key
        Note over N2: Verify sig against pinned old key
    end

    Dev-->>App: Result.Success(newPublicKey)
    deactivate Dev

    rect rgb(255, 243, 224)
        Note over Dev,N2: Grace period: 5× keepalive interval\n(25s Performance / 75s Balanced / 150–300s PowerSaver)
        Note over Dev: Accept messages encrypted with BOTH keys

        N1->>Dev: Message (old Noise K session)
        Note right of Dev: Accepted — old key still valid

        N2->>Dev: Message (new Noise K session)
        Note right of Dev: Accepted — new key active
    end

    rect rgb(224, 247, 250)
        Note over Dev,N2: 5. Tear down old-key sessions
        Dev->>N1: New Noise XX handshake (new key)
        N1-->>Dev: Handshake complete
        Dev->>N2: New Noise XX handshake (new key)
        N2-->>Dev: Handshake complete
    end

    Note over Dev: 6. Erase old private key from memory\nOnly new key persisted
```
