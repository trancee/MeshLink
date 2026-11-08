# What each observer learns

Per message type, what each observer learns in the secured profiles (`classical`, `post-quantum`), as decided in [ADR 0016](../adr/0016-routing-metadata-exposure.md) and [ADR 0018](../adr/0018-certificate-acquisition.md). Adversaries are defined in the [threat model](../explanation/threat-model.md). In `unsecured` every frame is in clear, so an outsider learns everything in the "Relay" and "Direct neighbor" columns, plus the sender's NodeId on routed messages.

## Observers

| Observer | Who |
|---|---|
| Outsider (A1) | Records radio traffic but holds no link. In a gated mesh, anyone without the mesh key. |
| Direct neighbor | A member linked with the node. Learns the node's identity certificate in the link handshake. |
| Relay (A3) | A member several hops away that forwards or receives the frame. |
| Key holder | Anyone who learned the node's certificate: a contact, a phone that linked with it in the last ~25 h, or a node caching it for lookups. May also be a relay. |
| NodeId holder | Anyone who knows the node's NodeId: a contact, or anyone who saw its signed broadcasts. |

## Exposure by message type

| Message | Outsider | Direct neighbor | Relay | Key holder (as relay) |
|---|---|---|---|---|
| Advertisement | That a MeshLink node is present; in an open mesh, which mesh; rotating identifier (5 min) | same | same | same (gated mesh: also who, if it holds the resolving key) |
| Link handshake | That two radios linked; sizes | Identity certificate | – | – |
| Neighbor list and shuffle | Size only | On-air identifiers of the node's other neighbors (current epoch) | – (never forwarded) | – |
| Catch-up summary | Size only | Message IDs received in the last 60 s; destination tags of held messages | – (direct neighbors only) | Which held messages are for nodes it knows |
| First unicast message (and held first message) | Size and timing | Destination's public tag, size in 32 B steps, hop count | same | That this node was contacted |
| Later messages, replies, acks, receipts | Size and timing | Conversation tags, size in 32 B steps | same | Nothing linkable to identities |
| Route request and reply | Size and timing | Searched and replying tags, hop count | same | For public tags: who is searched for; conversation tags: nothing |
| Certificate lookup and reply | Size and timing | Salt and salted tag, hop count; the reply is sealed | same | NodeId holders: that someone is looking for this node; never who |
| Tombstone (in a receipt) | Size and timing | Message ID and commitment opening | same | That a message it saw was delivered |
| Mesh broadcast, IHAVE, graft | Size and timing | Content and signing identity (open mesh: anyone; gated: mesh-key holders) | same | same |
| Hop counts and timing | Timing | Distance to origin in hops | same | Colluding members can estimate a sender's position |

## Limits

- No anonymity against a global observer (out of scope).
- Public tags of one certified static key are recognizable for up to about 25 h by whoever learned the certificate.
- The per-conversation routing cost and the lookup cost are not simulated yet (ticket 32).
