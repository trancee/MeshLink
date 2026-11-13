# Find a peer's certificate by lookup

Status: ready-for-agent
Type: AFK
Blocked by: 21, 37

## Behavior

In `Classical` and `PostQuantum`, a node that needs a peer's identity certificate gets it from its cache or, failing that, from an on-demand lookup across the mesh. Ticket 45 uses this source to seal first messages; this ticket delivers the source itself.

- **Cache:** up to 64 valid certificates of other nodes, least recently used evicted, each dropped at its `not_after`. Filled only from direct links, `IK` senders (ticket 45) and replies to the node's own lookups.
- **Request:** `LOOKUP_REQUEST` (`0x82`, 66 B) floods under the route-request rules: `hop_limit` 64, `expiry` 120 s ahead, suppression by `message_id`. It carries a fresh 8 B salt, `lookup_tag = trunc_8(H("lookup-tag", salt ‖ NodeId))` and a fresh X25519 ephemeral key. Relays record a reverse route keyed by salt.
- **Responders:** each node compares the tag with its own NodeId and with its cached certificates' NodeIds. A match answers and stops forwarding. The reply carries the certificate with the latest `not_after` the responder holds.
- **Reply:** `LOOKUP_REPLY` (`0x44`, 222 B or 1,438 B), addressed to the salt, with a random source tag. It is sealed with X25519, the `lookup-reply` KDF and ChaCha20-Poly1305 under a zero nonce. The responder rejects an all-zero shared secret. Relays pass only the first reply per salt.
- **Requester:** opens the first reply, validates the certificate for the NodeId it asked for, caches it and drops later replies. At most one lookup is outstanding per NodeId. Each retry uses a new salt, ephemeral key and `message_id`, on the origin's unicast retry schedule.
- **Profiles:** `Unsecured` has no certificates and no lookups. In a gated mesh, lookups ride the mesh-key links like every routed frame.
- **API:** no public change. `send` waiting as `Queued` for a lookup, and `peers` showing `Routed` for secured peers, land with ticket 45.

## Spec and ADR references

- [routing.md, Certificate lookup](../../../docs/spec/routing.md#certificate-lookup): [LOOKUP_REQUEST](../../../docs/spec/routing.md#lookup_request), [LOOKUP_REPLY](../../../docs/spec/routing.md#lookup_reply); [Rules](../../../docs/spec/routing.md#rules).
- [identity.md, Validation](../../../docs/spec/identity.md#validation).
- [D-09](../../../docs/spec/decisions.md#d-09-lookup-tag-input-order), [D-36](../../../docs/spec/decisions.md#d-36-lookup-reply), [D-37](../../../docs/spec/decisions.md#d-37-lookup-retry-and-first-reply).
- [ADR 0018](../../../docs/adr/0018-certificate-acquisition.md) decisions 2–7 and 10, and its amendments.
- [Vectors README, Tier 2: pending](../../../docs/spec/vectors/README.md#tier-2-pending) (sealed lookup replies; lookup-reply key from X25519).

## Acceptance criteria

- [ ] `LOOKUP_REQUEST` and `LOOKUP_REPLY` codecs pass every matching `routed-frames.json` vector. The `lookup-tag` and `lookup-reply-key` derivations pass `derivations.json`. Each new decoder has a deterministic mutation test.
- [ ] The tier 2 sets "Sealed lookup replies" and "Lookup-reply key from X25519" pass in the vector runner. If ticket 20 left them pending, this ticket generates them with the Rust reference.
- [ ] Seeded `VirtualAir` test, 6 nodes in a line, `Classical`: A gets F's certificate by lookup from F 5 hops away; a second lookup is answered by a middle node that cached it, and the request stops there.
- [ ] The same in `PostQuantum`, with the 1,438 B reply.
- [ ] Seeded tests show one outstanding lookup per NodeId, a fresh salt, key and `message_id` per retry, relays passing only the first reply per salt, and the requester dropping later replies.
- [ ] A unit test shows LRU eviction at 64 entries and removal at `not_after` in virtual time.
- [ ] T10: an all-zero shared secret, a reply failing AEAD, a certificate for another NodeId, an expired certificate, a bad signature and an unrequested reply are rejected and never cached.
- [ ] A seeded gated-mesh test shows a non-member sees no lookup frame.
- [ ] `./gradlew check` passes with Kover at 100%; `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- None expected. [What each observer learns](../../../docs/reference/observer-exposure.md) has the lookup row. If the code shows different exposure, update it in this PR.

## Devices needed

None. Host and simulator only.
