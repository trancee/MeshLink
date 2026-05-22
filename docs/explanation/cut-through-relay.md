# Cut-through vs store-and-forward relay

## The problem

When node A sends a large message to node C through relay node B, the relay can
handle the transfer in two very different ways.

**Store-and-forward** waits for the full payload:

1. A sends every chunk to B
2. B reassembles the full message
3. B re-chunks and forwards it to C

That makes every hop pay the full transfer cost.

**Cut-through forwarding** pipelines the transfer:

1. A sends the first chunk to B
2. B reads the routing header, resolves the next hop, and starts forwarding
3. later chunks continue to flow through B while A is still sending

That keeps relay latency much closer to one single-hop transfer plus per-chunk
processing overhead.

## How MeshLink does it

When a relay receives chunk 0 of a multi-chunk message, it tries to do four
things immediately:

1. parse the routing header
2. resolve the next hop
3. confirm the next-hop transport session is usable
4. start forwarding without waiting for full reassembly

If those conditions hold, MeshLink creates a cut-through relay buffer and
pipelines the transfer.

## Why the relay still buffers data

Cut-through does not mean "no buffer." The relay still keeps forwarded chunks
long enough to handle local retransmission.

That matters when the next hop asks for a resend. B should be able to retry the
chunk locally instead of cascading the retry all the way back to A.

## Re-encryption stays hop-local

Each hop has its own Noise transport session. The relay therefore:

1. decrypts the inbound chunk for the current hop
2. re-encrypts it for the next hop
3. forwards it without touching the inner end-to-end payload seal

The relay is helping transport the message, not terminating the application
payload.

## When MeshLink falls back

MeshLink falls back to full reassembly when cut-through cannot be started
safely. Typical reasons are:

- the routing header on chunk 0 cannot be parsed
- no route exists when the first chunk arrives
- the next-hop transport session is not ready

## Why this matters on BLE

BLE is especially sensitive to per-hop transfer cost. If every hop waits for the
full payload before forwarding, multi-hop delivery gets slow quickly.

Cut-through keeps large relayed transfers usable because the pipeline fills once
and then keeps moving.

## How to think about the trade-off

Cut-through relay adds complexity:

- in-flight relay state per transfer
- relay-side retransmission buffers
- more edge cases when the next hop disappears mid-transfer

MeshLink accepts that complexity because the latency improvement is material for
large multi-hop transfers over BLE.
