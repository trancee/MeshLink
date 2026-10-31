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
