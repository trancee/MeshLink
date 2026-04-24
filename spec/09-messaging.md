# 09 — Messaging: Unicast, Broadcast & Delivery Semantics

> **Covers:** §9 (Messaging — Unicast, Broadcast, Delivery Semantics)
> **Dependencies:** `01-vision-and-domain-model.md` (Direct Message, Broadcast, Delivery ACK, NACK), `05-security-encryption.md` (Noise K), `07-routing.md` (route lookup, buffering)
> **Key exports:** send() and broadcast() APIs, rate limiting, circuit breaker, delivery confirmation semantics, message ordering
> **Changes from original:** S4 (probabilistic relay suppression removed), S2 (NACK reason `ratchetDesync` removed)

---

## 1. Unicast (Direct Message)

```kotlin
send(recipient: ByteArray, payload: ByteArray, priority: Byte = 0): Result<SendResult>
```

1. **SendPolicyChain** evaluates: rate limit, buffer capacity, circuit breaker
2. Route exists → E2E seal (Noise K) → chunk → route via Noise XX
3. No route → queue message, broadcast Hello for route discovery
4. Returns `SendResult.Sent` or `SendResult.Queued` (reason: `PAUSED`, `ROUTE_PENDING`, `TRANSFER_LIMIT`)

### Return Type Decision Tree
```
Result.failure      → Infrastructure error (not started, no crypto, invalid state)
SendResult.Sent     → Transmission started (NOT yet delivered)
SendResult.Queued   → Deferred: PAUSED | ROUTE_PENDING | TRANSFER_LIMIT
```

Actual delivery arrives asynchronously via `deliveryConfirmations` (success) or `transferFailures` (failure). Each messageId gets **exactly one** terminal callback.

**Priority:** `-1` low, `0` normal (default), `1` high. Controls buffer eviction order.

## 2. Broadcast

```kotlin
broadcast(payload: ByteArray, maxHops: UByte, priority: Byte = 0): Result<MessageId>
```

**Ed25519-signed and unencrypted** by default. Flood hop-by-hop: each receiver re-sends to all neighbors (except source), decrementing hop counter. Dropped when counter = 0 or dedup hit.

### Properties
- `broadcastTtl` (default 2, configurable 1–`maxHops`)
- At TTL=2 with 5 neighbors → ~10–15 relay messages
- **Signing mandatory** (`requireBroadcastSignatures = true` by default). Unsigned accepted only when receiver sets `allowUnsignedBroadcasts = true`.
- **Chunked broadcasts:** Same chunking infrastructure as direct messages. Separate `maxBroadcastSize` (default 10,000 bytes). Small broadcasts (≤ single GATT write) sent as single frame.
- **No self-delivery:** Sender's `messages` flow does NOT emit own broadcasts.
- **Deterministic forwarding:** Every relay forwards received broadcasts to all neighbors (no probabilistic suppression). Dedup set prevents duplicate processing. Amplification controlled by `maxHops` + per-sender rate limiting.

### Broadcast Signing
Ed25519 signature covers `messageId + origin + appIdHash + payload`. Remaining hop count NOT signed (mutable for relay decrement). Cost ~0.1ms on hardware-accelerated platforms.

### Signature Verification Caching
Result cached in dedup set (keyed on messageId). Same broadcast via different path → skip verification. Reduces verify operations ~60% in typical meshes.

## 3. Rate Limiting

| Limiter | Default | Scope |
|---------|---------|-------|
| Outbound unicast | 60/min | Per-sender |
| Broadcast | 10/min | Per-sender (also local enforcement) |
| Per-sender-per-neighbor relay | 20/min | Per (neighbor, sender) pair |
| Per-neighbor aggregate | 100/min | All senders via single neighbor |
| Per-sender inbound (destination) | 30/min | Per-sender at final recipient |
| Handshake | 1/sec | Per BLE MAC (unknown peers only) |
| NACK | 10/sec | Per neighbor (prevents amplification) |

All use **monotonic-clock sliding windows**. Control-plane messages (Hello, Update, keepalive, chunk_ack, delivery_ack, handshake) are **exempt**.

## 4. Circuit Breaker

Configurable on outbound sends, operating **per-destination** (failures to one peer do not affect sends to others):
- `circuitBreakerMaxFailures` consecutive failures within `circuitBreakerWindowMillis` → circuit opens
- `circuitBreakerCooldownMillis` before retry
- Default: disabled (`maxFailures = 0`)

## 5. Delivery Confirmation

### Two-Tier Semantics

1. **`transferProgress`** — chunk ACK (SACK) tracking. Relay-level, unsigned. SACK forgery by relay possible.
2. **`deliveryConfirmations`** — fires ONLY on signed Delivery ACK (Ed25519). Cryptographic proof.
3. **`transferFailures`** — fires if no ACK within buffer TTL. Includes `DeliveryOutcome` reason.

### Strict Single-Signal Rule
Each messageId gets **exactly one** terminal callback — either `deliveryConfirmations` or `transferFailures`, never both. Late ACKs after timeout → silently dropped + `LATE_DELIVERY_ACK` diagnostic.

### ACK Routing
Routed as a standard unicast from recipient back to sender via the routing table (current best path). On failure: 3 attempts with exponential backoff (2s, 4s, 8s = 14s total), then buffered for next available route.

### ACK Authentication
Ed25519 signature over `messageId + recipient_peer_id`. Verified against recipient's public key from local peer table. Prevents relay ACK forgery.

## 6. Message Ordering

**No ordering guarantee.** Messages from same sender may arrive out of order (multi-path, relay delays, retransmission). Apps requiring order implement their own sequencing. Replay window protects against replays but does not enforce ordering.

## 7. Persistence

Messages are **ephemeral** — exist only in transit. Mesh buffering (TTL per priority: HIGH 45min, NORMAL 15min, LOW 5min) bridges temporary disconnections. Consuming App handles persistence.

## 8. Self-Send

`send(localPeerId)` → delivers asynchronously to `messages` (echo/testing convenience). No BLE activity.
