# Babel RFC 8966 — Protocol Operation

<data_structures>
## Data Structures (§3.2)

### Node Sequence Number (§3.2.2)
16-bit integer included in updates for locally-originated routes. Incremented only in response to seqno requests. **SHOULD NOT** be incremented spontaneously.

### Interface Table (§3.2.3)
Per-interface: outgoing Multicast Hello seqno (16-bit, incremented each Hello). Two timers: periodic multicast hello timer, periodic update timer.

### Neighbour Table (§3.2.4)
Indexed by **(interface, address)** — neighbourship is between interfaces, not nodes. Contains:
- Interface and address of neighbour
- Multicast Hello history (e.g. n-bit vector of received/missed)
- Unicast Hello history
- Transmission cost (txcost) from last IHU, or infinity if IHU timer expired
- Expected incoming Multicast/Unicast Hello seqnos
- Outgoing Unicast Hello seqno
- Three timers: multicast hello, unicast hello, IHU

### Source Table (§3.2.5)
Indexed by **(prefix, plen, router-id)**. Stores feasibility distances as (seqno, metric) pairs. One GC timer per entry (order of minutes).

### Route Table (§3.2.6)
Indexed by **(prefix, plen, neighbour)**. Contains:
- Source (prefix, plen, router-id)
- Neighbour that advertised the route
- Advertised metric (or 0xFFFF for retracted)
- Sequence number
- Next-hop address
- Selected flag (used for forwarding + re-advertisement)
- Expiry timer

**Two distinct (seqno, metric) pairs per route:** the route's distance (in route table) and the feasibility distance (in source table, shared across routes with same source).

### Pending Seqno Requests (§3.2.7)
Indexed by **(prefix, plen, router-id)**. Tracks forwarded/originated requests awaiting reply. Contains: requested seqno, forwarding neighbour (if any), resend counter.
</data_structures>

<neighbour_acquisition>
## Neighbour Acquisition (§3.4)

### Hello TLVs (§3.4.1)
Two kinds:
- **Multicast Hello** — per-interface seqno, sent to all neighbours (multicast or multiple unicasts). Used for neighbour discovery. Periodic (scheduled) Multicast Hellos SHOULD be sent.
- **Unicast Hello** — per-neighbour seqno, sent to one neighbour. Optional.

Each Hello carries: seqno (incremented each send), interval (upper bound until next scheduled Hello of same type). Interval=0 means unscheduled (no new promise).

### IHU ("I Heard You") TLVs (§3.4.2)
Confirm bidirectional reachability. Carry:
- **rxcost** — reception cost from sender's perspective
- **interval** — upper bound until next IHU

IHUs MAY be sent to multicast (avoids ARP/ND). SHOULD be sent less often than Hellos on low-loss links. On receiving IHU: set neighbour's txcost to received rxcost, reset IHU timer.

### Cost Computation (§3.4.3)
From Hello history + txcost. Link cost MUST satisfy:
1. Strictly positive
2. Infinite if no recent Hellos received
3. Infinite if txcost is infinite
</neighbour_acquisition>

<routing_table_maintenance>
## Routing Table Maintenance (§3.5)

### Update Processing (§3.5.3)
On receiving update (prefix, plen, router-id, seqno, metric) from neighbour:
- **No existing entry:** ignore if unfeasible; ignore if retraction of unknown route; otherwise create new entry
- **Existing entry, currently selected, unfeasible, same router-id:** MAY ignore
- **Otherwise:** update seqno/metric/router-id. If finite metric, reset expiry timer. If unfeasible, immediately unselect. If router-id changed, send triggered update urgently.

Unfeasible routes are kept in the table (never selected). They become feasible again via seqno requests.

**Expiry:** finite metric → set to infinity, reset timer. Already infinite → flush from table.

### Metric Computation (§3.5.2)
M(c, m) computes route metric from link cost c and neighbour's advertised metric m. MUST satisfy:
- If c is infinite → M is infinite
- **Strictly monotonic:** M(c, m) > m
- SHOULD be left-distributive: if m ≤ m' then M(c, m) ≤ M(c, m')

**Recommended default: additive metric** M(c, m) = c + m.

### Hold Time (§3.5.4)
When prefix P is retracted, maintain infinite-metric entry. Packets for P MUST NOT follow a shorter covering prefix until entry is cleared. Clear when: finite-metric update received, or all neighbours notified (via ack-requested retraction — **recommended** for fast convergence).

### Route Selection (§3.6)
Constraints:
- **Never select** infinite-metric routes
- **Never select** unfeasible routes
- **MUST NOT** consider seqnos (would cause oscillation + black-holes)

**Recommended:** smallest feasible finite metric, with **hysteresis** — only switch when the new route's metric AND smoothed metric are both better. Smoothed metric = exponentially weighted average with time constant of ~3× Hello interval.
</routing_table_maintenance>

<sending_updates>
## Sending Updates (§3.7)

### Periodic Updates (§3.7.1)
Every selected route MUST be advertised on every interface at least once per Update interval.

### Triggered Updates (§3.7.2)
- **Router-id change for selected route** → urgent update, MUST ensure reliable delivery (ack requests for small neighbour counts, or repeat 2-3× for large). Max 5 copies.
- **Route retraction** → SHOULD send triggered update with reasonable delivery effort
- **Significant metric change** → MAY send triggered update
- **Minor fluctuations, seqno propagation** → SHOULD NOT trigger updates

### Maintaining Feasibility Distances (§3.7.3)
Before sending a finite-metric update, update the source table:
- No entry exists → create with (seqno, metric)
- Entry (s', m') exists: if seqno > s' → update both; if seqno == s' and metric < m' → update metric only
- Reset GC timer. **Not updated on retraction.**

### Split Horizon (§3.7.4)
On symmetric transitive links (wired/Ethernet): don't send update for prefix P on the interface where P's selected route was learned. **SHOULD NOT** apply on wireless (not symmetric/transitive for multicast).
</sending_updates>

<requests>
## Explicit Requests (§3.8)

### Route Requests (§3.8.1.1)
Receiver sends update for exact prefix (or retraction if no route). Wildcard request → full route dump (rate-limited). Never forwarded.

### Seqno Requests (§3.8.1.2)
If receiver has selected route with matching prefix AND (different router-id OR sufficient seqno) → send update. If router-id matches but seqno too low AND it's the receiver's own router-id → increment seqno by 1, send update. **MUST NOT increment by more than 1 per request.**

Otherwise, if hop count ≥ 2 and node advertises this prefix → forward to next hop of a (preferably feasible) route. Decrement hop count, send as urgent unicast. **MUST NOT forward to multicast or multiple neighbours.** Duplicate suppression prevents forwarding redundant requests.

### When to Send Requests (§3.8.2)
- **Starvation** (§3.8.2.1): Lost all feasible routes but have unfeasible ones → MUST send seqno request. Requested seqno = source table value + 1. Hop count default: 64. Resend with exponential backoff.
- **Unfeasible update for selected route** (§3.8.2.2): SHOULD send unicast seqno request
- **Preventing expiry** (§3.8.2.3): Shortly before selected route expires → SHOULD send unicast route request to advertising neighbour
</requests>
