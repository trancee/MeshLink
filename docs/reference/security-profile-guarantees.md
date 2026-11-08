# Security profile guarantees

Per-profile guarantees against the adversaries A1–A7 defined in the [threat model](../explanation/threat-model.md). "Yes" is a guarantee the specification commits to; "No" is a stated non-guarantee. Mechanisms and algorithms are fixed by later specification work; status: accepted for v1 planning, 2026-10-31.

| Property | `unsecured` | `classical` | `post-quantum` |
|---|---|---|---|
| Unicast content confidentiality vs outsiders (A1, A2) | No | Yes (link + end-to-end) | Yes (link + end-to-end) |
| Unicast content confidentiality vs relays and carriers (A3) | No | Yes (end-to-end) | Yes (end-to-end) |
| Confidentiality vs harvest-now-decrypt-later (A6) | No | No | Yes (hybrid X25519 + ML-KEM-768) |
| Peer authentication (holder of installation key) | No | Yes, classical | Yes, classical |
| Silent identity-key replacement | n/a | Never; typed key-change event | Never; typed key-change event |
| Message integrity vs tampering (A2, A3) | No; corruption detection only | Yes | Yes |
| Mesh broadcast origin authenticity | No | Yes (Ed25519, verified before relay) | Yes (Ed25519, verified before relay) |
| Mesh broadcast confidentiality | No | Open mesh: no. Gated mesh: mesh-key holders only | Open mesh: no. Gated mesh: mesh-key holders only |
| Mesh admission control | Open only | Open or gated (mesh key) | Open or gated (mesh key) |
| Forward secrecy, links and interactive sessions (A7) | No | Yes | Yes |
| Forward secrecy, store-and-forward and first message to an offline recipient (A7) | No | No | No |
| Exposure of recorded first and held messages after key theft (A7) | All | About the last 25 h | About the last 25 h |
| Recognizing a held message's destination when it collects the message (A5) | Yes | Yes, until the message expires (at most 1 h) | Yes, until the message expires (at most 1 h) |
| Only the destination can purge delivered copies | No (forgeable) | Yes | Yes |
| Replay rejection within message lifetime | No | Yes | Yes |
| Receipt authenticity | No (forgeable) | Yes (end-to-end) | Yes (end-to-end) |
| Relay unlinkability of source/destination across epochs (A3 without the node's public key) | No | Yes, pending routing-simulation cost check | Yes, pending routing-simulation cost check |
| Exact message size hidden from relays (A3) | No | Yes, to 32 B steps | Yes, to 32 B steps |
| On-air identifier unlinkability across rotations (A5) | No claim | Android: yes. iOS: no against a continuous observer. Gated mesh: linked peers recognize you until your daily resolving key rotates | Android: yes. iOS: no against a continuous observer. Gated mesh: linked peers recognize you until your daily resolving key rotates |
| Hiding that a MeshLink node is present | No | No | No |
| Anonymity against a global observer | No | No | No |
| Delivery or availability against A3/A4 | No | No | No |
| Mesh connectivity against fake identities (Sybil, A3) | No | Open mesh: no (no routing-layer defense, [evidence](../evidence/2026-11-sybil/README.md)); gated mesh: costs a stolen mesh key | Open mesh: no (no routing-layer defense, [evidence](../evidence/2026-11-sybil/README.md)); gated mesh: costs a stolen mesh key |
| Bounded resources under any remote input | Yes | Yes | Yes |
| Isolation from secured meshes | Yes: secured meshes accept only handshaken frames | n/a | n/a |
