# Does catch-up of held messages and tombstones fit the airtime budget?

Type: prototype
Status: resolved

## Question

Rerun the routing simulation with ADR 0014's catch-up (held broadcasts, held unicasts as {message ID, destination tag}, tombstones, 1 h maximum lifetime, quota of 100 with ¼ per link) and ADR 0015's large messages (up to 128 KiB, route-only at every density, with a route request first at low density, held only by the origin) and decide whether delivery, airtime and busiest-phone load stay within the ADR 0008 budgets at low, medium and high density.

## Context

- ADR 0008 simulated catch-up of the last 60 s of message IDs only, and single-copy holding by nodes without a route.
- Throwaway simulator on branch `prototype/routing-sim` (worktree `../MeshLink-routing-sim`).
- Results are labeled simulated (P6).
- Also include ADR 0016: per-conversation routes (conversation tags after the first message, public tag for first messages) instead of per-node routes; confirm route-discovery cost and delivery stay within budget, or return the decision to the project owner.
- Also include ADR 0018: certificate lookups (salted, flooded under route-request rules, answered by D or a node caching its certificate, at most one outstanding per NodeId) before first messages to nodes without a cached certificate; confirm the cost stays within budget and whether route requests need a rate limit.

## Answer

Stage F of the routing simulation ([evidence](../../../docs/evidence/2026-11-routing-simulation/README.md#stage-f-store-and-forward-conversation-routes-lookups-and-power), simulated). The owner amended two criteria after the first results: an absolute busiest-phone budget (4,700 B/s, +3 mW) replaces the 1.25× ratio, and rules apply only to samples of 100 messages or more.

- **Catch-up (ADR 0014):** within budget. Idle control is at most 0.32% of capacity in 30 min runs and 0.43% in 1 h runs.
- **Large messages (ADR 0015):** 96.4–100% delivered when both ends stay in the mesh.
- **Per-conversation routes (ADR 0016):** cost the same as per-node routes. The decision stands; the A2L fallback was not adopted.
- **Certificate lookups (ADR 0018):** within budget once relays pass on only the first reply; ADR 0018 is amended. Before that, up to 47 cache answers per lookup doubled the busiest phone's load.
- **Rate limit:** none in v1; a 1 lookup/s attacker cost 0.02 pp of delivery at most and +10–16% busiest-phone load.
- **Final arm:** passes 9 of 10 main cells. The tenth is one lost message that the v1 reference also loses.
- **1 h runs:** one high-density half-iPhone cell misses the post-quantum lookup latency rule, because of a 50 s iPhone background sleep, not the lookup.

ADRs 0008 (v9), 0014 (v5), 0015 (v2), 0016 (v3) and 0018 (v2) are amended. Prototype: branch `prototype/routing-sim`.
