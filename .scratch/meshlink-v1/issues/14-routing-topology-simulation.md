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
