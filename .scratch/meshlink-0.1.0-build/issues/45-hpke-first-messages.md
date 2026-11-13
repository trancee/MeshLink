# Seal first messages with HPKE

Status: ready-for-agent
Type: AFK
Blocked by: 20, 44

## Behavior

In `Classical` and `PostQuantum`, `send(to, payload)` to any peer, direct or routed, arrives end-to-end encrypted as a first message. Relays learn neither the content nor the exact size.

- **Certificate:** taken from ticket 44's cache or lookup. While a lookup runs, the status stays `Queued`. When the certificate arrives, every waiting message is sealed and sent. With no certificate, the message becomes `Expired` at its lifetime. `Rejected(UnknownPeer)` only for a malformed NodeId. The sender never seals to an expired certificate (identity validation rule 5).
- **Container:** kind `0x01`, `IK` message 1 (`Classical`) or `IKhfs` message 1 (`PostQuantum`), with the `e2e-prologue`. The initiator's pre-message is the responder's certified `x25519_key`.
- **Plaintext:** message content with `SENDER_CERTIFICATE` and `CONVERSATION_SEED`, plus `READ_RECEIPT_REQUESTED` when asked, zero-padded to a multiple of 32 B.
- **Post-quantum seal:** the `IKhfs` payload is `enc ‖ ct` from HPKE `SealBase` (KEM `0x647A`, KDF `0x0001`, AEAD `0x0003`, mode `0x00`, empty AAD), with `info = DS("hpke-first-message") ‖ initiator x25519 static key ‖ responder NodeId`. It adds 1,136 B. This ticket implements HPKE base mode over `CryptoProvider` unless an earlier ticket did.
- **Tags:** destination is the responder's public tag for its newest valid certificate; source is the new conversation's `tag(0x02)`. A node recognizes the public tags of every certificate whose keys it still holds.
- **Responder:** opens message 1 once per `message_id`, checks every plaintext MUST, validates `SENDER_CERTIFICATE` and that its `x25519_key` equals the Noise remote static key, caches that certificate (ticket 44's cache) and delivers once.
- **One first message outstanding per peer.** Later messages to that peer wait until it expires. Opening the session with message 2 is ticket 46. Retries resend the same container.
- **Status:** `Queued`, `Sent`, `Expired`. `Delivered` comes with ticket 47.
- **`peers`:** a secured remote node shows `Routed(hops)` only while a valid certificate and a route exist.

## Spec and ADR references

- [end-to-end.md](../../../docs/spec/end-to-end.md): [Patterns](../../../docs/spec/end-to-end.md#patterns), [Containers](../../../docs/spec/end-to-end.md#containers), [Plaintext](../../../docs/spec/end-to-end.md#plaintext), [Plaintext extensions](../../../docs/spec/end-to-end.md#plaintext-extensions), [First messages](../../../docs/spec/end-to-end.md#first-messages), [Conversation tags](../../../docs/spec/end-to-end.md#conversation-tags).
- [routing.md, Routing tags](../../../docs/spec/routing.md#routing-tags); [identity.md, Validation](../../../docs/spec/identity.md#validation).
- [D-30](../../../docs/spec/decisions.md#d-30-container-kinds), [D-31](../../../docs/spec/decisions.md#d-31-plaintext-and-end-to-end-extensions), [D-32](../../../docs/spec/decisions.md#d-32-conversation-seed-and-tags), [D-35](../../../docs/spec/decisions.md#d-35-hpke-parameters), [C-06](../../../docs/spec/decisions.md#c-06).
- [ADR 0002](../../../docs/adr/0002-noise-suites-and-handshake-patterns.md) decisions "End-to-end layer", "Post-quantum first and held messages" and "Conformance vectors"; [ADR 0004](../../../docs/adr/0004-crypto-provider-libsodium.md) (HPKE ours, X-Wing pin, hpke-pq gate).
- [ADR 0016](../../../docs/adr/0016-routing-metadata-exposure.md) decisions 1 and 2; [ADR 0018](../../../docs/adr/0018-certificate-acquisition.md) decisions 8 and 9.

## Acceptance criteria

- [ ] HPKE passes the `draft-ietf-hpke-pq-05` Appendix A.5 vectors for `0x647A`. The tier 2 "HPKE X-Wing seal" set and the message 1 cases of "`IK` and `IKhfs` sessions" pass; ticket 20's Rust reference generates any still pending. `IK` passes cacophony.
- [ ] Secured plaintexts pass `inner-plaintext.json`. The `e2e-prologue`, `hpke-info`, `public-tag`, `conversation-secret` and `conversation-tag` derivations pass `derivations.json`. Each new decoder has a deterministic mutation test.
- [ ] Seeded `VirtualAir` tests, 5-node line, in `Classical` and in `PostQuantum`: A sends to E with no cached certificate; status stays `Queued` during the lookup; E receives once, intact; relays see only header, commitment and a padded size.
- [ ] A seeded test with no reachable certificate holder ends in `Expired` at the lifetime plus 30 s.
- [ ] T10: wrong padding, a missing `CONVERSATION_SEED` or `SENDER_CERTIFICATE`, a certificate whose `x25519_key` differs from the Noise static key, a tampered `enc` or `ct`, a mismatched inner `message_id` or `expiry`, and kind `0x04` in a secured mesh are all dropped without delivery.
- [ ] A seeded test shows `peers` turning `Routed(hops)` for a secured node with a valid certificate and a route, and not without either.
- [ ] `./gradlew check` passes with Kover at 100% and the ABI dump updated; `./gradlew iosSimulatorArm64Test` passes.

## Proof commands

```sh
./gradlew check
./gradlew iosSimulatorArm64Test
```

## Docs to update

- KDoc of `send`, `DeliveryStatus` and `Peer` for secured routed sends and lookups.
- [Security profile guarantees](../../../docs/reference/security-profile-guarantees.md) only if a tested row differs from the page.

## Devices needed

None. Host and simulator only. Ticket 26 measured first-message crypto on phones.
