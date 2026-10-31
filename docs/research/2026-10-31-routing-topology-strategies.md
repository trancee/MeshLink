# Routing and topology strategies for phone BLE meshes — 2026-10-31

Ticket: `.scratch/meshlink-v1/issues/04-routing-topology-strategies.md`.
Status: research input for a simulation comparison. Nothing here is a decision.

## Question

Which strategies should a simulation compare for MeshLink's unicast, mesh
broadcast, store-and-forward and neighbor selection? MeshLink targets the
provisional density classes (peers in radio range) low 2–10, medium 10–50 and
high 50–200, and meshes of at most about 1,000 nodes. The ticket asks for
behaviour at those densities, control overhead, convergence and energy.

## TL;DR

1. **On phones, topology is a bounded-degree graph that MeshLink builds itself.**
   AOSP has a fixed GATT link table: 7 entries up to Android 14, and 8–16 from
   Android 15. Both GATT roles and links that are still connecting share it
   (§2.1). cairn measured that a budget of 5–6 usable slots works on Android.
   iOS has no documented limit (**unverified**). At medium and high density, a
   node can therefore link to only a small fraction of the peers it hears.
   **Neighbor selection is a first-class protocol component, not a transport
   detail.**
2. **The broadcast advantage of MANET protocols is lost.** OLSR MPRs, Trickle
   suppression and Bluetooth Mesh managed flooding all assume that one
   transmission reaches every neighbor. Over GATT, each forward is a separate
   transmission on a separate link. Two consequences follow:
   - A flood costs about `N·(d−1)` link transmissions. An ideal spanning tree
     costs `N−1`. For a degree budget `d` of 4–6, flooding is therefore only
     about 3–5× the ideal, not a broadcast storm (§5, own derivation).
   - Everything that floods mesh-wide on a schedule costs `O(N²·d)` per period.
     That includes Babel full dumps, link-state announcements and BitChat-style
     neighbor lists. At N ≈ 1,000 this is likely infeasible (§5, back-of-envelope).
3. **Shortlist for simulation:**
   - **Neighbor selection:** {RSSI-strongest stable slots plus one rotating
     slot (cairn baseline); HyParView-style active/passive views with
     neighbor-overlap diversity}.
   - **Unicast:** {managed flood; BitChat-style scoped neighbor lists plus
     source route plus flood fallback; Babel distance-vector (the template's
     choice); AODV-style on-demand with expanding ring (as in Bluetooth Mesh 1.1
     directed forwarding)}.
   - **Broadcast:** {flood minus ingress with dedup and TTL; degree-based fanout
     subsetting; Plumtree eager/lazy}.
   - **Store-and-forward:** {sender-held only; binary spray-and-wait (L = 2–6);
     epidemic as an upper bound}.
4. **A Kotlin/JVM discrete-event simulation at the link level is reasonable.**
   It needs no PHY model at first and is calibrated from device-lab numbers.
   - It can later run the production common-code routing logic behind an
     injected clock and transport.
   - The ONE simulator (Java, GPLv3, last release 2015) has reference DTN
     routers but no connection caps.

## Sources

Primary sources were read unless marked *(metadata only)*. In that case only
the DOI and title were verified through Crossref, and claims attributed to the
work come from secondary descriptions and are flagged.

| ID | Source |
|---|---|
| RFC8966 | Babel, <https://www.rfc-editor.org/rfc/rfc8966> (§1.2 Limitations, App. B parameters) |
| RFC3561 | AODV, <https://www.rfc-editor.org/rfc/rfc3561> (§4 Applicability, §6.4 expanding ring, §10 parameters) |
| RFC7181 | OLSRv2, <https://www.rfc-editor.org/rfc/rfc7181> (§3 Applicability, MPR flooding) |
| RFC6206 | Trickle, <https://www.rfc-editor.org/rfc/rfc6206> (§1, §4) |
| RFC4838 | DTN architecture, <https://www.rfc-editor.org/rfc/rfc4838> |
| RFC9171 | Bundle Protocol v7, <https://www.rfc-editor.org/rfc/rfc9171> (App. A: custody transfer moved to BIBE) |
| RFC6693 | PRoPHET (Experimental), <https://www.rfc-editor.org/rfc/rfc6693> |
| RFC9159 | IPv6 mesh over BLE/IPSP, <https://www.rfc-editor.org/rfc/rfc9159> (route-over; routing protocol unspecified) |
| AOSP | `system/internal_include/bt_target.h`, `stack/gatt/gatt_utils.cc` (`gatt_get_max_phy_channel`), `stack/gatt/gatt_int.h`, main and `android14-release`, <https://android.googlesource.com/platform/packages/modules/Bluetooth/> |
| SIG-DF | Bluetooth SIG, *Bluetooth Mesh Directed Forwarding — Technical Overview* v1.0.0, 2023-09-19, <https://www.bluetooth.com/mesh-directed-forwarding/> |
| BITCHAT | bitchat Protocol Whitepaper v2.0, 2026-07-06, <https://github.com/permissionlesstech/bitchat/blob/main/WHITEPAPER.md> §4.2–4.5, §6 |
| HYPARVIEW | Leitão, Pereira, Rodrigues, *HyParView*, DI-FCUL TR-07-13 (DSN 2007), <https://asc.di.fct.unl.pt/~jleitao/pdf/dsn07-leitao.pdf> |
| PLUMTREE | Leitão, Pereira, Rodrigues, *Epidemic Broadcast Trees*, SRDS 2007, doi:10.1109/SRDS.2007.27, TR <https://asc.di.fct.unl.pt/~jleitao/pdf/srds07-leitao.pdf> |
| SNW | Spyropoulos, Psounis, Raghavendra, *Spray and Wait*, WDTN '05, doi:10.1145/1080139.1080143 *(metadata only)* |
| STORM | Ni et al., *The broadcast storm problem in a mobile ad hoc network*, MobiCom '99, doi:10.1145/313451.313525 *(metadata only)* |
| GOSSIP | Haas, Halpern, Li, *Gossip-based ad hoc routing*, INFOCOM 2002, doi:10.1109/INFCOM.2002.1019424 *(metadata only)* |
| BLESURV | Darroudi, Gomez, *Bluetooth Low Energy Mesh Networks: A Survey*, Sensors 17(7) 2017, doi:10.3390/s17071467 *(metadata only; MDPI returned 403)* |
| ONE | Keränen et al., *The ONE simulator for DTN protocol evaluation*, SIMUTools 2009, doi:10.4108/icst.simutools2009.5674 *(metadata only)*; code <https://github.com/akeranen/the-one> (GPLv3, last release v1.6.0 2015-10-27, routers incl. `SprayAndWaitRouter`, `EpidemicRouter`, `ProphetRouter`) |
| KALASIM | <https://github.com/holgerbrandl/kalasim> (Kotlin DES, MIT, v1.3.5 2026-04-10) |
| CAIRN | `../cairn` ADRs 0025 (Android GATT slot budget), 0031 (stable slots), 0033 (couriers), 0034/0036 (store-and-forward state), `docs/research/2026-10-23-ble-mesh-design.md`, `docs/agents/lessons.md` — device-lab evidence from a sibling project |
| TEMPLATE | `../MeshLink-template` `SPEC.md` §8, `docs/decisions/routing/routing-design.md`, `docs/decisions/model/mesh-size-limits.md`, `docs/explanation/template-design-review.md` |

## 1. Prior art in this workspace

- **TEMPLATE** chose a Babel-inspired distance-vector protocol with RSSI-derived
  quadratic link cost (SPEC §8.1–8.2). Its own design review says:
  - Neighbor selection under connection limits is "the missing architectural
    decision".
  - RSSI is not equally observable in central and peripheral roles.
  - Hop cost plus measured failures should come first.
  - Proactive and on-demand routing should be compared on the same workloads
    before choosing.

  Its connection-limit table (`mesh-size-limits.md`, e.g. "iOS typical 3–4
  central") has no cited source. Treat it as **unverified**.
- **CAIRN** measured on a 12–15-phone Android/iOS fleet:
  - Every phone was within 4 hops of every other by 120 s of a 900 s run.
  - With rotating eviction, the median link lived 27 s, the 90th percentile
    52 s, and no link lasted 300 s (ADR 0031).
  - Android slot exhaustion explained most fast status-133 failures (ADR 0025).

  cairn chose binary spray-and-wait couriers with L = 6 by default, a 24 h
  lifetime, and refuse-when-full without eviction (ADR 0033). Its links are
  contact-only, so its routing problem is narrower than MeshLink's.

## 2. Constraints that shape every strategy

### 2.1 Degree budget (verified for Android; unverified for iOS)

- AOSP `bt_target.h` on `android14-release` defines `GATT_MAX_PHY_CHANNEL 7`.
- On `main`, the definition is `GATT_MAX_PHY_CHANNEL 16` with
  `GATT_MAX_PHY_CHANNEL_FLOOR 8`. `gatt_get_max_phy_channel()` clamps the system
  property `bluetooth.core.le.max_number_of_concurrent_connections` into
  [8, 16].
- The TCB table `tcb[GATT_MAX_PHY_CHANNEL]` is shared by every GATT link (AOSP
  `gatt_int.h`).
- CAIRN ADR 0025 observed that pending dials and half-open links also hold
  entries. A usable budget of 5 (API ≤ 34) or 6 (API 35+) left headroom, and 3
  in a low-energy band.
- Other apps' GATT links on the same phone consume the same table (CAIRN
  lessons, Life360 case).
- **iOS:** CoreBluetooth documents no connection limit (CAIRN ADR 0025 "Open").
  This is **unverified**. It needs the platform-capabilities ticket or device
  measurement.

Consequence: a node has a degree budget `d` of roughly 3–6 links. That is below
the neighbor count at medium density and far below it at high density (10–200
peers in range).

### 2.2 No wireless broadcast advantage on the data path

- Classic MANET efficiency comes from overhearing:
  - OLSRv2 says MPR gains grow with density "compared to the classic link state
    algorithm" (RFC7181 §3).
  - Trickle suppresses a transmission when it has heard k consistent ones
    (RFC6206 §4).
  - Bluetooth Mesh managed flooding relays on the advertising bearer
    (SIG-DF §1.3).
- On GATT links, each relay is a unicast write or notification on one
  connection. Overhearing does not exist, and "send to all neighbors" costs `d`
  transmissions.
- An advertising-bearer broadcast exists. However:
  - Advertising payloads are small.
  - iOS restricts background advertising contents (CAIRN research §2.d).
  - Interop with Android extended advertising is fragile (CAIRN lessons, issue
    56).

  Its use for tiny mesh-wide announcements is an open question for the bearer
  ticket. It is **not assumed** here.

### 2.3 Slow, churny links

- AODV's default `NODE_TRAVERSAL_TIME` is 40 ms (RFC3561 §10).
- Babel's recommended Hello interval is 4 s, and it detects outages in 1.5–3.5
  Hello intervals (RFC8966 App. B).
- On phones, link setup plus authentication took about 1.1 s (CAIRN lessons),
  and links lived tens of seconds under rotation (CAIRN ADR 0031).

Route lifetimes therefore cannot exceed link lifetimes. Timers copied from
802.11 MANET defaults would be wrong. Every timer must be calibrated from
measured BLE values.

### 2.4 Asymmetric and partial link metrics

- RSSI is role-dependent and may be missing (TEMPLATE review).
- Babel needs bidirectional cost through Hello/IHU (RFC8966 §1, App. B).
- OLSRv2 expects "bidirectional additive link metrics" from an external process
  (RFC7181 §3).

Start with hop count plus observed delivery failures and queue pressure. Treat
RSSI as an input to neighbor selection, not a mandatory routing metric.

## 3. Candidate strategies

### 3.1 Neighbor selection and topology formation (the missing layer)

Goals:

- Keep the link graph connected with a small `d`.
- Avoid spending links on redundant local clusters.
- Repair partitions.
- Leave room for newcomers.

| ID | Strategy | Source basis | Notes |
|---|---|---|---|
| N0 | **Strongest-RSSI stable slots plus rotating slot(s)**: keep ⌊¾·budget⌋ links, rotate the rest by idleness | CAIRN ADR 0031 | Field-tested on about 15 phones. Risk at high density: nearest-neighbor choice builds geometric clusters with long diameters, and possibly partitions where clusters' strongest links stay internal. |
| N1 | **HyParView-style views**: a small symmetric *active view* (fanout + 1) held as live links; a larger *passive view* of known candidates promoted when an active link fails; periodic shuffles refresh the passive view | HYPARVIEW §1 ("small symmetric active view the size of the fanout+1"; "passive view of backup nodes") | Designed over TCP, with fast failure detection through the reliable transport. Maps directly to live GATT links (active) and recently heard advertisers or gossip-learned peers (passive). Difference: candidates are limited to radio range, not the whole system. |
| N2 | **Diversity-aware choice**: prefer a candidate whose advertised neighbor set overlaps least with the 1–2-hop set already reachable | Derived from OLSR 2-hop coverage idea (RFC7181 MPR selection) and BITCHAT §4.3 neighbor lists | **Own proposal, no primary evaluation.** Needs neighbor-list exchange, which costs bytes and has a metadata/privacy impact (BITCHAT §8 notes the neighbor list "hands a single sniffer the local adjacency graph"). |
| N3 | **Newcomer reservation**: always keep ≥ 1 slot free or rotating; advertise only while a slot is free | CAIRN ADR 0025 (advertise only with a free slot), ADR 0031 (≥ 1 rotating slot) | Orthogonal; combine with N0–N2. |

The simulation should measure, by density class: largest-component fraction,
partition-repair time, graph diameter, and the dial and connection churn each
policy causes.

### 3.2 Unicast

| ID | Strategy | Mechanism (source) | Fit |
|---|---|---|---|
| U1 | **Managed flood** | TTL, seen-cache dedup, relay jitter (SIG-DF §1.3; BITCHAT §4.2: TTL 7, LRU 1000 entries / 5 min, 10–220 ms jitter) | No control plane, robust to churn. Cost per message is about `N·(d−1)` link transmissions bounded by TTL. Baseline and fallback. |
| U2 | **Scoped topology plus source route, flood fallback** | Announcements carry ≤ 10 neighbor IDs (60 s fresh). A bidirectionally confirmed path is source-routed, otherwise flooded (BITCHAT §4.3, §4.5: announces every 4 s isolated, 15–30 s connected) | Proven in a deployed phone app (no published evaluation found). Mesh-wide announce flooding is O(N²·d) per period (§5), so it needs TTL scoping. |
| U3 | **Babel-style proactive distance-vector** | Feasibility condition plus sequence numbers for loop avoidance; Hello/IHU link sensing; periodic plus triggered updates; Hello 4 s, Update 16 s (RFC8966 §2.4–2.5, App. B) | Template's choice. RFC8966 §1.2 notes that periodic updates make it chatty "in large, stable networks". The per-period cost of full tables grows with N per link. Strong for small and medium meshes with many active pairs. |
| U4 | **On-demand (AODV-like)** | RREQ flood with expanding ring (TTL_START 1, TTL_INCREMENT 2, TTL_THRESHOLD 7), RREP unicast back, destination sequence numbers, route timeout (RFC3561 §6.4, §10). The same pattern is in Bluetooth Mesh 1.1 directed forwarding: PATH_REQUEST to all directed-forwarding nodes, discovery/forwarding tables (SIG-DF §2) | Zero control cost when idle. Discovery latency and a flood per new destination pair. Expanding ring helps when destinations are close. AODV assumes mutually trusted nodes (RFC3561 §4). |
| U5 | **OLSRv2 link-state with MPR** | HELLO 2-hop sensing, TC flooding by MPRs only (RFC7181) | **Deprioritised.** MPR savings depend on the broadcast medium (§2.2). Without it, MPR flooding reduces to forwarding-link selection, which U1/B2/B3 cover more cheaply. |
| U6 | **Hybrid: proactive within k hops, on-demand beyond** | Combines U3 or U2 with U4 | **Own proposal, not evaluated.** Fits a 1,000-node bound with small `d`. Simulate only if U3 and U4 each fail on a different side. |

### 3.3 Mesh broadcast

| ID | Strategy | Source | Notes |
|---|---|---|---|
| B1 | Flood to all links except ingress, dedup plus TTL plus jitter | SIG-DF §1.3; BITCHAT §4.2 | Cost about `N·(d−1)` transmissions. Reliability follows link-level reliability. |
| B2 | Fanout subsetting / probabilistic gossip | BITCHAT §4.2 (message-ID-seeded subset of about log₂(degree) links); GOSSIP *(metadata only; its probability-p forwarding is from secondary knowledge, unverified)* | With small `d`, log₂ d is about 2, so savings are modest and coverage risk is real. Simulate the coverage cliff. |
| B3 | **Plumtree**: eager push on tree links, lazy IHAVE on the rest, GRAFT/PRUNE repair | PLUMTREE §1 ("sends the message payload preferably via tree branches but uses the remaining links … for fast recovery") | Designed for reliable point-to-point overlays, which matches GATT. Payload cost is about `N−1`, plus small IHAVEs on non-tree links. Pairs naturally with N1 (HyParView is its intended membership layer). |
| B4 | Trickle-timed digest exchange for anti-entropy of broadcast or state | RFC6206 §1, §4 | Useful for reconciling missed broadcasts and S&F inventories across intermittent links. Suppression is weaker without overhearing. |

### 3.4 Store-and-forward

| ID | Strategy | Source | Notes |
|---|---|---|---|
| S0 | Sender-held outbox, retry when a route appears | BITCHAT §6 (outbox 100 messages / 24 h / 8 attempts) | Minimal privacy exposure, no relay storage. |
| S1 | **Binary spray-and-wait** with L copies | SNW *(metadata only)*; implemented in ONE `SprayAndWaitRouter` (`binaryMode`); BITCHAT §6 (start 4, cap 8, halve per handover); CAIRN ADR 0033 (L = 6, 24 h, quotas, tombstones) | Bounded copies and storage. Two independent phone designs converged on it. |
| S2 | Epidemic with summary-vector exchange | ONE `EpidemicRouter`; DTN arch RFC4838 | Delivery and latency upper bound for the simulation. Unbounded storage and airtime make it unsuitable for shipping as is. |
| S3 | PRoPHET (encounter-history predictability) | RFC6693 (Experimental; "prun[es] the epidemic distribution tree") | Needs encounter history, which has a privacy cost. Simulate only if S1 underperforms with recurrent-contact mobility. |

Custody: BPv7 moved custody transfer out of the base protocol into BIBE
(RFC9171 App. A). CAIRN keeps custody on the giver until the receiver accepts
(ADR 0033 rule 3). This is a design choice for the S&F ticket, not for routing.

## 4. Behaviour by density class (expected; to be simulated)

| Class | In range vs `d` ≈ 4–6 | Expected behaviour |
|---|---|---|
| Low 2–10 | Often ≤ `d`: near-complete local graph | Any strategy works. Flooding (U1/B1) is cheapest to build and nearly optimal. Proactive DV overhead is tiny. Partitions come from mobility, so S&F matters most here. |
| Medium 10–50 | Neighbors > `d` | Neighbor selection decides diameter and connectivity. Dial contention appears: CAIRN saw half of pairs never connect in a 12–15-phone fleet before slot budgeting (ADR 0025). Proactive DV is still affordable at mesh sizes ≤ about 100. |
| High 50–200 | Neighbors ≫ `d` | Risks: clustered graphs (N0), long diameters, advertising and scan-channel congestion, Android TCB exhaustion from inbound dials. Mesh-wide periodic floods (U2 announcements, U3 full tables) dominate airtime. Plumtree (B3) plus on-demand or scoped unicast look strongest. **Unverified until simulated and measured (P6).** |

## 5. Overhead and energy notes (back-of-envelope, own derivation)

Assumptions: N nodes, degree `d`, one forward per link except the ingress link.
These are link-level transmissions, not PHY packets.

- **Flood (U1/B1):** about `N·(d−1)+1` transmissions per message. With `d` = 4
  and N = 1,000 that is about 3,000, against `N−1` = 999 for a tree. Overhead
  ≈ `d−1`, which is bounded by the slot budget. For unicast, the ideal is h
  transmissions with h ≈ log₍d−1₎N ≈ 6–7, so flooding unicast wastes about 500×
  at N = 1,000. At N = 20 it wastes only about 10×.
- **Periodic mesh-wide announcements (U2, link-state):** N announcements, each
  flooded, cost about `N²·(d−1)` transmissions per period. At N = 1,000 and
  `d` = 4, that is about 3×10⁶ per 15–30 s, or about 3,000 forwards per node per
  period. Infeasible at that size, fine at N ≤ about 50. TTL scoping is mandatory.
- **Babel full-table updates (U3):** each node sends N route entries to `d`
  neighbors every Update interval (16 s by default). At N = 1,000 with about
  20 B per entry, that is about 20 KB per link per 16 s, or about 1.25 KB/s per
  link before triggered updates and fragmentation overhead. Plausible on a
  healthy GATT link but significant on a low-MTU or iOS-background link. The
  entry size is an assumption.
- **On-demand (U4):** one RREQ flood per new destination pair, plus a RREP of h
  hops. The cost scales with the number of active pairs × route-breaks, and
  route-breaks follow link lifetimes (§2.3).
- **Energy proxy:** for BLE, cost is dominated by connection events, scanning
  and advertising duty, plus bytes on air. Per-node maximum load matters more
  than the mean because hotspots drain first. The energy-methodology ticket
  should supply measured mJ per KB and per connection-hour (see
  `../cairn/docs/research/2026-10-05-energy-measurement-methodology.md`). None
  are assumed here.

## 6. Shortlist and simulation proposal

### 6.1 Shortlist

- Neighbor selection: **N0** (baseline), **N1 + N2**, each with **N3**.
- Unicast: **U1** (baseline), **U2** (TTL-scoped), **U3**, **U4**. U6 only if
  warranted.
- Broadcast: **B1**, **B2**, **B3**.
- Store-and-forward: **S0**, **S1** (L ∈ {2, 4, 6}), **S2** (upper bound only).

### 6.2 Simulator choice

- **Recommended: a throwaway Kotlin/JVM link-level discrete-event simulation**
  in a prototype module.
  - Nodes have positions and a mobility model. RSSI comes from a path-loss plus
    shadowing model.
  - Links are capacity-limited FIFO queues with measured setup latency, failure
    probability, throughput and supervision-drop rates.
  - Per-platform slot budgets are configurable.
  - There is no PHY collision model at first. Add a per-neighbourhood airtime
    contention factor for the high-density class.
  - A priority-queue event loop is small to hand-roll. KALASIM (MIT) is an
    option but needs E3 approval.
  - Reasonable size: 1,000 nodes × hours of simulated time is
    O(10⁷–10⁸) events per run. That is feasible on the JVM, but the event count
    is an **estimate**.
  - Main advantage: the same Kotlin common code (routing state machines behind
    an injected clock and transport) can later run in the simulator. Simulated
    and shipped logic then match.
- **ONE** (Java, GPLv3) is useful only as an external reference for S1/S2/S3
  delivery curves on contact traces. Its `NetworkInterface` has no connection
  cap (no such setting found in `core/NetworkInterface.java`), and it is GPL.
  Do not link it into Apache-2.0 code.
- **ns-3 / OMNeT++:** packet-level fidelity is not needed for this comparison.
  The availability and maturity of BLE models was **not verified**.

### 6.3 Metrics

1. Delivery ratio (unicast, broadcast coverage, S&F within lifetime).
2. Latency p50/p95/p99 (send→deliver; route acquisition for U4).
3. Link transmissions and bytes per delivered message, split into control and
   data.
4. Per-node load (mean and max): bytes, connection events, dials. Used as the
   energy proxy and the hotspot measure.
5. Topology: largest-component fraction, partition count, diameter,
   partition-repair time, dial and connection churn per node-minute, slot
   occupancy.
6. Convergence: time to a usable route after join, leave or link break; time to
   detect a broken route.
7. Duplicates delivered to the application, and seen-cache sizes.
8. S&F: storage occupancy (entries and bytes per node), copies per delivered
   message, expiries.
9. Memory: route-table and cache sizes vs N.

### 6.4 Scenarios

- **Density × size:** {low, medium, high} × N ∈ {10, 50, 200, 1,000}, with
  `d` ∈ {3, 5, 6, 8}.
- **Platform mix:** Android only, iOS only, 50/50. iOS background nodes are
  intermittent: about 10 s wake windows (CAIRN ADR 0003 via research §2.d).
- **Geometry and mobility:**
  - static crowd (hall/festival);
  - pedestrians with random waypoint;
  - group mobility (field teams);
  - corridor or chain (thin, long diameter);
  - partition-and-merge (two groups walk apart and back).
- **Churn:** join and leave rates. Bluetooth toggles. App backgrounding.
- **Traffic per preset:**
  - chat: sparse unicast, many pairs;
  - emergency: broadcast bursts from few sources;
  - proximity discovery: presence only;
  - local multiplayer: high-rate unicast or broadcast in a small diameter;
  - field teams: mixed, with S&F.
- **Calibration:** link setup time, failure rates, throughput and lifetimes from
  the device lab (29 Android phones, local iPhones). Use CAIRN numbers until
  MeshLink's own measurements exist.

## 7. Open questions (for new tickets or HITL)

1. **iOS concurrent-connection limit and role mix** (central vs peripheral
   links): undocumented. Needs measurement. Blocks realistic `d` for iOS.
2. Is an **advertising-bearer** micro-broadcast (presence or digests) viable
   across Android↔iOS, foreground and background? It would restore some
   broadcast advantage for control traffic.
3. **Neighbor-list exposure:** N2 and U2 need neighbor IDs on the wire. Under
   `classical` and `post-quantum` profiles, are they encrypted per hop, and what
   adjacency leaks to a passive sniffer? (BITCHAT §8 flags this as its weakest
   point.)
4. **Metric:** hop count plus failures vs RSSI-weighted. Which observations
   exist per role on each platform?
5. **Addressing and identity size** for route entries and dedup keys. This
   affects every overhead figure in §5.
6. **Trust model for routing control:** AODV assumes mutually trusted nodes
   (RFC3561 §4). What do signed origin statements protect, given the template
   review's caveat?
7. **Energy cost per connection-hour and per KB** on the lab phones, needed to
   turn simulated load into battery claims.

## 8. Limitations

- SNW, STORM, GOSSIP, BLESURV and ONE were verified only bibliographically
  (Crossref). Their full texts were not retrieved (403 or wrong file). Mechanism
  claims for spray-and-wait rest on the ONE implementation and on BITCHAT and
  CAIRN descriptions. Probabilistic-gossip parameters are not cited.
- No peer-reviewed evaluation of a multi-hop BLE GATT mesh **on phones** at
  medium or high density was found in this pass. BitChat publishes design
  parameters, not measurements. CAIRN's fleet data covers ≤ 15 phones and
  contact-only links.
- All overhead figures in §5 are back-of-envelope with stated assumptions and
  are not measurements.
- The Android TCB limit is verified from AOSP source. OEM stacks may differ.
  CAIRN saw 8, 10 and 16 on fleet phones that print it.
- The iOS connection limit is unverified.
