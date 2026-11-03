# 0014: Store-and-forward

Status: accepted (2026-11-03; ticket "How does store-and-forward work?"). Version: 2 (amended 2026-11-03, ADR 0015).

## Context

- **ADR 0008:** a node with no route holds a message; it holds up to 100 messages and takes one only with at least 10 s of lifetime left. The minimum lifetime is 120 s with ±30 s clock skew. On every new link, catch-up exchanges the message IDs of the last 60 s. Routing tags rotate every 15 min and are accepted for ±1 epoch. The routing simulation held one copy per node with no route.
- **ADR 0013:** the delivery receipt is always sent and is end-to-end in the secured profiles; status lives in memory; `Expired` at expiry plus 30 s.
- **ADR 0001 and ADR 0002:** held and first messages are sealed to the recipient's static key (`IK` message 1, plus HPKE X-Wing in `post-quantum`) and have no forward secrecy. ADR 0003 leaves the static-key rotation period to this decision.
- **ADR 0011:** holding for others is a preset toggle that never affects relaying.
- **Prior art** ([routing research §3.4](../research/2026-10-31-routing-topology-strategies.md)): bitchat keeps a sender-only outbox (100 messages, 24 h). bitchat and cairn also use spray-and-wait copies at relays (cairn: 6 copies, 24 h, quotas, tombstones). BPv7 moved custody transfer out of its base protocol (RFC 9171 Appendix A).
- **User direction:** a BLE mesh is for short-term, near-instant messaging, not long-term mail.

## Decision

1. **Custody:** the origin keeps its own copy until `Delivered` or `Expired`. Relays with holding on keep extra best-effort copies. There is no custody transfer and no copy budget.
2. **Lifetime:** at most 1 h, enforced by config validation and by every relay; at least 120 s (ADR 0008). Preset defaults: `OfflineChat` 30 min; `FieldTeams` and `EmergencyBroadcast` 1 h; `ProximityDiscovery` and `LocalMultiplayer` 120 s.
3. **Memory only:** held messages live in memory in v1 and are lost on `stop()` or when the process dies.
4. **Quota:** holding for others is limited to 100 messages and a byte cap that the message-size decision sets. One incoming link may fill at most ¼ of it. The node's own outbox is separate and bounded by the send queue.
5. **Eviction:** expired messages go first. When full, evict from the link furthest over its share; otherwise a new message replaces the held message closest to expiry, but only if the new one has more lifetime left.
6. **Mesh broadcasts** are held too, under the same quota and toggle, until they expire. They get no receipts and no tombstones.
7. **Encounter hand-off:** on every new link, catch-up also lists:
   - held broadcasts by message ID;
   - held unicasts as {message ID, destination tag};
   - tombstones (decision 8).

   The peer claims destination tags that are its own from any epoch within the maximum lifetime (at most 4 epochs at 1 h) and pulls those messages. Held unicasts also keep the routing retries of ADR 0008. The origin re-tags its own copies each epoch; relays cannot, because they don't know the destination's key.
8. **Tombstones:** the origin draws a random 16 B secret k per unicast, puts k inside the end-to-end payload and a 16 B commitment H(k) in clear in the routing header.
   - The delivery receipt also carries the tombstone {message ID, k} in clear. Any node checks H(k) against the header it holds and drops its copy.
   - Tombstones follow the receipt's path and are offered on catch-up until the message expires.
   - The tombstone store holds at most 1,000 entries; the oldest drop first.
   - Only the destination learns k, so nobody else can delete a message. In `unsecured`, k is plaintext and tombstones are forgeable.
9. **Static-key rotation:** a certificate is valid for 24 h, and a new one is issued every 12 h. The static private keys of a certificate are deleted at its not-after plus the 1 h maximum lifetime plus 30 s of skew.
10. **Expired certificates:** a sender never seals to an expired certificate; `send` returns `Rejected(PeerKeyExpired)` until a fresh one arrives. A node attaches its current certificate to `IK` replies and receipts whenever the sender's copy is past half of its validity.
11. **Residual tracking risk (documented, accepted):** anyone who recorded a message addressed to a node can recognize that node when it claims or acknowledges the message, until the message expires (at most 1 h).

## Alternatives

- **Sender-only outbox (bitchat S0):** least exposure, but nothing moves while the origin is out of range.
- **Spray-and-wait copy budgets:** relays can lie about the counter, and flooding at low density already makes copies.
- **Custody transfer:** extra ack traffic; BPv7 dropped it from the base protocol.
- **24 h lifetime (bitchat, cairn):** longer tracking and key-exposure windows for a use case the user ruled out.
- **Persisting held messages:** needs encryption at rest, secure deletion and an iOS file-protection class.
- **A per-origin quota share:** relays see only rotating origin tags, and Sybils dodge it.
- **Unauthenticated purge frames:** any node could delete other people's messages.
- **Purge only after expiry:** wastes relay storage and airtime on messages already delivered, against the user's direction.
- **Certificate validity 1 h or 7 days:** 1 h makes certificates stale for most contacts; 7 days exposes a week of recorded first and held messages to a stolen phone.

## Risks

- **Catch-up cost:** offering held messages and tombstones on every new link changes ADR 0008 and was not simulated. A routing-simulation rerun must confirm the airtime cost before the protocol spec.
- **Key exposure:** a phone stolen with its keys exposes the first and held messages sealed to it that an attacker recorded during roughly the last 25 h.
- **Certificate acquisition:** how a sender gets the certificate of a node it has never linked with is still open; short validity makes the answer matter more.
- **Process death on iOS:** a backgrounded app that iOS terminates loses its held messages.

## Consequences

- The routing header of a unicast grows by the 16 B commitment (ADR 0012).
- The threat model and the guarantees table gain the residual tracking risk and the key-exposure bound.
- The protocol spec defines the catch-up summary, the tombstone layout and `PeerKeyExpired`.

## Amendments

2026-11-03, message size ([ADR 0015](0015-message-size-and-compression.md)):

- **Quota (decision 4):** the byte cap is 512 KiB. Only small messages (at most 4 KiB) are held for others; large messages are held only by their origin.
