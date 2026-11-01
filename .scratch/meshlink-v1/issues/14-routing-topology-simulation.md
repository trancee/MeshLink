# Which routing and topology strategy should MeshLink use?

Type: prototype
Status: open
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
