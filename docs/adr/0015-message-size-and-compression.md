# 0015: Message size and compression

Status: accepted (2026-11-03; ticket "What is the message size cap, and is there compression?"). Version: 2 (amended 2026-11-08, stage F simulation).

## Context

- **User requirement:** apps must be able to send at least profile pictures of about 100 KB.
- **Throughput (ADR 0009):** 19–31 KB/s per link with an iPhone central, 47–82 KB/s between Androids; 2M PHY gives 1.4–1.9× on Android. A 128 KiB message therefore takes about 4–7 s per hop on the slowest pairing.
- **Routing (ADR 0008):** unicast floods at low density, and every hop reassembles the whole message before forwarding. Flooding 128 KiB costs about 1 MB of airtime per 10 nodes.
- **Holding (ADR 0014):** relays hold up to 100 messages; the byte cap was left to this decision. 100 messages of 128 KiB would be 12.5 MiB per phone.
- **Wire format (ADR 0012):** a fragment carries a 1 B link-local stream ID and a 2 B index (up to 32,767 fragments); streams interleave so a large message does not block small ones. The record size cap was left to this decision.
- **Security overhead:** `post-quantum` adds 1,120 B to a first message (ADR 0002); padding rounds to 32 B (ADR 0012).
- **Prior art ([research §compression](../research/2026-10-31-prior-art-ble-meshes.md)):** bitchat caps messages at 1 MiB, held messages at 16 KiB, and keeps 128 concurrent reassemblies with a 30 s timeout. Meshtastic caps at 239 B. Bridgefy's compression enabled a decompression bomb and a compression side channel.

## Decision

1. **Size classes of unicast:**
   - **Small message:** at most 4 KiB of payload. Behaves as decided in ADRs 0008, 0013 and 0014: flooded at low density, held by relays, offered on catch-up.
   - **Large message:** more than 4 KiB and at most 128 KiB of payload. Never flooded, in any density class. It goes over a direct link, or along a route that a route request (ADR 0008) found or confirmed; at low density the origin sends a route request first. Relays forward it but never hold it; only the origin holds and retries it, on the retry schedule of its density class.
2. **Mesh broadcast cap:** 1 KiB of payload.
3. **Same caps in every security profile.** Caps count application payload only; handshake, `post-quantum` and padding overhead never count against them.
4. **No compression in v1.** MeshLink neither compresses nor decompresses. The API reference and the sending guide warn that an app compressing secret data together with attacker-influenced data leaks it through ciphertext length (CRIME/BREACH-style).
5. **Link record cap:** a record body is at most 1,024 B, in every profile and bearer. Fragments of different frames interleave per link. There is no negotiation.
6. **Reassembly limits:**
   - per link: at most 4 frames reassembling at once, at most 1 of them large;
   - per node: at most 1 MiB of large-frame reassembly;
   - per stream: 10 s idle timeout, then the partial frame is discarded.
   - A large frame refused for lack of budget is answered link-locally with "busy, retry later", never dropped silently. The sending node backs off and retries within its retry schedule. A relay that cannot pass a large message on before its reassembly window ends drops it; the origin's retry covers it.
7. **Memory caps:**
   - holding for others: 512 KiB, small messages only, alongside ADR 0014's 100-message quota and ¼ per incoming link;
   - inbox: 1 MiB, with ADR 0007's ¼ per sender;
   - send queue: 1 MiB; a send that would exceed it returns `Rejected(QueueFull)`, and the caller uses `awaitSendCapacity()` (ADR 0007).
8. **Configuration:** `limits.maxUnicastPayload` (4 KiB to 128 KiB) and `limits.maxBroadcastPayload` (at most 1 KiB) may only lower the caps. A send above the configured cap returns `Rejected(TooLarge)`. The effective caps are readable as `config.limits` and are printed by `describe()`.
9. **Preset defaults:** every preset allows 128 KiB unicast, except `EmergencyBroadcast` and `ProximityDiscovery`, which use 4 KiB (no large messages). All presets keep the 1 KiB broadcast cap.
10. **Scope:** a profile picture is a message. File transfer and streaming stay out of scope: no resumable transfers, no payloads above 128 KiB, no progress API.

## Alternatives

- **4 KiB cap only:** keeps every message cheap, but fails the profile-picture requirement.
- **One class of 128 KiB:** flooding and holding large messages costs about 1 MB of airtime per 10 nodes and megabytes of relay memory.
- **Large messages direct-only:** a profile picture could not cross even one relay.
- **256 KiB cap:** about 8–14 s per hop on the slowest pairing, for no stated use case.
- **Bounded compression (e.g. DEFLATE with an output cap):** adds a parser on the receive path and a side channel, for little gain on already-compressed images.
- **Negotiated record size:** an extra handshake field and a downgrade surface, for a small gain over fragmentation.

## Risks

- **Airtime:** large messages occupy a link for seconds per hop; interleaving keeps small messages moving, but the effect on delivery and busiest-phone load at each density is unsimulated. The catch-up airtime simulation also covers large messages.
- **Low-density route discovery:** large messages at low density depend on a route request that ADR 0008 did not use in that class.
- **Relay drops:** a relay that cannot forward within the reassembly window wastes the airtime already spent; only the origin's retry recovers.
- **Sybil identities** can get around the per-sender and per-link shares (ADR 0007, A3).

## Consequences

- ADR 0007 gains `config.limits`; `TooLarge` and `QueueFull` cover byte caps.
- ADR 0008: large messages are route-only at every density.
- ADR 0011's preset table gains the size caps.
- ADR 0012 fixes the 1,024 B record cap and adds the link-local busy reply.
- ADR 0014's byte cap is 512 KiB, for small messages only.
- The protocol spec defines the busy reply and the size-class rules with vectors.

## Amendments

2026-11-08, large messages simulated ([stage F evidence](../evidence/2026-11-routing-simulation/README.md#stage-f-store-and-forward-conversation-routes-lookups-and-power)):

- **Large messages (decision 3):** confirmed. With 2% of chat messages at 16–128 KiB, sent along routes only and held only by their sender, 96.4–100% arrive when sender and destination stay in the mesh. Small messages still pass every rule. Latency is 3.4–29 s at the median and 14–193 s at the 95th percentile. Raw delivery drops to 79.5% where the sender or destination leaves the mesh, because relays never hold large messages.
