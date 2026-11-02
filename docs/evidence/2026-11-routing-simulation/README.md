# Routing and topology simulation — 2026-11

Ticket: `.scratch/meshlink-v1/issues/14-routing-topology-simulation.md`.
Status: evidence for the routing ADR. The v1 configuration it supports is accepted as provisional.

**Every number on this page is simulated.** It comes from a throwaway link-level simulator, not from phones. Device facts appear only as calibration inputs, and their source is named. Under Constitution T9, a simulator result is not hardware proof.

The full v1 parameter list is in [`v1-parameters.md`](v1-parameters.md). Terms follow [`GLOSSARY.md`](../../../GLOSSARY.md).

## Question

Which routing and topology strategy should MeshLink use?

The question covers four layers:

- neighbor selection (which peers a phone links to);
- unicast (one phone to another);
- mesh broadcast (one phone to everyone);
- store-and-forward and message lifetime rules.

The strategies under test are defined in the [routing research note](../../research/2026-10-31-routing-topology-strategies.md) (N0–N3, U1–U6, B1–B4). Gossipsub was added in stage B.

## Answer in short

- **Neighbor selection:** N1+N2 (HyParView-style active and passive views with a diversity preference), with N3 newcomer reservation. Degree d = 6 / 5 / 5 at low / medium / high density. A rate-limited bridge rule (one per 60 s) repairs partitions.
- **Density is local and dynamic.** Each phone estimates its own density class from its own scans and links. There is no mesh-wide coordination and no wire-format change.
- **Unicast:** flood at low density. At medium and high density, use U6 (3-hop data flood with route discovery) with a full-range route request (F) and a delivery ack that refreshes the route (K2).
- **Broadcast:** flood (B1 behavior) at low density, Plumtree (B3) at medium and high density. Every phone runs catch-up (CU) on each new link.
- **Lifetime and store-and-forward:** minimum message lifetime 120 s, minimum hold 10 s, hold capacity 100 messages.
- **Pinned low class:** an optional low-latency preset that floods everywhere.
- **Security costs (stage E):** the shortest rotation period within budget is 5 min. The owner chose 15 min (2026-11-02), also within budget, to stay in step with the BLE address rotation (ADR 0001). Keep the routing tag at 8 B. Shorten the message ID to 12 B. Reassemble long messages at every hop. The post-quantum XXhfs link handshake fits the latency budget.

v1 meets the pre-registered criteria in 16 of 16 dynamic cells and 37 of 40 fixed-density cells. Flooding everywhere meets them in 16 of 16 and 36 of 40. The aim of unicast p95 ≤ 1 s at medium and high density is met in only 30 of 56 cells. See [Final criteria check](#final-criteria-check) and [Limitations](#limitations).

## Simulator model

The simulator is a hand-rolled discrete-event simulator in Kotlin/JVM, standard library only. Strategy code has no JVM-only APIs. The full model description is in `prototypes/routing-sim/README.md` on branch `prototype/routing-sim`.

The model is link-level, not packet-level. There is no radio collision model.

| Part | Model | Status |
|---|---|---|
| Radio | RSSI from log-distance path loss (exponent 3, 40 dB at 1 m), shadowing σ 4 dB per pair, scan noise σ 2 dB, sensitivity −88 dBm (about 40 m range) | assumption |
| Density | Peers in radio range. The area is sized per cell for a mean of 6 (low), 25 (medium) or 100 (high) peers. Classes: low 2–10, medium 10–50, high 50–200 | provisional classes from the ticket |
| Mobility | Static crowd; random waypoint 0.8–1.8 m/s; groups of 8 within 15 m; corridor 10 m wide; partition and merge. Stage C adds venue scenarios: arrival, departure, dense core with sparse edge, surge, threshold | assumption |
| Scanning | A 2 s tick. Each advertiser in range is heard with probability 0.8 per scan (0.5 for slow advertising). Only phones with a free slot advertise (N3) | assumption |
| Link setup | Connect U(0.7, 1.2) s, Noise handshake, one round trip. Failure 0.3 on the first attempt, 0.1 on a retry, plus contention | calibrated, provisional |
| Links | Capacity-limited FIFO queue per direction, delivery after half a round trip | calibrated |
| Contention | Throughput × 1/(1 + peers/100). Setup failure + peers/400, capped at 0.6 | **assumption** |
| Link drops | Exponential hazard, mean 45 s, fitted to cairn link lifetimes | fitted |
| Churn | Each phone leaves after Exp(10 min), stays away 30 s, rejoins fresh | assumption |
| iOS background | Half the phones are iOS in "half-iOS" cells. Half of those are in the background: awake 10 s every 60 s; asleep means no links, no scans, no advertising | **assumption, unverified on devices** |
| Sizes | Routing tag 8 B, message ID 16 B, frame header 4 B, 8 B per identifier in neighbor lists and shuffles | ticket defaults in stages A–D; tag and message ID swept in stage E |
| Traffic | Chat: Poisson, mean gap 240 s per phone, to 2 near and 1 far contact, 256 B payload + 24 B. First message of a pair: Noise IK overhead +96 B classical, +2,396 B post-quantum (half the pairs). Broadcast: one per 15 s mesh-wide, 256 B. Per-hop processing 2–10 ms | assumption |
| Duration | 15 min simulated, measured after a 2 min warm-up. Dynamic scenarios 15–20 min. Long-lifetime runs 60 min | justified by a warm-up time series (the largest group settles within about 60 s) |
| Power | Index = 0.6 × scan duty + 0.4 × advertising duty | **assumption, ranks settings only, not a battery measurement** |

Delivery counts only destinations that were reachable when the message was sent. Criteria are applied by the sender's true local density at the moment of sending.

## Calibration sources

### Smoke numbers (stages A–D)

Source: MeshLink device-lab bearer bake-off smoke run, Pixel 6 ↔ Galaxy S10e, MTU 517, 2026-11. Plus figures from cairn, a sibling project with about 15 phones.

| Input | Value used | Source |
|---|---|---|
| GATT echo round trip (2M PHY) | U(34, 56) ms | bake-off smoke |
| GATT throughput | U(62, 89) KB/s at 2M. 1M (40–44 KB/s) not used | bake-off smoke |
| Noise XX handshake | U(55, 90) ms | bake-off smoke (median range) |
| Noise XXhfs handshake | 76–159 ms, not used in the smoke calibration | bake-off smoke |
| L2CAP CoC open | 74–99 ms, folded into connect | bake-off smoke |
| First-attempt connect failure | 0.3; retry 0.1 (status 147/133 seen often) | bake-off smoke, **provisional** |
| Link setup + authentication | about 1.1 s | cairn |
| Link lifetime | median 27 s, p90 52 s; a drop hazard of mean 45 s gives 26.4 s / 50.4 s | cairn, fitted |
| Legacy phones | 9–10 KB/s, not used | cairn |

### Bake-off matrix values (iOS-cap rechecks only)

Source: MeshLink bearer bake-off on real devices, 2026-11. Its own evidence record lists the devices and OS versions. The simulator used these values only in the iOS-cap rechecks of stage D (calibration label `meas`). The main stage D grid was not rerun with them.

| Input | Value used |
|---|---|
| Throughput by dialing (central) phone | Android 47–79 KB/s; iPhone 19–31 KB/s |
| 64 B echo round trip by central | Android 30–45 ms; iPhone 60 ms |
| GATT setup | U(1.3, 3.7) s |
| Post-quantum XXhfs handshake | U(101, 255) ms |
| Connect retries | up to 3 retries inside one dial, backoff 500 ms × n |
| iPhone peripheral role | kept at most 4 incoming links. An iPhone X with 4 incoming links became invisible to scanners; at 3 it stayed visible |
| Central-role link counts | iPhone 8–9, Android 7–8, Android peripheral 8 (lower bounds, limited by available phones) |

Unchanged in `meas`: drop hazard, first-attempt failure 0.3, contention, iOS background windows. iPhone peripheral-side throughput was not measured, so a link's throughput follows the dialing phone.

## Pre-registered criteria and amendments

From the ticket design (2026-11-01):

1. Delivery within a connected component ≥ 99% at low and medium density, ≥ 95% at high density.
2. Idle control traffic ≤ 10% of link capacity.
3. Among passing strategies: lowest maximum per-node load, then lowest p95 latency.
4. First-message p95 ≤ 1 s classical and ≤ 1.5 s post-quantum, for 3 hops or fewer on established links.
5. Tag rotation every 15 min: at most +10% transmissions and at most −1 pp delivery.

Amendments:

- **After stage B:** at low density, delivery must come within 0.5 pp of U1 (flooding) in the same cell. 99% still applies where U1 reaches it. Reason: no protocol, not even flooding, reached 99% in every low-density cell.
- **Stage C:** criteria are applied by the sender's true local density at send time, because density changes within a run.
- **Stage D aim (not a pass/fail criterion):** unicast p95 ≤ 1 s at medium and high density.

## Stages

| Stage | Question | Grid | Seeds | Runs | Wall time | Cuts |
|---|---|---|---|---|---|---|
| A | Neighbor selection: N0 vs N1+N2, both with N3 | density × N {10, 50, 200, 1000} × 5 mobility × 2 platform mixes × d {3, 5, 6, 8}; Sybil 10% / 30%; drop sensitivity | 10 | 7,560 | 70 min | none. Skipped as impossible: medium N = 10, high N = 10 and 50 |
| B | Unicast U1–U4, broadcast B1–B3, gossipsub, on N1+N2 | N {50, 200, 1000} × 4 mobility × 2 mixes × d {5, 6}; lifetime sweep; bridge rule | 10 (N = 1000: 5) | 1,500 | 2 h 53 min | N = 1000 static only; U3 not run at N = 1000; N = 1000 with 5 seeds |
| C | Local dynamic density; U4sf, U6, adaptive unicast and broadcast; bridge limit; relay preference; minimum lifetime; 1 h runs | 8 dynamic scenarios + 20 fixed cells × 2 mixes × 3–4 topologies; N = 1000 arrival; lifetime; 1 h; estimator drives; follow-up with d = 5 everywhere; bridge sweep | 10 (N = 1000, 1 h and drives: 5) | 2,910 | about 2 h 10 min + 30 min pilots | scoping only: N = 1000 one dynamic scenario, reduced protocol set |
| D | Close the gaps: route keep-alive (K1–K3), full-range search (F), sparse senders flood (S5), catch-up (CU), faster graft (FG), pinned low class | 16 dynamic + 40 fixed cells; conversation traffic; N = 1000 arrival; lifetime; pinned | dynamic 10, fixed 5, conversation 3, N = 1000 5 | 750 | about 81 min (budget about 1 h) | fixed cells 5 seeds instead of 10; conversation 3 instead of 5; S8 dropped; broadcast FG+CU dropped; K1, K2, K1+K2+K3 without F dropped from conversation runs |
| D, iOS cap 4 | iOS inbound cap 4, bake-off calibration | half-iOS medium static N = 50 / 200, high static N = 200, arrival N = 200 × 5 calibrations | 5 | 100 | 55 s | none |
| D, iOS cap 3 | iOS inbound cap 3, undiscoverable at the cap | same 4 cells × 2 calibrations | 5 | 40 | 26 s | none |
| E | Security costs: rotation period, tag and message-ID sizes, fragment reassembly, XX vs XXhfs | N = 200: low, medium, high static, medium walking, arrival × 2 mixes; final v1, bake-off calibration | 5 | 350 | about 41 min (budget 30–45 min) | none |

All runs used at most 4 worker threads and `-Xmx3g` on a machine shared with the bake-off. Stage D ran about 20 min over its budget for a follow-up that backed the recommendation with lifetime and N = 1000 evidence.

## Results

All values are simulated. "±" is a 95% confidence interval over seeds. "Busiest phone" is the highest average send rate of any phone.

### Stage A: neighbor selection

| Finding | N0 | N1+N2 |
|---|---|---|
| Settings with a clearly larger connected group (of 360) | 6 | 216 (138 no clear difference) |
| Settings with a clearly shorter diameter (of 360) | 30 | 219 |
| Diameter, medium, N = 200, static, d = 5, Android | 15.1 ± 0.2 hops | 10.4 ± 0.1 hops |
| Diameter, high, N = 1000 | 31.8 ± 0.4 hops | 16.4 ± 0.2 hops |
| Largest group, medium, N = 200, static, d = 3 | 0.41 ± 0.03 | 0.97 ± 0.01 |
| Largest group, medium, N = 1000, corridor | 0.84 ± 0.01 | 0.98 ± 0.00 |
| First link for a joining phone (median over settings) | 1.3 s | 1.2 s; faster in 303 settings, never slower |
| Partition repair, low, N = 200 (first bridging link) | 2.0 ± 0.6 s | 5.7 ± 1.8 s |
| Control traffic at d = 5 | n/a | 18–48 B per phone per second; ≤ 0.12% of capacity |
| Sybil 10%: honest largest group, medium, static | 0.61 ± 0.05 | 0.91 ± 0.02 |
| Sybil 30% | collapses (≤ 0.05) | collapses (≤ 0.05) |

N1+N2 won. The user chose d = 5 for stage B. A run without supervision drops picked the same winner.

### Stage B: routing and broadcast on N1+N2

| Topic | Result |
|---|---|
| Unicast, low density | U1 passes 30 of 36 cells (mean 99.55%, worst 98.4%). U3 29 of 32. U4 25 of 36. U2 18 of 36. Failures are corridor, half-iOS and N = 1000 static cells |
| Unicast, medium density | U1 and U3 pass every cell. Busiest phone: U1 3,206 B/s, U3 3,968 B/s |
| Unicast, high density | all pass. U4 is cheapest (380 B/s busiest phone) with p95 2.2 s |
| U4 cost and latency | about 0.8 KB/s busiest phone at N = 1000 medium vs U1 12.8 KB/s. p95 9.5 / 2.9 / 2.2 s at low / medium / high |
| Broadcast, low density | B1 32 of 36 cells (mean 99.44%). B3 26 of 36. Gossipsub 15 of 36. B2 worst cell 62% |
| Broadcast, medium and high | gossipsub, B3 and B1 pass every cell (busiest phone about 45 / 48 / 72 B/s at medium) |
| Lifetime | 30 s lifetime with ±30 s skew delivers 55–87%. At ≥ 5 min skew has no effect |
| Tag rotation, 15 min | within budget per density class for every protocol. Single cells over budget only for U2 (+11.2% bytes) and U3 (−1.75 pp) |
| Bridge rule | first link between rejoining groups 2–4× faster (low N = 200 half-iOS 6.5 → 1.7 s; high N = 1000 20.8 → 5.6 s). Cost: +40–60% link setups in moving crowds |

Dropped after stage B: U2, U3, B2. The low-density criterion was amended.

### Stage C: local dynamic density

| Topic | Result |
|---|---|
| Estimator w10e (10 s window, smoothing 0.3) | 0.89–1.20× the true count, 8–27% mean error. 60 s windows over-count 1.1–2.8× |
| Identifier rotation inflation | about window ÷ rotation period, about 1%. Aligned rotation did not change the switch rate measurably |
| Classifier (up 12.5 / 62.5, down 8 / 40, dwell 60 s) | a phone standing at a boundary switches 0.6–1.8 times per hour. Lag p90 45–57 s |
| Mixed modes | unicast across the mode boundary 99.0–100%; broadcast 99.3–99.9%; during transitions 99.4–99.8% |
| Adaptive vs flooding | adaptive passes 16 of 16 dynamic and 34 of 40 fixed cells; flooding 16 of 16 and 38 of 40. Adaptive cuts the busiest phone's load by 22–78% in dynamic scenarios. Unicast p95 2.3–3.6 s vs 0.4–0.7 s |
| Class boundaries | non-flooding protocols fail only below 5 peers in range. Nothing fails above 10. The 10-peer boundary keeps a 2× margin; the 50-peer boundary is set by load, not delivery |
| Relay preference for awake phones | delivery +0.00 ± 0.02 pp, first-message p95 +29 ± 13 ms, busiest phone +4%. Dropped |
| Minimum lifetime | 60 s with ±30 s skew costs adaptive 0.4–2.0 pp. At 120 s adaptive stays within 0.5 pp of flooding. Hold 0 s and 10 s are equivalent; 60 s hurts short lifetimes |
| 1 h runs, low density | holding raises delivery (incl. destinations unreachable at send) from 43% to 84% (corridor, Android) and from 63% to 96% (walking, half-iOS) |
| Tag rotation, 15 min | data transmissions −0.2 to +0.1%, control +0.2 to +2.7%, delivery within ±0.03 pp |
| Bridge limit 60 s | repair 6.8 / 6.2 / 7.0 s (low / medium / high) vs 11.3 / 14.2 / 26 s without the rule; +0 / +2 / +3% link setups |
| d = 5 at low density | cuts low-density load by 12–19% but loses 0.8 ± 0.3 pp delivery. d = 6 kept at low |

### Stage D: closing the gaps

| Configuration | Dynamic cells passing | Fixed cells passing | Busiest phone (B/s) | Control (B/phone·s) | Unicast p95 medium / high (ms) | Cells with p95 ≤ 1 s |
|---|---|---|---|---|---|---|
| Flooding (U1 · B1) | 16 / 16 | 36 / 40 | 1,849 | 39.8 | 407 / 458 | 55 / 56 |
| Stage C adaptive (unicast UA · broadcast adaptive) | 15 / 16 | 35 / 40 | 1,101 | 116 | 3,628 / 2,423 | 8 / 56 |
| **v1 (unicast UA F+K2 · broadcast CU)** | **16 / 16** | **37 / 40** | **1,113** | **136** | **1,262 / 983** | **30 / 56** |
| F+K1+K2+K3 | 16 / 16 | 39 / 40 | 2,028 | 275 | not reported | 42 / 56 |
| Pinned low class | 16 / 16 | not run | 2,300–3,800 | not reported | about 350–620 overall | not reported |

Other stage D results:

- **Route keeping (K1, K2, K3) without F** barely helps. Pairs write about every 12 min, so routes expire anyway. The p95 comes from first contact with a recipient more than 3 hops away.
- **F (full-range search)** is the change that matters for p95.
- **S5 (sparse senders flood)** changed nothing measurable. The two remaining medium-cell misses come from senders whose own estimate was not below 5. S8 and S10 did not recover them either.
- **Sparse corridor, 120 s lifetime, 60 s hold:** F+K2 reached 99.19% vs U1 98.97% (same runs).
- **N = 1000 half-iOS arrival, broadcast:** catch-up reaches 99.99% ± 0.02 for medium senders. FG stayed at 98.88% and B1 at 98.88%. Cost: about +10 B/phone·s control; busiest phone's broadcast load 42 → 114 B/s.
- **N = 1000 unicast:** v1 99.81% (UA 99.44%). p95 6.3 s medium, 6.7 s high. Control 1,039 B/phone·s; busiest phone 5,943 B/s.
- **Long chains** stay slow: medium corridor N = 200 has a p95 of about 7.4 s with every routed variant.
- **Conversation traffic** (replies after about 30 s): F reaches p95 ≤ 1 s in 14 of 20 cells, F+K3 in 18 of 20.
- **Pinned low class** meets every criterion in all 16 dynamic cells, at 2–4× v1's busiest-phone load and power index 1.0 (v1 0.61–0.96).

### iOS inbound cap rechecks

Half-iOS cells, 5 seeds, v1 and flooding. Cap 3 also makes an iPhone at the cap undiscoverable.

| Metric | No cap | Cap 4 | Cap 3 + undiscoverable |
|---|---|---|---|
| Combinations meeting all criteria (v1) | 4 of 4 per calibration | 20 of 20 | 8 of 8 |
| Flooding misses | none | none | 1 (high, bake-off calibration, medium senders 98.73%) |
| v1 unicast delivery | 99.86–100% | 99.65–100% | 99.80–100% |
| v1 broadcast delivery | ≥ 99.98% | ≥ 99.98% | 100.00% |
| iPhone links (incoming) | 3.1–4.2 | 3.1–4.1 (1.5–1.9) | 3.1–4.1 (1.4–1.8) |
| Refused dials per phone per minute | 0 | 0.17–0.34 | 0.36–0.48 |
| v1 first-message p95, classical / post-quantum | ≤ 0.26 / ≤ 0.72 s | ≤ 0.24 / ≤ 0.89 s | ≤ 0.19 / ≤ 0.72 s |

Unicast p95 per cell (v1, ms, medium / high senders):

| Cell | Model calibration, cap 4 | Model calibration, cap 3 | Bake-off calibration, cap 4 | Bake-off calibration, cap 3 |
|---|---|---|---|---|
| Medium static N = 50 | 396 / – | 344 / – | 487 / – | 488 / – |
| Medium static N = 200 | 758 / – | 789 / – | 1,061 / – | 1,100 / – |
| High static N = 200 | 1,972 / 1,027 | 703 / 1,032 | 2,441 / 1,459 | 2,516 / 1,494 |
| Arrival N = 200 | 869 / 905 | 832 / 907 | 1,413 / 1,517 | 2,400 / 1,383 |

The high static medium-sender column and the arrival cell have few medium senders, so their p95 is noisy. d = 6 / 5 / 5 still holds at cap 3: with one slot reserved for newcomers, an iPhone rarely needs more than 2 incoming links. The bake-off calibration leaves delivery unchanged. It raises unicast p95, mostly by about 0.1–0.7 s; in the noisy arrival medium-sender cell at cap 3 by 1.6 s.

### Stage E: security costs

Final v1 (stage D v1 + iOS inbound cap 3) with the bake-off calibration. N = 200; low, medium and high static, medium walking, and arrival; Android-only and half-iOS; 5 seeds. Several protocol instances share one run where they share a topology. Comparisons are paired on the same seeds.

**Tag rotation period.** Budget per density class: at most +10% transmissions and at most −1 pp delivery against no rotation.

| Period | Worst Δ delivery | Worst Δ bytes on air | Worst Δ data transmissions | Estimator over-count, low / medium / high | Route-search bytes vs tags fixed | Verdict |
|---|---|---|---|---|---|---|
| 1 min | −0.25 pp | +10.8 ± 1.5% (arrival, half-iOS) | +1.0% | 12–13 / 6–7 / 3% | +60–109% at low; +21–46% in half-iOS and arrival cells | within, except bytes unclear in one cell |
| 5 min | −0.26 pp | +1.4% | +1.1% | 2.5 / 1.3 / 0.6% | +3–20% | within in every class |
| 5 min, aligned (all phones at once) | −0.04 pp | +3.1% | +2.0% | 2.0 / 1.0 / 0.5% | – | within |
| 15 min (stages B–D) | −0.09 pp | +3.0% | +1.5% | 0.7 / 0.4 / 0.2% | about +0–7% | within |
| 60 min | −0.17 pp | +3.5% | +2.0% | 0.1–0.2% | – | within; shows the noise floor (a quarter of phones rotate once per run) |

The verdict uses the stricter of bytes on air and data transmissions. On data transmissions alone, 1 min is also within budget; its extra bytes are route searches.

Unicast delivery right after a rotation is 99.5–99.9% at 1 min in half-iOS cells and about 100% at 5 min and longer. The p95 after a rotation is no worse than the tags-fixed control at the same moments. At 1 min some cells send fewer bytes (−5 to −8%), because the over-count pushes phones into a higher class that floods less.

**Wire sizes** (against tag 8 B, message ID 16 B; delivery changed by at most 0.03 pp):

| Change | Unicast routing bytes | Broadcast routing bytes |
|---|---|---|
| Tag 4 B | −6 to −7% | −1% |
| Tag 16 B | +11% | +2.1% |
| Message ID 8 B | −1% | −10.5% |
| Message ID 12 B | −0.6% | −5.2% |

**Random collisions** (analytic birthday bound, N = 1000, 4.23 messages per second mesh-wide):

| Field and population | Probability |
|---|---|
| Message ID 8 B, 300 s lifetime (1,270 messages alive) | 4.4×10⁻¹⁴ per lifetime; 9.2×10⁻⁹ per year |
| Message ID 8 B, 24 h lifetime (365,760 alive) | 3.6×10⁻⁹ per lifetime; 2.6×10⁻⁶ per year |
| Message ID 12 B, 24 h lifetime | 8.4×10⁻¹⁹ per lifetime |
| Tag 4 B, whole mesh (3,000 tags valid) | 1.0×10⁻³ per period; 0.10 per day at 15 min |
| Tag 4 B, route table (44 entries) | 2.2×10⁻⁷ per period |
| Tag 8 B, whole mesh | 2.4×10⁻¹³ per period; 2.3×10⁻¹¹ per day |

These are random collisions only. A malicious phone can reuse a known ID or tag at any size. Binding the ID to the content is a threat-model question.

**Long messages** (unicast, pooled over cells; median latency in ms):

| Message | Delivery | 1 hop | 3 hops | 5 hops |
|---|---|---|---|---|
| 1 × 514 B | 99.94% | 40 | 122 | 421 |
| 16 × 182 B, reassemble per hop | 99.85% | 101 | 323 | 591 |
| 16 × 182 B, cut-through | 99.26% | 103 | 205 | 518 |
| 16 × 514 B, reassemble per hop | 99.79% | 242 | 774 | 1,306 |
| 16 × 514 B, cut-through | 97.46% (−2.48 pp) | 242 | 435 | 844 |

Broadcast 16 × 514 B: 99.94% with per-hop reassembly, 96.03% with cut-through (as low as 92.5% in half-iOS cells). The cut-through model is pessimistic: a broken partial copy blocks a later full copy.

**XX vs XXhfs under churn** (medium walking and arrival shown; static cells are similar):

| Cell | Handshake | Handshake share of capacity | Dial to usable p50 / p95 (s) | Unicast delivery | First-message p95, classical / post-quantum IK (ms) |
|---|---|---|---|---|---|
| Medium walking, Android | XX | 0.019% | 2.98 / 6.84 | 99.89% | 110 / 285 |
| Medium walking, Android | XXhfs | 0.237% | 3.09 / 6.99 | 99.89% | 105 / 283 |
| Medium walking, half-iOS | XX | 0.032% | 2.91 / 6.62 | 99.74% | 151 / 523 |
| Medium walking, half-iOS | XXhfs | 0.395% | 3.04 / 6.74 | 99.68% | 214 / 500 |
| Arrival, Android | XX | 0.017% | 3.08 / 7.51 | 100.00% | 110 / 311 |
| Arrival, Android | XXhfs | 0.219% | 3.20 / 7.53 | 100.00% | 110 / 324 |
| Arrival, half-iOS | XX | 0.030% | 3.02 / 7.14 | 99.90% | 165 / 546 |
| Arrival, half-iOS | XXhfs | 0.379% | 3.17 / 7.25 | 99.79% | 188 / 537 |

Across all 10 cells, XXhfs uses 0.12–0.41% of link capacity. First-message p95 by hop count (1–3) and by sender density class stays below 1 s classical and 1.5 s post-quantum in every cell. The worst median over seeds is 828 ms (post-quantum, 3 hops, high static half-iOS). Pooled, 99.8% of first messages are within budget. The rare misses are single messages caught by a 60 s iPhone background gap.

## Final criteria check

v1 = N1+N2 + N3 with d = 6 / 5 / 5, bridge limit 60 s, local density estimator, unicast UA with F+K2, broadcast with catch-up (CU), relay preference off, iOS inbound cap 3. Compared with stage C adaptive and with flooding on the same topology.

| Criterion | v1 | Stage C adaptive | Flooding | Verdict |
|---|---|---|---|---|
| Delivery, dynamic cells | 16 / 16 | 15 / 16 | 16 / 16 | pass |
| Delivery, fixed cells (low within 0.5 pp of U1; medium 99%; high 95%) | 37 / 40 | 35 / 40 | 36 / 40 | pass with 3 small-sample misses (below) |
| Idle control ≤ 10% of capacity | < 0.1% everywhere | < 0.1% | < 0.1% | pass |
| Rank: busiest phone | 1,113 B/s | 1,101 B/s | 1,849 B/s | v1 about 40% below flooding |
| Rank: p95 | 1,262 / 983 ms (medium / high) | 3,628 / 2,423 ms | 407 / 458 ms | flooding is faster |
| First-message p95 ≤ 1 s / ≤ 1.5 s | ≤ 0.19 s / ≤ 0.72 s (cap 3, both calibrations); worst post-quantum 0.89 s (cap 4, bake-off calibration) | same order | same order | pass |
| Tag rotation (≤ +10% transmissions, ≤ −1 pp) | stage E, final v1, paired: 5 min worst +1.4% / −0.26 pp; 15 min +3.0%; 1 min +10.8% in one cell | within budget per class | within budget | pass; 5 min proposed |
| First-message p95 by hops (≤ 3) and class, XXhfs under churn | ≤ 0.83 s post-quantum, ≤ 0.22 s classical (stage E, medians over seeds) | – | – | pass |
| Unicast p95 ≤ 1 s at medium / high (aim) | 30 / 56 cells | 8 / 56 | 55 / 56 | **not met**; pinned low class is the low-latency preset |
| iOS inbound cap 3 | 8 / 8 combinations pass | n/a | 7 / 8 | pass |
| Minimum lifetime 120 s | within 0.5 pp of flooding | within 0.5 pp | reference | pass |

The three v1 fixed-cell misses:

| Cell | Sub-group | Delivery | Sample |
|---|---|---|---|
| Medium, group mobility, half-iOS | truly low-density senders | 98.57% | about 11–14 messages per run, 5 seeds; one lost message |
| Medium, random waypoint, Android | truly low-density senders | 98.33% | same; one lost message |
| High, group mobility, half-iOS | medium-density senders | 98.06% | about 20 messages per run |

## Limitations

- **Simulated only.** No collision model, no real radio scheduling. Link setup, drops and contention are fitted or assumed.
- **iOS background** is an on/off model (awake 10 s every 60 s), not measured behavior.
- **Contention** and the **power index** are assumptions. The power index ranks settings; it does not predict battery use.
- **The drop hazard** is fitted to cairn, not to the bake-off.
- **The bake-off calibration** was used only in the iOS-cap rechecks. Under it, unicast p95 rises by about 0.1–0.7 s.
- **Unicast p95 ≤ 1 s** is not reached in 26 of 56 cells, nor at N = 1000 (6.3–6.7 s), nor in long corridors (about 7.4 s).
- **Small sub-samples** decide the three remaining fixed-cell misses. One lost message flips pass or fail.
- **Sybil at 30%** collapses both topologies. Open meshes have no defense in v1 routing.
- **N = 1000** covers static cells (stage B) and the arrival scenario (stages C and D) with 5 seeds.
- **Stage E** covers N = 200 and five cell types only. Collision figures are analytic and cover random collisions, not deliberate reuse. Cut-through was modeled pessimistically, and the measured throughput was assumed at MTU 185. Rotation reaches the topology only through the density estimator.
- **1 h runs** cover low density only, N = 200, 5 seeds.
- **iPhone at the cap:** the model refuses at connect time. A real iPhone accepts and then closes, which costs the dialer slightly more.
- **U3** was a simplified Babel; its control traffic is an upper bound.
- **Determinism:** separate run sets give slightly different values for the same protocol (for example U1 99.00% vs 98.97%). Comparisons always use the same runs.

## Files in this folder

| File | Content |
|---|---|
| `README.md` | this summary |
| `v1-parameters.md` | every v1 routing parameter, its value, stage and evidence |
| `stage-a-report.html` … `stage-d-report.html`, `stage-d-ios-cap3-report.html` | self-contained HTML reports per stage, inline SVG, no external resources |
| `stage-a-agg.csv`, `stage-b-agg.csv` | aggregated results, one row per cell × strategy or protocol instance |
| `stage-c-agg-{main,big,life,long}.csv` | stage C aggregates per run set, one row per cell × protocol instance |
| `stage-d-agg-{main,conv,big,big-v1,life,life-v1,pin,ios4,ios3}.csv` | stage D aggregates per run set |
| `stage-e-report.html`, `stage-e-agg-{base,rot,hs}.csv` | stage E: sizes, fragments and v1 references (`base`), rotation periods (`rot`), classical XX handshakes (`hs`) |
| `*-topology.csv` | topology and density metrics, one row per cell (these repeat on every protocol row in the full aggregates) |

The CSVs are trimmed to fit the folder in about 5 MB:

- numbers are rounded to 4 significant digits, confidence intervals to 2;
- 95% intervals are kept only for key metrics (delivery, p95, first-message p95, control, idle control share, busiest phone, connected group, diameter, partition repair, join time, slot occupancy, Sybil shares, dial and link-up rates, power index, per-sender-class delivery and p95);
- simulator diagnostics are dropped (drop reasons, table sizes, duplicate rate, hop percentiles, mean and p99 loads, wall time);
- estimator and classifier columns: stage C main keeps every estimator and every classifier on the w10e estimator; the other files keep only the driving pair `w10e:s1h25/d60`;
- stage E files keep a fixed column list: key metrics with intervals, plus means for per-hop latency, first message by hops and class, rotation over-count, handshake and dial metrics.

Column meanings are in `prototypes/routing-sim/README.md`. The full aggregates and the raw per-run CSVs are not committed. They live in the session folder of the agent run (`files/routing-sim/stage-{a,b,c,d,e}/`, about 200 MB) and can be regenerated with the commands below.

## How to reproduce

Code: branch `prototype/routing-sim`, directory `prototypes/routing-sim/`.

| Stage | Commit |
|---|---|
| A simulator | `87eff78` |
| A report | `aedc261`, `4742911` |
| B | `4427579`, report `35a8851` |
| C | `9cec6e7`, `a543c76`, report `9637738` |
| D and cap-4 recheck | `d15d0f3` |
| Cap-3 recheck | `3a6bf7a` |
| E (final) | `ca9a393f1a17a86f5d10fdadb12bed6e95cb32c3` (`ca9a393`) |

Commands, from `prototypes/routing-sim/` at `ca9a393` (stages A–D reproduce unchanged at this commit):

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew -q simLib
CP='build/sim/lib/*'; M=meshlink.routingsim.runner.MainKt

# Stage A
java -Xmx3g -cp "$CP" $M stage-a --out A --threads 4 --seeds 10 --plan full
java -cp "$CP" $M aggregate --out A && java -cp "$CP" $M report --out A

# Stage B
java -Xmx3g -cp "$CP" $M stage-b --out B --threads 4 --n1000-mobility static --n1000-seeds 5 --n1000-u3-seeds 0
java -cp "$CP" $M stage-b-aggregate --out B && java -cp "$CP" $M stage-b-report --out B

# Stage C
java -Xmx3g -cp "$CP" $M c-bridge --out C/bridge
java -Xmx3g -cp "$CP" $M c-run --out C --set=main,life,long,drives
java -Xmx3g -cp "$CP" $M c-run --out C --set=tune
java -cp "$CP" $M c-aggregate --out C && java -cp "$CP" $M c-report --out C

# Stage D
java -Xmx3g -cp "$CP" $M d-run --out D --set=big,main,conv,life,pin
java -Xmx3g -cp "$CP" $M d-run --out D --set=big-v1,life-v1
java -Xmx3g -cp "$CP" $M d-run --out D --set=ios4 --seeds=5
java -Xmx3g -cp "$CP" $M d-run --out D/ios-cap3 --set=ios3 --seeds=5
java -cp "$CP" $M d-aggregate --out D && java -cp "$CP" $M d-report --out D
java -cp "$CP" $M d-ios3-report --out D/ios-cap3 --cap4=D

# Stage E
java -Xmx3g -cp "$CP" $M e-pilot --out E --set=base,rot,hs --seeds=1
java -Xmx3g -cp "$CP" $M e-run --out E --seeds=5
java -cp "$CP" $M e-aggregate --out E && java -cp "$CP" $M e-report --out E
```

Every run is fully determined by (scenario, strategy or protocol set, seed). Runs are resumable.

## Run metadata (T9)

| Field | Value |
|---|---|
| Dates | stage A 2026-11-01 13:39; stage B 2026-11-01 afternoon; stage C 2026-11-01 20:00–21:53 plus follow-up; stage D 2026-11-01 late evening to 2026-11-02 00:06; cap-3 recheck 2026-11-02 08:46; stage E 2026-11-02 09:24–10:12 |
| Machine | Apple M1, 8 cores, 8 GB RAM, macOS 27.0.1 (26A434); shared with the bearer bake-off |
| JDK | OpenJDK 21.0.13 (Homebrew `openjdk@21`). Deviation from ADR 0005 (JDK 25): kept on 21 for consistency across stages |
| Kotlin / Gradle | Kotlin 2.4.21, Gradle wrapper 9.7.1, `jvmToolchain(21)` |
| Dependencies | Kotlin standard library only |
| Resources | at most 4 worker threads, `-Xmx3g` |
| Base commit | branched from `main` at `6a92ad6` |
| Final commit | `ca9a393` on `prototype/routing-sim` (local, not pushed) |
| Hardware evidence | none produced here. Device values are inputs from the bearer bake-off and cairn; their own evidence records apply |
