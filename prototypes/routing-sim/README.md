# routing-sim — THROWAWAY prototype

> **Throwaway.** This is a research prototype for ticket 14 ("Which routing and topology strategy should MeshLink use?").
> It is not library code, has no tests, and must not be lifted into MeshLink as-is. Every number it produces is
> **simulated**, not a device measurement.

A hand-rolled, link-level discrete-event simulator. Stage A compares neighbor-selection (topology formation)
strategies; stage B (see [Stage B](#stage-b-routing-and-mesh-broadcast)) compares unicast routing and mesh broadcast
on the stage A winner. Stage A strategies:

| Strategy | Code | Summary |
|---|---|---|
| N0 strongest signal | `strategy/StrongestSignalSelection.kt` | Peer table of 4·d strongest heard advertisers. ⌊¾·d⌋ (≥ 1) strongest links are stable; the remaining slots rotate: at most one rotating link per 2 s tick is closed once ≥ 30 s idle and a fresh candidate exists (cairn ADR 0031 style). |
| N1+N2 diverse views | `strategy/DiverseViewSelection.kt` | HyParView-style active view (live links, ≤ d) + passive view (4·d, random replacement) fed by scans, shuffles (every ~10 s, 2 active + 3 passive IDs) and neighbor lists. Diversity: candidates are ranked by overlap of their neighbor list with the node's 1–2-hop reach; every 30 s the most redundant link is replaced if a candidate is ≥ 2 better. Immediate promotion from the passive view on link loss or dial failure. |
| N3 newcomer reservation (both) | `strategy/NewcomerReservation.kt` | Advertise and accept inbound only while a slot is free; own dials only while used + 1 ≤ d − 1, so one slot stays free for newcomers. |
| Sybil attacker | `strategy/SybilSelection.kt` | Always advertises (+10 dB), dials heard honest nodes, never closes, never forwards, empty neighbor list, answers shuffles with fake IDs only. |

## Run

Requires JDK 21 at `/opt/homebrew/opt/openjdk@21` (see deviations).

```sh
cd prototypes/routing-sim
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew -q simLib                          # jar + stdlib into build/sim/lib
CP='build/sim/lib/*'; M=meshlink.routingsim.runner.MainKt
java -cp "$CP" $M help
java -cp "$CP" $M probe medium,200,waypoint,android,5      # one cell, both strategies, seed 1
java -cp "$CP" $M pilot --out out --threads 4               # wall-time pilot
java -cp "$CP" $M calibrate-drop                            # supervision-drop fit against cairn
java -cp "$CP" $M timeseries --out DIR                      # warm-up justification
java -Xmx3g -cp "$CP" $M stage-a --out DIR --threads 4 --seeds 10 --plan full   # resumable
java -cp "$CP" $M aggregate --out DIR
java -cp "$CP" $M report --out DIR                          # DIR/stage-a-report.html (embeds DIR/findings.html and DIR/run-notes.txt if present)
```

`stage-a` writes `stage-a-raw.csv` (one row per run), resumes by skipping run IDs already present, then writes
`stage-a-aggregate.csv` (mean and 95% CI half-width per cell × strategy, Student t). Every run is fully determined
by (scenario, strategy, seed): independent RNG streams for placement/roles, churn, mobility, shadowing, protocol and
each node's strategy instance; the world streams do not depend on the strategy, so N0 and N1+N2 runs of the same seed
see the same placement, movement and churn.

## Layout

- `src/commonMain/.../strategy/` — strategy code. No `java.*`; it talks to the world only through `Clock`,
  `Randomness`, `LinkPort` and `LinkTable` (`Ports.kt`), so it could be lifted into Kotlin common code. The project
  is Kotlin Multiplatform with only a `jvm()` target to enforce this.
- `src/commonMain/.../routing/` — stage B protocols (U1–U4, B1–B3, gossipsub), message lifetime rules and holding.
  Same rule: no `java.*`; protocols see the world only through `RoutingPort` (`RoutingPorts.kt`).
- `src/jvmMain/.../sim/` — simulator core: binary-heap event queue, radio, mobility, links, churn, metrics;
  `RoutingLayer.kt` multiplexes protocol instances over the topology and generates traffic.
- `src/jvmMain/.../runner/` — grid definition, thread pool (≤ 4), CSV, aggregation, HTML reports
  (`HtmlReport.kt` stage A, `StageBReport.kt` stage B, `StageB.kt` stage B grid).

## Model

Link-level, not packet-level.

- **Radio.** RSSI = tx 0 dBm − 40 dB − 10·3·log10(distance) + per-pair Gaussian shadowing (σ 4 dB, fixed per pair) +
  per-scan noise (σ 2 dB). Sensitivity −88 dBm (≈ 40 m mean range). A link drops when the mean pair RSSI falls 2 dB
  below sensitivity (checked every 2 s mobility tick).
- **Density** = peers in radio range. The area is sized per (N, mobility) by binary search so that the mean
  peers in range at placement hits the class target: low 6, medium 25, high 100 (classes 2–10, 10–50, 50–200).
  Measured values are reported per cell (`peersInRange`); random waypoint concentrates nodes in the middle and
  overshoots (≈ 9 / 35 / 136) but stays inside the classes.
- **Mobility.** Static crowd; random waypoint 0.8–1.8 m/s with ≤ 30 s pauses; group mobility (groups of 8 within
  15 m around a waypoint-moving leader); corridor (waypoint in a 10 m wide strip, i.e. a chain); partition-and-merge
  (two halves walk 0.55 × max range apart at 1.4 m/s at minute 4 and return at minute 8).
- **Scanning / advertising.** Every node ticks every 2 s: each in-range advertiser is heard with probability 0.8,
  then the strategy's `onScan` and `onTick` run. Only nodes with a free slot advertise (N3).
- **Dial.** Connect phase U(700, 1200) ms → failure check → Noise handshake U(55, 90) ms + one RTT U(34, 56) ms.
  Failure probability = min(0.6, base + peersInRange/400), base 0.3 for a first attempt and 0.1 for a retry within
  30 s of a failure on the same pair. Dials also fail when the target is gone, out of range or out of slots
  (pending dials count against d on both sides).
- **Links.** Capacity U(62, 89) KB/s / (1 + peersInRange/100); FIFO queue per direction, delivery after the
  one-way latency (RTT/2). Only control traffic (neighbor lists, shuffles) is carried in stage A.
- **Supervision drop.** Exponential link-drop hazard, mean 45 s (fitted, see calibration).
- **Churn.** Each honest node leaves after Exp(mean 10 min), stays away 30 s, rejoins with a fresh strategy
  instance. Initial joins spread over the first 10 s.
- **Platform mix.** Android-only, or 50/50 Android/iOS where 50% of iOS nodes are backgrounded: awake 10 s every
  60 s (random phase); asleep = all its links drop and it neither scans nor advertises.
- **Sizes.** Routing tag 8 B, message ID 16 B (not used in stage A), frame header 4 B. Neighbor list = header + 8 B per ID; shuffle =
  header + 8 B per ID, same-size reply.
- **Duration.** 15 min simulated, metrics from minute 2. The `timeseries` command shows the largest connected
  component settles within ~60 s (N=200…1000, low/medium/high density), so 2 min warm-up is conservative and
  13 min of measurement covers ~1.3 churn cycles per node and ~17 supervision-drop means.

### Metrics (research note §6.3, topology subset)

Sampled every 5 s over online honest nodes: largest connected component fraction (`lcc`), the same on the full radio
graph (`radioLcc`, upper bound), component count (singletons included), isolated nodes, slot occupancy, Sybil slot
share and peer-table share. Every 30 s: diameter and mean path length in the LCC (exact ≤ 300 nodes, otherwise 32
BFS sources with double sweep — a lower bound). Per run: dials and link-ups per node-minute, dial failure rate,
link lifetime median/p90, control bytes per node-second and as share of capacity, time to first link for nodes that
rejoin with a peer in range (share within 60 s, median, p95; unconnected joins count with their elapsed time, a
lower bound), and for partition-merge: time from the first radio edge between the halves to the first bridging link,
and until LCC ≥ 0.9 × radio LCC (censored at 420 s).

## Calibration

| Parameter | Value used | Source |
|---|---|---|
| GATT echo RTT (2M PHY) | U(34, 56) ms | MeshLink device-lab bake-off smoke, Pixel 6 ↔ Galaxy S10e, MTU 517, 2026-11 |
| Link throughput | U(62, 89) KB/s (2M PHY); 1M PHY 40–44 KB/s not used | same bake-off smoke |
| Noise XX handshake | U(55, 90) ms (median range; XXhfs 76–159 ms not used in stage A) | same bake-off smoke |
| L2CAP CoC open | 74–99 ms — folded into the connect phase | same bake-off smoke |
| First-attempt connect failure | 0.3; retry failure 0.1 (status 147/133 seen often) — **provisional, recalibrate after the full matrix** | same bake-off smoke |
| Link setup + authentication | ≈ 1.1 s total (connect U(0.7, 1.2) s + handshake + RTT) | cairn (sibling project, ~15 phones) |
| Supervision-drop hazard | exponential, mean 45 s | fitted: `calibrate-drop` (15 nodes in a 15 m room, d=5, N0, 10 seeds) gives link-lifetime median 26.4 s / p90 50.4 s vs cairn's 27 s / 52 s with rotating eviction. Mean 60 s gave 30.4 / 57.4 s. |
| Legacy old phones | ≈ 9–10 KB/s — not used (no low-end mix in stage A) | cairn |
| Airtime contention | throughput × 1/(1 + peersInRange/100); setup failure + peersInRange/400, capped at 0.6 | **assumption** (no measurement) |
| Degree budget d | sweep {3, 5, 6, 8}; platform defaults Android 5 (API ≤ 34) / 6 (API 35+), iOS 6 **unverified** | research note §2 |
| iOS background | awake 10 s every 60 s, 50% of iOS nodes backgrounded | ticket design (assumption) |
| Sybil radio budget | 16 concurrent links per fake identity | AOSP `GATT_MAX_PHY_CHANNEL` (an attacker with one radio per identity is generous) |
| Path loss | exponent 3, 40 dB at 1 m, shadowing σ 4 dB, sensitivity −88 dBm | generic indoor/crowd BLE values (assumption) |

## Grid (stage A)

- Main: density × N ∈ {low: 10, 50, 200, 1000; medium: 50, 200, 1000; high: 200, 1000} × 5 mobility × 2 platform
  mixes × d ∈ {3, 5, 6, 8} × 2 strategies × 10 seeds = 7,200 runs.
- Skipped as impossible: medium density with N = 10 (at most 9 peers in range < 10) and high density with N = 10 or
  50 (at most 49 < 50).
- Sybil: 10% and 30% fake identities × {low, medium, high} × N = 200 × {static, waypoint} × Android × d = 5 × 2
  strategies × 10 seeds = 240 runs.
- Supervision-drop sensitivity: mean 180 s and no drops × medium × N = 200 × {static, waypoint, group} × Android ×
  d = 5 × 2 strategies × 10 seeds = 120 runs.
- `--plan pruned` exists as a fallback (fewer mobility × d cells at N = 1000) but was not needed: the full grid
  (7,560 runs) took 70 min wall at 4 threads on the shared machine (pilot extrapolation: ~50 min).

## Stage B: routing and mesh broadcast

Runs on the stage A winner, N1+N2 with d ∈ {5, 6}. All protocols of one run are **multiplexed** over one simulated
topology: each protocol instance has its own per-link FIFO queues and state, but they share placement, movement,
churn, links and the offered traffic (separate RNG streams). Verified: the topology metrics of a run are identical
with and without the routing layer, so instances do not influence each other or the topology.

| Protocol | Code | Model |
|---|---|---|
| U1 managed flood | `routing/Flood.kt` | Forward every new message on all links except ingress; message-ID dedup (lifetime-bounded seen cache); hop limit 255; no holding. |
| U2 scoped topology + source route | `routing/ScopedSourceRoute.kt` | Neighbor announcements (≤ 10 tags) every 15–30 s and ~1 s after link changes (≥ 4 s apart), flooded 3 hops; source route over fresh (60 s), bidirectionally confirmed edges, else flood; no holding. |
| U3 Babel-like distance vector | `routing/DistanceVector.kt` | RFC 8966 simplified: Hello 4 s, full update 16 s, triggered updates ≥ 1 s apart, feasibility distance, retractions, route expiry 56 s, seqno requests (unicast to the neighbor with the unfeasible route, ≤ 3 per seqno). Hop-count metric, no ETX/hysteresis. |
| U4 on-demand (AODV-like) | `routing/OnDemand.kt` | RFC 3561 simplified: expanding ring 1, 3, 5, 7 then 64 hops with 2 retries, RREP on reverse route, destination seqnos, intermediate replies, active route 30 s, RERR to precursors, local repair. No Hellos (link sensing from the link layer). |
| B1 flood / B2 fanout subset | `routing/Flood.kt` | B2: relays forward to a message-ID-seeded subset of max(2, ⌈log₂ links⌉) links; the originator sends on all. |
| B3 Plumtree | `routing/Broadcast.kt` | Eager push on tree links, lazy IHAVE on the rest, PRUNE on duplicate, GRAFT after 500 ms (retry 250 ms), 60 s message cache; new links start eager. |
| gossipsub | `routing/Broadcast.kt` | v1.1/v1.2 single topic, no peer scoring; D = 3 (D_lo 2, D_hi 4, D_lazy 3) scaled to BLE degree, 1 s heartbeat, flood publish, IHAVE over 3 windows, IWANT, IDONTWANT ≥ 1 KB, prune backoff 10 s. |

- **Message lifetime rules (ticket design).** Every relay checks expiry against its own skewed clock before forwarding,
  caching or holding; expired messages are dropped everywhere (`HoldQueue.sweep`, `MessageCache.get`, relay checks). A message
  enters store-and-forward holding only if `expiresAt − now ≥ minimum hold` (`mayHold`). U3/U4 have a short ungated
  route wait (U3 2 s convergence, U4 one discovery) before the gated hold; held messages retry every 10 s; hold
  capacity 100 messages per node. U1/U2/B* do not hold (flooding needs no route).
- **Clock skew.** Per node and instance, offset = U(−1, 1) × skew (0 or 30 s). `expiresAt` is stamped with the
  originator's clock.
- **Routing tags and rotation.** Destinations are 8 B tags. With rotation, every node's tag changes at simulated time
  500 s (one 15-minute epoch boundary inside the measurement window), at its own skewed clock; nodes accept tags of
  epochs e−1, e, e+1, so traffic to the old tag still reaches the node, but routes keyed by tag must be rebuilt.
- **Links are acknowledged.** When a link goes down, queued packets are refunded and packets already on the air are
  lost (`lostInFlight`); unicast protocols get `onSendFailed` for every unacknowledged packet and may retry.
- **Traffic.** Starts at 60 s. Chat: each honest node sends Poisson with mean gap 240 s to one of its contacts (2 near
  ≤ 60 m, 1 random far), payload 256 B + 24 B; the first message of a pair carries a Noise IK first message (+96 B
  classical; 50% are post-quantum, +2,396 B). Mesh broadcast: one per 15 s mesh-wide from a random honest node, 256 B.
  Per-hop processing 2–10 ms. Messages sent in the last 60 s are not measured.
- **Metrics.** `delivery` = delivered share of messages whose destination was online and in the sender's connected
  component at send time (`deliveryAll` counts all); latency p50/p95/p99; first-message p95 for pairs ≤ 3 hops apart
  (classical / PQ); transmissions per delivery; per-node bytes sent (mean, p99, max); control bytes; idle control =
  periodic upkeep (Hello, periodic updates, announcements, heartbeats, IHAVE gossip) as share of link capacity in the
  measurement window; drop reasons per message; hops; broadcast coverage (mean, p10, share ≥ 99%).
- **Bridge variant (`N1+N2+bridge`, `DiverseViewSelection(bridge = true)`).** A peer heard for the first time after
  ≥ 60 s of silence (within the last 10 s), not in the node's 1–2-hop reach, RSSI ≥ −88 dBm, is dialled at most once
  per 10 s; if all slots are full, the most redundant link is dropped first (only if it has redundancy > 0).

### Stage B commands

```sh
java -cp "$CP" $M b-probe medium,200,waypoint,android,5            # one run, every protocol instance printed
java -cp "$CP" $M b-probe low,1000,static,android,5,1,main --only U4
java -Xmx3g -cp "$CP" $M stage-b --out DIR --threads 4 --n1000-mobility static --n1000-seeds 5 --n1000-u3-seeds 0 [--dry]
java -cp "$CP" $M stage-b-aggregate --out DIR
java -cp "$CP" $M stage-b-report --out DIR                         # DIR/stage-b-report.html (+ findings.html, run-notes.txt)
```

`stage-b` is resumable (skips run IDs already in `stage-b-raw.csv`) and runs at most two N = 1000 routing runs at a
time (each peaks near 1 GB of heap).

### Stage B grid and pruning

Requested: N ∈ {50, 200, 1000} × {static, walking, group, corridor} × {Android, 50/50} × d ∈ {5, 6} × 10 seeds.
Density classes per N as in stage A (N = 50: low and medium). Pilot: N = 200 runs ~37 s, N = 1000 static runs without
U3 ~1–2 min, and a single N = 1000 U3 run did not finish in 16 min; the full grid projected to ≥ 28 h. Cuts, in order:
(1) N = 1000 static only (pre-agreed); (2) no U3 at N = 1000; (3) 5 seeds at N = 1000. N = 50 and N = 200 run the full
requested grid. Extra runs: lifetime × hold × skew sweep (N = 200, low/medium, static/walking, Android, d = 5),
bridge partition-merge (all sizes, topology only), bridge with traffic (low, N = 200), bridge regression (N = 200).
The exact list is in `run-notes.txt` next to the results. Actual: 1,500 runs, 0 failures, 10,376 s (2 h 53 min) wall
at 4 threads on the shared machine (N = 1000 main runs 2–5 min each, N = 200 sweep runs 3–4 min each).

## Deviations and caveats

- **JDK 21 instead of JDK 25** (ADR 0005 baseline): the task assumed JDK 25 was not installed and prescribed JDK 21.
  In fact a JDK 25.0.4 (sdkman, `~/.sdkman/candidates/java/current`) exists and is the default `java` on PATH; all
  runs used JDK 21 explicitly as instructed. Kotlin 2.4.21, Gradle wrapper 9.7.1 as per the baseline; `jvmToolchain(21)`.
- **Stage A control share fix.** Stage A's `controlShareOfCapacity` divided by link capacity accumulated over the
  whole run including warm-up, while control bytes were counted only after warm-up, so it was understated by roughly
  the warm-up share (about 13%, i.e. ×13/15). Fixed in stage B (capacity counted only in the measurement window); stage A
  results were not re-run (both strategies carried the same bias, so the comparison is essentially unaffected).
- Stage B: 1 h and 24 h lifetimes cannot expire inside a 15-minute run; they show the "hold until a route appears"
  limit, not long-term storage. Each run contains exactly one tag-epoch boundary.
- Stage B U3 is a simplified Babel (hop count, no ETX, no hysteresis, no source-table garbage collection). Its
  sequence-number request traffic under the calibrated link churn dominates simulator cost and likely overstates
  what a tuned babeld would send; treat U3 control-traffic numbers as an upper bound.
- Kotlin Multiplatform with only a JVM target, purely to keep strategy code free of JVM APIs.
- The swept d applies to iOS nodes as well (the iOS limit is unverified).
- Supervision drops are fitted under N0 rotation; leave and sleep are detected immediately (no supervision delay).
- Advertising-channel contention is represented only through the contention factor.
- Diameter is a lower bound when the LCC has more than 300 nodes.
- The N1 shuffle mainly adds knowledge (passive-view entries); it only adds reachability once a link is dialled,
  which still needs a scan sighting (the simulated radio cannot dial a node it has not heard).
- Stage A carries no data traffic, so N0's "idle ≥ 30 s" rotation rule equals "link age ≥ 30 s": with supervision
  drops switched off, N0 link lifetimes pile up at ~31 s. With real traffic N0 would rotate less.
- Diameter is measured on the largest connected group, so it is only comparable when both strategies reach a similar
  group size (at d = 3, N1+N2's larger group also has a larger diameter).
- The Sybil attacker is strong (dials every advertiser it hears, +10 dB) and the strategies have no dedicated
  defenses; the Sybil results measure the strategies' inherent exposure, not MeshLink's final design.
