# Babel RFC 8966 — Conceptual Model & Feasibility

<overview>
## What Babel Is

Babel is a **loop-avoiding distance-vector** routing protocol (IETF Standards Track, January 2021). It obsoletes RFC 6126 and RFC 7557. Designed for both wired networks and wireless mesh networks.

### Key Properties
- When each prefix is originated by one router: **never** has routing loops
- When a prefix has multiple originators: transient loops disappear in time proportional to loop diameter, and the same routers won't loop again for that prefix (up to source GC time)
- Black-holes after mobility events are corrected in time proportional to network diameter
- Supports heterogeneous timer configurations — nodes with different Hello/Update intervals interoperate
- Hybrid: carries routes for IPv4 and IPv6 regardless of transport protocol
- **Limitations:** periodic updates generate more traffic than link-state protocols in large stable networks; hold time on retracted prefixes can delay aggregation

### How It Differs from Other Protocols
| Protocol | Approach | Babel's Advantage |
|----------|----------|-------------------|
| RIP | Naive distance-vector | Babel has loop-avoidance via feasibility conditions |
| OSPF/IS-IS | Link-state | Babel handles wireless/unstable links better; no flooding |
| EIGRP | Distance-vector with DUAL | Babel avoids global synchronisation on starvation; uses seqno requests instead |
| DSDV | Distance-vector with seqnos | Babel's feasibility condition is strictly less restrictive than DSDV's |
| AODV | Reactive (on-demand) | Babel is proactive — no route discovery latency |
</overview>

<bellman_ford>
## Bellman-Ford with Feasibility

Each node A maintains for each source S:
- **D(A)** — estimated distance (metric) to S
- **NH(A)** — next-hop router to S

On receiving update from neighbour B advertising D(B):
- If B is current next-hop → update: D(A) = C(A,B) + D(B)
- If C(A,B) + D(B) < D(A) → switch: NH(A) = B, D(A) = C(A,B) + D(B)

Refinements: triggered updates on topology change, alternate routes maintained for fast failover.
</bellman_ford>

<feasibility>
## Feasibility Condition (§2.4, §3.5.1)

**Purpose:** Discard updates that could create routing loops. An update is only accepted if it passes the feasibility check.

### Feasibility Distance
**FD(A)** = the minimum distance A has **ever advertised** for source S to any neighbour.

A distance is a pair **(seqno, metric)**. Comparison is lexicographic with seqno inverted:

```
(seqno, metric) < (seqno', metric')
  when seqno > seqno'
  OR (seqno == seqno' AND metric < metric')
```

### The Rule
An update carrying distance (s', m') is **feasible** when any of:
1. metric is infinite (retraction — always feasible)
2. No source table entry exists for this (prefix, plen, router-id)
3. Entry (s, m) exists and: `s' > s` OR `(s' == s AND m' < m)`

**Key insight:** The feasibility condition considers the metric **advertised by the neighbour**, not the route's metric at the local node. A fluctuation in link cost cannot render a selected route unfeasible.

### Why It's Better Than DSDV
DSDV: accept only if `C(A,B) + D(B) <= D(A)` (metric must not increase).
Babel: accept if `D(B) < FD(A)` — strictly less restrictive. A route with worse total metric but low neighbour metric is still feasible as an alternate.
</feasibility>

<sequencing>
## Sequenced Routes (§2.5)

Every route carries a **sequence number** — a non-decreasing 16-bit integer incremented only by the source. A distance is (seqno, metric).

### Solving Starvation
When a node runs out of feasible routes (spurious starvation), it sends a **seqno request** hop-by-hop to the source. The source increments its seqno and broadcasts an update. The new seqno makes previously-unfeasible routes feasible again.

- Requests are forwarded without regard to feasibility (may loop — bounded by hop count)
- Duplicate detection prevents request loops
- At least one request reaches the source if the network is still connected
- Requests are resent a small number of times to compensate for packet loss
- **A node SHOULD NOT increment its seqno spontaneously** — doing so makes it less likely other nodes will have feasible alternates

### Sequence Number Arithmetic (§3.2.1)
16-bit modular arithmetic:
```
s + n = (s + n) MOD 65536
s < s' when 0 < ((s' - s) MOD 65536) < 32768
```
</sequencing>

<multiple_routers>
## Multiple Routers & Overlapping Prefixes (§2.7, §2.8)

### Multiple Originators
Routes are distinguished by **(prefix, plen, router-id)** — not just prefix. Feasibility distances are per-source, not per-prefix. Loop-freedom is guaranteed per-source; with multiple originators, transient loops break within one round-trip of the loop.

### Overlapping Prefixes
After retracting a prefix P, a shorter covering prefix P' cannot safely be used for P's traffic (would create a loop). Solution: maintain an **unreachable route** entry for retracted P until all neighbours have been notified. Two strategies:
1. Wait for route expiry timer (simpler but slower — minutes)
2. Send retraction with acknowledgment request to all neighbours, wait for acks (**recommended** — much faster)
</multiple_routers>
