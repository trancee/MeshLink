# 10 — Power Management & Presence Detection

> **Covers:** §10 (Power Management & Presence Detection)
> **Dependencies:** `01-vision-and-domain-model.md` (Power Mode, Hysteresis, Eviction, Peer Lifecycle)
> **Key exports:** Fixed power tiers, scan duty cycling, connection limits, BLE connection parameters, presence detection, peer lifecycle state machine
> **Changes from original:** S10 (fixed tiers replace continuous scaling), S6 (fixed buffer/dedup sizing reflected in sweep), S2 (ratchet sweep removed), A4 (bootstrap mode referenced)

---

## 1. Power Mode Tiers

Three fixed tiers. Precedence:
1. **`customPowerMode`** — if set, overrides everything
2. **Charging** — use Performance
3. **Battery level** — threshold-based

| Mode | Battery | Scan Duty | Ad Interval | Max Connections | Keepalive | Presence Timeout |
|------|---------|-----------|-------------|----------------|-----------|-----------------|
| **Performance** | >80% or charging | 80% | 250ms | 6 (configurable up to 8) | 5s | 1.5s (6×) |
| **Balanced** | 30–80% | 50% | 500ms | 4 | 15s | 4s (8×) |
| **PowerSaver** | <30% | 17% | 1000ms | 2 | 30s | 10s (10×) |

**Sweep interval** = `ad_interval × 5` (Performance: 1.25s, Balanced: 2.5s, PowerSaver: 5s).

### Hysteresis (Two Layers)
1. **Level dead zone (±2%):** Downward needs battery 2% below threshold; upward 2% above
2. **Time delay (30s):** Downward requires 30s sustained below. Upward = immediate.
3. **Cascading:** Deeper threshold during grace → target updates, timer restarts. Charger → grace cancelled.

> **Bootstrap mode exception:** During first-launch bootstrap (see `03-transport-ble.md` §8), scan duty and ad interval are forced to Performance regardless of battery. Reverts after `bootstrapDurationMs` or first successful connection.

## 2. Scanning Duty Cycle

Fixed-window duty cycling (e.g., 10s cycle: 8s scan + 2s idle in Performance):

| Platform | Implementation |
|----------|---------------|
| Android | Hardware-level: `ScanSettings.setScanWindow()` / `setScanInterval()` |
| iOS foreground | Software-level start/stop on timer (no CoreBluetooth hardware scan control) |
| iOS background | Platform-controlled — power mode does NOT apply |

## 3. Connection Limits

| Power Mode | Max Connections |
|------------|----------------|
| Performance | 6 (default; configurable up to 8) |
| Balanced | 4 |
| Power Saver | 2 |

Shared across both BLE roles (Central + Peripheral).

**Inbound rejection:** Slots full + inbound GATT → evict lowest-priority. If all have active transfers above minimum throughput → reject at BLE level before Noise XX.

## 4. BLE Connection Parameters

**3-tier adaptation:**

| State | Interval | Slave Latency | Trigger |
|-------|----------|---------------|---------|
| Bulk transfer (>10 chunks pending) | 7.5ms | 0 | >10 chunks pending |
| Active transfer (<10 chunks) | 15ms | 0 | Chunk sent/received |
| Idle | 100ms | 4 | No data for 2s |

**Transitions:**
- Idle → Active: first chunk
- Active → Bulk: >10 chunks pending
- Bulk → Active: transfer complete, no chunks for 500ms
- Active → Idle: no data for 2s

OS has final authority on actual negotiated values.

## 5. Power Mode Transitions

When battery crosses a threshold (with hysteresis):
1. Scan/advertising parameters update immediately
2. BLE connection parameters re-requested
3. Lowest-priority connections evicted to new mode's limit

### Eviction Grace Period
- Connections with active transfers deferred for `max(evictionGracePeriodMillis, estimated_remaining_time)`
- Default base grace: 30s
- **Minimum throughput:** Transfer must exceed 1 KB/s to count as "progressing" — below = stalled = evictable
- **Absolute cap:** 120s regardless of transfer size
- **Budget enforcement:** Grace connections count against NEW budget. If new budget is 4 and 8 connections in grace → 4 lowest-priority evicted immediately
- **Memory pressure:** OS warning cancels all grace periods

## 6. Presence Detection

Based on **advertisement timestamps**, not GATT connection state.

1. Scanner tracks last-seen advertisement per peer
2. Sweep timer at mode-specific interval
3. Peers exceeding presence timeout → eviction candidate

## 7. Adaptive Timeout

```
timeout(peer) = max(own_timeout, peer_timeout)
```

Read from peer's power mode in advertisement. Prevents Performance device from falsely evicting PowerSaver peer.

| Mode | Multiplier | Timeout |
|------|------------|---------|
| Performance | 6× ad interval | ~1.5s |
| Balanced | 8× ad interval | ~4s |
| PowerSaver | 10× ad interval | ~10s |

Unknown power mode → Balanced timeout (8×).

## 8. Eviction Hardening

Peer marked **Gone** only after **2 consecutive sweep intervals** with no advertisements. Any ad during a sweep resets miss counter.

**Sweep atomicity:** Atomic snapshot of all timestamps before evaluation. Ads during evaluation update live timestamp but don't affect current sweep.

## 9. Three-State Peer Lifecycle

| State | Trigger | Meaning |
|-------|---------|---------|
| **Connected** | Ad seen + GATT active | Reachable with active channel |
| **Disconnected** | GATT drops | May still be in range (still advertising) |
| **Gone** | 2 consecutive sweep misses | Removed from peer + routing tables |

GATT disconnect is a strong signal → Disconnected. Not evicted until sweep confirms no ads.

## 10. Bounded-State Sweep Architecture

All long-lived mutable maps have documented sweep paths. Sweeps are **automatic and internal** (driven by sweep timer). Consuming App never calls sweep methods.

| Module | Sweep Action |
|--------|-------------|
| DeliveryPipeline | Evict tombstones (5min TTL), stale reverse-path caches, per-sender rate counts |
| RoutingEngine | Remove routes for evicted peers, expire stale routes. **Hard cap: 2,000 entries; LRU.** |
| CutThroughBuffer | Evict TTL-expired sessions, dangling chunk state |
| RateLimiter | Evict dormant sender keys (30min inactivity) |
| TrustStore | Evict TOFU pins not seen in 90 days. Hard cap: 5,000 pins; LRU. |
| SecurityEngine (PROMPT) | Auto-reject pending key changes after `3 × timeout`. Global cap: 10. |

`MeshStateManager` orchestrates all sweeps. Manual methods are `internal` (testing only).
