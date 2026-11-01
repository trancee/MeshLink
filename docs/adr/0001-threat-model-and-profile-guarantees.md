# 0001: Threat model and security-profile guarantees

Status: accepted (2026-10-31; ticket "What is the threat model, and what does each security profile guarantee?"). Version: 1.

## Context

MeshLink offers three security profiles (`none`, `classical`, `post-quantum`), one per mesh and never negotiated. Phone BLE meshes that shipped without an explicit model were broken through relay-readable content, forgeable broadcasts, cache poisoning and impersonation (Bridgefy, bitchat). The guarantees each profile makes must be fixed before suites, wire format, routing or discovery are designed. Rule O3 requires a versioned ADR for security and protocol decisions.

## Decision

- Adversaries A1–A7 (passive, active on-path, malicious member, resource exhaustion, tracker, harvest-now-decrypt-later, after-the-fact compromise) are in scope. Jamming (detect only), live OS/baseband compromise, hardware side channels, coercion, a malicious host app and global traffic analysis are out of scope. The host app is trusted.
- The secured profiles use two layers: authenticated, encrypted links, plus end-to-end encryption of unicast messages and receipts.
- Mesh admission is open or gated per mesh; a gated mesh requires a mesh key mixed into every link handshake.
- Mesh broadcasts carry an Ed25519 origin signature, verified before relay, in both secured profiles.
- Fragmentation is per hop: relays reassemble, verify and re-fragment; reassembly is bounded per neighbor.
- Relays see source and destination only as epoch-rotating tags derived from the identity key, subject to a routing-simulation cost check that, if failed, returns to the project owner.
- Every on-air identifier rotates in step with the BLE address; node presence is not hidden.
- `post-quantum` means hybrid confidentiality with classical authentication.
- Store-and-forward and first messages to an offline recipient have no forward secrecy in v1.
- Replay is rejected within a bounded message lifetime under a stated clock-tolerance assumption.
- A key compromise is remedied by an identity-reset API; there is no revocation protocol. Keys at rest use the platform keystore.
- MeshLink never guarantees delivery but always guarantees bounded resources.
- `none` guarantees only bounded resources and corruption detection, and can never enter a secured mesh.

The full per-profile table is [Security profile guarantees](../reference/security-profile-guarantees.md); the reasoning is the [threat model](../explanation/threat-model.md).

## Alternatives

- Hop-by-hop only: rejected; relays read and forge content (Bridgefy).
- End-to-end only: rejected; routing headers exposed to passive listeners, and no authentication before relay.
- Open-only or gated-only admission: rejected; disaster broadcast needs open, field teams need gated.
- Post-quantum signatures now: rejected for v1. Harvest-now-decrypt-later does not threaten signatures; ML-DSA-44 adds 2,420 B per signature; SQIsign (200 B at level 1; verify 2.7–5.8 ms on lab phones per `../the-sqisign` benchmarks) is only a NIST round-3 candidate, and rule S6 requires an approved provider. SQIsign is the leading candidate for a later suite version.
- End-to-end fragmentation across relays: rejected; it prevents verify-before-relay and enables reassembly poisoning.
- Stable node IDs in routing headers: rejected as the target; relays would learn who talks to whom.
- Signed prekeys for store-and-forward forward secrecy: deferred; complex, and still falls back to static keys.

## Risks

- Epoch-rotating routing tags may cost too much route relearning at high density; mitigated by the routing-simulation gate and owner escalation.
- Classical authentication becomes forgeable once a cryptographically relevant quantum computer exists; mitigated by suite-bound, versioned signature algorithms.
- A leaked mesh key or installation key stays usable until the app re-keys or resets identity, with no revocation.
- The clock-tolerance assumption may fail offline; the bound must be chosen with lab evidence.
- Presence of MeshLink nodes, and the mesh of an open mesh, remain observable.

## Migration

None: greenfield, nothing released. Any change to a guarantee in the reference table needs a new version of this ADR.
