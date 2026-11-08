# How does a sender obtain the certificate of a node it has never linked with?

Type: grilling
Status: resolved

## Question

Decide how a sender gets the current identity certificate of a destination it has never linked with directly, so it can seal an end-to-end `IK` first message: supplied by the app out of band, carried in route replies, fetched on demand, or a combination. Decide what relays learn, how stale certificates are refreshed, and what the API exposes.

## Context

- ADR 0003: certificates travel inside the encrypted XX payload, so only direct neighbors receive them today.
- ADR 0014: certificates are valid for 24 h; a sender never seals to an expired one (`Rejected(PeerKeyExpired)`); nodes attach their current certificate to `IK` replies and receipts.
- ADR 0007: `peers` lists routed peers, which implies some knowledge of them.

## Answer

ADR 0018.

- **Addressing:** apps address peers by NodeId only; MeshLink obtains certificates itself, with no import or export API in v1.
- **Sources:** the node's own cache, filled from links, `IK` senders and refreshes, then an on-demand lookup. There are no certificate floods.
- **The lookup:** carries an 8 B salt, an 8 B tag BLAKE2b(salt ‖ NodeId) and a 32 B ephemeral key, and floods under route-request rules.
- **Replies:** D or any node caching a valid certificate answers (cache of 64, least recently used). The reply is sealed to the ephemeral key, and the first valid one wins.
- **Sending:** `send` stays `Queued` until the certificate arrives, or becomes `Expired`. `PeerKeyExpired` is removed.
- **Peers:** `Routed` needs a valid certificate and a route.
- **Gated meshes:** lookups travel inside the mesh-key layer.

The lookup cost is added to ticket 32. Amended: ADR 0003 v4, ADR 0007 v6, ADR 0012 v5, ADR 0013 v4, ADR 0014 v4, ADR 0016 v2, and the observer page.
