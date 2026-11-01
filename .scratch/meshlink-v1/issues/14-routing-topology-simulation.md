# Which routing and topology strategy should MeshLink use?

Type: prototype
Status: claimed
Blocked by: 01, 04

## Question

Build a throwaway simulation comparing the shortlisted strategies (incl. neighbor selection under connection limits) across low/medium/high density and up to ~1,000 nodes: delivery ratio, latency, control overhead, energy proxy, churn tolerance. Decide routing for unicast, mesh broadcast and store-and-forward hooks, and validate or revise the density classes.

## Scope additions (from research, 2026-10-31)

- Shortlist ([routing findings](04-routing-topology-strategies.md)): neighbor selection (strongest-signal vs HyParView-style views); unicast (flood, source routing, Babel, AODV-style); broadcast (flood, fanout subsetting, Plumtree); store-and-forward (sender-held, spray-and-wait, epidemic bound).
- Throwaway Kotlin/JVM discrete-event simulator calibrated from device-lab timings; kalasim needs dependency approval; ONE is GPLv3 (external reference only).
- Also decide the routing metric (hops + failures vs RSSI) and peer-table size/eviction that resists fake identities.
- From the [threat model](08-threat-model.md): measure (1) the cost of route relearning when source/destination tags rotate per epoch, across epoch lengths and density classes, and (2) the extra latency of per-hop reassembly for multi-fragment messages. If (1) is unacceptable, return to the user; don't silently fall back to stable IDs.
- From the [suite decision](09-security-profile-suites.md): model `XXhfs` link-establishment airtime under link churn, and first-message latency for classical vs post-quantum by hop count and density class (an explicit metric; the budget is set by the user).
- From libp2p prior art (2026-11-01): add gossipsub to the broadcast shortlist next to Plumtree: mesh degree `D` with GRAFT/PRUNE and lazy IHAVE/IWANT (v1.0), peer scoring under Sybil pressure (v1.1), IDONTWANT duplicate suppression at high density (v1.2). See the libp2p section of the [prior-art findings](../../../docs/research/2026-10-31-prior-art-ble-meshes.md).
- From the [toolchain baseline](11-toolchain-baseline.md) (ADR 0005): the simulation is throwaway. If it is written in Kotlin, use the baseline tuple, and add no new dependency without a register row and approval.

## Design (accepted 2026-11-01)

Grilling round 1, all recommendations accepted, with one amendment to stage C.

- **Simulator:** hand-rolled Kotlin/JVM simulator with only the Kotlin standard library, in `prototypes/routing-sim/` on throwaway branch `prototype/routing-sim`. Strategy code uses no JVM-only APIs.
- **Model:** links, not radio packets. RSSI from path loss plus shadowing. Links have setup time, failure probability and limited throughput. At high density, a per-area contention factor slows links. No collision model.
- **Calibration:** bake-off smoke numbers now (setup times, 147/133 first-attempt failures, 2M about 60–100 KB/s, 1M about 40–55 KB/s, round trips about 25–55 ms, handshakes XX 55–90 ms / XXhfs 76–159 ms), plus cairn's figures (setup plus authentication about 1.1 s, link lifetimes). Recalibrate after the full matrix.
- **Degree budget:** sweep 3, 5, 6 and 8. Android 5 (API 34 and older) or 6 (API 35+); iOS 6, **unverified**. An iOS maximum-connections measurement is added to the bake-off hub step.
- **Stages:**
  - **A:** link choice, N0 vs N1+N2, each with N3.
  - **B:** on the stage A winner. Unicast: U1–U4. Broadcast: B1, B2, B3 and gossipsub.
  - **C:** store-and-forward: S0, S1 (L = 2, 4, 6), S2 as an upper bound, with message-lifetime rules:
    - never forward or hold an expired message;
    - take a message into store-and-forward only if its remaining lifetime is at least the minimum hold time;
    - sweep lifetimes of 30 s, 5 min, 1 h and 24 h, minimum hold times of 0, 10 s and 60 s, and clock skew of ±30 s.
  - **D:** security costs: tag-rotation periods of 1, 5, 15 and 60 min; per-hop reassembly; XXhfs link setup under churn; first-message latency, classical vs post-quantum.
- **Scenarios:**
  - density low, medium and high × N of 10, 50, 200 and 1,000;
  - mobility: static crowd, random waypoint, group, corridor, partition and merge;
  - Android only, and 50/50 with iOS background windows of about 10 s;
  - preset traffic;
  - 10 seeds per cell, with 95% confidence intervals.
- **Decision criteria (pre-registered):**
  - delivery within a connected component of at least 99% at low and medium density, at least 95% at high;
  - idle control traffic at most 10% of link capacity;
  - among passing strategies, pick the lowest maximum per-node load, then the lowest p95 latency.
- **First-message latency budget:** p95 at most 1 s (classical) and 1.5 s (post-quantum), for 3 hops or fewer on established links.
- **Tag rotation:** acceptable if it adds at most 10% transmissions and costs at most 1 percentage point of delivery at a 15-minute period. Otherwise return to the user.
- **Sizes:** default routing tag 8 B and message ID 16 B; both swept.
- **Sybil:** 10% and 30% fake identities, testing link choice and peer-table eviction only. gossipsub scoring is tested only if gossipsub makes the shortlist.
- **Density classes:** confirm or revise from where the delivery criteria start to fail.
- **Execution and outputs:** run by a background agent on the Mac, reporting after each stage.
  - Summary, aggregated CSV and a self-contained HTML report go to `docs/evidence/2026-11-routing-simulation/`, followed by a routing ADR.
  - Raw outputs go to the session folder.
- **Terms:** "message lifetime" (time) and "hop limit" (relays) replace "TTL" (GLOSSARY).

## Stage A verdict (accepted 2026-11-01)

These results are simulated, not measured on devices. The evidence is on throwaway branch `prototype/routing-sim` (`87eff78`, `aedc261`, `4742911`); the HTML report is in the session files.

- **Q1, winner:** neighbor selection is **N1+N2 (diverse views)** with target degree d=5.
  - It forms a larger connected group in 216 of 360 cells (N0 better in 6) and a shorter diameter in 219 (N0 better in 30).
  - A joining phone gets its first link faster in 303 cells and never slower.
  - At d=3, medium density, 200 phones, static: group 0.97 vs 0.41.
  - Overhead is at most 0.12% of link capacity.
- **Q2, partition repair:** at low density, N1+N2 links two rejoining groups more slowly (5.7±1.8 s vs 2.0±0.6 s). Stage B also tests N1+N2 plus "prefer a newly discovered phone from another group".
- **Q3, fake identities (Sybil):**
  - At 10% fakes, N1+N2 keeps honest phones much better connected (0.91 vs 0.61).
  - At 30% fakes, both strategies collapse.
  - This becomes a documented limitation of open and unsecured meshes, and an open question on the map (identity cost or peer scoring). It is not fixed in v1 routing.
- **Q4, unverified inputs:** the iOS link limit, the 30% first-attempt failure rate, the contention factor and the 45 s link-drop fit get filled in from the bake-off. Rerun only if they would change the winner; a rerun without drops picked the same winner.
- **Q5, stage B:** routing options U1–U4 and broadcast options B1–B3, plus gossipsub, all on N1+N2 topology.
  - Grid: N ∈ {50, 200, 1000} × mobility {static, walking, group, corridor} × both platform mixes × d ∈ {5, 6} × 10 seeds.
  - Pilot first. If the run is over 3 h, cut the N=1000 mobility cells and report exactly what was cut.

## Stage B verdict (accepted 2026-11-01)

These results are simulated, not measured on devices. Evidence: throwaway branch `prototype/routing-sim` (`4427579`, `35a8851`); the HTML report is in the session files. Stage B ran 1,500 runs with 0 failures in 2 h 53 min at 4 threads.

- **Q1, low-density target:** no protocol reaches 99% delivery in every low-density cell, not even flooding (U1: 30 of 36 cells, mean 99.55%). The misses come from corridor cells, half-iOS cells and N=1000 static, so the limit is physical reach and iOS wake, not protocol choice.
  - **Amended criterion:** at low density, delivery must come within 0.5 pp of flooding in the same cell, and 99% still applies where flooding reaches it.
  - Medium density (99%) and high density (95%) are unchanged.
- **Q2, unicast:**
  - U1 is best at low and medium density (busiest-phone load at medium density: U1 3.2 KB/s, U3 4.0 KB/s).
  - U4 is best at high density (0.38 KB/s on the busiest phone, about 10× less than flooding; p95 2.2 s). Its p95 at low density is 9.5 s.
  - **Dropped:** U2, which behaves like flooding, and U3, which can't be simulated at N=1000, whose request traffic floods under churn and which is the most sensitive to tag rotation.
  - Stage C compares U6, a hybrid (route when a route is known, otherwise a limited flood or hold), and U4 with tuned store-and-forward against U1.
- **Q3, broadcast:**
  - B1 is best at low density (32 of 36 cells).
  - At medium density gossipsub, B3 and B1 pass, in that order (45 / 48 / 72 B/s).
  - **Dropped:** B2, which reached only 62% in its worst cell.
  - Stage C tests density-adaptive broadcast, switching from B1 to B3 or gossipsub, and picks one of those two.
- **Q4, bridge rule** (prefer a newly discovered phone from another group):
  - The first link between rejoining groups forms faster in every cell, for example 6.5 → 1.7 s (low density, N=200, half iOS) and 20.8 → 5.6 s (high density, N=1000).
  - Cost: 40–60% more link setups in moving crowds.
  - Adopted with a rate limit, which stage C tunes.
- **Q5, minimum message lifetime:**
  - A 30 s lifetime with ±30 s clock skew delivers only 55–87%; at 5 min or more, skew has no effect.
  - Stage C runs 60 s and 120 s lifetimes to set the minimum allowed lifetime. Config validation rejects anything shorter.
- **Q6, iOS wake windows** cause most delivery and first-message misses. Stage C adds an iOS-aware relay preference: prefer relays that are awake, such as Android or foreground phones.
- **Q7, tag rotation:** judged per density class, where every protocol stays within budget. Only U2 and U3 exceed it in single cells (+11.2% transmissions; −1.75 pp delivery), and both are dropped.
- **Q8, cuts:** at N=1000, stage B ran static only, with 5 seeds, and without U3. Accepted; stage C keeps the same N=1000 scope.
  - Stage C also runs 1-hour simulated runs for the 1 h and 24 h lifetimes, about 3 h in total.

## Dynamic local density (accepted 2026-11-01)

The user pointed out that density changes over time. A phone walking to a festival goes from low to medium to high density, and a dense core and a sparse edge coexist in one mesh. Stage C is rescoped around this. Accepted:

- **Q1:** each node estimates its own local density from what it observes alone: a smoothed count of distinct peers heard in recent scans, plus its link count. There is no mesh-wide coordination. The count window must be shorter than the identifier rotation period.
- **Q2:** class switches use hysteresis (different up and down thresholds) plus a minimum dwell time of 30–60 s. Target: at most a few switches per hour for a phone at a threshold. Stage C sets the values.
- **Q3:** adapting changes only local forwarding behavior, never the wire format. Every node always receives and relays every message type, so phones in different modes interoperate across a density boundary. Mixed-mode meshes are tested.
- **Q4:** adapted per class:
  - unicast and broadcast mode;
  - target degree d;
  - scan and advertise duty cycle;
  - bridge rate limit;
  - store-and-forward eagerness.
- **Q5:** presets set limits and bias, not a fixed class. The default is automatic, and an app may pin a class. The class and switch events appear in diagnostics only; ADR 0007 is unchanged.
- **Q6:** stage C adds dynamic scenarios: arrival, departure, dense core with sparse edge, surge, and threshold oscillation.
  - Each compares the adaptive policy with the best single fixed configuration and with flooding everywhere.
  - Metrics: delivery and p95 latency during transitions and across the boundary between modes, time to switch, switches per hour, busiest-phone load, and control traffic.
  - Budget: about 4–5 h.
- **Q7:** "Local density" is added to the glossary. The class boundaries stay provisional until stage C confirms or revises them.

## Stage C verdict (accepted 2026-11-01)

All numbers are simulated. The stage C report is in the session files (`routing-sim/stage-c/`); the prototype is on the `prototype/routing-sim` branch (`9637738`).

- **Q1:** a short stage D closes the gaps before the ADR:
  - keep routes alive during conversations (target unicast p95 ≤1 s at medium and high density);
  - a phone with fewer than 5 peers in range always floods its own messages;
  - broadcast in the large half-iOS mesh at medium density (98.86% vs 99%).

  What stays unfixed becomes a documented limitation.
- **Q2:** the stage C configuration is accepted, provisional until the bearer bake-off calibration:
  - 10 s estimator window;
  - thresholds up at 12.5 / 62.5 and down at 8 / 40, with a 60 s dwell;
  - d = 6 / 5 / 5;
  - low: flood; medium and high: U6 + Plumtree;
  - scan every 2 / 4 / 6 s;
  - bridge limit one per 60 s;
  - minimum lifetime 120 s;
  - store-and-forward: 100 messages, 10 s minimum hold.
- **Q3:** the iOS-aware relay preference is dropped from v1 (no delivery gain, +29 ms first-message p95, +4% load).
- **Q4:** v1 offers a low-latency preset that pins the low class (flood everywhere).
- **Q5:** radio power and real iOS background behavior are open questions to measure on devices.
- **Q6:** the routing ADR is written after stage D, then this ticket is resolved.
