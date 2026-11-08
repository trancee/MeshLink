# 0018: Certificate acquisition

Status: accepted (2026-11-08; ticket "How does a sender obtain the certificate of a node it has never linked with?"). Version: 2 (amended 2026-11-08, stage F simulation).

## Context

In the secured profiles, a first message to a node is sealed end to end with `IK` to the destination's certified static keys ([ADR 0002](0002-noise-suites-and-handshake-patterns.md)). It is addressed to the destination's public routing tag, which is derived from the certified X25519 static key ([ADR 0016](0016-routing-metadata-exposure.md)). Without the current certificate, a sender can neither seal nor address the message.

Inputs:

- **Handle ([ADR 0007](0007-public-api-shape.md)):** apps send to a `NodeId`, the 32 B BLAKE2b-256 hash of the Ed25519 identity key ([ADR 0003](0003-installation-identity-key-model.md)).
- **The certificate ([ADR 0012](0012-wire-format-and-codec.md) decision 9):** a fixed record of about 140 B in `classical` and about 1.35 KB in `post-quantum`, because of the 1,216 B X-Wing key.
  - It is self-authenticating: an Ed25519 signature by the identity key, whose hash must equal the NodeId.
  - It is valid for 24 h and replaced every 12 h ([ADR 0014](0014-store-and-forward.md)).
- **Where certificates come from today:**
  - the link handshake (XX), so only direct neighbors receive them;
  - `IK` first messages, which carry the sender's certificate so the recipient can reply;
  - piggybacked refreshes on `IK` replies and receipts (ADR 0014).
- **Gap:** a sender that never linked with the destination, or whose copy expired, had only `Rejected(UnknownPeer)` or `Rejected(PeerKeyExpired)`.
- **Privacy ([ADR 0016](0016-routing-metadata-exposure.md)):** whoever holds a certificate recognizes the node's public tags for about 25 h. The decisions there keep the set of holders small.

## Decision

1. **Apps address peers by NodeId only.** MeshLink obtains certificates itself. v1 has no API to import or export certificates.
2. **Sources, in order:**
   1. the node's own cache, filled from links, `IK` first messages and piggybacked refreshes;
   2. an on-demand lookup.

   MeshLink never floods certificates periodically.
3. **Salted lookup request.**
   - **Contents:** an 8 B random salt, an 8 B lookup tag and a 32 B ephemeral X25519 public key. The request names no requester.
   - **The tag:** libsodium BLAKE2b (`crypto_generichash`) with a domain-separation string fixed in the spec, over salt ‖ NodeId, truncated to 8 B.
   - **Flooding:** a request floods under the route-request rules of [ADR 0008](0008-routing-topology-and-density-adaptation.md): hop limit and duplicate suppression.
   - **Matching:** each node compares the tag with its own NodeId and with the NodeIds of its cached certificates, at one hash each.
4. **Responders.**
   - D itself, or any node holding a valid cached certificate for D, answers. A node that answers stops forwarding the request.
   - The reply carries the certificate with the latest not-after the responder holds.
   - It travels back along the reverse path, like a route reply.
   - An offline D can therefore still receive first messages through store-and-forward, if some node cached its certificate.
5. **Sealed replies.**
   - The reply is sealed to the request's ephemeral key: X25519, HKDF and ChaCha20-Poly1305, adding a 16 B tag.
   - Relays on the return path don't learn the certificate.
   - A classical seal is enough in both profiles: certificates are public, so only metadata is at stake.
6. **Accepting a reply.** The requester checks the signature and that the identity key hashes to the NodeId. It seals with the first valid certificate, meaning any certificate before its not-after, and drops later replies.
7. **Certificate cache.**
   - Up to 64 certificates of other nodes, evicting the least recently used. That's about 87 KB in `post-quantum`.
   - It holds only valid certificates and drops each at its not-after.
   - It is filled from direct links and `IK` senders, and from lookup replies the node itself requested.
8. **Sending without a certificate.**
   - `send` returns `Accepted`, and the status stays `Queued` while a lookup runs. Lookups retry on the unicast schedule of ADR 0008.
   - At most one lookup is outstanding per NodeId.
   - When a certificate arrives, every waiting message is sealed and sent.
   - If no certificate arrives, a message becomes `Expired` at its lifetime.
   - `Rejected(PeerKeyExpired)` is removed. `Rejected(UnknownPeer)` remains only for a malformed NodeId, or a NodeId in `unsecured` that is unknown.
9. **`peers`:** a remote node appears as `Routed` only while MeshLink holds a valid certificate for it and has a route to it.
10. **Profiles.**
    - `unsecured`: no certificates and no lookups; tags come from the NodeId (ADR 0016).
    - Gated meshes: lookups and replies travel inside the mesh-key layer like every routing message, so only members see them.
11. **Cost check:** the cost of lookups is added to ticket 32's simulation. If it breaks the ADR 0008 budgets, the decision returns to the owner.

## Alternatives

- **Periodic mesh-wide certificate floods:** cost about 1.35 KB per node every 12 h in `post-quantum`, which is more than the 1 KiB broadcast cap. They would also hand every member every certificate, undoing ADR 0016's limit on who can recognize public tags. Rejected.
- **Out-of-band certificates only** (QR codes, a server): a certificate goes stale within 24 h, and many meshes have no other channel. Rejected as the main mechanism. An import API is deferred, because it would only speed things up.
- **A deterministic lookup tag per epoch, H(NodeId, epoch):** lets members who don't know the NodeId link repeated lookups for the same node. The salt costs 8 B.
- **A lookup by plain NodeId:** names the target in every request. Rejected.
- **Unsealed replies:** every relay on the return path would become a certificate holder. Rejected; the seal costs 16 B and one X25519 operation per side.
- **Only D answers:** an offline D could never get first messages, even though store-and-forward could hold them. Rejected.
- **Keeping `Rejected(PeerKeyExpired)`:** pushes retry logic into every app. Rejected.

## Risks

- **Lookups reveal interest:** anyone who knows D's NodeId can recognize "someone is looking for D". NodeIds are learned from contacts and from signed broadcasts. The requester stays anonymous. This is documented in the [observer page](../reference/observer-exposure.md).
- **Flood cost:** lookups add mesh-wide floods. Simulated in stage F; see Amendments.
- **Cache poisoning:** not possible, because a certificate only verifies against its own NodeId. A stale but valid certificate costs D at most one missed refresh, since D still holds its static keys until not-after plus 1 h 30 s (ADR 0014).
- **Lookup storms:** an app sending to many unknown NodeIds creates many lookups. At most one is outstanding per NodeId, and the send queue's caps (ADR 0007) bound how many NodeIds can wait. ADR 0008 has no route-request rate limit yet; ticket 32 shows whether one is needed.

## Consequences

- **[ADR 0003](0003-installation-identity-key-model.md):** certificates also come from lookups.
- **[ADR 0007](0007-public-api-shape.md):** removes `PeerKeyExpired`, narrows `UnknownPeer` and defines `Routed`.
- **[ADR 0012](0012-wire-format-and-codec.md):** gains the lookup request and reply messages.
- **[ADR 0013](0013-message-lifecycle-and-receipts.md):** `Queued` also covers waiting for a certificate.
- **[ADR 0014](0014-store-and-forward.md):** removes `PeerKeyExpired`.
- **[ADR 0016](0016-routing-metadata-exposure.md):** lookups are added to the message set, and caching nodes become certificate holders.
- **[Observer page](../reference/observer-exposure.md):** gains a lookup row.
- **The protocol spec (ticket 29):** defines the layouts, the domain string and the vectors.

## Amendments

2026-11-08, lookup cost ([stage F evidence](../evidence/2026-11-routing-simulation/README.md#stage-f-store-and-forward-conversation-routes-lookups-and-power), simulated):

- **Sealed replies (decision 5):** a relay passes on only the first reply per lookup request and drops the rest. Every neighbor of D caches D's certificate, so a lookup drew 0.5–47 cache answers depending on density and movement. That alone raised the busiest phone's load by up to 2.1× over v1. Passing on only the first reply cut the replies reaching the requester from up to 43 to at most 3.7.
- **Flood cost (Risks):** measured.
  - Lookups succeed 89–99.5% of the time.
  - The first answer comes from 1–3 hops away at the median and 2–6 at p90. It never came from D itself: D's neighbors answer first.
  - With a lookup, first messages reach a 95th percentile of 0.20–0.34 s classical and 0.35–1.23 s post-quantum (rule: 2 s and 3 s).
  - The one remaining miss came from backgrounded iPhones, not from lookups: in the 1 h high-density half-iPhone runs, one seed had two messages stuck behind an iPhone's 50 s background sleep.
- **Lookup storms (Risks):** no route-request rate limit in v1; the measured cost of a misbehaving phone is in ADR 0008.
