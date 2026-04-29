# Configuration Reference

## Root: MeshLinkConfig

Created via DSL builder:

```kotlin
val config = meshLinkConfig("com.example.myapp") {
    messaging { ... }
    transport { ... }
    security { ... }
    power { ... }
    routing { ... }
    diagnostics { ... }
    rateLimiting { ... }
    transfer { ... }
    region(RegulatoryRegion.EU)
}
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `appId` | String | *required* | Mesh isolation key. Hashed to 16-bit FNV-1a mesh hash in advertisements. |
| `region` | RegulatoryRegion | `DEFAULT` | EU clamps ad interval ≥300ms and scan duty ≤70% |

---

## MessagingConfig

| Field | Type | Default | Constraints | Description |
|-------|------|---------|-------------|-------------|
| `maxMessageSize` | Int | 102,400 | Must be ≤ `bufferCapacity` (throws) | Max payload bytes for unicast or transfer |
| `bufferCapacity` | Int | 1,048,576 | Clamped ≥ 65,536 | Store-and-forward buffer total bytes |
| `broadcastTtl` | Int | 2 | Clamped ≤ `maxHops` | Initial hop count for flood broadcasts |
| `maxBroadcastSize` | Int | 10,000 | — | Max payload bytes for a broadcast message |

---

## TransportConfig

| Field | Type | Default | Constraints | Description |
|-------|------|---------|-------------|-------------|
| `mtu` | Int | 517 | — | BLE ATT MTU. 517 for L2CAP, 244 for GATT |
| `l2capEnabled` | Boolean | true | — | Advertise and use L2CAP CoC channels |
| `forceL2cap` | Boolean | false | — | Skip GATT fallback entirely |
| `forceGatt` | Boolean | false | — | Skip L2CAP, always use GATT |
| `bootstrapDurationMillis` | Long | 30,000 | — | Time in PERFORMANCE tier on cold start |
| `l2capRetryAttempts` | Int | 3 | — | L2CAP connection retries before GATT fallback |
| `advertisementIntervalMillis` | Long | 250 | EU: clamped ≥ 300 | BLE advertisement interval |
| `scanDutyCyclePercent` | Int | 100 | EU: clamped ≤ 70 | Percentage of time actively scanning |

---

## SecurityConfig

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `requireEncryption` | Boolean | true | Reject plaintext frames |
| `trustMode` | TrustMode | STRICT | STRICT (auto-reject key changes) or PROMPT (app decides) |
| `keyChangeTimeoutMillis` | Long | 30,000 | Auto-reject if app doesn't respond to PROMPT within this window |
| `requireBroadcastSignatures` | Boolean | true | Reject unsigned broadcasts |
| `ackJitterMaxMillis` | Long | 500 | Random jitter on ACK timers to prevent sync storms |

---

## PowerConfig

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `powerModeThresholds.performanceThreshold` | Float | 0.80 | Battery level above which → PERFORMANCE tier |
| `powerModeThresholds.powerSaverThreshold` | Float | 0.30 | Battery level below which → POWER_SAVER tier |
| `customPowerMode` | PowerTier? | null | Force a specific tier (null = automatic) |
| `evictionGracePeriodMillis` | Long | 30,000 | Grace before evicting peers when tier drops |

---

## RoutingConfig

| Field | Type | Default | Constraints | Description |
|-------|------|---------|-------------|-------------|
| `routeCacheTtlMillis` | Long | 210,000 | — | Route expiry (3.5 minutes) |
| `maxHops` | Int | 10 | Clamped [1, 20] | Maximum hop count for all messages |
| `dedupCapacity` | Int | 25,000 | Clamped [1000, 50000] | Max entries in broadcast dedup set |
| `maxMessageAgeMillis` | Long | 2,700,000 | — | TTL for dedup entries (45 minutes) |

---

## DiagnosticsConfig

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `enabled` | Boolean | true | Master switch for diagnostic pipeline |
| `bufferCapacity` | Int | 1,000 | Ring buffer size for SharedFlow |
| `redactPeerIds` | Boolean | false | Truncate peer IDs in payloads (GDPR) |
| `healthSnapshotIntervalMillis` | Long | 5,000 | Interval between meshHealthFlow emissions |

---

## RateLimitConfig

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `maxSends` | Int | 60 | Max outbound unicast messages per minute |
| `broadcastLimit` | Int | 10 | Max outbound broadcasts per minute |
| `handshakeLimit` | Int | 1 | Max handshake initiations per peer per second |

---

## TransferConfig

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `chunkInactivityTimeout` | Long | 30,000 | Base inactivity timeout per transfer (scaled by hop count, up to 4×) |
| `maxConcurrentTransfers` | Int | 4 | Max simultaneous transfers (hard cap: 100) |
| `ackTimeoutMultiplier` | Float | 1.0 | ACK timeout scaling on degraded links (reserved) |
| `degradationThreshold` | Float | 0.5 | ACK timeout fraction triggering degradation (reserved) |

---

## Presets

| Factory Method | Use Case | Key Settings |
|----------------|----------|--------------|
| `smallPayloadLowLatency(appId)` | Text chat | maxMsg=10KB, buffer=512KB |
| `largePayloadHighThroughput(appId)` | File transfers | maxMsg=100KB, buffer=2MB, L2CAP on |
| `minimalResourceUsage(appId)` | IoT/wearable | maxMsg=10KB, buffer=256KB, L2CAP off |
| `sensorTelemetry(appId)` | Sensor data | maxMsg=1KB, buffer=64KB, maxHops=3, L2CAP off |

---

## Validation Rules

### Throws (safety-critical)
- `maxMessageSize > bufferCapacity` → `IllegalArgumentException`

### Clamped (best-effort, emits CONFIG_CLAMPED diagnostic)
- `bufferCapacity < 65536` → clamped to 65536
- `maxHops` outside [1, 20] → clamped to boundary
- `broadcastTtl > maxHops` → clamped to maxHops
- `dedupCapacity` outside [1000, 50000] → clamped to boundary
- EU region: `advertisementIntervalMillis < 300` → clamped to 300
- EU region: `scanDutyCyclePercent > 70` → clamped to 70
