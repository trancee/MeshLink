# Prior art: BLE peer-to-peer meshes

- Ticket: `.scratch/meshlink-v1/issues/01-prior-art-ble-meshes.md`
- Date: 2026-10-31. Every URL below was accessed on 2026-10-31 unless noted.
- Vocabulary follows `GLOSSARY.md`: node, peer, installation identity, message, mesh broadcast, store-and-forward, security profile.

## Question

Survey bitchat, Briar, Bridgefy, Meshtastic, Berty/Wesh, Apple Multipeer Connectivity and Bluetooth SIG Mesh, including its proxy/GATT bearer. For each, cover:

- transport
- discovery
- routing/flooding
- security
- density behavior
- documented failures
- published evaluations

Also verify whether SIG Mesh fits phone-to-phone meshes, and explain why or why not. The output is a cited comparison plus lessons for MeshLink.

## Method

- Started from cairn's `docs/research/2026-10-23-ble-mesh-design.md`. Every claim was re-checked against primary sources: official specs, repos and docs, and peer-reviewed papers. No code was copied.
- Claims are marked **UNVERIFIED** when only secondary sources were found, or when they are inferences.
- Tools: direct HTTP fetches, the GitHub API, the arXiv API, the Semantic Scholar API, and the Tavily search/extract API.

## Sources

### bitchat

- [B1] bitchat whitepaper v2.0 (dated 2026-07-06): <https://raw.githubusercontent.com/permissionlesstech/bitchat/main/WHITEPAPER.md>
- [B2] bitchat README: <https://github.com/permissionlesstech/bitchat>
- [B3] BARGHEST, "bitchat cache poisoning", CVE-2026-51376: <https://barghest.asia/blog/bitchat-cache-poisoning>
- [B4] Trail of Bits, "Building secure messaging is hard: a nuanced take on the bitchat security debate", 2025-07-18: <https://blog.trailofbits.com/2025/07/18/building-secure-messaging-is-hard-a-nuanced-take-on-the-bitchat-security-debate>
- [B5] TechCrunch, 2025-07-09, reporting Alex Radocea's impersonation finding: <https://techcrunch.com/2025/07/09/jack-dorsey-says-his-secure-new-bitchat-app-has-not-been-tested-for-security>
- [B6] bitchat GitHub issues:
  - #1618 <https://github.com/permissionlesstech/bitchat/issues/1618>
  - #202 <https://github.com/permissionlesstech/bitchat/issues/202>
  - #116 <https://github.com/permissionlesstech/bitchat/issues/116>
  - #1064 <https://github.com/permissionlesstech/bitchat/issues/1064>

### Bridgefy

- [BF1] Albrecht, Blasco, Jensen, Mareková, "Mesh Messaging in Large-scale Protests: Breaking Bridgefy", CT-RSA 2021: <https://eprint.iacr.org/2021/214>
- [BF2] Albrecht, Eikenberg, Paterson, "Breaking Bridgefy, again: Adopting libsignal is not enough", USENIX Security 2022: <https://www.usenix.org/conference/usenixsecurity22/presentation/albrecht> (PDF <https://www.usenix.org/system/files/sec22-albrecht.pdf>)

### Briar

- [BR1] Briar Transport Protocol v4: <https://code.briarproject.org/briar/briar-spec/raw/master/protocols/BTP.md>
- [BR2] Briar Android Bluetooth plugin: <https://code.briarproject.org/briar/briar/-/raw/master/bramble-android/src/main/java/org/briarproject/bramble/plugin/bluetooth/AndroidBluetoothPlugin.java>
- [BR3] How it works: <https://briarproject.org/how-it-works/>
- [BR4] Download page: <https://briarproject.org/download-briar/>

### Meshtastic

- [M1] Mesh broadcast algorithm: <https://meshtastic.org/docs/overview/mesh-algo/>
- [M2] Encryption limitations: <https://meshtastic.org/docs/about/overview/encryption/limitations/>
- [M3] Introduction: <https://meshtastic.org/docs/introduction/>

### Berty/Wesh

- [W1] Wesh protocol docs: <https://berty.tech/docs/protocol>
- [W2] Berty blog, "Bluetooth Low Energy": <https://berty.tech/blog/bluetooth-low-energy>
- [W3] Repos:
  - <https://github.com/berty/weshnet> (`pkg/proximitytransport`, `pkg/ble-driver`)
  - <https://github.com/berty/berty> (Android `GattServer.java`)

### Apple Multipeer Connectivity

- [A1] Apple Multipeer Connectivity docs: <https://developer.apple.com/documentation/multipeerconnectivity>, plus the `MCSession`, `kMCSessionMaximumNumberOfPeers` and `MCEncryptionPreference` pages
- [A2] Stute et al., "A Billion Open Interfaces for Eve and Mallory" (AWDL/AirDrop BLE attacks), USENIX Security 2019: <https://www.usenix.org/conference/usenixsecurity19/presentation/stute>

### Bluetooth SIG Mesh

- [S1] Bluetooth Mesh Protocol 1.1 (revision date 2023-09-12): <https://www.bluetooth.com/specifications/specs/html/?src=MshPRT_v1.1/out/en/index-en.html>
- [S2] Apple Core Bluetooth, `CBPeripheralManager.startAdvertising(_:)`: <https://developer.apple.com/documentation/corebluetooth/cbperipheralmanager/startadvertising(_:)>
- [S3] Android `AdvertiseData.Builder`: <https://developer.android.com/reference/android/bluetooth/le/AdvertiseData.Builder>
- [S4] Nordic nRF Mesh library for iOS, README: <https://github.com/NordicSemiconductor/IOS-nRF-Mesh-Library>
- [S5] Silicon Labs AN1299, "Understanding the Bluetooth Mesh Lighting Demo": <https://www.silabs.com/documents/public/application-notes/an1299-understanding-bluetooth-mesh-lighting-demo-sdk-2x.pdf> (quote taken from the search-engine excerpt; full PDF not read)
- [S6] Baert et al., "The Bluetooth Mesh Standard: An Overview and Experimental Evaluation", Sensors 18(8):2409, 2018: <https://pmc.ncbi.nlm.nih.gov/articles/PMC6111614/>
- [S7] Rondón, Mahmood, Grimaldi, Gidlund, "Understanding the Performance of Bluetooth Mesh: Reliability, Delay and Scalability Analysis", arXiv:1910.03345: <https://arxiv.org/abs/1910.03345>
- [S8] Aijaz et al., "Demystifying the Performance of Bluetooth Mesh: Experimental Evaluation and Optimization", IEEE Wireless Days 2021, arXiv:2106.04230: <https://arxiv.org/abs/2106.04230>
- [S9] Hernández-Solana et al., "Bluetooth Mesh Analysis, Issues, and Challenges", IEEE Access 8, 2020, doi:10.1109/ACCESS.2020.2980795. Abstract via <https://api.semanticscholar.org/graph/v1/paper/b880a497d6905d9fbd3a8c5199c3817fccbb1c00>

## Findings

### bitchat (permissionlesstech) — iOS + Android, BLE

#### Transport and discovery [B1]

- Every device is simultaneously a GATT central and a GATT peripheral. There is no pairing and no accounts.
- Peers send announcements every 4 s while isolated, backing off to 15–30 s once connected.
- A peer stays reachable for 60 s after its last announcement.
- Connections are RSSI-gated and scanning is duty-cycled.

#### Routing [B1]

- Mesh broadcast is a controlled flood:
  - TTL 7, clamped to 5 when the node has ≥6 links; full depth when it has ≤2 links.
  - A seen-set (LRU, 1,000 entries, 5-minute expiry) drops duplicates.
  - Relays wait a random 10–220 ms. A scheduled relay is cancelled if a duplicate arrives first.
  - Broadcasts go to a seeded subset of about log2(degree) links, never back to the ingress link.
- Directed traffic relays at TTL−1 with full fanout.
- Source routing is used over bidirectionally confirmed paths, falling back to flood.
- Fragmentation:
  - ~469 B fragments
  - up to 128 concurrent reassemblies
  - 30 s reassembly timeout
  - 1 MiB message cap
- The README advertises LZ4 compression and a maximum of 7 hops [B2].

#### Store-and-forward [B1]

- Outbox: 100 messages per peer, kept 24 h, 8 attempts.
- Courier ("mail") mode:
  - up to 3 couriers per message
  - recipient identified by a daily-rotating 16 B HMAC tag
  - spray-and-wait copy budget 4, capped at 8
  - per-sender quotas of 5 (favorites) / 2 (verified) inside a 20-of-40 pool
  - 16 KiB payload limit, 24 h lifetime
- Gossip sync uses a GCS (Golomb-coded set) filter every ~15 s, with 6 h retention.
- Nostr is the internet fallback.

#### Security [B1]

- Live sessions use Noise XX (25519/ChaChaPoly/SHA256).
- Courier mail uses Noise X, which gives no forward secrecy for that message.
- Padding to 256/512/1024/2048-byte buckets applies to Noise frames only.
- The peer ID is the first 8 bytes of SHA-256(Noise static key) and never rotates.
- Announcements carry the nickname, static keys and up to 10 neighbor IDs in cleartext.
- The whitepaper itself states "Metadata is the weakest part" and lists unlinkable presence as future work.

#### Failures and analyses

- **Identity verification (2025-07):** Alex Radocea showed impersonation/MITM through broken identity verification ("Favorites"). Reported by [B5] and discussed by [B4]. Trail of Bits also notes a buffer overflow patched within about 4 hours and the move to the Noise framework.
  - **UNVERIFIED:** Radocea's primary post was not retrieved.
- **CVE-2026-51376 [B3]:** iOS 1.15.0 trusted the sender ID embedded in the packet and admitted *unsigned* packets into the gossip-sync cache. The poison then self-propagated through sync. Patched 2026-01-28.
- **Cross-platform divergence [B6 #1618]:** iOS caps decompressed payloads at 1,179,760 B while Android allows 10 MiB, so large messages are silently dropped. The issue was open as of 2026-08-02.
- **Other issues [B6]:**
  - #202: plaintext accepted in a password-protected channel, because encryption is flag-driven.
  - #116: private key stored in UserDefaults.
  - #1064: the default view is the unencrypted #mesh channel.

#### Density and scale evaluations

- None found in peer-reviewed literature. The density adaptations (TTL clamp, log2 fanout, jitter) are design choices without published measurements.

### Bridgefy — iOS + Android SDK/app, BLE (also Classic Bluetooth)

#### Protocol, as described in [BF2]

- BLE is the default bearer; Classic Bluetooth is also supported.
- Payloads are MessagePack plus gzip.
- The forwarded-packet header has:
  - a 1-byte `hops` TTL
  - a `track` list of CRC-32s of the userIds of the last n relaying nodes
- The libsignal handshake runs only over a direct link.
- Private messages go direct when the peer is in range.
- Over 6.5M downloads.

#### CT-RSA 2021 findings [BF1]

- Users can be tracked.
- No authenticity and no effective confidentiality.
- Attackers can build social graphs and impersonate anyone.
- A single crafted message — a gzip decompression bomb — shut down the network.

**USENIX Security 2022 findings [BF2]** (app 3.1.3 / SDK 2.0.2, after the switch to libsignal)

- MITM, broadcast impersonation and limited DoS persist.
- A new time-of-check/time-of-use attack.
- Broadcast messages can be recovered via a compression side channel without knowing the shared key.
- Privacy issues remain.

**Lesson:** dropping a good primitive (libsignal) into a protocol with unauthenticated relay metadata, compression and a shared broadcast key does not give a secure system.

#### Density and scale evaluations

- No published density evaluation found.

### Briar — Android (desktop beta), Bluetooth Classic + Wi-Fi + Tor

#### Transport

- The Android Bluetooth plugin uses Classic RFCOMM (`listenUsingInsecureRfcommWithServiceRecord`, [BR2] lines ~153–181). It is **not** BLE.
- BTP v4 [BR1]:
  - no plaintext headers
  - forward secrecy from a time-based key rotation
  - explicitly provides no anonymity or unlinkability at the transport layer
  - transport-agnostic, so it also works over removable disks

#### Routing

- None in the mesh sense. Data syncs only between contacts (contact-only delay-tolerant sync) [BR3].
- The adversary model assumes the adversary may control an unlimited number of devices [BR3].

#### Platforms

- The download page offers Android via Google Play and F-Droid [BR4].
- No iOS app is listed. **UNVERIFIED** whether one exists or is planned; the FAQ link returned 404.

#### Lesson

- Contact-gated sync avoids Sybil/flooding attacks by design, but it cannot carry traffic for strangers. That conflicts with MeshLink's any-node relaying.

### Meshtastic — LoRa radios; phones are BLE clients

#### Transport

- The mesh runs over LoRa, not BLE.
- Phones pair with a single radio [M3] and act as its UI/client over BLE (Bluetooth, Wi-Fi or serial). That phones are clients only, not mesh nodes, is **inferred** from [M3]/[M1].

#### Routing [M1]

- 16-byte header:
  - dest, sender, packet ID
  - flags: 3-bit HopLimit, WantAck, ViaMQTT, HopStart
  - channel hash, next-hop, relay node
- Max payload 239 B.
- Channel access is CSMA/CA with a contention window sized by channel utilization.
- "Managed flooding":
  - Nodes listen before rebroadcasting and cancel if they hear the packet relayed.
  - The contention window is SNR-scaled so *farther* nodes rebroadcast first.
  - ROUTER-role nodes always rebroadcast.
- Broadcast acknowledgement is implicit: hearing a rebroadcast counts as an ACK.
- Retries: 3 total for reliable broadcast, 5 for DMs.
- Since 2.6, DMs use **next-hop routing** learned from replies, falling back to flooding on the last retry.
- Broadcast intervals scale up once a mesh exceeds 40 nodes.
- The node number is the CRC-32 of the public key.

#### Security limitations, self-documented [M2]

- TOFU is called "essentially a hard requirement of a decentralized mesh".
- The NodeDB holds 120 nodes. Fake nodes can evict real ones and enable spoofing; a warm store retains evicted keys.
- Channel encryption is a PSK with AES-CTR, which is **unauthenticated**. IV = node number + packet ID, so known plaintext enables spoofing.
- DMs use X25519 + AES-CCM (since 2.5).

#### Lesson

- Managed flooding (listen, cancel, jitter, implicit ACK) plus learned next-hop for unicast is the best-documented density-aware design found in this survey.
- The peer table is a Sybil target.

### Berty / Wesh — iOS + Android, libp2p proximity transports

#### Transport [W1]

- Direct transports plug into libp2p:
  - Android Nearby on Android
  - Multipeer Connectivity on iOS
  - BLE for Android↔iOS
- Wi-Fi Direct is used where available.
- On a direct connection, peers exchange the lists of rendezvous points they listen on. The docs warn this lets the other side link a member's identities across groups.
- Direct transport is "synchronous": both devices must be connected. The repo has `pkg/proximitytransport` and `pkg/ble-driver` [W3].
- **Inferred, not explicitly stated:** no multi-hop BLE relaying is documented.

#### Experience report [W2]

- The iOS BLE driver was comparatively easy.
- Android BLE was much harder: concurrency and rotating MAC addresses.
- Android↔iOS needed extensive tuning.
- Connections were "very unstable": loops, and drops after seconds with unexplained causes.

#### Lesson

- Expect Android BLE stack instability and budget for a device lab.
- Exchanging membership lists on contact is a linkability leak.

### libp2p — modular P2P networking stack (added 2026-11-01)

Sources: [libp2p.io](https://libp2p.io/), [libp2p/libp2p](https://github.com/libp2p/libp2p), [libp2p/specs](https://github.com/libp2p/specs), read 2026-11-01.

#### Security handshake [L1]

- [noise-libp2p](https://github.com/libp2p/specs/blob/master/noise/README.md) uses one fixed suite, `Noise_XX_25519_ChaChaPoly_SHA256`, with no negotiation inside it.
- **Why XX:** an earlier Noise Pipes design (IK + XXfallback) was dropped as "too much complexity to be worth the benefit".
- **Why ChaChaPoly:** it is safe to implement in software on every platform, unlike AES.
- **Distinct keys:** a separate identity key signs the Noise static key (`identity_sig` in a protobuf `NoiseHandshakePayload`). The static key may be regenerated per session and must not be persisted.
- **Early data:** extensions ride in the handshake payload, restricted to internal libp2p use.

#### Post-quantum (open, not merged) [L2]

- [specs#723](https://github.com/libp2p/specs/issues/723) and draft [specs#727](https://github.com/libp2p/specs/pull/727) propose `Noise_XXhfs_25519+ML-KEM-768_ChaChaPoly_SHA256` as a separately negotiated protocol. Authentication stays classical (the identity signature); only confidentiality is hybrid. Token placement and KDF order are deferred to the Noise HFS spec. Still open: the protocol id, test vectors, and whether to cover the whole ML-KEM family.
- **Reference implementations:**
  - [rust-libp2p#6481](https://github.com/libp2p/rust-libp2p/pull/6481) (draft);
  - [snow#210](https://github.com/mcginty/snow/pull/210) (open), which adds ML-KEM-768 to snow's `hfs`. snow 0.10.0 ships `hfs` with Kyber1024 only.

  The PR author claims that four implementations in four languages interoperate. That claim is unverified.

#### Dissemination [L3]

- [gossipsub](https://github.com/libp2p/specs/tree/master/pubsub/gossipsub):
  - v1.0: a mesh of degree `D` with GRAFT/PRUNE, plus lazy IHAVE/IWANT gossip.
  - v1.1: peer scoring, flood publishing, adaptive gossip.
  - v1.2: IDONTWANT, which suppresses duplicate full messages.
- Other specs: Kademlia DHT, mDNS discovery, rendezvous, circuit relay with DCUtR.

#### Transport

- The specs index has no Bluetooth/BLE transport spec, and a search of the specs issues for "bluetooth" returned nothing. BLE use exists only out of tree, e.g. Berty/Wesh above.
- The full stack (multistream-select negotiation, yamux multiplexing, protobuf framing, multihash peer IDs) is not designed for 20–512 B BLE frames. Its per-link overhead is unquantified here.

#### Lessons

- libp2p independently converged on MeshLink's link handshake (ADR 0002): XX only, ChaChaPoly + SHA-256, one fixed suite.
- It also converged on our identity model (ADR 0003): an identity key that certifies a rotatable Noise static key.
- Its post-quantum proposal is our `post-quantum` link suite. Aligning byte-for-byte lets both projects share `XXhfs` vectors.
- gossipsub v1.1 peer scoring and v1.2 IDONTWANT are relevant to mesh broadcast under Sybil pressure and at high density.
- Don't adopt the libp2p stack itself over BLE (user decision 2026-11-01: study only, plus aligned `XXhfs`).

### Apple Multipeer Connectivity — Apple platforms only

- **Transport [A1]:** infrastructure Wi-Fi, peer-to-peer Wi-Fi (AWDL) and Bluetooth PAN on iOS.
- **Sessions:**
  - invitation-based
  - "Sessions currently support up to 8 peers, including the local peer" (`kMCSessionMaximumNumberOfPeers`)
  - In the background the framework stops advertising/browsing and disconnects sessions.
- **Encryption:** `MCEncryptionPreference` is optional, required or none. If any peer chooses none, the session is unencrypted — a negotiated downgrade.
- **Interop:** no Android interop.
- **Deprecation:** `MCSession` availability metadata shows "iOS 7.0–27.0". Whether this signals a planned deprecation is **UNVERIFIED**; no deprecation text was found.
- **AWDL analysis [A2]:** AWDL and AirDrop's BLE discovery enable MITM, DoS and long-term tracking that undermines MAC randomization.
- **Lesson:** not a mesh. Usable at most as an optional, iOS-only, foreground, ≤8-peer bearer. Copy neither its downgrade semantics nor its implicit trust.

### Bluetooth SIG Mesh 1.1

#### Bearers [S1]

- Advertising bearer:
  - `ADV_NONCONN_IND` with the Mesh Message AD type
  - nodes are expected to scan with a near-100% duty cycle
- GATT bearer:
  - Proxy protocol
  - exists "to enable devices that are not capable of supporting the advertising bearer"
  - PB-GATT lets "legacy phones" act as Provisioners

#### Forwarding rules (§3.4.6, Table 3.13) [S1]

- The Relay feature forwards ADV→ADV only.
- The Proxy feature forwards between GATT and ADV.
- "If the Proxy feature is supported, then both GATT and advertising bearers shall be supported."
- So a GATT-only node can be neither a Relay nor a Proxy. A mesh made only of phones (GATT-only) has **no standard forwarding path**.
- Directed forwarding (1.1) also relies on nodes supporting these bearers.

#### Security model [S1]

- NetKeys secure the network layer and AppKeys the upper transport. "The keys are shared between nodes."
- Nodes are admitted by a Provisioner. Every relay holds the NetKey, so any compromised relay can inject network traffic.
- Other limits:
  - IV Update takes ≥8 days (≥96 h in each of the two states, §3.11.5)
  - 32,767 unicast addresses
  - access message ≤380 B (upper transport ≤384 B via SAR, 32 segments)
- Also defined: subnet bridge, Friend/LPN, Mesh Private beacons.

#### Phone APIs

- iOS `startAdvertising` accepts only `CBAdvertisementDataLocalNameKey` and `CBAdvertisementDataServiceUUIDsKey` [S2].
- Android `AdvertiseData.Builder` exposes only:
  - manufacturer data
  - service data
  - service UUIDs
  - solicitation UUIDs
  - transport discovery data
  - device name
  - TX power

  It has no raw AD-type field, so the Mesh Message AD type cannot be emitted [S3].
- Nordic's iOS mesh library states "Implementing ADV bearer on iOS is not possible due to API limitations" and requires a GATT Proxy node [S4].
- Silicon Labs notes that mesh nodes "also use Bluetooth connections and GATT services to communicate with devices that do not natively support Bluetooth mesh" [S5].

#### Evaluations

- [S6] Sensors 2018: the random back-off dominates round-trip time. More neighbors lower RTT until density causes collisions. The standard is "not suited to a network solely of power limited nodes".
- [S7] arXiv 2019 simulations:
  - timing parameters are highly interdependent
  - randomization helps
  - ~250 ms delay over >10 hops
  - vulnerable to Wi-Fi interference despite using 3 channels
  - "scalability is especially challenging … prone to broadcast storm, hindering … reliability for denser deployments"
- [S8] nRF52840 testbed:
  - unicast/group modes, segmentation cost, latency for perfect reliability
  - improvements from parameter tuning, extended advertising, power control and customized relaying (abstract only read)
- [S9] Real-device assessment of repetition and randomization schemes. Managed flooding suits small or highly dynamic networks; proposes tuning criteria and standard improvements (abstract only read).

**Verdict: SIG Mesh does not fit phone-to-phone meshes.**

- Neither iOS nor Android apps can emit the advertising bearer.
- The GATT bearer is defined only as an edge attachment to a Proxy node that must also support ADV.
- The shared NetKey/Provisioner admission model also conflicts with MeshLink's open, per-installation-identity model.
- Its published evaluations still matter: they are the best available data on flooding under density.

## Comparison

| System | Bearer (phones) | Discovery | Multi-hop | Routing | Peer auth / E2E | Broadcast authenticity | On-air identity | Store-and-forward | Platforms | Published security / scale findings |
|---|---|---|---|---|---|---|---|---|---|---|
| bitchat | BLE GATT, dual role | Adverts + periodic announces (4 s→15–30 s) | Yes, ≤7 hops | Managed flood (TTL clamp, log2 fanout, jitter, cancel-on-dup) + source routes | Noise XX; Noise X for mail (no FS) | Public #mesh unencrypted; signing gaps led to CVE-2026-51376 | Static 8-byte ID + cleartext nickname/keys/neighbors | Outbox + couriers (spray-and-wait, quotas) + GCS gossip | iOS, Android | Impersonation (2025, secondary sources), cache poisoning CVE, cross-platform limit divergence; no scale study |
| Bridgefy | BLE (+ Classic) | SDK-internal | Yes | Flood with 1-byte hop TTL + `track` list | libsignal over direct link only | Shared key; impersonable; compression side channel | Tracking via userId CRCs | Not established | iOS, Android | CT-RSA'21, USENIX Sec'22: MITM, impersonation, gzip-bomb DoS, tracking |
| Briar | Bluetooth Classic RFCOMM, Wi-Fi, Tor | Contacts only | No (contact sync) | None | BTP: FS, no plaintext headers | n/a | No unlinkability claimed | Contact-to-contact sync | Android (+ desktop) | Adversary may control unlimited devices; no BLE |
| Meshtastic | LoRa (phone↔radio via BLE) | NodeInfo broadcasts | Yes (HopLimit ≤7) | Managed flood (SNR-ordered, listen-cancel, implicit ACK) + learned next-hop DMs | X25519 + AES-CCM DMs; TOFU | Channel PSK AES-CTR, unauthenticated | NodeNum = CRC32(pubkey), static | Router roles / store-and-forward module (not reviewed) | Radios + phone apps | Self-documented: TOFU, 120-node DB eviction, PSK spoofing |
| Berty/Wesh | MPC (iOS), Nearby (Android), BLE (cross-OS) | Platform frameworks + BLE | Not documented (inferred no) | libp2p direct links | Wesh crypto (X25519/Ed25519, NaCl) | Group-signed | RDV-point exchange links identities | CRDT/IPFS log sync | iOS, Android | Self-reported BLE instability |
| Apple MPC | Wi-Fi, P2P Wi-Fi, BT PAN | Bonjour-style browse/advertise | No | n/a | Optional; any peer can force none | n/a | Display name | No | Apple only | ≤8 peers, foreground only; AWDL attacks (USENIX'19) |
| SIG Mesh 1.1 | ADV bearer (not emittable by phone apps); GATT proxy edge only | Unprovisioned beacons / provisioning | Yes (relays, directed forwarding) | Managed flood + directed forwarding | NetKey/AppKey shared, Provisioner-admitted | Network-key MIC only (any member can forge) | Obfuscated header; Private beacons | Friend/LPN | Embedded SoCs; phones via proxy | Broadcast storm at density; backoff dominates latency (S6–S9) |

## Lessons and recommendations for MeshLink

1. **Bearer: our own connection-based BLE protocol.**
   - Use GATT, with L2CAP CoC where available; to be decided by the transport tickets. SIG Mesh is out: it fails its own bearer rules on phones.
   - Every surveyed phone system (bitchat, Bridgefy, Berty) converged on dual-role GATT links.
2. **Mesh broadcast: managed flooding, combining bitchat and Meshtastic.**
   - Seen-set dedup.
   - Random relay jitter, with cancel-on-duplicate.
   - Degree-adaptive TTL and fanout subsetting.
   - Split horizon.
   - Implicit ACK by overhearing.
   - **Directed messages:** learned next-hop with flood fallback, as in Meshtastic 2.6.
   - SIG Mesh evaluations [S6–S9] show that randomization and back-off tuning dominate reliability and latency at density. Make these parameters explicit and simulate them.
3. **Authenticate before admission.**
   - Nothing enters the seen-set, relay queue, store-and-forward cache or sync set without verifying its origin signature or MAC.
   - Lessons: bitchat CVE-2026-51376, Bridgefy impersonation.
   - Never trust a sender ID embedded in a packet.
4. **Per-sender broadcast authenticity.**
   - A shared key gives confidentiality at best, never authenticity: see Meshtastic AES-CTR PSK, Bridgefy, and the SIG Mesh NetKey.
   - Broadcasts need sender signatures. Their size and cost under the post-quantum security profile is an open question.
5. **Unlinkable on-air identity.**
   - Rotate advertised and relay identifiers.
   - Disclose the installation identity only inside an encrypted handshake.
   - Never put static IDs, neighbor lists or relay trails in cleartext. bitchat, Bridgefy's `track` list and Meshtastic all leak these.
6. **No negotiated downgrade.**
   - A security profile is fixed by configuration and never silently relaxed by a peer.
   - Anti-patterns: MPC `none`, bitchat's flag-based encryption (#202).
7. **Compression.**
   - Avoid it, or bound the decompressed size before inflating and never compress secret data alongside attacker-controlled data.
   - Lessons: Bridgefy gzip-bomb DoS and compression side channel; bitchat cross-platform cap mismatch.
8. **One constants table across platforms.**
   - Covers limits, TTLs and sizes.
   - Enforce it with shared conformance vectors to avoid silent drops like bitchat #1618.
9. **Peer table under Sybil pressure.**
   - Bounded tables with eviction policies that new identities cannot game. Lesson: Meshtastic NodeDB eviction.
   - This feeds the threat model.
10. **Store-and-forward.**
    - bitchat's bounded spray-and-wait with per-sender quotas, rotating recipient tags and TTL is a concrete starting point.
    - Avoid sender-only forward-secrecy gaps like Noise X for courier mail, or document them.
11. **Pad all frames, not only encrypted sessions.**
12. **Plan for platform instability.**
    - Berty's report and Apple MPC's background/8-peer limits mean dense, background operation must be measured on real devices, not assumed.

## Open questions (candidate tickets)

- **Density data gap:** no published phone-based BLE mesh evaluation at 50–200 peers in range was found. Simulation and a device lab are required to choose flood parameters.
- **Rotating identifier design:** rotation period, linkage to connection state, and interaction with iOS background overflow-area advertising.
- **Broadcast signature cost:** under classical vs post-quantum security profiles, given fragment sizes of ~469 B (bitchat) or 380 B (SIG Mesh access).
- **Compression policy for the wire format:** none vs bounded.
- **Peer-table sizing and Sybil-resistant eviction.**
- **Store-and-forward custody:** couriers, copy budget, quotas, forward-secrecy trade-off.
- **Optional platform bearers:** whether MPC, Wi-Fi Aware or Nearby Connections should be offered for throughput while BLE stays the interoperable baseline.

## Limitations

- Radocea's bitchat findings are known only through secondary sources [B4, B5]; the primary write-up was not retrieved.
- Only the abstracts of [S8] and [S9] were read. [S6] and [BF2] were read in full text; [BF1] and [S7] only via abstract/summary.
- Unverified items:
  - whether Briar has an iOS app
  - whether Multipeer Connectivity is deprecated
  - Meshtastic's phone-as-client-only role (inferred)
  - Wesh's lack of multi-hop (inferred)
  - current Bridgefy SDK status after 2022
- bitchat's density parameters are design statements, not measured results.
- libp2p: the interop claim in snow#210 and the stack overhead over BLE are unverified.
- Cairn claims not re-verified here and not relied on: Exposure Notification / Find My as rotating-identifier references, and the SIG Mesh Network PDU field layout.
