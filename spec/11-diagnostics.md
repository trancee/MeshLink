# 11 — Diagnostics & Observability

> **Covers:** §11 (Diagnostics & Observability)
> **Dependencies:** `01-vision-and-domain-model.md` (Peer, Key Hash)
> **Key exports:** DiagnosticSink, all event codes, DiagnosticEvent structure, MeshHealthSnapshot, redaction
> **Changes from original:** S2 (RATCHET_DESYNC removed)

---

## 1. Design Philosophy

`.diagnosticEvents` Flow is the **sole observability surface**. No built-in crash reporting. No console output in release builds. Consuming App pipes to its own observability stack.

## 2. DiagnosticSink

Central event emitter using replay-buffered `SharedFlow`:

- **Ring buffer** (configurable, default 256)
- **`DROP_OLDEST` overflow** — late subscribers get up to `bufferCapacity` recent events
- **Lazy payload** — `payloadProvider` lambda only called when diagnostics enabled (no hot-path allocation)
- **Disable entirely:** `diagnosticsEnabled = false` → replaced with `NoOpDiagnosticSink` singleton (zero overhead, saves ~50KB)

## 3. Event Codes (27 total)

### 🔴 Critical

| Code | Severity | Description |
|------|----------|-------------|
| `DUPLICATE_IDENTITY` | ERROR | Same Ed25519 key from two peer IDs (possible cloned identity or attack) |
| `BLE_STACK_UNRESPONSIVE` | ERROR | BLE hardware not responding |
| `DECRYPTION_FAILED` | WARN | AEAD auth failed; message discarded |
| `ROTATION_FAILED` | ERROR | Key rotation failed; rolled back to old keys |

### 🟡 Threshold-Based

| Code | Severity | Description |
|------|----------|-------------|
| `REPLAY_REJECTED` | WARN | Replay guard rejected counter |
| `RATE_LIMIT_HIT` | WARN | Rate limit triggered; message dropped |
| `BUFFER_PRESSURE` | WARN | Buffer utilization >80% |
| `MEMORY_PRESSURE` | ERROR | Memory exceeded; tiered shedding |
| `NEXTHOP_UNRELIABLE` | WARN | Next-hop failure >50% |
| `LOOP_DETECTED` | WARN | Loop via visited list |
| `HOP_LIMIT_EXCEEDED` | INFO | Max hops reached |
| `MESSAGE_AGE_EXCEEDED` | INFO | Routed message exceeded `maxMessageAge`; dropped |

### ℹ️ Log Only

| Code | Severity | Description |
|------|----------|-------------|
| `ROUTE_CHANGED` | INFO | Route added/updated/removed |
| `PEER_PRESENCE_EVICTED` | INFO | Peer removed after 2 sweep misses |
| `TRANSPORT_MODE_CHANGED` | INFO | L2CAP ↔ GATT change |
| `L2CAP_FALLBACK` | WARN | Fell back from L2CAP to GATT |
| `GOSSIP_TRAFFIC_REPORT` | INFO | Route exchange completed |
| `HANDSHAKE_EVENT` | INFO | Noise XX lifecycle event |
| `LATE_DELIVERY_ACK` | WARN | ACK arrived after deadline |
| `SEND_FAILED` | WARN | Transport send failure |
| `DELIVERY_TIMEOUT` | WARN | E2E ACK not received |
| `APP_ID_REJECTED` | INFO | App ID mismatch; dropped |
| `UNKNOWN_MESSAGE_TYPE` | WARN | Unrecognized wire type |
| `MALFORMED_DATA` | WARN | Wire parsing failed |
| `PEER_DISCOVERED` | INFO | New peer via BLE advertisement |
| `CONFIG_CLAMPED` | INFO | Config value clamped to valid range |
| `INVALID_STATE_TRANSITION` | WARN | Invalid lifecycle transition (no-op) |

## 4. DiagnosticEvent Structure

| Field | Type | Description |
|-------|------|-------------|
| `code` | DiagnosticCode | What happened |
| `severity` | INFO / WARN / ERROR / FATAL | Impact level |
| `monotonicMillis` | Long | Monotonic clock |
| `wallClockMillis` | Long | Wall-clock (epoch) |
| `droppedCount` | Int | Events dropped since last emit (buffer overflow) |
| `payload` | DiagnosticPayload (sealed) | Type-safe, per-code payload |

### Type-Safe Payloads

```kotlin
@JvmInline
value class PeerIdHex(val hex: String)  // String for correct equals/hashCode

sealed interface DiagnosticPayload {
    data class DecryptionFailed(val peerId: PeerIdHex, val reason: String) : DiagnosticPayload
    data class RateLimitHit(val peerId: PeerIdHex, val limit: Int, val window: Long) : DiagnosticPayload
    data class RouteChanged(val destination: String, val cost: Double) : DiagnosticPayload
    data class BufferPressure(val utilization: Int) : DiagnosticPayload
    data class TextMessage(val message: String) : DiagnosticPayload
    // ... one per DiagnosticCode
}
```

## 5. MeshHealthSnapshot

Point-in-time state via `meshHealth()`:

| Field | Description |
|-------|-------------|
| `connectedPeers` | Direct BLE neighbor count |
| `reachablePeers` | Total via routing |
| `bufferUtilizationPercent` | 0–100 |
| `activeTransfers` | In-flight count |
| `powerMode` | Current mode |
| `avgRouteCost` | Average across routing table |
| `relayBufferUtilization` | Relay fraction (0.0–1.0) |
| `ownBufferUtilization` | Own-outbound fraction |
| `relayQueueSize` | Relay forwarding queue depth |

## 6. Peer ID Redaction

`diagnosticRedactPeerIds = true` → peer IDs replaced with short SHA-256 hashes. Useful for log correlation without revealing actual peers.
