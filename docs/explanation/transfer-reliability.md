# Transfer Reliability: SACK and Retransmission

## The problem

BLE connections are lossy. Radio interference, peer movement, and OS scheduling all cause frame drops. When MeshLink sends a 50KB file across a mesh, individual chunks will be lost. The system must detect and recover from loss without retransmitting the entire payload.

## Why not TCP?

TCP operates over IP. BLE mesh has no IP layer — frames travel over L2CAP byte streams or GATT characteristics directly. MeshLink implements its own reliable transfer on top of BLE's unreliable delivery.

But we borrow TCP's best ideas: selective acknowledgment (RFC 2018) and adaptive retransmission.

## The transfer model

A large message is split into fixed-size chunks:

```
Message (50KB) → [Chunk 0] [Chunk 1] [Chunk 2] ... [Chunk N]
```

Each chunk carries:
- `transferId` — unique identifier for this transfer session
- `sequenceNumber` — 0-indexed position in the chunk sequence
- `totalChunks` — total number of chunks (so receiver knows when done)
- `data` — the chunk payload bytes

## Selective Acknowledgment (SACK)

After receiving chunks, the receiver sends a `ChunkAck` message containing a SACK bitmap:

```
ChunkAck {
    transferId: UUID
    cumulativeAck: UInt    // everything below this is received
    sackBitmap: ByteArray  // bitmap of received chunks above cumulativeAck
}
```

### How the bitmap works

- Bit 0 = chunk at `cumulativeAck + 1`
- Bit 1 = chunk at `cumulativeAck + 2`
- ...
- Bit set = received, bit clear = missing

Example: chunks 0, 1, 2, 4, 6 received (3 and 5 missing):
```
cumulativeAck = 2  (everything through 2 is received)
sackBitmap = 0b00000101  (bit 1 = chunk 4 received, bit 3 = chunk 6 received)
```

The sender retransmits only chunks 3 and 5 — not the entire transfer.

## Why SACK over Go-Back-N

Go-Back-N (what basic TCP does without SACK) retransmits everything after the last acknowledged chunk. On a BLE link with 50ms round-trip:

- Go-Back-N: lose chunk 3 of 100 → retransmit chunks 3–99 (97 redundant transmissions)
- SACK: lose chunk 3 of 100 → retransmit only chunk 3

For bandwidth-constrained BLE links, SACK reduces radio time by orders of magnitude on lossy connections.

## NACK messages

The receiver sends an explicit NACK when it detects a problem that requires sender action:

```
Nack {
    transferId: UUID
    reason: NackReason  // BUFFER_FULL, TIMEOUT, INVALID_SEQUENCE
}
```

### NACK reasons

| Reason | Meaning | Sender action |
|--------|---------|---------------|
| `BUFFER_FULL` | Receiver's reassembly buffer is at capacity | Back off, retry with exponential delay |
| `TIMEOUT` | Receiver hasn't received any chunk in the inactivity window | Retransmit from last SACK state |
| `INVALID_SEQUENCE` | Sequence number exceeds totalChunks or other structural error | Abort transfer |

### Exponential backoff on NACK

```
delay = nackBaseBackoffMillis * 2^(attemptCount - 1)
```

Capped at `maxBackoffMillis`. Prevents overwhelming a buffer-pressured peer.

## Resume after disconnection

If a BLE link drops mid-transfer:

1. Sender detects disconnection
2. Transfer session is paused (not abandoned) — state preserved
3. On reconnection, sender sends `ResumeRequest`:
   ```
   ResumeRequest {
       transferId: UUID
       lastReceivedAck: UInt  // last SACK the sender received
   }
   ```
4. Receiver responds with its current SACK state
5. Sender resumes from where it left off — no re-sending received chunks

## Inactivity timeout

Each transfer session has a timeout: `chunkInactivityTimeout` (default 30s), scaled by hop count:

```
effectiveTimeout = chunkInactivityTimeout * min(hopCount, 4)
```

Multi-hop transfers get more time because each hop adds latency. A 3-hop transfer gets 90s before timeout.

If no chunk or ACK is received within the timeout:
- Sender: retransmits unacknowledged chunks
- Receiver: sends NACK(TIMEOUT)
- After 3 consecutive timeouts: transfer abandoned, `DELIVERY_TIMEOUT` diagnostic emitted

## Chunk sizing

`ChunkSizePolicy` selects chunk size based on transport:

| Transport | Chunk Size | Rationale |
|-----------|------------|-----------|
| L2CAP | Up to 4KB | L2CAP MTU is negotiable, credit-based flow control handles large frames |
| GATT | 244 bytes | Fits in a single ATT Write Without Response (BLE 5.x 251-byte ATT_MTU - 7 header) |

Smaller chunks on GATT mean more round-trips but work within the characteristic write size limit.

## Interaction with cut-through relay

At relay nodes, chunks are forwarded immediately (cut-through). The relay buffers forwarded chunks locally so it can handle NACKs from the next hop without cascading them back to the original sender. This means:

- NACK from C to B (relay): B retransmits from its local buffer
- NACK only cascades to A if B has already evicted the chunk from its buffer (capacity limit reached)
