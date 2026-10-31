# What is the threat model, and what does each security profile guarantee?

Type: grilling
Status: resolved

## Question

Define adversaries (passive eavesdropper, active MITM, malicious relay, tracker/observer, quantum harvest-now-decrypt-later, compromised device), assets (content, metadata, identities, location/presence), and per-profile guarantees (none/classical/post-quantum): confidentiality, authentication, integrity, forward secrecy, replay protection, tracking resistance, relay visibility (hop-by-hop vs end-to-end).

## Answer

Agreed with the user in three grilling rounds on 2026-10-31. All recommendations were accepted, plus two follow-ups the user raised: SQIsign, and broadcast reassembly. The decision record is `docs/adr/0001-threat-model-and-profile-guarantees.md`. The reasoning is in `docs/explanation/threat-model.md`, and the per-profile table in `docs/reference/security-profile-guarantees.md`.

- **Adversaries and scope:** A1–A7 are in scope. Out of scope: jamming (detected only), live OS compromise, side channels, coercion, a malicious host app, and global traffic analysis. The host app is trusted.
- **Encryption layers:** the secured profiles use authenticated, encrypted links plus end-to-end encryption for unicast messages and receipts.
- **Mesh admission:** open or gated per mesh. A gated mesh requires a mesh key in every link handshake. New glossary terms: Mesh, Mesh key, Open mesh, Gated mesh.
- **Broadcasts:** an Ed25519 origin signature, verified before relay. Broadcast content is public in an open mesh and private to mesh-key holders in a gated mesh.
- **Fragmentation:** per hop. Each relay reassembles, verifies and re-fragments, with bounded buffers per neighbour.
- **Routing metadata:** relays see source and destination only as epoch-rotating tags. If the routing simulation finds the cost unacceptable, it comes back to the user.
- **Tracking:** on-air identifiers rotate with the BLE address; node presence is not hidden.
- **`post-quantum`:** hybrid confidentiality with classical authentication. SQIsign is the leading candidate for a future signature suite, not v1, since it is a NIST round-3 candidate and S6 applies.
- **Forward secrecy:** none for store-and-forward or first messages to an offline recipient.
- **Replay:** rejected within a bounded message lifetime, under a clock-tolerance assumption.
- **Key compromise:** an identity-reset API, no revocation; keys at rest in the platform keystore.
- **Availability:** no delivery guarantee, but bounded resources are always guaranteed.
- **`none`:** guarantees only bounded resources and corruption detection, and is isolated from secured meshes.
