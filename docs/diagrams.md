# MeshLink — Visual Diagrams

Reference diagrams for the MeshLink BLE mesh networking library. All diagrams are authored in Mermaid and correspond to specifications in [design.md](./design.md).

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

Byte layout of the E2E encrypted payload inside a `routed_message` (0x05).

```mermaid
---
title: "Noise K Sealed Payload (with appId)"
---
packet
    0-63: "Replay Counter (uint64 LE)"
    64-71: "Flags (bit 0 = 1: appId present)"
    72-199: "App ID Hash (BLAKE2b-128, 16 bytes)"
    200-263: "Message Data (N bytes, plaintext)"
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
title: "Broadcast Envelope (fixed header + variable payload)"
---
packet
    0-127: "Message ID (UUID, 16 bytes)"
    128-383: "Sender Ed25519 Public Key (32 bytes)"
    384-391: "Remaining Hop Count (1 byte)"
    392-423: "Payload Length (uint32 LE, 4 bytes)"
    424-431: "Payload (N bytes) ..."
```

> **Note:** After the payload, a 64-byte Ed25519 signature covers bytes \[0, 53+N). Signature starts at byte offset 53+N. Total envelope = 117+N bytes.

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
| 0x00 | broadcast |
| 0x01 | handshake |
| 0x02 | gossip |
| 0x03 | chunk |
| 0x04 | chunk_ack |
| 0x05 | routed_message |
| 0x06 | delivery_ack |
| 0x07 | resume_request |
| 0x08–0xFF | reserved |

---

## 3. Architecture Overview

The actor-based internal architecture with transport, security, and routing layers.

```mermaid
flowchart TB
    subgraph App["Consuming App"]
        API["MeshLink Public API<br/>(send, broadcast, meshHealth)"]
    end

    subgraph MeshLinkEngine["MeshLinkEngine (Actor Supervisor)"]
        direction TB

        subgraph Actors["Actor DAG"]
            TA["TransferActor<br/>Chunking, SACK, L2CAP I/O"]
            BA["BufferActor<br/>Store-and-Forward, Dedup, TTL"]
            RA["RouterActor<br/>Enhanced DSDV, Route Selection"]
            PA["PresenceActor<br/>Peer Discovery, Timeout Sweep"]
            CA["ConnectionActor<br/>BLE Lifecycle, Tie-Breaking"]
            GA["GossipActor<br/>Peer Announcements, Routing Updates"]
            CRA["CryptoActor<br/>Noise XX/K, Replay Counters"]
        end

        subgraph Security["Security Layer"]
            NK["Noise K (E2E)<br/>0-RTT Sender Auth"]
            NXX["Noise XX (Hop-by-Hop)<br/>Mutual Auth + Key Exchange"]
            RC["Replay Counter<br/>64-entry Sliding Window"]
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

    API --> TA
    API --> BA
    TA --> BA
    TA --> NXX
    BA --> RA
    RA --> GA
    PA --> CA
    GA --> CA
    CRA --> TA
    CRA --> CA

    NXX --> L2CAP
    NXX --> GATT
    NK -.->|"E2E wraps payload"| CRA
    RC -.->|"per-sender window"| NK

    L2CAP --> CONN
    GATT --> CONN
    PA --> SCAN
    CA --> ADV
    CA --> CONN

    style App fill:#e8f5e9,stroke:#388e3c
    style Security fill:#fff3e0,stroke:#f57c00
    style Transport fill:#e3f2fd,stroke:#1976d2
    style BLE fill:#fce4ec,stroke:#c62828
```

---

## 4. Implementation Phase Dependencies

Gantt chart showing the 7 implementation phases and their dependency relationships.

```mermaid
gantt
    title MeshLink Implementation Phases
    dateFormat X
    axisFormat %s

    section Foundation
        Phase 0 - Protocol Spec & Test Infra   :done, p0, 0, 1

    section Transport
        Phase 1 - BLE Transport (GATT + L2CAP) :active, p1, 1, 2

    section Crypto
        Phase 2 - Encryption & Key Storage     :p2, 2, 3

    section Mesh & Power (Parallel)
        Phase 3 - Mesh Routing & Gossip        :p3, 3, 5
        Phase 5 - Power Mgmt & Hardening       :p5, 3, 5

    section API
        Phase 4 - Public API & Messaging       :p4, 5, 6

    section Release
        Phase 6 - Integration Testing & Docs   :p6, 6, 7
```

### Phase Dependency Graph

```mermaid
flowchart LR
    P0["Phase 0<br/>Foundation"]
    P1["Phase 1<br/>BLE Transport"]
    P2["Phase 2<br/>Crypto"]
    P3["Phase 3<br/>Mesh Routing"]
    P4["Phase 4<br/>Public API"]
    P5["Phase 5<br/>Power & Hardening"]
    P6["Phase 6<br/>Testing & Docs"]

    P0 --> P1
    P1 --> P2
    P2 --> P3
    P2 --> P5
    P3 --> P4
    P5 --> P4
    P4 --> P6

    P5 -. "P5.3 uses route costs<br/>from P3.4 (stub: 1.0/hop)" .-> P3

    style P0 fill:#c8e6c9,stroke:#388e3c
    style P1 fill:#bbdefb,stroke:#1976d2
    style P2 fill:#fff9c4,stroke:#f9a825
    style P3 fill:#ffccbc,stroke:#e64a19
    style P4 fill:#d1c4e9,stroke:#7b1fa2
    style P5 fill:#ffccbc,stroke:#e64a19
    style P6 fill:#b2dfdb,stroke:#00796b
```

---

## 5. Multi-Hop Routed Message Flow

Sequence diagram showing a direct message from Sender → Relay → Recipient with delivery ACK.

```mermaid
sequenceDiagram
    actor Sender as Sender App
    participant SLib as Sender MeshLink
    participant Relay as Relay Node
    participant RLib as Recipient MeshLink
    actor Recipient as Recipient App

    Sender->>SLib: send(recipientPubKey, payload)
    activate SLib

    Note over SLib: Noise K encrypt (E2E)<br/>Wrap in routed_message (0x05)

    SLib->>Relay: Noise XX encrypted chunks (0x03)
    activate Relay

    Note over Relay: Decrypt hop-by-hop (Noise XX)<br/>Check visited list (loop prevention)<br/>Decrement TTL, buffer copy

    Relay->>RLib: Re-encrypt + forward chunks (0x03)
    deactivate Relay
    activate RLib

    RLib-->>Relay: chunk_ack (0x04) per SACK interval

    Note over RLib: Reassemble chunks<br/>Noise K decrypt (E2E)<br/>Dedup check (message ID)<br/>appId filter check

    RLib->>Recipient: onMessageReceived(senderId, payload)

    Note over RLib: Sign delivery ACK (Ed25519)

    RLib-->>SLib: delivery_ack (0x06) reverse-path
    deactivate RLib

    SLib->>Sender: onDeliveryConfirmed(messageId)
    deactivate SLib

    Note over Relay: Evict buffer copy<br/>(delivery ACK or TTL expiry)
```

---

## 6. Noise XX Handshake

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

## 7. Power Mode Transitions

The 3-tier automatic power management system with battery-driven transitions.

```mermaid
---
title: Power Mode State Machine
---
stateDiagram-v2
    [*] --> Balanced : Default on start()

    state "Performance\n>80% or charging\nScan 80–100% · Ads 250ms\nGossip 5s · Conn 4/5" as Perf
    state "Balanced\n30–80%\nScan 40–60% · Ads 500ms\nGossip 15s · Conn 3/4" as Bal
    state "PowerSaver\n<30%\nScan 10–25% · Ads 1–2s\nGossip 30–60s · Conn 2/2" as PS

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

## 8. GATT Chunking & SACK Flow

The chunk transfer sequence with selective acknowledgement, relay buffering, and resume-on-disconnect.

```mermaid
sequenceDiagram
    participant S as Sender
    participant R as Relay
    participant D as Recipient

    Note over S,D: 100KB message → ~244 chunks at MTU 244

    S->>R: chunk 0x03 (seq=0, totalLen=100KB)
    R->>D: Re-encrypt + forward chunk 0
    Note right of R: Buffer copy locally

    S->>R: chunk 0x03 (seq=1)
    R->>D: Forward chunk 1

    S->>R: chunk 0x03 (seq=2..7)
    R->>D: Forward chunks 2–7

    D-->>R: chunk_ack 0x04 (base=0, bitmask=0xFF)
    R-->>S: Forward SACK

    Note over S,D: SACK every 8 chunks (AIMD: min 2, max 16)

    loop Chunks 8..N
        S->>R: chunk 0x03 (seq=8..N)
        R->>D: Forward chunks
    end

    Note over R,D: Link drops mid-transfer

    R-xD: chunk lost — L2CAP disconnects

    Note over R: 30s chunk inactivity timeout
    R->>D: New Noise XX handshake
    D->>R: resume_request 0x07 (messageId, bytesReceived)
    R->>D: Retransmit from byte offset (from local buffer)

    D-->>R: Final SACK (all chunks received)
    R-->>S: Forward final SACK
    Note over R: Evict buffer copies

    S->>S: Transfer complete
```

---

## 9. Actor DAG & Message Flow

The 7-actor supervision hierarchy with strict downstream-only message flow.

```mermaid
flowchart TD
    subgraph Tier1["Tier 1 — Strict (crypto: 2 crashes/60s)"]
        CRA["CryptoActor\n📦 64 mailbox\nNoise XX/K, Replay Counters"]
    end

    subgraph Tier3["Tier 3 — Lenient (5 crashes/60s)"]
        CA["ConnectionActor\n📦 128 mailbox\nBLE Lifecycle, L2CAP Setup"]
        TA["TransferActor\n📦 64 mailbox\nChunking, SACK, L2CAP I/O"]
    end

    subgraph Tier2["Tier 2 — Standard (3 crashes/60s)"]
        RA["RouterActor\n📦 32 mailbox\nDSDV, Route Selection"]
        BA["BufferActor\n📦 32 mailbox\nStore-Forward, Dedup, TTL"]
        PA["PresenceActor\n📦 16 mailbox\nPeer Discovery, Sweep"]
        GA["GossipActor\n📦 16 mailbox\nDifferential Exchange"]
    end

    BLE["BLE Transport"]

    BLE -->|BleEvent| CA
    CA -->|"Downstream ①"| CRA
    CRA -->|"Downstream ②"| TA
    TA -->|"Downstream ③"| RA
    RA -->|"Downstream ④"| BA
    PA -->|ScanResults| RA
    GA -->|RouteUpdates| RA
    BA -.->|"OutboundFrame\n(shared channel)"| CA

    CRA -.->|CompletableDeferred| TA
    TA -.->|CompletableDeferred| RA

    style CA fill:#ffcccc,stroke:#cc0000
    style TA fill:#ffcccc,stroke:#cc0000
    style CRA fill:#ffffcc,stroke:#cc9900
    style RA fill:#cce5ff,stroke:#0066cc
    style BA fill:#cce5ff,stroke:#0066cc
    style PA fill:#cce5ff,stroke:#0066cc
    style GA fill:#cce5ff,stroke:#0066cc
```

> **DAG invariant:** Downstream actors NEVER send to upstream actors. Reverse communication uses `CompletableDeferred` response channels only. Enforced by code review + compile-time lint.

---

## 10. Gossip Protocol Exchange

Differential gossip exchange between neighbors with split horizon and triggered updates.

```mermaid
sequenceDiagram
    participant A as Node A
    participant B as Neighbor B

    Note over A: Gossip timer fires (5–60s per power mode)

    alt First connection to B
        A->>B: Full routing table (all routes + seq + costs)
        B->>A: Full routing table
        Note over A,B: Both sides store per-neighbor last-sent seq#
    else Subsequent cycle
        alt Routing table changed since last exchange
            Note over A: Build differential (entries with seq > last sent)
            Note over A: Apply split horizon: exclude routes learned from B
            alt Cost change > 30% (triggered update)
                A->>B: Immediate differential (gossip 0x02)
                Note right of A: Rate-limited: 1 triggered update per interval
            else Normal change
                A->>B: Differential update (gossip 0x02)
            end
            Note right of B: Verify, update routing table
        else No changes
            A->>B: Keepalive (6 bytes: entry_count=0, highest_seq)
        end
    end

    Note over A,B: Poison reverse on withdrawal
    B->>A: cost=∞ for withdrawn route (fast invalidation)
```

---

## 11. TOFI Trust Model

Trust-on-First-Discover key pinning with strict/softRepin modes and signed rotation handling.

```mermaid
flowchart TD
    Discover["Peer key discovered"] --> Origin{"Source?"}

    Origin -->|"Gossip"| Routing["Routing candidate only\n(NOT trusted for TOFI)"]
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

## 12. Key Rotation Sequence

Identity key rotation with gossip announcement, grace period, and old-key teardown.

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
        Note over Dev,N2: 4. Gossip rotation announcement (132 bytes)
        Dev->>N1: gossip 0x02: oldKey(32) | newKey(32) | seq(4) | sig(64)
        Dev->>N2: gossip 0x02: rotation announcement
        N1-->>Dev: Write-with-response ACK
        N2-->>Dev: Write-with-response ACK
        Note over N1: Verify sig against pinned old key
        Note over N2: Verify sig against pinned old key
    end

    Dev-->>App: Result.Success(newPublicKey)
    deactivate Dev

    rect rgb(255, 243, 224)
        Note over Dev,N2: Grace period: 5× gossip interval\n(25s Performance / 75s Balanced / 150–300s PowerSaver)
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
