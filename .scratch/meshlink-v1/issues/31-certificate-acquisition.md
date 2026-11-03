# How does a sender obtain the certificate of a node it has never linked with?

Type: grilling
Status: open

## Question

Decide how a sender gets the current identity certificate of a destination it has never linked with directly, so it can seal an end-to-end `IK` first message: supplied by the app out of band, carried in route replies, fetched on demand, or a combination. Decide what relays learn, how stale certificates are refreshed, and what the API exposes.

## Context

- ADR 0003: certificates travel inside the encrypted XX payload, so only direct neighbors receive them today.
- ADR 0014: certificates are valid for 24 h; a sender never seals to an expired one (`Rejected(PeerKeyExpired)`); nodes attach their current certificate to `IK` replies and receipts.
- ADR 0007: `peers` lists routed peers, which implies some knowledge of them.
