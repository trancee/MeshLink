# 07 — Routing: Babel Adaptation for BLE

> **Covers:** §7 (Routing — Babel Adaptation for BLE)
> **Dependencies:** `01-vision-and-domain-model.md` (Routing Table, Route Cost, Visited List, Dedup Set, Buffer), `04-wire-format.md` (Hello, Update, Routed Message)
> **Key exports:** Babel protocol adaptation, route propagation, feasibility condition, composite metric, multipath, loop prevention, dedup, buffering, cut-through forwarding
> **Changes from original:** S1 (Bloom filter removed), S6 (fixed buffer/dedup sizing), M2 (fixed TTL per priority), M3 (Double for route cost), A1 (max message age), A2 (mesh partition handling), A6 (Sybil limitations). Review fixes: origination_time corrected to wall-clock, route digest uses FNV-1a (not SHA-256), dedup time bound simplified to max buffer TTL, relay processing clarifies deliver-at-destination vs drop, buffer sizing rationale documented, metric wire encoding cross-referenced.

---

## 1. Why Babel (RFC 8966)

Babel selected over RIP v1/v2 (count-to-infinity, no feasibility condition), DVMRP (multicast-specific), OSPF (link-state floods saturate BLE), AODV (no loop-free guarantee during convergence), and BGP (requires TCP). Babel is the only evaluated protocol with **loop-free guarantee at all times** + native composite metrics + heterogeneous timers.

**BLE-specific simplifications:**
1. Skip IHU — Noise XX proves bidirectional reachability
2. Unicast-only — BLE has no multicast
3. Triggered updates dominate periodic full dumps
4. Each Update carries both public keys (key propagation)

## 2. Route Propagation Protocol

### Hello (`0x03`)
Periodic neighbor liveness. New neighbor → triggers full routing table dump as Updates. Known neighbors → keepalive only. Carries `routeDigest` (4-byte XOR-fold) for lost-update detection.

### Update (`0x04`)
Route advertisement: destination, metric, seqNo, Ed25519 + X25519 public keys. `metric = 0xFFFF` = Route Retraction.

### Periodic Full Update
Every **10× Hello interval** (safety net only). Primary mechanism is **differential Updates:**
- Track `lastSentSeqNo` per neighbor per destination
- On timer, send only routes where `currentSeqNo > lastSentSeqNo`
- Full dumps only for: new neighbor join, 10× safety timer
- **Reduces routing traffic by 80–95%** in stable meshes

### Route Digest (Lost-Update Detection)
XOR-fold of per-entry hashes, 4 bytes, exchanged with each Hello:
```
entry_hash(dest, seqNo) = FNV-1a(dest || seqNo_as_2B_LE) // 4-byte output
route_digest = XOR of all entry_hash values
```
Maintained incrementally (add/remove operations XOR the entry hash). FNV-1a chosen over SHA-256 because this is a consistency check, not a security function — ~100× faster, same collision properties for this use case. Mismatch → targeted full dump for that neighbor only.

### On-Demand Fallback
No proactive route → Hello broadcast to all neighbors, triggering Update responses. Message queued until route installed.

## 3. Feasibility Condition (Loop Prevention)

Per RFC 8966 §2.4 (derived from EIGRP DUAL):

Each peer tracks **feasibility distance** (FD) per destination. Incoming Update accepted only if:
- Strictly newer sequence number → **FD reset** to advertised metric, OR
- Same seqno AND `metric < FD`

**FD reset rule:** Strictly newer seqno resets FD. Without this, stale FD values reject valid routes.

**Retraction handling:** `metric = 0xFFFF` with newer seqno → remove route, clear FD. Cleared FD accepts next finite-metric Update.

Guarantees **loop-freedom at all times**.

## 4. Route Metric: Composite Link Quality

```
link_cost = RSSI_base_cost × loss_rate_multiplier × freshness_penalty + stability_penalty
```

### Components

1. **RSSI base cost:** `max(1.0, (-RSSI - 50) / 5.0)`
   - -50 dBm → 1.0, -62 dBm → 2.4, -70 dBm → 4.0, -90 dBm → 8.0

2. **Loss rate multiplier:** `1.0 + (loss_rate × 10.0)` — 0% = ×1.0, 5% = ×1.5, 20% = ×3.0

3. **Freshness penalty:** `1.0 + 0.5 × min(1.0, age / (4 × keepaliveInterval))` — continuous decay from 1.0 to 1.5

4. **Stability penalty:** `max(0.0, 3.0 - consecutive_stable_intervals)` — new links +3, decays to 0 after 3 intervals

**Cost range:** ~1.0 (excellent single link) to ~40.0+ (marginal single link). Route cost = sum of per-link costs; multi-hop routes accumulate.

**Internal representation:** `Double`. No fixed-point conversion, no artificial ceiling. Route costs are internal only (never on wire as Double). All arithmetic uses IEEE 754 double precision. Wire encoding: `floor(cost × 100)` as uint16 — see `04-wire-format.md` §3 Update schema.

**Fallback:** No link quality data → cost defaults to 1.0 per hop.

## 5. Multipath Routing

Up to **2 routes** per destination:

| Route | Criteria |
|-------|---------|
| Primary | Lowest cost, highest seqno |
| Backup | Second-lowest cost, via **different** next-hop |

Failover: Primary fails → promote Backup. No proactive backup probing.

## 6. Route Expiry & Failure Recovery

- Routes expire by power mode (Performance: 70s, Balanced: 210s, PowerSaver: 420s — 14× Hello interval). Lazily removed on lookup.
- Broken routes (delivery timeout, GATT disconnect) → remove immediately + send retractions
- Discovery rate limit: max 1 Hello broadcast per destination per `routeDiscoveryTimeoutMillis`

## 7. Loop Prevention: Visited List

Each Routed Message carries a **Visited List** — a variable-length array of 12-byte peer IDs that have forwarded the message.

**Relay processing:**
1. Check visited list for own peer ID → if found, **DROP** (definite loop)
2. If destination is self → **DELIVER** (regardless of hop limit)
3. If `hopLimit == 0` → **DROP** + `HOP_LIMIT_EXCEEDED` diagnostic
4. Append own peer ID to visited list
5. Decrement hop limit
6. Look up next-hop in routing table
7. Forward

**Zero false positives** — no legitimate messages are dropped. Wire overhead: 12 bytes per hop (at 4 hops = 48 bytes, negligible on both GATT and L2CAP).

`LOOP_DETECTED` diagnostic emitted on drop.

## 8. Message Age Limit

Each Routed Message carries `origination_time` (wall-clock epoch millis at sender). Relays reject messages older than `maxMessageAge` (default 30 min). This prevents messages bouncing indefinitely through the mesh via buffering at multiple peers.

Wall clocks across BLE peers typically agree within seconds to minutes — a 30-minute cap tolerates significant drift. NTP manipulation could extend message lifetime, but the visited list + dedup set bound re-forwarding independently. Monotonic clocks are NOT used here because they are device-local and incomparable across peers.

## 9. Message Deduplication

`LinkedHashMap<MessageId, Long>` with access-order LRU:
- **Time bound:** `max(bufferTtlMillis)` = 45 min (HIGH priority TTL). A message cannot survive longer than its buffer TTL, so dedup entries beyond that age are provably stale.
- **Count bound:** `dedupCapacity` (default 25,000). **Fixed allocation at startup.**
- O(1) lookup, insertion, eviction. Zero false positives.
- In-memory only — crash may cause recent redelivery
- Monotonic clock (immune to NTP jumps)

## 10. Message Buffering (Store-and-Forward)

**Who buffers:** Any peer (E2E encrypted — buffers can't read content).

**Fixed TTL per priority:**

| Priority | TTL |
|----------|-----|
| HIGH | 45 min |
| NORMAL | 15 min |
| LOW | 5 min |

Predictable — Consuming Apps know exactly how long a message survives.

**Buffer:** Fixed-size, allocated at startup. Configurable via `bufferCapacity` (default 1 MB). 75% relay cap / 25% own outbound. At 100KB max message size this holds ~10 messages (7 relay, 2 own). Intentionally conservative for memory-constrained devices — use `largePayloadHighThroughput` preset (2 MB) or custom config for relay-heavy deployments. Most BLE mesh messages are ≤10KB, so the practical message count is much higher (50–100+).

**Eviction tiers** (applied in order when buffer full):
1. No known route — within tier: lowest priority evicted first, then shortest TTL remaining first, then oldest first (FIFO)
2. Relay with routes — same ordering
3. Own outbound — same ordering

**OOM:** `MEMORY_PRESSURE` diagnostic.

**Low-memory tiered shedding:**
1. Flush relay buffers (exempt active cut-through)
2. Shrink dedup 50% (evict oldest entries)
3. Drop lowest-priority connections to 50%

### Mesh Partition Handling

When the mesh splits, routes to now-unreachable peers go stale and expire (per §6 timers). Retractions propagate to neighbors. Buffered messages for unreachable destinations are evicted when their route expires (tier 1 eviction: "no known route"). The sender receives a `transferFailures` callback with `DeliveryOutcome.ROUTE_EXPIRED` after `bufferTtlMillis`.

No special partition detection is needed — the existing route expiry + buffer eviction mechanisms handle it.

## 11. Cut-Through Forwarding

Relays pipeline message forwarding — E2E ciphertext passes through opaque (relays never decrypt it).

**Relay processing for multi-chunk messages:**
1. Receive and decrypt chunks (hop-by-hop Noise XX) into a local reassembly buffer
2. Once enough bytes are received to parse the Routed Message FlatBuffers header (typically chunk 0 — the header fields are ~60 bytes), extract routing metadata (origin, destination, visited list, hop limit, priority, origination_time)
3. Perform routing checks: visited list loop detection, hop limit, message age, dedup
4. Modify header: append own peer ID to visited list, decrement hop limit
5. Re-serialize modified Routed Message, re-chunk at outbound connection's chunk size
6. Begin forwarding re-encrypted chunks to next-hop **concurrently with receiving remaining inbound chunks** (pipeline effect)

For single-chunk messages (≤1 GATT write), the relay processes the complete Routed Message in one shot.

**Key property:** The relay fully reassembles the Routed Message *envelope* (header + opaque E2E payload) but never decrypts the E2E ciphertext. The pipelining benefit comes from overlapping inbound receive and outbound send for large transfers — the relay does not need to receive all chunks before starting to forward.

- **Local buffer:** Relay keeps copy of forwarded chunks. Next-hop drops → retransmit from local buffer (no cascade to original sender).
- **Hop-by-hop re-encryption:** Decrypt from inbound Noise XX → re-encrypt under outbound session. Per-chunk symmetric crypto only.
- **Amortized key schedule:** ChaCha20-Poly1305 key schedule initialized ONCE per transfer. Nonce: `transferMessageId[0:4] || chunkSeqNum`. 100KB through 3 relays: ~18ms total crypto overhead.

## 12. Topology Change Handling

| Event | Action |
|-------|--------|
| New Neighbor (GATT + Noise XX complete) | Add to connection table. Routes discovered on demand. |
| Neighbor Gone (sweep eviction) | Invalidate routes using that next-hop. Send retractions. |
| Route failure (delivery timeout) | Remove route. Hello triggers rediscovery on next send. |

Babel overhead: ~60 bytes/neighbor/Hello interval + periodic Updates (~0.06% BLE bandwidth).

## 13. Route Cost Sanity, Sybil Mitigation & Relay Reputation

- `addRoute()` validates advertised cost ≥ measured link cost. Below floor → clamped.
- Routes through any single neighbor capped at 30% of routing table (Sybil mitigation).
- **Local-only** per-relay failure/success counters. >50% failure + 10+ samples → skipped. Reputation **never shared** (prevents poisoning).

### Sybil Attack Limitations

MeshLink is a permissionless BLE mesh — any device can generate a new identity and join. **Sybil resistance is fundamentally limited.** A malicious actor can create multiple identities (different keypairs) and appear as multiple peers. Mitigations:
- 30% per-neighbor route cap limits single-source route table poisoning
- Handshake rate limit (1/sec per BLE MAC) slows identity flooding
- Connection slot limits bound the number of Sybil peers that can maintain active connections

These are **soft mitigations, not guarantees**. Consuming Apps in adversarial deployments should document that a sophisticated local attacker with multiple BLE radios can degrade mesh performance.

## 14. Timer Mapping to Power Modes

| Timer | Performance | Balanced | Power Saver |
|-------|-------------|----------|------------|
| Hello interval | 5s | 15s | 30s |
| Full Update (safety net) | 50s (10×) | 150s (10×) | 300s (10×) |
| Differential Update check | 20s (4×) | 60s (4×) | 120s (4×) |
| Route expiry | 70s | 210s | 420s |
