# Which routing and topology strategies suit phone BLE meshes?

Type: research
Status: resolved

## Question

Survey literature and implementations: managed flooding with dedup/TTL, gossip, Babel/distance vector, AODV/on-demand, OLSR, DTN/epidemic and spray-and-wait (for store-and-forward), and neighbor selection/topology formation under per-phone connection limits. Assess behavior at the provisional density classes and mesh sizes, control overhead, convergence, energy. Output: cited shortlist of candidates for a simulation comparison.

## Answer

Phone BLE meshes are bounded-degree graphs that the library must build itself:
- AOSP shares one GATT link table across both roles and pending dials. It has 7 entries up to Android 14 and 8–16 from Android 15.
- cairn found 5–6 usable Android slots.
- iOS documents no limit (unverified).

So neighbor selection under a degree budget of about 3–6 is a first-class protocol component.

GATT links also lose the broadcast advantage that OLSR MPRs, Trickle and SIG managed flooding rely on:
- A flood costs about N·(d−1) link transmissions, only about 3–5× an ideal tree.
- Any periodic mesh-wide flood (full Babel tables, link-state or BitChat-style neighbor lists) scales as O(N²·d) per period. That is likely infeasible near 1,000 nodes without scoping.

Shortlist for simulation:
- **Neighbor selection:** RSSI-strongest stable plus rotating slot (cairn baseline) vs HyParView-style active/passive views with neighbor-overlap diversity, both with newcomer reservation.
- **Unicast:** managed flood, TTL-scoped neighbor-list source routing with flood fallback (BitChat), Babel distance-vector (template), AODV-style on-demand with expanding ring (as in SIG Mesh 1.1 directed forwarding). OLSR is deprioritised.
- **Broadcast:** flood minus ingress, degree-based fanout subsetting, Plumtree.
- **Store-and-forward:** sender-held, binary spray-and-wait (L = 2–6), epidemic as an upper bound.

Recommended tool: a throwaway Kotlin/JVM link-level discrete-event simulation calibrated from device-lab link setup, lifetime and throughput. The ONE is GPLv3 and has no connection caps. The note proposes metrics and density × size × mobility × preset scenarios.

Findings: [`docs/research/2026-10-31-routing-topology-strategies.md`](../../../docs/research/2026-10-31-routing-topology-strategies.md) (merged into `main` from `research/routing-topology-strategies` on 2026-11-01).
