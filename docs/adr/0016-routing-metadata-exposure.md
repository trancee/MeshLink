# 0016: Routing metadata exposure

Status: accepted (2026-11-08; ticket "What do members learn about neighbors and routes?"). Version: 1.

## Context

Link encryption hides routing messages from outsiders in the secured profiles (ADR 0001), but every mesh member that relays or links with a node reads them. In an open mesh, any phone running MeshLink is a member. Inputs:

- **Identity on links (ADRs 0002, 0003):** every link runs Noise XX, and the identity certificate travels inside it. Every direct neighbor therefore learns a node's identity and current static keys.
- **Routing tags (ADRs 0001, 0008, 0010):** source and destination appear as 8 B tags that rotate every 5 min. They were derived from the long-term identity key, so anyone holding a node's public key could compute its tag for any epoch and recognize its routed traffic mesh-wide, for as long as the identity lasts.
- **Sybil evidence ([2026-11-08](../evidence/2026-11-sybil/README.md)):** identities are free in an open mesh, and a few attacker radios can link with many phones. Learning public keys is cheap.
- **Message set (ADRs 0008, 0013, 0014):** neighbor lists and shuffles (N1+N2), route requests and replies with hop counts, unicast headers, delivery acks and receipts, tombstones, Plumtree IHAVE and graft, catch-up summaries with held messages as {message ID, destination tag}.
- **Prior art ([research](../research/2026-10-31-prior-art-ble-meshes.md)):** bitchat, Bridgefy and Meshtastic leak static IDs, neighbor lists or relay trails in cleartext.

## Decision

1. **Public routing tags come from the certified static key.** A node's public tag for an epoch is derived from the X25519 static key of a valid certificate, not from the Ed25519 identity key. A node accepts the tags of every static key whose certificate is valid, and of an expired one for the 1 h maximum lifetime plus 30 s of skew (ADR 0014). Whoever learned a certificate can recognize the node's public tags for at most about 25 h after learning it, not for the life of the identity. Senders need the current certificate anyway, so this costs nothing.
2. **Conversation tags after the first message.** Only a first message to a node, including a held first message, is addressed to the destination's public tag. Once the end-to-end session exists, every later message, reply, delivery ack and receipt, in both directions, uses per-epoch conversation tags that only the two ends can compute, derived from the end-to-end session (one tag per direction). Relays, including holders of either public key, see that someone contacted the destination, and nothing linkable after that. When the session ends (age, message count or cache eviction, ADR 0002), the next message is a first message again. A node recognizes its public tags plus one tag per active conversation; routes are per conversation, not per node.
3. **Neighbor lists and shuffles carry on-air advertisement identifiers.** Each entry is the 8 B identifier field of the peer's current advertisement (ADR 0012 decision 10), never a NodeId or routing tag. Lists and shuffles go only to direct neighbors over the link and are never forwarded. N2 diversity compares these entries with advertisements it hears, so this is also the identifier the mechanism needs; the new information is only "these on-air identifiers are linked to me".
4. **Accepted and documented, unchanged:**
   - **Catch-up summaries** go to direct neighbors only, who already know the node's identity: the message IDs received in the last 60 s and the destination tags of held messages.
   - **Hop counts and timing** in route requests, replies and floods let colluding members estimate a sender's position. Timing leaks more than hop counts, and a global observer is out of scope (ADR 0001).
   - **Broadcast origin:** every member learns which identity signed a mesh broadcast, by design.
   - **`unsecured`:** no end-to-end session, so no conversation tags. Tags come from the NodeId, which routed messages carry in clear anyway (ADR 0012 decision 12); everything is visible to outsiders.
5. **Documentation:** the reference page [What each observer learns](../reference/observer-exposure.md) lists, per message type, what an outsider, a direct neighbor, a relay and a holder of the public key learn.

## Alternatives

- **Tags from the identity key (previous design):** one link with a node lets the peer follow that node's routed traffic indefinitely.
- **A random reply tag hiding only the sender:** hides the sender but leaves every conversation addressed to the recipient's public tag, linkable by any holder of its key.
- **Blinded destinations with an ephemeral key per message:** hides even first messages, but adds 32 B and an X25519 operation at every node for every flooded frame and catch-up claim, and prevents intermediate route replies. Left for a later protocol version.
- **Anonymous links in open meshes (no certificate in XX):** stops neighbors learning identities, but certificate exchange on links is how nodes discover each other's identities and keys (ADR 0003). Out of scope for v1.
- **NodeIds or routing tags in neighbor lists:** a permanent topology map, or a link between on-air presence and routed traffic for key holders.
- **Randomized hop-count offsets:** small gain while timing still reveals distance.

## Risks

- **Not simulated:** per-conversation routes and tag sets change the route-discovery load. The catch-up airtime simulation (ticket 32) reruns the routing simulation and must confirm the cost; if it fails, the decision returns to the project owner.
- **First messages:** the first message of every session still reveals its destination's public tag, so a holder of that node's key sees that the node was contacted.
- **Direct neighbors:** a direct neighbor knows the node's identity, its other neighbors' on-air identifiers and its recent catch-up history.
- **Session ends:** a read receipt sent after the session ended starts a new session and is addressed to the original sender's public tag.

## Consequences

- The protocol spec defines the public and conversation tag derivations, their epoch schedule and vectors, and the neighbor-list entry layout.
- ADRs 0001, 0008, 0012, 0013 and 0014 are amended (tags, header addressing, receipts, catch-up claims).
- The threat model's metadata section and the profile guarantees table point to the new reference page.

## Migration

None: greenfield.
