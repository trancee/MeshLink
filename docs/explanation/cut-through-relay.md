# Cut-Through vs Store-and-Forward Relay

## The problem

When Node A sends a 10KB message to Node C through relay Node B:

**Store-and-forward** (the naive approach):
1. A sends all chunks to B
2. B reassembles the entire message
3. B re-chunks and sends all chunks to C

Total latency: 2× the single-hop transfer time. Every relay hop doubles latency.

**Cut-through forwarding** (what MeshLink does):
1. A sends chunk 0 to B
2. B reads the routing header from chunk 0, determines next hop is C
3. B immediately begins forwarding chunk 0 to C
4. A sends chunk 1 to B → B forwards chunk 1 to C (pipelined)
5. ...and so on

Total latency: ~1× single-hop time + per-chunk processing overhead. Relay hops add minimal latency.

## How it works

### CutThroughBuffer

When `DeliveryPipeline` receives chunk 0 of a multi-chunk message at a relay node:

1. Parse the FlatBuffer routing header (destination key hash, next hop)
2. Look up the next hop in the routing table
3. If route exists: create a `CutThroughBuffer` and begin forwarding immediately
4. If route doesn't exist: fall back to store-and-forward (buffer all chunks, then route when complete)

### Chunk re-encryption

Each hop has its own Noise transport session. The relay:
1. Decrypts the inbound chunk with its transport key from the previous hop
2. Re-encrypts with its transport key to the next hop
3. Forwards without touching the inner Noise K payload (which is E2E sealed to the final destination)

### Local relay buffer

Even with cut-through, B keeps a copy of forwarded chunks in its relay buffer. This serves retransmission: if C NACKs a chunk, B can resend it without cascading the NACK back to A.

### Fallback conditions

Cut-through is abandoned (falls back to full reassembly) when:
- Routing header parse fails on chunk 0
- No route exists for the destination at the time chunk 0 arrives
- Next-hop transport session is not established

## Why this matters for BLE mesh

BLE has high per-hop latency (~10–50ms per chunk, depending on connection interval and MTU). In a 3-hop path:

- Store-and-forward: 3 × (10KB / MTU) × per-chunk-latency = seconds
- Cut-through: (10KB / MTU) × per-chunk-latency + 2 × per-chunk-processing = sub-second for the pipeline to fill

For the "Tinder without Internet" use case (broadcasting 10KB profiles), the difference between 3 seconds and 1 second across a 3-hop mesh is noticeable.

## How to verify in tests

The 3-node integration test proves cut-through by asserting structural interleaving:

```kotlin
// B starts sending chunks to C before receiving all chunks from A
val chunksSentByBtoC = nodeB.transport.sentFrames.filter { toC && isChunk }
val chunksReceivedByBfromA = nodeB.transport.receivedFrames.filter { fromA && isChunk }

// At the point B has sent its first chunk to C,
// it has NOT yet received all chunks from A
assertTrue(chunksReceivedByBfromA.size > chunksSentByBtoC.size)
assertTrue(chunksSentByBtoC.isNotEmpty())
```

## Tradeoff

Cut-through adds complexity:
- CutThroughBuffer state management per in-flight relay
- Memory: relay buffer holds chunks until transfer completes
- Failure handling: what if next-hop disconnects mid-relay?

But the latency improvement is critical for usable multi-hop messaging over BLE.
