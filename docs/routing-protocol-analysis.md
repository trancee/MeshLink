# MeshLink — Routing Protocol Analysis

> Analysis of 7 routing RFCs for BLE mesh networking. Conclusion: **Babel
> (RFC 8966)** adapted for BLE constraints.

## RFCs Evaluated

| RFC | Protocol | Type | Year | Designed For |
|-----|----------|------|------|-------------|
| 1058 | RIP v1 | Distance-vector, proactive | 1988 | Small wired LANs (max 15 hops) |
| 1075 | DVMRP | Distance-vector, multicast | 1988 | IP multicast routing |
| 2328 | OSPF v2 | Link-state, proactive | 1998 | Enterprise/ISP wired networks |
| 2453 | RIP v2 | Distance-vector, proactive | 1998 | Moderate-size wired networks |
| 3561 | AODV | Distance-vector, reactive | 2003 | Mobile ad-hoc networks (MANETs) |
| 4271 | BGP-4 | Path-vector, proactive | 2006 | Inter-AS Internet routing |
| 8966 | Babel | Distance-vector, hybrid | 2019 | Wired + wireless mesh networks |

## Eliminated

### BGP-4 (RFC 4271)
Inter-AS Internet routing over TCP. Requires explicit peering, policy-based
path selection, and AS_PATH attributes. None applicable to BLE mesh.

### OSPF v2 (RFC 2328)
Link-state protocol requiring every node to maintain an identical topology
database. In a 500-peer BLE mesh:
- ~72KB link-state database per peer
- Every topology change floods all 500 peers (saturates BLE bandwidth)
- Dijkstra SPF computation on every change (expensive on mobile devices)
- Complex adjacency state machine (7 states) would flap constantly on BLE

### DVMRP (RFC 1075)
Multicast-specific reverse-path tree construction based on RIP. MeshLink's
existing broadcast mechanism (flood-and-dedup with TTL) is simpler and more
efficient for BLE's small mesh sizes.

### RIP v1/v2 (RFC 1058 / RFC 2453)
Simple distance-vector with Bellman-Ford. Eliminated because:
- **Count-to-infinity**: Routing loops during convergence take up to 10
  rounds × 30s = 300s to resolve. Unacceptable for BLE mesh.
- **No feasibility condition**: Loops form during topology changes.
- **Hop-count only**: No support for composite link quality metrics.
- **Fixed 30s update interval**: Continuous BLE radio activity.

## Finalists: AODV vs Babel

### AODV (RFC 3561)

**Strengths:** Zero idle overhead; simple implementation (~280 lines); proven
for MANETs.

**Weaknesses:**
- Route discovery latency (1–5s RREQ/RREP flood per new destination)
- RREQ flooding is expensive (50 peers × 1 RREQ = 50 transmissions)
- No loop-free guarantee during convergence
- No RERR in current implementation (30s timeout to detect broken routes)
- Hop-count routing; composite metrics bolted on externally

### Babel (RFC 8966)

**Strengths:**
- **Loop-free at all times** via feasibility condition (D(B) < FD(A))
- **Native composite metrics** (arbitrary monotonic metrics supported)
- **Heterogeneous timers** — nodes with different intervals interoperate
  (maps to MeshLink's PowerProfile: 5s/15s/30s)
- **Triggered updates** — fast convergence with low idle overhead
- **Natural key propagation** — Update TLVs carry per-destination metadata
- **Fast failure detection** — 1.5–3.5× Hello interval (vs AODV's 30s timeout)
- **Route retraction** — explicit infinity-metric Updates replace missing RERR

**Weaknesses:**
- Periodic Hello overhead (~60 bytes/5s with 4 neighbors = 0.06% BLE bandwidth)
- Full routing table dumps every Update interval (manageable with triggered updates)
- ~200 lines more complex than AODV

### Head-to-Head Comparison

| Property | AODV | Babel | Winner |
|----------|------|-------|--------|
| Loop freedom | Not during convergence | Always (feasibility condition) | **Babel** |
| First-message latency | 1–5s (RREQ flood) | 0ms (proactive routes) | **Babel** |
| Idle overhead | Zero | ~60 bytes/5s | AODV |
| Metric support | Hop count (cost bolted on) | Native arbitrary metrics | **Babel** |
| Failure detection | 30s delivery timeout | 1.5–3.5× Hello interval | **Babel** |
| Route quality | First-discovered path | Best-metric path | **Babel** |
| Heterogeneous timers | Not supported | Native | **Babel** |
| Key propagation | Requires extension | Natural in Update | **Babel** |
| Implementation complexity | ~280 lines | ~480 lines | AODV |

## Decision: Babel Adapted for BLE

### Timer Mapping to PowerProfile

| Babel Timer | Performance | Balanced | PowerSaver |
|-------------|-------------|----------|------------|
| Hello interval | 5s | 15s | 30s |
| Update interval | 20s | 60s | 120s |
| Route expiry | 70s | 210s | 420s |

### BLE-Specific Simplifications

1. **Skip IHU** — Noise XX handshake proves bidirectional reachability.
2. **Unicast-only** — BLE has no multicast. Per RFC 8966 §3.4.1, sending
   Hellos to each neighbor individually is explicitly supported.
3. **Triggered updates dominate** — periodic full dumps are infrequent
   safety nets; topology changes trigger immediate updates.
4. **Key propagation** — each Update carries a 32-byte Ed25519 public key.
5. **Feasibility distance** — single comparison per route update; trivial
   overhead.

### Implementation Changes from AODV

1. Add feasibility distance tracking per destination
2. Add periodic Update timer (4× keepalive interval)
3. Add triggered updates on topology changes
4. Replace RREQ flooding with Babel seqno-request mechanism
5. Add route retraction (metric = 0xFFFF) replacing missing RERR
6. Remove AODV reverse-path and RREQ dedup state
