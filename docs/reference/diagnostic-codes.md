# Diagnostic Codes Reference

All 27 codes grouped by severity tier. Each code has a fixed `DiagnosticLevel` and a typed `DiagnosticPayload` subclass.

## Severity Levels

| Level | Meaning | Action expected |
|-------|---------|-----------------|
| ERROR | System integrity at risk | Immediate app attention required |
| WARN | Degraded but operational | Monitor, may need intervention |
| INFO | Normal protocol activity | Informational, no action needed |
| DEBUG | Verbose internal state | Not emitted unless explicitly enabled |

---

## Critical Tier (4 codes)

| Code | Level | Payload | When emitted |
|------|-------|---------|--------------|
| `DUPLICATE_IDENTITY` | ERROR | `DuplicateIdentity(peerId)` | Two peers advertise the same Ed25519 public key |
| `BLE_STACK_UNRESPONSIVE` | ERROR | `BleStackUnresponsive(reason)` | Platform BLE scan/advertise API stops responding |
| `DECRYPTION_FAILED` | WARN | `DecryptionFailed(peerId, reason)` | Authenticated AEAD decryption fails (tag mismatch, wrong key, corrupted) |
| `ROTATION_FAILED` | ERROR | `RotationFailed(reason)` | `rotateIdentity()` could not complete (storage write failed, key gen failed) |

## Threshold Tier (8 codes)

| Code | Level | Payload | When emitted |
|------|-------|---------|--------------|
| `REPLAY_REJECTED` | WARN | `ReplayRejected(peerId)` | Inbound frame has a replay counter already seen in the sliding window |
| `RATE_LIMIT_HIT` | WARN | `RateLimitHit(peerId, limit, windowMillis)` | Outbound rate limiter blocked a send attempt |
| `BUFFER_PRESSURE` | WARN | `BufferPressure(utilizationPercent)` | Store-and-forward buffer exceeds 80% capacity |
| `MEMORY_PRESSURE` | ERROR | `MemoryPressure(usedBytes, maxBytes)` | Heap/native memory crosses critical threshold |
| `NEXTHOP_UNRELIABLE` | WARN | `NexthopUnreliable(peerId, failureRate)` | A relay peer has >50% frame delivery failure in sliding window |
| `LOOP_DETECTED` | WARN | `LoopDetected(destination)` | Babel feasibility check detected a potential routing loop |
| `HOP_LIMIT_EXCEEDED` | INFO | `HopLimitExceeded(hops, maxHops)` | A relayed message's TTL reached zero before delivery |
| `MESSAGE_AGE_EXCEEDED` | INFO | `MessageAgeExceeded(ageMillis, maxAgeMillis)` | A message's timestamp is older than the dedup window |

## Log Tier (15 codes)

| Code | Level | Payload | When emitted |
|------|-------|---------|--------------|
| `ROUTE_CHANGED` | INFO | `RouteChanged(destination, cost)` | Best route to a destination updated (new next-hop or metric) |
| `PEER_PRESENCE_EVICTED` | INFO | `PeerPresenceEvicted(peerId)` | A peer transitioned to Gone (evicted after 2 sweeps) |
| `TRANSPORT_MODE_CHANGED` | INFO | `TransportModeChanged(mode)` | BLE transport mode switched (L2CAP↔GATT, scan interval change) |
| `L2CAP_FALLBACK` | WARN | `L2capFallback(peerId)` | L2CAP connection failed, falling back to GATT for this peer |
| `GOSSIP_TRAFFIC_REPORT` | INFO | `GossipTrafficReport(routeCount)` | Periodic routing table size snapshot |
| `HANDSHAKE_EVENT` | INFO | `HandshakeEvent(peerId, stage)` | Noise XX handshake stage completed (msg1/msg2/msg3/complete/failed) |
| `LATE_DELIVERY_ACK` | WARN | `LateDeliveryAck(messageIdHex)` | DeliveryAck arrived after the transfer session timed out |
| `SEND_FAILED` | WARN | `SendFailed(peerId, reason)` | Unicast send failed (no route, peer disconnected, buffer full) |
| `DELIVERY_TIMEOUT` | WARN | `DeliveryTimeout(messageIdHex)` | Chunked transfer timed out without completion |
| `APP_ID_REJECTED` | INFO | `AppIdRejected(appId)` | Received a frame from a different mesh (appId hash mismatch) |
| `UNKNOWN_MESSAGE_TYPE` | WARN | `UnknownMessageType(typeCode)` | Received frame has unrecognized type byte |
| `MALFORMED_DATA` | WARN | `MalformedData(reason)` | Frame failed InboundValidator structural checks |
| `PEER_DISCOVERED` | INFO | `PeerDiscovered(peerId)` | New peer completed Noise XX handshake, session available |
| `CONFIG_CLAMPED` | INFO | `ConfigClamped(field, original, clamped)` | A config value was silently clamped at startup |
| `INVALID_STATE_TRANSITION` | WARN | `InvalidStateTransition(from, trigger)` | Invalid lifecycle method call (e.g., `send()` while Stopped) |

---

## Generic Payload

`TextMessage(message: String)` — fallback for ad-hoc diagnostics not covered by a specific code. Should not appear in production; used during development.

## Payload Redaction

When `DiagnosticsConfig.redactPeerIds = true`, all `PeerIdHex` fields in payloads are truncated to the first 8 hex characters. Useful for GDPR compliance in diagnostic logs.

## Flow Semantics

- `diagnosticEvents: Flow<DiagnosticEvent>` uses `MutableSharedFlow(extraBufferCapacity = bufferCapacity, onBufferOverflow = DROP_OLDEST)`
- If no collector is attached, events are silently dropped (zero cost)
- Payload lambdas are evaluated lazily: `diagnosticSink.emit(code) { ExpensivePayload() }` — the lambda only runs if collectors exist
