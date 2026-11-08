# v1 routing parameters

Every routing parameter of the provisional v1 configuration, with the stage that set it and the evidence. All evidence is **simulated** (see [README](README.md)). Device-derived values name their source.

"Swept" means the simulator compared values and this one was chosen. "Assumed" means the value was fixed in the model and never varied; treat it as a starting point, not a result. Code names refer to `prototypes/routing-sim/` at commit `3a6bf7a`.

Density classes are written low / medium / high. "Class" means the phone's own estimated class, not the true density.

## Neighbor selection and topology

| Parameter | v1 value | Stage | Evidence | Basis |
|---|---|---|---|---|
| Strategy | N1+N2: HyParView-style active view (live links) and passive view, diversity preference by neighbor-list overlap | A | larger connected group in 216 of 360 settings (N0: 6); diameter medium N = 200 15.1 → 10.4 hops | swept (N0 vs N1+N2) |
| Newcomer reservation (N3) | keep at least 1 slot free; advertise and accept inbound only while a slot is free; a phone full for 30 s frees one slot | A | applied to both strategies; joining phone's first link median 1.2 s | assumed (always on; 30 s not swept) |
| Degree d (max concurrent links, both roles, pending dials count) | 6 / 5 / 5 | A, C | A: d ∈ {3, 5, 6, 8}, d = 3 fails for N0. C follow-up: d = 5 at low loses 0.8 ± 0.3 pp delivery | swept |
| Pinned low class: d | 6 in every class | D | pinned preset passes 16 of 16 dynamic cells | follows low-class value |
| iOS inbound (peripheral-role) cap | 3 incoming links, counting incoming dials in progress. At the cap an iPhone stops being discoverable; further links start from the iPhone as central | D (cap-3 recheck) | bake-off: iPhone X invisible to scanners at 4 incoming links. Simulated cap 3: 8 of 8 combinations pass; refused dials 0.36–0.48 per phone per minute | device-derived; rechecked, not swept |
| Passive view (peer table) size | 4·d (24 / 20 / 20) | A | control traffic 18–48 B/phone·s at d = 5 | assumed |
| Passive view eviction | random replacement when full (HyParView); only peers heard in the last 4 s can be dialed | A | – | assumed |
| Shuffle | every 10 s with one neighbor: 2 active + 3 passive identifiers, same-size reply, 8 B per identifier | A | counted as control traffic | assumed |
| Neighbor-list exchange | to all neighbors on change, at most every 2 s; 4 B header + 8 B per neighbor | A | same | assumed |
| Diversity replacement | every 30 s, replace the most redundant link if a candidate overlaps ≥ 2 fewer peers in the 1–2-hop set | A | – | assumed |
| Former neighbor list reuse | 120 s | A | – | assumed |
| RSSI floor for dialing | −85 dBm (radio sensitivity −88 dBm) | A | – | assumed |
| Dial backoff to one peer | 3 s after the first failure, 60 s after repeated failures; 120 s cool-down after a rotation or a remote close | A | – | assumed |
| Connect retries inside one dial | up to 3, backoff 500 ms × n | D (recheck only) | bake-off measurement | device-derived |

## Bridge rule

| Parameter | v1 value | Stage | Evidence | Basis |
|---|---|---|---|---|
| Rule | dial a newly discovered peer outside the known 1–2-hop neighborhood first, with a relaxed RSSI floor (−88 dBm), even into the reserved slot; then rotate out the most redundant link | B | first link between rejoining groups 2–4× faster | swept (on vs off) |
| "Newly discovered" | not heard for ≥ 60 s, and first heard again within the last 10 s | B | – | assumed |
| Rate limit | one bridge attempt per 60 s, same in every class | C | sweep off / 10 / 30 / 60 / 120 s: repair 6.8 / 6.2 / 7.0 s vs 11.3 / 14.2 / 26 s without the rule, +0 / +2 / +3% link setups | swept |

## Local density estimator and classifier

| Parameter | v1 value | Stage | Evidence | Basis |
|---|---|---|---|---|
| Estimator input | distinct advertisement identifiers heard in the window, plus linked peers whose current identifier was not heard (the link-count term) | C | ratio 0.89–1.20 to true peers in range | swept (4 variants) |
| Window | 10 s | C | 60 s windows over-count 1.1–2.8× | swept (10, 20, 60 s) |
| Smoothing | exponential average, weight 0.3 per scan; first estimate unsmoothed | C | w10e error 8–27% | swept (1.0, 0.3, 0.2) |
| Identifier rotation inflation | about window ÷ rotation period; linked peers announce their next identifier over the link | C, E | stage E, measured against true peers in range: over-count 2.5 / 1.3 / 0.6% (low / medium / high) at 5 min, 0.7 / 0.4 / 0.2% at 15 min, 12–13 / 6–7 / 3% at 1 min | swept (1, 5, 15, 60 min, 5 min aligned) |
| Up thresholds | low → medium at 12.5, medium → high at 62.5 | C | – | swept (scale 1 / 1.3 × band 0 / 25 / 50%) |
| Down thresholds | high → medium below 40, medium → low below 8 | C | – | swept (same) |
| Hysteresis band | 25% (up at bound × 1.25, down at bound ÷ 1.25) | C | a phone at a boundary switches 0.6–1.8 times per hour | swept |
| Dwell | 60 s minimum in a class; the first classification is immediate | C | lag p90 45–57 s after a true change | swept (30, 60 s) |
| Class bounds (true peers in range) | 10 and 50 confirmed | C | non-flooding protocols fail only below 5 peers; nothing fails above 10; 50 is set by load | confirmed from where criteria fail |

## Scan and advertise duty cycle

| Parameter | Low | Medium | High | Stage | Evidence | Basis |
|---|---|---|---|---|---|---|
| Scan interval | every 2 s | every 4 s | every 6 s | C | adaptive vs adaptive without duty cycling in the dynamic scenarios; v1 power index 0.61–0.96 vs 1.0 for the pinned low class | swept (on vs off), interval values assumed |
| Advertising | fast | fast | slow | C | same | assumed (slow advertising heard with probability 0.5 per scan, an assumption) |
| Pinned low class | every 2 s, fast | every 2 s, fast | every 2 s, fast | D | power index 1.0 vs v1 0.61–0.96 | follows low class |

The power index (0.6 × scan duty + 0.4 × advertising duty) is an assumption. It ranks settings; it does not predict battery use.

Energy measurements on real phones (2026-11-08) replace the power index with an absolute model in mW and change the scan schedule to start/stop windows of at least 6 s: see the [energy evidence](../2026-11-energy/README.md#4-energy-model-feeds-adr-0008) and the ADR 0008 amendment. The rows above record what this simulation ran.

Stage F (2026-11-08, energy model, start/stop windows; [results](README.md#stage-f-store-and-forward-conversation-routes-lookups-and-power)) sets the v1 schedule:

| Parameter | Low | Medium | High | Stage | Evidence | Basis |
|---|---|---|---|---|---|---|
| Scan, `Balanced` (s on / s off) | 4/4 | 4/12 | 4/16 | F2 | lowest energy passing criteria 1–4 with newcomer join p95 ≤ 1.25 × the interim schedule; dynamic confirmation 17–22 mW, join 0.99–1.04× | swept (4/16, 4/12, 4/8, 4/4, 4/2) |
| Scan, `Responsive` | 4/2 | 4/8 | 4/12 | F2 | one step faster than `Balanced`; 21–27 mW, join 0.82–0.88× | rule |
| Background scan (Android) | duty 0.12 (4 s every 34 s) | same | same | F2 | operating-system behavior measured in the energy evidence; report only | measured on one phone |

## Unicast

| Parameter | v1 value | Stage | Evidence | Basis |
|---|---|---|---|---|
| Low class: mode | flood every message (U1 behavior on the UA wire format), hop limit 255; the destination acknowledges | B, C | U1 best at low density (30 of 36 cells; others worse) | swept |
| Low class: retry | on failure, re-send with backoff 2 s doubling to 30 s; also re-try on every new link (at most once per 5 s per destination) | C | UA passes 16 of 16 dynamic cells | assumed (one setting) |
| Medium and high class: mode | U6: no route known → flood the message 3 hops (100 ms per hop timeout) and send a full-range route request at once (F); known route → unicast; no reply → store-and-forward | C, D | F cuts p95 medium / high from 3,628 / 2,423 to 1,262 / 983 ms | swept (U4sf, U6, UA4, F) |
| U6 data flood radius | 3 hops | C | – | assumed |
| Full-range route request radius | 64 hops (`netDiameter`), 2 retries, 50 ms per hop allowance | B, D | – | assumed (RFC 3561 style) |
| K2 delivery ack | the recipient acks every routed message along the route; the ack re-arms the route on every hop | D | v1 37 of 40 fixed cells vs 35 of 40 for stage C | swept (K1, K2, K3 alone and combined) |
| K2 resend | no ack within 1 s + 2 × 100 ms × hops → one resend via a new search | D | – | assumed |
| Route lifetime | 30 s after last use (active route); a forward route learned from a reply holds 60 s; reverse routes 6 s | B | K1 (180 s conversation keep-alive) and K3 (180 s reverse cache) rejected: little gain without F, about 2× load | 30 s assumed; alternatives swept |
| Routing metric | hop count with destination sequence numbers (AODV style); fewest hops among fresh routes | B | – | assumed (no link-quality metric tested) |
| Medium class: retry | backoff 2 s doubling to 30 s; re-try on new links | C | – | assumed |
| High class: retry | backoff 5 s doubling to 60 s; no re-try on new links | C | lower load at high density | assumed |
| Sparse sender floods (S5) | **off** | D | no measurable change; S8 and S10 also did not recover the misses | swept, rejected |
| Relay preference for awake phones | **off** | C | +0.00 ± 0.02 pp delivery, +29 ± 13 ms first-message p95, +4% load | swept, rejected |
| Pinned low class | low-class unicast in every class | D | p95 about 0.35–0.62 s; busiest phone 2.3–3.8 KB/s | swept (preset) |

## Mesh broadcast

| Parameter | v1 value | Stage | Evidence | Basis |
|---|---|---|---|---|
| Low class | flood: push to every link, never prune (B1 behavior on the Plumtree wire format) | B, C | B1 32 of 36 low cells; B3 26; gossipsub 15 | swept |
| Medium and high class | Plumtree (B3): eager push on eager links, IHAVE (one message ID) at once on lazy links; a duplicate prunes the sender's link to lazy; new links start eager | B, C | B3 passes every medium and high cell; chosen over gossipsub in C | swept (B3 vs gossipsub) |
| Graft timeout | 500 ms after the first IHAVE, then 250 ms per further announcer | B | FG (150 / 100 ms) had no effect: N = 1000 arrival 98.88% either way | swept (500 vs 150 ms) |
| Message cache for grafts | 60 s, capped by the message lifetime | B | – | assumed |
| Catch-up (CU) | on every new link, each side sends one IHAVE with the IDs of unexpired messages received in the last 60 s; the other side grafts what it lacks | D | N = 1000 half-iOS arrival medium senders 98.86% → 99.99% ± 0.02; +10 B/phone·s control | swept (on vs off); window assumed |
| Catch-up content (stage F) | held broadcast IDs, held unicasts as {message ID 12 B, destination tag 8 B}, tombstones {message ID 12 B, k 16 B}; claims by own public or conversation tag; holding 100 messages and 512 KiB, ≤ ¼ per incoming link, ≤ 4 KiB per message | F | idle control ≤ 0.43% of capacity; busiest phone +130–260 B/s | ADR 0014 |
| Certificate lookup reply (stage F) | relays pass on the first reply per request only | F2 | replies at requester 0.3–3.7 instead of up to 43 | swept (all vs first) |
| Catch-up message size | 4 B header + 1 B + one message ID (16 B in stages A–D; 12 B accepted) per message; all IDs in the window in one frame (about 4 IDs at one broadcast per 15 s mesh-wide) | D | – | assumed; no size cap modeled |
| Pinned low class | flood in every class, plus catch-up | D | 16 of 16 dynamic cells | swept (preset) |

## Message lifetime, hop limit and store-and-forward

| Parameter | v1 value | Stage | Evidence | Basis |
|---|---|---|---|---|
| Minimum allowed message lifetime | 120 s; config rejects shorter | B, C | 30 s with ±30 s skew: 55–87%. 60 s: adaptive −0.4 to −2.0 pp. 120 s: within 0.5 pp of flooding | swept (30 s, 60 s, 120 s, 5 min, 1 h, 24 h) |
| Default message lifetime in the grids | 300 s | B–D | – | assumed (not a v1 recommendation) |
| Clock skew tolerated | ±30 s | B, C | – | given by the ticket |
| Expired messages | never forwarded, never held | B | rule from the ticket design | rule |
| Minimum hold | 10 s: take a message into store-and-forward only with at least 10 s lifetime left | B, C | 0 s and 10 s indistinguishable; 60 s hurts short lifetimes | swept (0, 10, 60 s) |
| Hold capacity | 100 messages per phone | B | – | assumed |
| Store-and-forward eagerness | low and medium: re-try on every new link (at most once per 5 s per destination) and on backoff; high: backoff only (5–60 s); flush at once when the destination's own route request passes | C | 1 h runs at low density: delivery 43% → 84% and 63% → 96% with holding | partly swept (U4 vs U4sf); per-class split assumed |
| Default hop limit | 255 | B | U1 flood scope | assumed |
| Maximum hop limit | 255 (one byte) | B | – | assumed |
| Duplicate suppression memory | each message ID until the message expires | B | – | assumed |

## Wire sizes and tag rotation

| Parameter | v1 value | Stage | Evidence | Basis |
|---|---|---|---|---|
| Routing tag | 8 B | E | 4 B: whole-mesh collision 1.0×10⁻³ per rotation period at N = 1000 (−6 to −7% unicast routing bytes); 8 B: 2.4×10⁻¹³; 16 B: +11% unicast routing bytes, no gain; delivery unchanged (\|Δ\| ≤ 0.03 pp) | swept (4, 8, 16 B) + analytic collisions |
| Message ID | **12 B** (accepted 2026-11-02; 16 B in stages A–D) | E | −5.2% broadcast routing bytes vs 16 B; random collision 8.4×10⁻¹⁹ per 24 h lifetime at N = 1000. 8 B: −10.5% but 2.6×10⁻⁶ per mesh-year at 24 h lifetimes; delivery unchanged | swept (8, 12, 16 B) + analytic collisions; deliberate reuse is a threat-model question at any size |
| Frame header (model) | 4 B | – | – | assumed |
| Tag and advertisement identifier rotation period | **5 min** (ADR 0008 and ADR 0010 amendments; 15 min accepted 2026-11-02 was superseded because Android rotates its address every 7–9 min) | B, C, E | 15 min: within budget in every class (stage C, delivery ±0.03 pp). 5 min: shortest period within budget (≤ +10% transmissions, ≤ −1 pp delivery) in every class: worst −0.26 pp, +1.4% bytes. 1 min: −0.25 pp but +10.8 ± 1.5% bytes in arrival half-iOS (unclear), route-search bytes +60–109% at low density | swept (1, 5, 15, 60 min, 5 min aligned) |
| Rotation phase | each phone on its own random phase and clock; tags accepted for ±1 epoch | E | aligned 5 min (all at once) also within budget: +3.1% bytes worst | swept (phased vs aligned at 5 min); ±1 epoch assumed |
| Long messages | per-hop reassembly (store, then forward) | E | 16 × 514 B: −0.15 pp vs one fragment; cut-through −2.48 pp unicast, −3.9 pp broadcast (to 92.5% in half-iOS cells) for a 3-hop median 774 → 435 ms | swept (1, 4, 16 fragments × MTU 185 / 517; cut-through model pessimistic) |
| Fragment payload | MTU − 3 B (182 B at MTU 185, 514 B at MTU 517) | E | – | assumed (ATT write header); measured throughput assumed at MTU 185 too |
| Link handshake | Noise XXhfs (post-quantum, about 2.5 KB per link set-up) | E | 0.12–0.41% of link capacity (XX 0.01–0.03%); dial-to-usable p50 +0.1 s (2.8–3.5 s); delivery unchanged; first-message p95 ≤ 828 ms PQ, ≤ 216 ms classical for 1–3 hops in every cell (median over seeds) | swept (XX vs XXhfs) under walking and arrival churn |

## Not in v1

| Option | Stage | Why |
|---|---|---|
| N0 strongest-signal slots | A | smaller connected group, longer diameter |
| U2 scoped link state | B | behaves like flooding, more control traffic |
| U3 distance vector | B | could not finish at N = 1000; most sensitive to tag rotation |
| U4 alone | B, C | p95 9.5 s at low density |
| B2 probabilistic fanout | B | worst cell 62% |
| Gossipsub | C | B3 chosen for the adaptive broadcast |
| K1 conversation keep-alive, K3 reverse cache | D | little p95 gain without F; F+K1+K2+K3 roughly doubles load |
| S5 / S8 / S10 sparse sender floods | D | no measurable effect |
| FG faster graft | D | no effect |
| Relay preference | C | no delivery gain, +29 ms first-message p95 |
