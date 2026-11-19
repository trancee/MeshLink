# 0008: Routing, topology and density adaptation

Status: accepted (2026-11-02; ticket "Which routing and topology strategy should MeshLink use?"). Version: 13 (amended 2026-11-02, ADRs 0010 and 0013; 2026-11-03, ADRs 0014 and 0015; 2026-11-08, energy measurements, fake identities, ADR 0016 and the stage F simulation; 2026-11-09, iOS Power Profiler; 2026-11-19, bridge rule and diversity replacement built; 2026-11-19, RSSI floor and former lists built; 2026-11-19, density classes built). All values are provisional until device measurements confirm them (see Risks).

## Context

MeshLink phones hold a few BLE links each. Messages to one node (unicast) and to the whole mesh (mesh broadcast) must cross several hops. This has to work at a picnic and at a festival, and while a phone walks from one to the other: local density differs between nodes and changes over time.

The evidence is a throwaway discrete-event simulator (Kotlin standard library only), on branch `prototype/routing-sim`.

- It ran stages A to E plus rechecks: more than 12,000 runs with N up to 1,000, five mobility models, Android-only and half-iOS mixes, and five dynamic scenarios: arrival, departure, a dense core with a sparse edge, a surge and threshold oscillation.
- It was calibrated with the device bake-off (ADR 0009).
- All numbers are **simulated**, not device measurements.

The summary, aggregated data and HTML reports are in [the routing evidence](../evidence/2026-11-routing-simulation/README.md). Every parameter, with its value, the stage that set it, its evidence and whether it was swept or assumed, is in [v1 parameters](../evidence/2026-11-routing-simulation/v1-parameters.md).

Pre-registered criteria, amended once in the open:

- **Delivery within a connected component:** within 0.5 pp of flooding in the same cell at low density, and still 99% where flooding reaches it; 99% at medium density; 95% at high density.
- **Idle control traffic:** at most 10% of link capacity.
- **Ranking:** among passing strategies, the lowest busiest-phone load, then the lowest p95 latency.
- **First message:** p95 at most 1 s classical and 1.5 s post-quantum, over 3 hops or fewer.
- **Tag rotation:** at most +10% transmissions and −1 pp delivery, per density class.

Results for the v1 configuration:

- **Criteria:** it meets them in 16 of 16 dynamic scenarios and 37 of 40 fixed-density cells. Flooding everywhere meets 16 and 36.
- **Load:** the busiest phone carries about 40% less than with flooding.
- **First-message p95:** at most 0.83 s post-quantum and 0.22 s classical.
- **The three failing cells** each come from one or two lost messages in small samples.

## Decision

1. **Topology: HyParView-style active and passive views, plus a diversity preference** (N1+N2).
   - **Link budget d:** 6 at low density, 5 at medium and high.
   - **Newcomer reservation:** one link slot stays free for newcomers.
   - **Bridge rule:** a newly heard peer from outside the known 1–2-hop neighborhood is dialed first, at most once per 60 s. It repairs a split mesh in 6–7 s instead of 11–26 s.
   - **iOS incoming links:** an iPhone accepts at most 3 incoming links, pending the per-version device sweep (ADR 0009).
2. **Local density:**
   - **Estimate:** each node estimates its own local density alone. It counts the distinct advertisement identifiers heard in the last 10 s plus linked peers not heard, smoothed with weight 0.3 per scan.
   - **Classes:** low under 10 peers, medium 10–50, high over 50.
   - **Switching:** up at 12.5 and 62.5, down below 8 and 40, with at least 60 s in a class. A phone standing at a threshold switches 0.6–1.8 times an hour.
   - **Adaptation:** switching changes only the node's own forwarding behavior, never the wire format. Every node receives and relays every frame type, so nodes in different classes interoperate.
3. **Per-class behavior:**

   | Setting | Low | Medium | High |
   |---|---|---|---|
   | Unicast | flood; the destination acknowledges; retry 2 s doubling to 30 s, and again on each new link | U6: a 3-hop flood plus a mesh-wide route request at once; known route → forward; no answer → hold. The delivery ack refreshes the route; one resend if no ack. Retry 2 → 30 s | as medium; retry 5 → 60 s; no retry on new links |
   | Mesh broadcast | flood | Plumtree (eager push and lazy IHAVE, graft after 500 ms) | Plumtree |
   | Catch-up | on every new link, both sides exchange the message IDs of the last 60 s and graft what they lack | same | same |
   | Scan and advertise | scan every 2 s, fast advertising | every 4 s, fast | every 6 s, slow |
   | Store-and-forward | eager: retry on every new link | eager | backoff only |

4. **Routes:** the metric is hop count with destination sequence numbers (AODV-style). Active routes expire 30 s after last use.
5. **Message lifetime and store-and-forward:**
   - **Lifetime:** at least 120 s; config validation rejects anything shorter, because clock skew of up to ±30 s is tolerated.
   - **Expired messages:** never forwarded or held.
   - **Holding:** a node holds up to 100 messages, and takes one only if it has at least 10 s of lifetime left.
   - **Hop limit:** one byte, default 255.
6. **Wire sizes and rotation:**
   - **Routing tag:** 8 B.
   - **Message ID:** 12 B.
   - **Rotation:** routing tags and advertisement identifiers rotate every 15 min, in step with the BLE address (ADR 0001). Each node uses its own phase, and tags are accepted for ±1 epoch.
   - **Cost at 15 min:** delivery −0.09 pp and transmissions +3.0% at worst, so it is within budget. The shortest in-budget period was 5 min.
7. **Long messages:** reassembled at every hop. Passing fragments straight on (cut-through) was faster but lost 2.5–3.9 pp of delivery under churn.
8. **Presets:**
   - The default is automatic.
   - An app may pin a class. Pinning the low class (flood everywhere) gives the low-latency preset: unicast p95 of about 0.35–0.62 s, for 2–4× the busiest-phone load.
   - Presets set limits and bias but don't fix a class (ADR 0007).
   - The class and switch events appear in diagnostics only.

## Alternatives

- **Strongest-signal neighbor slots (N0):** formed a larger connected group in only 6 of 360 cells (N1+N2: 216) and gave a longer mesh diameter.
- **Unicast:**
  - Scoped link state (U2) behaves like flooding, with more control traffic.
  - Babel-style distance vector (U3) could not finish at N = 1,000 and was the most sensitive to tag rotation.
  - AODV-style routing alone (U4) has a p95 of 9.5 s at low density.

- **Broadcast:**
  - Probabilistic gossip (B2) reached only 62% in its worst cell.
  - gossipsub passed, but Plumtree was chosen for the adaptive mode.

- **Flooding everywhere:** passes as many cells, but at about 1.7× the busiest-phone load and more radio time. It ships as the pinned-low preset instead of the default.
- **A single fixed configuration:** the best one passed 15 of 16 dynamic scenarios.
- **Rejected tweaks:**
  - an iOS-aware relay preference: no delivery gain, +29 ms first-message p95;
  - a sparse sender always flooding its own messages: no measurable effect;
  - keeping routes alive during a conversation, or caching reverse paths: about 2× the load;
  - a shorter graft timeout: no effect;
  - iPhones stopping advertising when full: it hid them from newcomers.

- **Mesh-wide density agreement:** not needed. Local estimates switched within 45–57 s (p90) of a real change.

## Risks

- **Simulated, not measured:**
  - the power index is a model assumption;
  - iOS background wake windows are modeled, not verified;
  - many timers and capacities are assumed rather than swept (marked in [v1 parameters](../evidence/2026-11-routing-simulation/v1-parameters.md)).

  Device measurements may change the defaults.

- **Unicast p95 at 1 s or less** is met in only 30 of 56 medium and high cells. It is about 6.5 s at N = 1,000, and 1.6 s at high density with the bake-off calibration. This goal is reported, not gated.
- **Fake identities (Sybil):** at 30% fake identities, every topology collapses, with or without the defenses tested in stage S. Open and unsecured meshes have no routing defense in v1 (see the 2026-11-08 Sybil amendment).
- **Staying in step with the BLE address:** the operating system controls the address, so how MeshLink matches its rotation, especially on iOS, is open. The discovery and advertising ticket decides it.
- **Deliberate message-ID reuse** by a malicious node is a threat-model question at any size.

## Consequences

- The protocol spec defines one wire format for all classes: a routing header with the 8 B tag, 12 B message ID, hop limit and lifetime; route request and reply; Plumtree IHAVE and graft; catch-up; acknowledgments; fragments.
- The modules ticket maps unicast, broadcast, store-and-forward and receipts to modules and presets.
- Diagnostics expose the local density class and switch events (ADR 0007).
- Reproduce with `prototype/routing-sim`; the commands are in the evidence README.

## Amendments

2026-11-02, discovery measurements (ADR 0010):

- **Rotation (decision 6):** routing tags and advertisement identifiers rotate every 5 min, not 15 min. Android's OS rotates the address every 7–9 min on the Pixel 6, so a 15 min epoch could not stay in step. The simulation found 5 min within budget. Each node keeps its own phase; tags are accepted for ±1 epoch. On iOS the address cannot be kept in step (ADR 0010 decision 7).
- **Bridge rule (decision 1):** skips sightings that resolve to a known peer through its resolving key (gated meshes). Reading the identifier of a backgrounded iPhone counts against the same limit of one dial per 60 s.
- **Local density (decision 2):** the estimate also counts distinct addresses of identifier-less MESH sightings (backgrounded iPhones). Background iOS scanners restart every 10 s so the 10 s window holds. A node that rotates inside the window counts twice for that window, about 3% at 5 min.
- **Risk "Staying in step with the BLE address":** resolved by ADR 0010.

2026-11-02, message lifecycle ([ADR 0013](0013-message-lifecycle-and-receipts.md)):

- **Unicast ack (decision 3):** the destination's ack is the end-to-end delivery receipt, sent once the app takes the message and batched by message ID. Duplicates are acknowledged again. Running out of retries does not fail a message; it expires at its expiry plus 30 s.

2026-11-03, store-and-forward ([ADR 0014](0014-store-and-forward.md)):

- **Lifetime (decision 5):** at most 1 h.
- **Holding (decision 5):** the 100-message quota is for holding for others, with at most ¼ per incoming link and a byte cap from the message-size decision. Mesh broadcasts are held too. Delivered messages are purged through tombstones.
- **Catch-up (decision 3):** also offers held broadcasts, held unicasts as {message ID, destination tag} and tombstones. Not yet simulated; a routing-simulation rerun must confirm the cost.

2026-11-03, message size ([ADR 0015](0015-message-size-and-compression.md)):

- **Large messages (decision 3):** unicast above 4 KiB is never flooded at any density. It goes over a direct link or a route found by route request; at low density the origin sends a route request first. Relays forward but never hold large messages; only the origin retries them. Not yet simulated; the catch-up airtime simulation covers it.

2026-11-08, energy measurements ([evidence](../evidence/2026-11-energy/README.md)):

- **Energy model (Risks, "the power index"):** an absolute model in mW replaces the power index (0.6 × scan duty + 0.4 × advertising duty). Coefficients from one Pixel 6 Bluetooth rail, above baseline: scanning 55 mW × scan duty, advertising 0 (under the ±2 mW noise), 1 mW per idle link, 0.25 mJ per relayed 200 B message, 0.75 mJ/KiB bulk over CoC and 0.83 mJ/KiB over GATT. They compare presets; they are not a battery guarantee. iOS is reported as bounds only: foreground components stay under about 50 mW of whole-phone power, and combined medium-density discovery costs 18–53 mW.
- **Scan and advertise (decision 3):** every class scans in start/stop windows with a period of at least 6 s; no class scans continuously. AOSP rejects more than 5 scan starts per 30 s, so a 2 s on / 2 s off schedule lost a third of its bursts, and continuous filtered scans fell to baseline after 2–5 min with the screen off. Interim schedule: low and medium 4 s on / 4 s off (duty 0.5), high 4 s on / 8 s off (duty 0.33). The table's "every 2 s / 4 s / 6 s" was read as duty 1, ½ and ⅓. The power-policy ticket sets the final per-class schedule by rerunning the simulation with the energy model.
- **Background duty:** the operating system sets it on both platforms. Android Doze stretched the 4 s on / 4 s off timer to 30 s gaps (duty 0.12) overnight; iOS suspends the app about 30 s after locking. Presets cannot promise background discovery latency.

2026-11-08, fake identities ([Sybil evidence](../evidence/2026-11-sybil/README.md)):

- **Neighbor selection (decision 1):** unchanged; v1 adds no routing-layer Sybil defense. Stage S (simulated, medium N = 200, 10 seeds) tested peer scoring by usefulness (D1), probation slots for unproven inbound links (D2) and confirmed neighbor lists (D3), alone and combined, against fakes that forward nothing and fakes that relay floods but drop unicast. At 30% fakes the honest largest group stayed at 0.02–0.03 in every arm (criterion 0.8), because a free identity refills every freed slot. D2 cost honest newcomers (10%: 0.90 → 0.82). Proof of work was rejected on paper. The realistic budget of 3 radios × 20 links left the mesh at 0.99. Gated meshes remain the availability answer.

2026-11-08, routing metadata exposure ([ADR 0016](0016-routing-metadata-exposure.md)):

- **Routing tags (decision 6):** public tags derive from the certified static key; after the first message, conversations use per-epoch conversation tags, so routes are per conversation, not per node. Not simulated; the catch-up airtime simulation must confirm the route-discovery cost.
- **Neighbor lists and shuffles (decision 1):** entries are 8 B on-air advertisement identifiers, sent to direct neighbors only and never forwarded.

2026-11-08, store-and-forward, conversation routes, lookups and power ([stage F evidence](../evidence/2026-11-routing-simulation/README.md#stage-f-store-and-forward-conversation-routes-lookups-and-power), simulated):

- **Catch-up, large messages and per-conversation routes (decision 3):** confirmed within budget. The final arm adds full catch-up, conversation tags, certificate lookups and large messages to v1. It passes every criterion in 9 of 10 main cells. The tenth fails on one lost message, which the v1 reference loses too. Idle control is at most 0.43% of capacity. The busiest phone sends at most 4.2 kB/s and uses at most +1.9 mW over v1. Two of the pass/fail rules were changed after the first results; the evidence labels both changes.
- **Scan schedule (decision 3):** two policies per class, as start/stop windows (seconds on / off):

  | Class | Balanced | Responsive |
  |---|---|---|
  | low | 4/4 (duty 0.5) | 4/2 (0.67) |
  | medium | 4/12 (0.25) | 4/8 (0.33) |
  | high | 4/16 (0.2) | 4/12 (0.25) |

  - Low scans more than medium. A newcomer is in the low class until its first estimate, so how fast it joins depends on the low schedule. Every slower low schedule raised the 95th-percentile join time by 1.45× or more.
  - Medium and high join times barely depend on the phone's own schedule (0.99–1.03× the interim schedule).
  - Balanced cuts mean energy from 24–34 mW to 17–22 mW in the dynamic scenarios, with join time 0.99–1.04× the interim schedule. Responsive costs 21–27 mW and joins in 0.82–0.88× the time.
  - Advertising is unchanged.
- **Background (Risks):** with half the phones in the background, simulated delivery stays at or above 99.87%. Join p95 rises to 13–17 s, from about 7–8 s in the foreground. Background cost is about 12 mW on Android; iOS figures are bounds only.
- **Route-request rate limit (Risks):** none in v1. A phone sending 1 certificate lookup per second for random NodeIds cost other phones 0.00–0.02 pp of delivery and raised the busiest honest phone's load by 10–16%, under the adoption thresholds of 1 pp and 25%.

2026-11-09, iOS Power Profiler ([evidence](../evidence/2026-11-energy/README.md#ios-power-profiler-iphone-12-mini-run)):

- **Energy model (Risks, "the power index"):** iOS now has a whole-phone figure instead of bounds only. On the iPhone 12 mini with the screen on, combined medium-density discovery in the foreground costs about 5–80 mW (three sessions; 4 of 4 blocks positive in session 2). Background discovery is not resolvable with Power Profiler: median about 0, at most about +17 mW in 3 of 4 blocks. The energy model keeps no iOS component coefficients; one phone, screen on.
- **Background (Risks):** this replaces "iOS figures are bounds only" in the stage F amendment. iOS still owns background duty, so presets promise neither background latency nor background cost on iOS.

2026-11-19, bridge rule and diversity replacement built (ticket 35b; host simulation in `VirtualAir`):

- **Bridge rule (decision 1):** "newly discovered" means its current identifier was first heard 5 to 13 s ago, or heard again after at least 60 s unheard, and no linked peer's neighbor list names it. The 5 s settle is new. Each 5 min identifier rotation is heard before any neighbor list names the new identifier, so without the settle every rotation looked like a newcomer two hops away. That caused dials, evictions and lasting churn: about 110 link ends per node in 1 h in an 8-node mesh, against about 7 without the rule. A full node frees its most redundant link, if any link has redundancy, and dials the bridge into that slot. Once kept at the budget, the bridge frees the most redundant other link. The relaxed RSSI floor (−88 dBm) is deferred to ticket 35d.
- **Neighbor lists (decision 1):** a node also resends its list when a linked peer's first list arrives or names a new identifier (spec "Neighbor list"). Neither the settle nor the resend alone stopped the churn; together they keep the 8-node mesh at 5 to 7 link ends per node.
- **Diversity replacement (decision 1):** only a candidate first heard at least 5 s ago may replace a link, for the same reason as the settle.
- **Degree (decision 1, "pending dials count"):** while its own bridge or replacement dial is in flight, a node accepts one bearer beyond its budget. Two full nodes that bridge to each other at once otherwise each refused the other with `RESOURCE_LIMIT`, which also stops both from redialing. In 100 split-mesh runs, 9 had not repaired 40 s after the move and 2 took 31 s. With the slack, all 100 repaired 13 s after the move. The duplicate-link rule, or freeing the most redundant link once the dial is kept, brings the node back within its budget.

2026-11-19, RSSI floor and former neighbor lists built (ticket 35d; host simulation in `VirtualAir`):

- **RSSI floor (decision 1):** the −85 dBm dialing floor and the bridge rule's relaxed −88 dBm floor apply to a smoothed signal, not one sighting. Each sighting moves it 0.25 of the way to its own value, as proximity does. The smoothing restarts whenever the candidate's first-heard time does: a new identifier, or 60 s unheard. One weak sighting then can't hide a strong peer, nor one strong sighting admit a weak one. The owner chose this over the latest sighting alone.
- **Former neighbor list reuse (decision 1):** a closed link's last list counts for 120 s in the bridge test ("no linked peer's list names it") and in the count of lists naming a candidate. It doesn't count in a link's redundancy, which describes current links. A peer that links again replaces its former list at once.

2026-11-19, density classes built (ticket 36; host simulation in `VirtualAir`):

- **Scan schedule (decision 3):** `Responsive` low scans 4 s on / 2.5 s off, not 4/2 (duty 0.62). Scan starts then stay 6.5 s apart, under AOSP's limit of 5 starts per 30 s (library rule R11). The owner chose this over 4/2. `Balanced` and the other classes are unchanged.
- **Estimate (decision 2):** a node estimates at the end of each scan window. One estimate may move it two classes, from low to high or back.
  - An identifier-less address that belongs to a linked peer, such as a linked backgrounded iPhone, counts once, as that peer.
  - An identifier-less address that fails vetting (its capability record carries no advertisement of the mesh) no longer counts.
  - The node resolves each heard identifier again when it estimates. A peer that links during the window is heard before its neighbor list resolves its identifier, so it counted twice: as an identifier and as a linked peer not heard. A 6-node cluster's first estimate was then about 10, which held every node at medium for the 60 s dwell.
- **Link budget (decision 1):** a switch that lowers d closes no link at once. A node holding the new d frees a random link after 30 s, as any full node does for newcomers. The passive view holds 4·d and drops random entries once d shrinks.
- **Adaptation (decision 2):** in `VirtualAir`, a low and a medium node exchange every cleartext link frame type on one link, and a medium node sends its handshake, hourly rekeys and next resolving key over 25 h. `BUSY` has no sender yet, so it isn't exchanged. The 2 → 70 → 2 node mesh keeps each node's scan schedule and peer count within its class's settings.
