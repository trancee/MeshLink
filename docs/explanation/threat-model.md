# Threat model

This page explains whom MeshLink defends against, what it protects, and why each security profile promises what it does. The exact per-profile guarantees are in [Security profile guarantees](../reference/security-profile-guarantees.md); the decision record is [ADR 0001](../adr/0001-threat-model-and-profile-guarantees.md). Terms follow [`GLOSSARY.md`](../../GLOSSARY.md).

Status: accepted for v1 planning (2026-10-31). Concrete algorithms, wire layout and mechanisms are decided later in the security-profile-suite, discovery, routing and store-and-forward work. Where this page names a primitive (Noise, X25519, ML-KEM-768, Ed25519), it states a constraint already agreed, not a final suite.

## What MeshLink protects

| Asset | Examples |
|---|---|
| Message content | Unicast messages, mesh broadcasts, held store-and-forward messages, receipts |
| Metadata | Who talks to whom, when, how often, message sizes, routing state, neighbor lists |
| Identities | Installation identity keys; the mesh key of a gated mesh |
| Location and presence | Whether, where and when a given node is nearby; long-term movement profiles |
| Node resources | Memory, storage, airtime, connection slots, battery |

## Trust boundary

The application that embeds MeshLink is trusted. MeshLink protects its users from the radio network and from other nodes, not from their own app. Binding an installation identity to a person or contact is the application's job; MeshLink proves only that a frame came from the holder of a given installation key, and offers the tools (trust-on-first-use pinning, fingerprints, short-authentication-string and QR verification helpers) to make that binding.

## Adversaries in scope

| ID | Adversary | Capabilities |
|---|---|---|
| A1 | Passive eavesdropper | Records all radio traffic in range, possibly with several colluding sniffers |
| A2 | Active on-path attacker | Injects, modifies, replays, relabels and relays frames; attempts man-in-the-middle |
| A3 | Malicious mesh member | A legitimate-looking node that drops, delays, modifies or selectively forwards traffic; creates Sybil identities; blackholes routes |
| A4 | Resource-exhaustion attacker | Floods messages, opens connections to occupy slots, starts but never finishes reassemblies |
| A5 | Tracker | Correlates on-air identifiers over time and place to follow a node |
| A6 | Harvest-now-decrypt-later | Records traffic today to decrypt it with a future quantum computer |
| A7 | Device compromised after the fact | Extracts keys from a node after the traffic happened |

## Out of scope

These are stated limits, not defended threats:

- RF jamming: detected and reported, not prevented.
- A live compromise of the OS, kernel or Bluetooth baseband.
- Hardware side channels.
- Physical coercion of a user.
- A malicious host application.
- A global observer doing traffic analysis: MeshLink makes no anonymity claim.

## How the design answers each adversary

### Two encryption layers

In the secured profiles (`classical`, `post-quantum`), every link between neighbors is mutually authenticated and encrypted, and every unicast message and receipt is additionally end-to-end encrypted between source and destination.

- The **link layer** hides routing headers from A1, defeats injection and relabeling by A2, and lets a node authenticate a neighbor before relaying or caching anything it sends. This is the main defense against relay-cache poisoning of the kind found in bitchat (CVE-2026-51376).
- The **end-to-end layer** keeps content away from relays (A3) and from store-and-forward carriers. Bridgefy showed that hop-by-hop protection alone lets every relay read and forge traffic.

### Mesh admission: open or gated

The app chooses per mesh:

- An **open mesh** lets any node with the mesh identifier and security profile join. Link encryption then protects only against outsiders who do not run MeshLink, and mesh broadcasts are effectively public.
- A **gated mesh** additionally requires a **mesh key**, distributed by the app out of band and mixed into every link handshake. Only key holders can join, relay or read broadcasts, which also makes Sybil identities (A3) cost a stolen mesh key.

A leaked mesh key admits outsiders until the app re-keys the mesh; v1 has no revocation.

### Broadcast authenticity and confidentiality

Every mesh broadcast in a secured profile carries an Ed25519 origin signature that relays verify before relaying, so no member can forge another member's broadcast. Broadcast content is readable by every member: in an open mesh it is not private; in a gated mesh it is private to mesh-key holders. There is no per-recipient confidentiality for broadcasts, because encrypted groups are out of scope.

### Fragmentation is per hop

A relay reassembles a whole message, verifies it (link AEAD; for broadcasts also the origin signature), and only then re-fragments it for each next link. Fragments are link-local: they carry a 1 B stream ID and a 2 B index whose top bit marks the last one, and no CRC, because Noise AEAD (secured profiles) or BLE's link-layer CRC24 (`unsecured`) already covers them (ADR 0012). Reassembly buffers are bounded per neighbor in size and time, and incomplete messages are dropped with a typed diagnostic. A forged or corrupt fragment therefore never travels further than one hop, and the destination only ever receives whole, verified messages. The cost is per-hop latency for multi-fragment messages, which bounded message sizes keep acceptable; the routing simulation measures it.

### Metadata visible to relays

Relays must read routing headers. In the secured profiles, source and destination appear as epoch-rotating pseudonymous tags derived from the identity key, so a relay that does not already know a node's public key cannot link its traffic across epochs. Contacts who know the key can. In `unsecured`, routed messages also carry the sender's NodeId in clear. In the secured profiles, end-to-end plaintext is padded to a multiple of 32 B, so relays see message sizes only in 32 B steps (ADR 0012). This requirement stands unless the routing simulation shows the epoch rollover cost to be unacceptable, in which case it returns to the project owner rather than silently weakening.

### Tracking resistance

In the secured profiles, every on-air identifier rotates every 5 min: advertisement payloads, readable GATT values and routing tags seen on air ([ADR 0010](../adr/0010-discovery-and-advertising.md)). What A5 can still do depends on the platform and the mesh:

- **Android:** each rotation starts a new advertising set, so the BLE address and the identifier change in the same packet, and A5 cannot link them. On Android 8 and 9 phones whose OS rotates faster than 5 min, the address may also change inside an epoch.
- **iOS:** the operating system controls the address; an app can neither change it nor see when it changes. A nearby observer who keeps watching links each identifier change by the unchanged address, and each address change by the identifier. iOS gives no unlinkability against a continuous observer.
- **Held messages:** anyone who recorded a message addressed to a node can recognize that node when it claims or acknowledges the message, until the message expires (at most 1 h, [ADR 0014](../adr/0014-store-and-forward.md)).
- **Gated meshes:** a node gives its resolving key, rotated daily, only to peers it links with, inside the encrypted handshake. Those peers can recognize it across rotations until that key rotates, at most a day after their last link. The mesh tag in the advertisement comes from the mesh key but is the same for every member, so a leaked mesh key reveals that members are present, not which ones.
- **Open meshes:** no resolving keys; a node is recognized only after a handshake.
- **After the app stops on iOS:** the phone keeps sending its last scan response, with the MeshLink marker, for at least 26 min. It shows that MeshLink ran and links the phone's addresses.

What is not hidden: that a MeshLink node is present (the `MESH` service UUID) and, in an open mesh, which mesh it belongs to.

### Harvest-now-decrypt-later

The `post-quantum` profile uses hybrid X25519 + ML-KEM-768 key establishment, so recorded traffic stays confidential unless both are broken. Authentication stays classical, including broadcast signatures: forging a signature or handshake needs a quantum computer during the exchange, which A6 does not have today, while post-quantum authentication would add 1.1–2.4 KB per handshake or broadcast (ML-DSA-44 signatures are 2,420 B). The signature algorithm is bound to the protocol suite and never negotiated, so a later protocol version can adopt a post-quantum signature once standardized. SQIsign (level 1: 83 B public key, 200 B signature) is the leading candidate but is still a NIST round-3 candidate.

### Forward secrecy and key compromise

Links and interactive end-to-end sessions use ephemeral keys, so a key stolen later (A7) does not decrypt them. Store-and-forward messages and first messages to an offline recipient are encrypted to the recipient's static key and have no forward secrecy against later theft of that key. The exposure is bounded instead ([ADR 0014](../adr/0014-store-and-forward.md)): messages live at most 1 h, and static keys rotate daily and are deleted at most 1 h and 30 s after their certificate expires, so a stolen phone exposes the recorded first and held messages of about the last 25 h.

A stolen installation key lets A7 impersonate that node and read store-and-forward messages addressed to it until the app performs an identity reset. Peers then see a key change, which MeshLink always reports as a typed event and never accepts silently. There is no revocation protocol in v1, because a stolen key could forge revocations as well. Keys at rest are protected by the platform keystore (Android Keystore; iOS Keychain, this device only).

### Replay

Link frames use AEAD with counter nonces, so replays are rejected. End-to-end messages and broadcasts carry a message ID and are de-duplicated per origin for their lifetime, and expired messages are rejected. This assumes device clocks agree within a tolerance that the wire-format and store-and-forward work fixes; the de-duplication state is bounded by the message lifetime.

### Availability

MeshLink does not guarantee delivery against A3 or A4. It does guarantee bounded resources: no remote input can push a node beyond its configured memory, storage, airtime or connection budgets. In the secured profiles it authenticates before relaying or caching and rate-limits each neighbor.

Fake identities (Sybil, A3) are the main availability limit of an **open** mesh and of `unsecured`: creating an identity costs nothing, so an attacker can fill honest nodes' link slots. A gated mesh raises the cost to a stolen mesh key. In the routing simulation (simulated only, not measured on devices), 10% fake identities took about 40% of honest link slots, and diverse neighbor views still kept about 90% of honest nodes connected. At 30%, the honest mesh broke apart under every strategy and every defense tested: peer scoring by usefulness, probation slots for new links and confirmed neighbor lists ([Sybil evidence](../evidence/2026-11-sybil/README.md)). A free identity refills every slot a defense frees. A fake that relays floods but drops unicast (black hole) keeps broadcasts at 100% while unicast falls to about 75% at 10% fakes. Proof of work was rejected on paper: a laptop out-computes a phone, and honest phones would pay in battery. The real cost to the attacker is radios: 3 radios with 20 links each barely affect a 200-phone mesh. v1 has no routing-layer defense beyond the gated mesh. Apps that need availability against a determined attacker should use a gated mesh.

### Profile `unsecured`

`unsecured` is plaintext and unauthenticated by design and promises nothing against A1–A7. It still keeps resource bounds and per-fragment corruption detection. Nodes in a secured mesh accept only frames from a completed handshake, so `unsecured` frames, even relabeled ones, never enter a secured mesh. Receipts in `unsecured` can be forged.

## Lessons this model builds on

- Bridgefy (CT-RSA 2021, USENIX Security 2022): a good primitive inside a protocol with unauthenticated relay metadata, compression and a shared broadcast key is not a secure system.
- bitchat (2025–2026): broken identity verification allowed impersonation; relay cache poisoning (CVE-2026-51376); no forward secrecy for mail; the authors call metadata "the weakest part".
- Apple Multipeer Connectivity: any peer can force an unencrypted session, which is a negotiated downgrade; MeshLink never negotiates its security profile.

Sources and details: research branches `research/prior-art-ble-meshes` and `research/crypto-options` (`docs/research/2026-10-31-*.md`).
