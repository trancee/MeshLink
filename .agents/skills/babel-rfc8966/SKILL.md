---
name: babel-rfc8966
description: RFC 8966 — The Babel Routing Protocol reference. Loop-avoiding distance-vector protocol for wired/wireless mesh networks. Covers Bellman-Ford with feasibility conditions, sequenced routes, seqno requests for starvation recovery, data structures (interface/neighbour/source/route tables), protocol operation (Hello/IHU, cost computation, route selection with hysteresis, periodic/triggered updates, split horizon), wire format (UDP 6696, address encodings AE 0-3, 11 TLV types, sub-TLVs), cost algorithms (k-out-of-j, ETX, hysteresis), default parameters, security (Babel-MAC/DTLS), and extensions. Use when implementing Babel routing, debugging route convergence, working with TLV encoding, or any RFC 8966 question.
---

<essential_principles>

**RFC 8966** defines Babel, a loop-avoiding distance-vector routing protocol. IETF Standards Track (Jan 2021). Obsoletes RFC 6126, RFC 7557.

### Core Concepts an Implementer Must Know

**Feasibility condition (§2.4, §3.5.1):** The heart of loop avoidance. Each node tracks a feasibility distance FD(A) = minimum distance ever advertised for each source. An update with distance (s', m') is feasible when `s' > FD.seqno` OR `(s' == FD.seqno AND m' < FD.metric)`. Unfeasible routes are kept but never selected.

**Sequenced routes (§2.5):** Every route carries a 16-bit seqno (modulo 2^16), incremented only by the source. When starvation occurs (no feasible routes), send seqno request hop-by-hop to the source. Source increments seqno → routes become feasible again. **Never increment seqno spontaneously.**

**Source = (prefix, plen, router-id):** Routes are distinguished by originator, not just prefix. Feasibility is per-source.

**Metrics:** Strictly monotonic: M(c, m) > m. Recommended: additive M(c, m) = c + m. Costs: 2-out-of-3 (wired, C=96), ETX (wireless). Route selection: smallest feasible finite metric with hysteresis.

**Wire format:** UDP port 6696. Magic=42, Version=2. TLV-based with stateful parser (default prefix, next-hop, router-id carry across TLVs). 11 TLV types (Pad1, PadN, AckReq, Ack, Hello, IHU, Router-Id, Next Hop, Update, Route Request, Seqno Request).

**Security:** Protocol itself is insecure. SHOULD implement Babel-MAC (RFC 8967). Alternative: Babel-DTLS (RFC 8968).

**Key timers:** Hello 4s, IHU 12s, Update 16s, Source GC 3min, Urgent 0.2s.

</essential_principles>

<routing>

| Topic | Reference |
|-------|-----------|
| Conceptual model (Bellman-Ford, feasibility condition with FD, sequenced routes, seqno arithmetic, starvation recovery, multiple originators, overlapping prefixes with hold time) | `references/concepts-and-feasibility.md` |
| Protocol operation (all data structures, neighbour acquisition via Hello/IHU, cost computation rules, route acquisition, metric computation, hold time, route selection with hysteresis, periodic/triggered updates, feasibility distance maintenance, split horizon, route/seqno requests and forwarding) | `references/protocol-operation.md` |
| Wire format (UDP port 6696, packet structure with body+trailer, address encodings AE 0-3, all 11 TLV types with field layouts and flags, sub-TLVs with mandatory bit, stateful parser state) | `references/wire-format.md` |
| Cost algorithms (Hello history maintenance, k-out-of-j for wired, ETX for wireless, hysteresis), recommended parameters (all timers/intervals), security (Babel-MAC/DTLS), extension mechanisms, route filtering, stub implementations | `references/costs-parameters-security.md` |

</routing>

<reference_index>

**concepts-and-feasibility.md** — what Babel is (loop-avoiding distance-vector, IETF Jan 2021, obsoletes 6126/7557), key properties (loop-free per-source, transient loops for multi-originator, black-hole correction proportional to diameter), limitations (periodic updates, hold time on retracted prefixes), comparison table (vs RIP/OSPF/EIGRP/DSDV/AODV), Bellman-Ford algorithm (D(A), NH(A), update processing), feasibility condition (§2.4/§3.5.1, feasibility distance FD, lexicographic comparison with inverted seqno, three acceptance conditions, key insight: considers neighbour's metric not local metric, strictly less restrictive than DSDV), sequenced routes (§2.5, seqno incremented only by source, seqno request mechanism for starvation recovery, SHOULD NOT increment spontaneously, 16-bit modular arithmetic), multiple routers (§2.7, per-source feasibility, transient loops bounded by diameter), overlapping prefixes (§2.8, unreachable route entry after retraction, two clearing strategies)

**protocol-operation.md** — all data structures (§3.2: node seqno, interface table with Multicast Hello seqno and two timers, neighbour table indexed by interface+address with Hello histories and txcost and three timers, source table indexed by prefix+plen+router-id with GC timer, route table indexed by prefix+plen+neighbour with selected flag and expiry timer, pending seqno requests), neighbour acquisition (§3.4: Multicast vs Unicast Hello, IHU with rxcost/interval, cost computation rules), routing table maintenance (§3.5: update processing, metric computation M(c,m) requirements, hold time, route selection constraints with hysteresis), sending updates (§3.7: periodic, triggered with urgency rules, feasibility distance maintenance before sending, split horizon), explicit requests (§3.8: route requests never forwarded, seqno requests forwarded with hop count, seqno increment rules, starvation/unfeasible/expiry request triggers)

**wire-format.md** — packet format (UDP port 6696, IPv6 multicast ff02::1:6, IPv4 multicast 224.0.0.111, Magic=42 Version=2, body+trailer, MTU constraints, jitter), address encodings (AE 0-3: wildcard/IPv4/IPv6/link-local IPv6), all 11 TLV types with field layouts (Pad1, PadN, AckReq with Opaque+Interval, Ack, Hello with U flag+Seqno+Interval, IHU with AE+Rxcost+Interval+Address, Router-Id 8 octets, Next Hop with AE, Update with P/R flags+Plen+Omitted+Interval+Seqno+Metric+Prefix, Route Request with AE+Plen, Seqno Request with Seqno+Hop Count+Router-Id), sub-TLVs (mandatory bit types 128-255 cause enclosing TLV to be ignored), stateful parser (default prefix per AE, current next-hop per address family, current router-id, state updated before mandatory sub-TLV check)

**costs-parameters-security.md** — Hello history maintenance (16-bit vector, undo history/fast-forward logic, timer at 1.5× interval), k-out-of-j for wired (recommended 2-out-of-3 C=96, cost=txcost when link up), ETX for wireless (beta from Multicast history, rxcost=256/beta, cost=256/(alpha*beta), ignores Unicast), hysteresis (smoothed metric ms(R), switch only when both m and ms improve), all recommended parameters table (Hello 4s, Unicast Hello disabled, IHU 12s, Update 16s, IHU Hold 3.5×IHU, Route Expiry 3.5×Update, Request timeout 2s doubling, Urgent 0.2s, Source GC 3min), interval encoding (16-bit centiseconds), router-id rules, security (completely insecure without extensions, Babel-MAC RFC 8967 recommended, Babel-DTLS RFC 8968 alternative, privacy concern for mobile nodes), extension mechanisms table (new TLV/sub-TLV mandatory/non-mandatory/new AE/trailer/version bump), route filtering (safe with infinite metric assignment, unsafe to reduce metric or lengthen prefix, default filters for link-local/multicast/loopback), stub implementations (no feasibility/source table needed, must answer acks/Hellos/requests, ~1000 lines C for IPv6-only stub)

</reference_index>
