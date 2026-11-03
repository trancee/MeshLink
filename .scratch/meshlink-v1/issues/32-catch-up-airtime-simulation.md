# Does catch-up of held messages and tombstones fit the airtime budget?

Type: prototype
Status: open

## Question

Rerun the routing simulation with ADR 0014's catch-up (held broadcasts, held unicasts as {message ID, destination tag}, tombstones, 1 h maximum lifetime, quota of 100 with ¼ per link) and ADR 0015's large messages (up to 128 KiB, route-only at every density, with a route request first at low density, held only by the origin) and decide whether delivery, airtime and busiest-phone load stay within the ADR 0008 budgets at low, medium and high density.

## Context

- ADR 0008 simulated catch-up of the last 60 s of message IDs only, and single-copy holding by nodes without a route.
- Throwaway simulator on branch `prototype/routing-sim` (worktree `../MeshLink-routing-sim`).
- Results are labeled simulated (P6).
