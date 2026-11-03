# What is the message size cap, and is there compression?

Type: grilling
Status: resolved

## Question

Set the maximum application payload for unicast and broadcast per profile, the link record size cap (ADR 0012), and decide no compression vs bounded compression.

## Context

- Inputs: bake-off throughput (ADR 0009), per-hop reassembly cost (ADR 0008), post-quantum overhead (+1,120 B first message, ADR 0002), 32 B padding (ADR 0012).
- Prior art: Bridgefy gzip-bomb and compression side-channel lessons (ticket 01).

## Resolution

Two unicast size classes: small (at most 4 KiB) behaves as before; large (up to 128 KiB, for profile pictures) is never flooded, travels only over a direct link or a discovered route, and is held only by its origin. Broadcast cap 1 KiB; same caps in every profile; no compression (side-channel warning for apps). Record body cap 1,024 B. Reassembly: 4 frames per link (1 large), 1 MiB large per node, 10 s idle timeout, link-local busy reply. Memory: hold 512 KiB (small only), inbox 1 MiB, send queue 1 MiB. Apps may only lower caps; `EmergencyBroadcast` and `ProximityDiscovery` default to 4 KiB. See [ADR 0015](../../../docs/adr/0015-message-size-and-compression.md); amends ADRs 0007, 0008, 0011, 0012 and 0014.
