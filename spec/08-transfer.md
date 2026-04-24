# 08 — Transfer: Chunking, SACK & Flow Control

> **Covers:** §8 (Transfer — Chunking, SACK, Congestion Control)
> **Dependencies:** `01-vision-and-domain-model.md` (Chunk, Transfer, SACK, Byte-Offset Resume), `04-wire-format.md` (Chunk, Chunk ACK formats), `05-security-encryption.md` (Noise K E2E)
> **Key exports:** Chunking pipeline, SACK mechanics, link degradation handling, concurrent transfer scheduling, resume protocol, latency estimates
> **Changes from original:** S9 (RSSI-based send rate → observation-based), A5 (message size guidance)

---

## 1. Chunking Overview

Consuming App calls `send()` → library handles fragmentation transparently.

### Encryption Pipeline Order
1. E2E encrypt payload (Noise K — `05-security-encryption.md`)
2. Chunk the ciphertext into BLE-sized pieces
3. Transmit chunks within hop-by-hop Noise XX session

Chunks are raw byte ranges of E2E ciphertext — **no per-chunk AEAD**. Integrity verified once on full reassembly + decryption.

**Max message size:** ~100KB (configurable via `maxMessageSize`).

**Message size guidance for Consuming Apps:**

| Payload Size | Use Case | Expected Latency (1 hop, GATT) | Multi-Hop Viability |
|-------------|----------|-------------------------------|---------------------|
| ≤1 KB | Text, commands, status | 50–200ms | Excellent (4+ hops) |
| 1–10 KB | Long text, small JSON | 200ms–1s | Good (3–4 hops) |
| 10–50 KB | Small images, documents | 1–5s | Fair (2–3 hops) |
| 50–100 KB | Larger images | 4–10s | Poor (prefer 1–2 hops) |

> **Recommendation:** Avoid >50KB payloads over multi-hop routes. At 4 hops, a 100KB transfer takes 20–60s and risks chunk inactivity timeouts.

**Chunk headers:**
- First chunk (seq=0): includes `totalChunks` field
- Subsequent: omits `totalChunks` (saves 2B per chunk)

At 244-byte MTU, 100KB → ~410 chunks for 1-hop transfer.

## 2. Selective Acknowledgements (SACK)

**GATT-mode only.** Receivers send Chunk ACK (`0x06`) with 64-bit SACK bitmask covering up to 64 chunks beyond `ackSequence`. Enables precise retransmission of only missing chunks.

Single-chunk messages use the same SACK format (no special fast-path — uniform code path).

## 3. Link Degradation Handling (GATT Mode Only)

BLE = point-to-point dedicated radio time slots (not TCP-style congestion). Simple **stop-and-wait with timeout:**

```
ACK timeout:           2 × connection_interval (min 50ms)
Degradation:           3 consecutive ACK timeouts → degraded
Degraded response:     Pause 2 seconds
Probe:                 Send single chunk after pause
Recovery:              Probe ACK received → resume
Persistent failure:    3 failed probes → connection torn down + SEND_FAILED
```

### Observation-Based Sending Rate

Instead of coupling to RSSI routing metrics, the sending rate adapts based on observed ACK success:

1. **Start:** 1 chunk, then wait for ACK (stop-and-wait)
2. **After 3 consecutive ACKs:** increase to 2 chunks before waiting
3. **After 6 more consecutive ACKs:** increase to 4 chunks before waiting
4. **Any ACK timeout:** reset to 1 chunk (stop-and-wait)

Pure observation-based — no coupling between routing cost calculator and transfer engine. On a strong link, the ramp-up to 4-chunk batches completes in ~9 ACK rounds (~200ms at 15ms connection interval).

**L2CAP mode:** No app-level flow control needed — L2CAP credit-based flow control provides automatic backpressure.

## 4. Concurrent Transfers & Interleaving

Multiple transfers in-flight on a single BLE connection.

### Weighted Round-Robin by Priority

| Priority | Chunks per round |
|----------|------------------|
| High (+1) | 3 |
| Normal (0) | 1 |
| Low (-1) | 1, skip every other round |

High-priority gets ~3× bandwidth; fair within same class.

### Concurrent Transfer Limits by Power Mode

| Power Mode | Max Concurrent |
|------------|---------------|
| Performance | 8 |
| Balanced | 4 |
| Power Saver | 1 |

Exceeding limit → NACK with `bufferFull`.

## 5. Resumable Transfers

On disconnect, receiver reports **total bytes received** via `resume_request` (`0x08`). Sender resumes from that byte offset, chunked at the **new connection's** chunk size.

Enables seamless resume across transport mode changes (GATT 244-byte chunks → L2CAP 4096-byte chunks).

**Byte-offset alignment:** On mode change, sender rounds `bytesReceived` **down** to nearest chunk boundary. Receiver discards overlapping bytes.

After N failed resume attempts → full restart (configurable).

## 6. Chunk Inactivity Timeout

No new chunk within timeout → drop incomplete transfer, free buffer.

**Adaptive:** Base 30s for direct (1-hop). Multi-hop scales:
```
timeout = base_timeout × min(hops_traversed, 4)
```
Up to 120s for 4+ hops. `hops_traversed = config.maxHops - hopLimit + 1`.

Timer resets on each chunk — slow but progressing transfers survive. Each relay runs its own independent timeout.

## 7. Transfer Scheduling

`TransferScheduler` manages outbound queue:
- Per-power-mode concurrent limits
- Round-robin interleaving across active transfers
- Priority ordering for queued transfers
- Chunk size selection by transport mode + power mode

## 8. Transport Mode Comparison

| Aspect | GATT | L2CAP |
|--------|------|-------|
| Chunk size | MTU-dependent (244–512 bytes) | 1024–8192 bytes (per power mode) |
| Flow control | App-level SACK + observation-based rate | L2CAP credits (automatic) |
| Backpressure | NACK `bufferFull` | L2CAP credits + app-level NACK |
| Concurrent limit | Per-power-mode (8/4/1) | Same |
| 100KB throughput | ~5–10s | ~1–2s |

## 9. Expected Latency Estimates

Assumptions: 15ms connection interval, write-without-response, Noise XX established.

| Scenario | Estimated Latency |
|----------|------------------|
| 1KB direct (1 hop, GATT) | 50–200ms |
| 1KB direct (1 hop, L2CAP) | 20–80ms |
| 1KB routed (2 hops) | 200–500ms |
| 1KB routed (4 hops) | 500ms–2s |
| 100KB direct (GATT) | 4–8s |
| 100KB direct (L2CAP) | 1–3s |
| 100KB routed (2 hops, GATT) | 8–20s |
| 100KB routed (4 hops, GATT) | 20–60s |

> With idle connection intervals (100ms), first-write latency may add up to 100ms. 4-hop 100KB approaches 30s chunk timeout — adaptive timeout (§6) handles this.
