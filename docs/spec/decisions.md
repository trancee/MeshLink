# Decision log

Status: Draft. Part of [MeshLink protocol 1](README.md).

This log lists every protocol 1 choice that no ADR fixed when the specification was written, and every conflict or ambiguity found between ADRs. The owner accepted every entry on 2026-11-08 (ticket "Write the v1 protocol spec", round 2). The specification cites each decision as `[D-nn]` or in a data file's `decision` field.

Protocol 1 stays Draft until library 0.1.0. After that, Constitution rules C6 and C7 apply to every entry.

Each decision gives the question, the decision, the evidence or rationale, and the alternatives considered.

## Decisions

### D-01 Domain-string binding

- **Question:** how is a domain string bound into each derivation?
- **Decision:** `DS = u8(len) ‖ "MeshLink/1 <purpose>"`. Hashes: BLAKE2b with 32 B output over `DS ‖ inputs`, keyed when stated, then truncated. Keys: HKDF-SHA-256 with `info = DS ‖ context`; empty salt is 32 zero bytes. Signatures and prologues start with `DS`.
- **Evidence:** ADR 0012 decision 8 picks BLAKE2b for short identifiers; ADR 0002 picks HKDF-SHA-256 for keys; ADR 0018 decision 3 requires "a domain-separation string fixed in the spec". The length prefix makes every input unambiguous. A fixed 32 B output then truncation lets one libsodium call shape serve every tag.
- **Alternatives:** BLAKE2b `personal` parameter (16 B limit, not exposed by every binding); output length per use (changes the parameter block, so different values per length); NUL-terminated strings.

### D-02 Epoch number and acceptance

- **Question:** how are epochs numbered, and how do per-node phases fit a global number?
- **Decision:** epoch = `floor(unix / 300)`, `u32`. A node switches to the current global epoch number at its own random rotation instant. Receivers accept e − 1, e and e + 1.
- **Evidence:** ADR 0010 decision 3 and ADR 0008 amendment (5 min, own phase, ±1 epoch). A shared number lets receivers compute tags without knowing the sender's phase.
- **Alternatives:** per-node epoch offsets carried on air (linkable); aligned rotation (simulated "5 min aligned"; synchronized rotation is a tracking aid).

### D-03 Mesh identifier encoding and digest

- **Question:** how is the mesh identifier encoded and bound to the profile?
- **Decision:** UTF-8, 1 to 64 B, no normalization. `MeshIdDigest = H("mesh-id", u16(len) ‖ id ‖ u8(profile))`.
- **Evidence:** ADR 0010 decision 1 (the identifier names the mesh); ADR 0001 (one profile per mesh, never negotiated).
- **Alternatives:** Unicode NFC normalization (platform differences); longer identifiers.

### D-04 Profile codes

- **Question:** which byte values name the profiles?
- **Decision:** `0x00` unsecured, `0x01` classical, `0x02` post-quantum.
- **Evidence:** order of ADR 0001's profile list; 0 for "no security" is easy to spot in dumps.
- **Alternatives:** start at 1 and reserve 0.

### D-05 Mesh tag derivation

- **Question:** how is the 4 B mesh tag derived, and is it keyed?
- **Decision:** keyed BLAKE2b over `u32(epoch)`. Key: `MeshIdDigest` for open and unsecured meshes; `KDF(mesh_key, MeshIdDigest, "mesh-tag-key")` for gated meshes. Truncated to 4 B.
- **Evidence:** ADR 0010 decision 1 (tag rotates per epoch; gated meshes must not be recognizable without the key).
- **Alternatives:** unkeyed hash of identifier and epoch (gated mesh identifiers could be guessed offline).

### D-06 Mesh psk

- **Question:** how is the `XXpsk3` psk derived from the mesh key?
- **Decision:** `KDF(mesh_key, MeshIdDigest, "mesh-psk", "", 32)`.
- **Evidence:** ADR 0002 decision ("psk = HKDF-SHA-256 of the mesh key bound to the mesh identifier").
- **Alternatives:** mesh identifier as `info` context instead of salt.

### D-07 Resolvable tag input

- **Question:** what does the resolving key hash?
- **Decision:** `trunc_8(H("resolvable-tag", u32(epoch); key = resolving_key))`.
- **Evidence:** ADR 0010 decision 4 (precomputed per epoch).
- **Alternatives:** include the mesh tag (no gain: the key is already per node).

### D-08 Public and unsecured tags

- **Question:** what are the exact inputs of public and unsecured routing tags?
- **Decision:** public: `trunc_8(H("public-tag", x25519_key ‖ u32(epoch)))`. Unsecured: `trunc_8(H("unsecured-tag", NodeId ‖ u32(epoch)))`.
- **Evidence:** ADR 0016 decision 1 (public tags from the certified static key) and ADR 0018 decision 10 (unsecured tags from the NodeId).
- **Alternatives:** keyed BLAKE2b with the static key as key (equivalent; the unkeyed form is simpler to vector).

### D-09 Lookup tag input order

- **Question:** in what order are salt and NodeId hashed?
- **Decision:** `trunc_8(H("lookup-tag", salt ‖ NodeId))`.
- **Evidence:** ADR 0018 decision 3 ("over salt ‖ NodeId").
- **Alternatives:** none material.

### D-10 Tombstone commitment

- **Question:** how is `H(k)` computed and truncated?
- **Decision:** `trunc_16(H("tombstone", k))`.
- **Evidence:** ADR 0014 decision 8 (16 B commitment); 128-bit preimage resistance suffices for a 1 h lifetime.
- **Alternatives:** full 32 B (16 B more per message).

### D-11 Certificate version and signature input

- **Question:** what version byte does the certificate carry and what exactly is signed?
- **Decision:** version `0x01`. Ed25519 (pure) over `DS("certificate") ‖` every byte before the signature.
- **Evidence:** ADR 0012 decision 9 (fixed layout, 138 / 1,354 B including a version and profile byte).
- **Alternatives:** Ed25519ph; signing without a domain string (cross-protocol risk).

### D-12 Advertisement byte 0 and UUID byte order

- **Question:** how are layout version and flags packed, and how do payload bytes map to the UUID?
- **Decision:** bits 7–5 layout version `001`; bit 0 `COC_AVAILABLE`; bit 1 `PHY_2M`; bits 2–4 reserved. Payload byte 0 is the first byte of the UUID in canonical string order.
- **Evidence:** ADR 0012 decision 10 (16 B, only byte 0 bit-packed); ADR 0009 decisions 1 and 3 (CoC and 2M are peer-choice hints).
- **Alternatives:** little-endian Bluetooth byte order (platforms differ in what they expose; string order is what both platform APIs print).

### D-13 GATT service and characteristic UUIDs and properties

- **Question:** which UUIDs do the GATT service and its three characteristics use, and with which properties?
- **Decision:** service `4D455348-4D4C-4E4B-8000-000000000000`; characteristics `4D455348-4D4C-4E4B-8001-00000000000{1,2,3}`: capability (read), central to peripheral (write without response), peripheral to central (notify). None lies on the Bluetooth Base UUID, which only the advertised MESH UUID uses.
- **Evidence:** ADR 0009 decision 1 and ADR 0010 decision 5 (capability read before handshake). "MESH" "ML" "NK" in ASCII keeps them recognizable. The service moved off the Base UUID after the Xiaomi Mi Note 3 (Android 9) sent the Base-UUID service as a 32-bit UUID, which ATT forbids, so no central found it (ADR 0010 amendment 2026-11-15).
- **Alternatives:** one characteristic with write and notify (mixes directions); indications (slower, acknowledged at ATT); serving the advertised MESH UUID as the service (a vendor stack shortened it on the wire).

### D-14 Capability record

- **Question:** what is the capability record layout and its limits?
- **Decision:** `u8` count of 1 to 8 versions, strictly descending; then capability TLVs `L2CAP_PSM` (`0x01`, 2 B) and `ADVERTISEMENT` (`0x02`, 16 B). At most 512 B.
- **Evidence:** ADR 0012 decision 6 and ADR 0019 amendment (version list exchanged before the handshake), ADR 0009 decision 1 (PSM), ADR 0010 decision 5 (identifier of a backgrounded iPhone). 512 B is the ATT attribute maximum.
- **Alternatives:** a single version byte plus a bitmap; a separate characteristic for the PSM.

### D-15 Handshake roles, frame and prologue

- **Question:** who initiates, how do handshake messages travel, and what does the prologue bind?
- **Decision:** the GATT central initiates. `HANDSHAKE` frame = version, step 1–3, Noise message, in cleartext records, fragmented if needed. Link prologue = `DS ‖ u8(n) ‖ peripheral versions ‖ selected ‖ profile ‖ MeshIdDigest`. End-to-end prologue = `DS ‖ version ‖ profile`.
- **Evidence:** ADR 0012 decision 6 (prologue binds the version), ADR 0009 decision 1 (central reads capability first).
- **Alternatives:** the lower NodeId initiates (unknown before the handshake); binding both version lists (the central's list never goes on air).

### D-16 Link handshake payload

- **Question:** what do the `XX` payloads carry?
- **Decision:** message 1 empty. Messages 2 and 3: TLVs `RESOLVING_KEY` (`0x02`, 36 B, gated meshes only) and `CERTIFICATE` (`0x81`, critical).
- **Evidence:** ADR 0003 (certificate binds identity to static key); ADR 0010 decision 4 (resolving key inside the authenticated handshake).
- **Alternatives:** certificate in message 3 only from the initiator and message 2 from the responder (same thing in `XX`); send the NodeId and fetch the certificate later.

### D-17 Unsecured link hello

- **Question:** how do `unsecured` peers exchange identity and mesh?
- **Decision:** `PLAIN_HELLO` (`0x03`): version, profile `0x00`, `MeshIdDigest`, NodeId. 67 B, both directions.
- **Evidence:** ADR 0012 decision 12 (claimed NodeId); ADR 0002 "one framework" (same record layer).
- **Alternatives:** run `NN` Noise for framing only (adds code for no guarantee).

### D-18 Frame type codes

- **Question:** which codes do the frames get within the ranges?
- **Decision:** link-local `0x01`–`0x0F`, unicast `0x40`–`0x44`, flood `0x80`–`0x82`, as in [data/frame-types.json](data/frame-types.json).
- **Evidence:** owner-approved ranges; sequential assignment.
- **Alternatives:** grouping by subsystem with gaps.

### D-19 Record and frame size thresholds

- **Question:** what are the minimum record body, fragment data limits and frame-size class thresholds?
- **Decision:** record body 1 to 1,024 B; small frame ≤ 8,192 B; frame ≤ 139,264 B (128 KiB + 8 KiB).
- **Evidence:** ADR 0015 decisions 1, 3 and 5; see [C-05](#c-05). 8 KiB covers a 4 KiB payload plus the `post-quantum` first-message overhead (about 2.5 KB) and headers.
- **Alternatives:** carry a size-class bit in the header (one more field relays must trust).

### D-20 Handshake denial-of-service bounds

- **Question:** what numbers bound handshake cost?
- **Decision:** Noise message ≤ 4,096 B; one handshake per bearer; 8 at once; 10 s timeout; one new handshake per remote address per 2 s.
- **Evidence:** ADR 0002 decision "Handshake denial-of-service bounds" (numbers left to the spec); largest legal message about 2.6 KB; v1 parameters dial-to-usable p50 2.8–3.5 s.
- **Alternatives:** 5 s timeout (too close to measured p95 under churn); no per-address limit.

### D-21 Rekey schedule

- **Question:** how often do links rekey, and how?
- **Decision:** `REKEY` frame, then Noise `Rekey()` per direction, every 3,600 s or 65,536 records. Receivers close a direction exceeding 131,072 records.
- **Evidence:** ADR 0002 decision "Sessions" (time and count schedule, numbers left to the spec). 65,536 records is 64 MiB, far below ChaCha20-Poly1305 limits; hourly limits key lifetime on long links.
- **Alternatives:** implicit rekey at fixed counts without a frame (fragile if counts drift).

### D-22 Duplicate links and close reasons

- **Question:** which link survives when two exist, and what close reasons exist?
- **Decision:** keep the link whose central has the lower NodeId; the losing link stops writing after its `CLOSE` and keeps reading until the peer's `CLOSE` or `duplicate-drain-timeout` (10,000 ms), so frames the peer already wrote on it are delivered. Reasons `0x00`–`0x07` as in [data/codes.json](data/codes.json); unknown means `NORMAL`.
- **Evidence:** ADR 0009 decision 4 (one link per neighbor); ADR 0020 (typed diagnostics).
- **Alternatives:** keep the older link (both sides may disagree on "older"); disconnect the losing link at once (drops frames the peer wrote before it saw the duplicate, while its sender already reported them sent).

### D-23 Busy reply

- **Question:** how does a receiver refuse a stream?
- **Decision:** `BUSY` (`0x06`): stream ID and reason (`0x01` slots, `0x02` large budget). The sender treats the frame as not sent on that hop.
- **Evidence:** ADR 0015 decision 5 ("busy reply").
- **Alternatives:** silent drop (sender wastes airtime until timeout).

### D-24 Neighbor list

- **Question:** what does a neighbor list carry, how large can it be, and how do linked peers learn a rotated identifier?
- **Decision:** own current 8 B identifier, own next 8 B identifier, `u8` count up to 32, 8 B neighbor identifiers. A node sends a neighbor list before each rotation, so linked peers learn its next identifier in advance; only already-linked peers learn it (owner addition, resolving [C-10](#c-10)).
- **Evidence:** ADR 0016 decision 3 and ADR 0008 amendment (8 B advertisement identifiers, direct neighbors only); v1 parameters "Identifier rotation inflation" (linked peers announce their next identifier). 32 exceeds any realistic link count (3 radios × 20 links is a stress case).
- **Alternatives:** `u16` count (unneeded).

### D-25 Catch-up summary

- **Question:** what are the catch-up layout and caps?
- **Decision:** three `u16`-counted lists: offered IDs (≤ 128), held `{ID, destination tag}` (≤ 100, the hold quota), tombstones `{ID, k}` (≤ 128). Newest kept on overflow. At most 7,127 B.
- **Evidence:** ADR 0014 decision 7 and amendment; ADR 0008 amendment (catch-up); stage F within budget.
- **Alternatives:** separate frames per list; Bloom filter summaries (false positives lose messages).

### D-26 Plumtree frames

- **Question:** what do `IHAVE`, `GRAFT` and `PRUNE` carry?
- **Decision:** `IHAVE`: `u8` count 1 to 32 of message IDs. `GRAFT`: one message ID. `PRUNE`: empty.
- **Evidence:** ADR 0008 decision 3 (Plumtree, graft after 500 ms); v1 parameters (graft per announcer).
- **Alternatives:** `GRAFT` with a list (rare need).

### D-27 Routing header and hop limit

- **Question:** what order do header fields take, and how does the hop limit count?
- **Decision:** type, hop limit, message ID, expiry, destination tag, source tag. Relays decrement and forward only if the result is ≥ 1; 0 on receipt is malformed. Direct-only sends use hop limit 1.
- **Evidence:** ADR 0012 decision 5 (header contents); ADR 0008 decision 5 (1 B, default 255); ADR 0013 decision 8 (direct-only sends).
- **Alternatives:** TTL counted up (relays could not bound scope).

### D-28 Route request and reply

- **Question:** what fields do route discovery frames carry, and which nodes may reply?
- **Decision:** request: target tag, origin tag, origin sequence, target sequence (0 = unknown), hop count; reply: target tag, target sequence, hop count. One `u32` sequence per node, RFC 1982 comparison. Intermediate replies allowed for fresh routes. No route error. Request expiry is `message-lifetime-min` ahead.
- **Evidence:** ADR 0008 decision 4 (AODV-style hop count and sequence numbers); v1 parameters (route lifetimes, radius 64).
- **Alternatives:** RERR messages (more traffic; simulation used expiry); destination-only replies (slower).

### D-29 Duplicate suppression

- **Question:** how do relays suppress duplicates?
- **Decision:** floods by message ID until expiry. Unicast: drop a copy if the same `(message_id, attempt)` pair was forwarded within `unicast-seen-window`. Retries reuse the ID and carry the next `attempt`. Changed by D-61 on 2026-11-25: the window was 1,000 ms and keyed by ID alone.
- **Evidence:** routing simulation SeenCache (suppresses by ID until expiry for floods); ADR 0013 amendment (duplicates acknowledged again). A short unicast window let retries pass; D-61 found that it also let late echoes of one send flood again.
- **Alternatives:** suppress unicast by ID until expiry (blocks retries); include a retry counter in the header (adopted by D-61).

### D-30 Container kinds

- **Question:** how are end-to-end containers framed and which frames allow which kinds?
- **Decision:** kinds `0x01` IK initiator, `0x02` IK responder, `0x03` transport (explicit `u64` nonce), `0x04` unsecured. `MESSAGE` and `READ_RECEIPT` allow 1, 3, 4; `DELIVERY_RECEIPT` allows 2, 3, 4.
- **Evidence:** ADR 0002 (IK message 1 carries the first message; replies complete the session); ADR 0013 (receipt always sent, so message 2 rides in it); ADR 0016 amendment to ADR 0013 (a late read receipt starts a session).
- **Alternatives:** message 2 in any frame (the initiator could not match it to its message 1).

### D-31 Plaintext and end-to-end extensions

- **Question:** what does the encrypted plaintext contain?
- **Decision:** `u32` length, content, zero padding to 32 B. Message content: ID, expiry, `k`, TLVs, payload. Receipt content: ID, expiry, TLVs. TLVs: `SENDER_CERTIFICATE` `0x01` (required in IK initiator), `READ_RECEIPT_REQUESTED` `0x04`, `CONVERSATION_SEED` `0x82`, `MESSAGE_IDS` `0x83`.
- **Evidence:** ADR 0012 decisions 7 and 11; ADR 0014 decisions 8 and 10; ADR 0018 decision 7 (cache filled from `IK` senders).
- **Alternatives:** padding length byte instead of content length; no ID and expiry binding (relays could alter them).

### D-32 Conversation seed and tags

- **Question:** how are conversation tags derived and assigned to directions?
- **Decision:** 32 B seed in the first message; `secret = KDF(seed, "", "conversation", NodeId_i ‖ NodeId_r)`; `tag(d) = trunc_8(H("conversation-tag", u8(d) ‖ u32(epoch); key = secret))`; `tag(d)` is the destination of frames in direction `d`. First message source tag is `tag(0x02)`.
- **Evidence:** ADR 0016 decision 2 ("derived from the end-to-end session, one tag per direction").
- **Alternatives:** derive from the Noise handshake hash (not available to the initiator for the first message's source tag).

### D-33 End-to-end session limits

- **Question:** how long do end-to-end sessions live and how are replays rejected?
- **Decision:** 6 h or 4,096 frames per direction; explicit `u64` nonce; 1,024-nonce replay window.
- **Evidence:** ADR 0002 decision "Sessions" (numbers left to the spec); 6 h is half the certificate renewal interval; flooding can reorder frames, so implicit nonces fail.
- **Alternatives:** 1 h (more first-message overhead, 1.1–2.4 KB each); 24 h (longer exposure).

### D-34 Session opening and failure

- **Question:** how are first messages, message 2 and lost session state handled?
- **Decision:** one outstanding first message per peer; responder answers each message 1 once, caches the message 2 container and resends it for duplicates; initiator matches by receipt message ID. Crossed sessions: keep the one whose initiator has the lower NodeId. No receipt by the second retry of a session frame: end the session and resend as a first message, with the same `message_id` (D-51).
- **Evidence:** ADR 0002 (IK, LRU cache may evict sessions); ADR 0013 (delivery receipt is always sent).
- **Alternatives:** every queued message as its own first message (several sessions, more airtime); explicit session-reset frame (unauthenticated or needs keys the peer lost).

### D-35 HPKE parameters

- **Question:** which HPKE mode, KDF, AEAD and info are used for the nested seal?
- **Decision:** base mode `0x00`, KEM `0x647A`, KDF `0x0001` HKDF-SHA256, AEAD `0x0003` ChaCha20Poly1305, empty AAD, `info = DS ‖ initiator x25519 static key ‖ responder NodeId`.
- **Evidence:** ADR 0002 and ADR 0004 (X-Wing codepoint); same AEAD family as Noise. The initiator's NodeId is not yet known when the responder opens the seal, so its Noise static key stands in.
- **Alternatives:** auth mode (X-Wing has no auth mode); SHA-512 KDF.

### D-36 Lookup reply

- **Question:** how is the lookup reply addressed, keyed and laid out?
- **Decision:** destination tag = request salt; source tag random; responder ephemeral key 32 B; `u16` sealed length; key `KDF(X25519, salt ‖ lookup tag, "lookup-reply", eph_req ‖ eph_resp)`; ChaCha20-Poly1305 with zero nonce and empty AAD.
- **Evidence:** ADR 0018 decisions 4 and 5. See [C-02](#c-02).
- **Alternatives:** HPKE base mode with DHKEM(X25519) (same security, more framing); sealing from the responder's static key (identifies the responder to the requester).

### D-37 Lookup retry and first reply

- **Question:** how do lookups retry and how do relays limit replies?
- **Decision:** each retry uses a new salt, ephemeral key and message ID on the unicast retry schedule; request expiry `message-lifetime-min` ahead; relays pass only the first reply per salt.
- **Evidence:** ADR 0018 decision 8 and amendment.
- **Alternatives:** reuse the salt (links retries).

### D-38 Delivery receipt batching

- **Question:** how many IDs and how much delay per delivery receipt?
- **Decision:** up to 32 IDs, at most 500 ms delay. Receipt expiry is the latest expiry acknowledged.
- **Evidence:** ADR 0013 decision 2 (batched); resend timer is 1 s + 200 ms per hop, so 500 ms keeps receipts in time.
- **Alternatives:** no delay (more frames under bursts).

### D-39 Read receipt batching

- **Question:** how many IDs and how much delay per read receipt?
- **Decision:** up to 64 IDs, at most 2,000 ms delay.
- **Evidence:** ADR 0013 decision 4 (batched, no artificial delay beyond batching); best effort.
- **Alternatives:** same as delivery receipts.

### D-40 Broadcast containers and signing

- **Question:** how are mesh broadcasts carried and authenticated?
- **Decision:** secured profiles: kind `0x05` with the origin's Ed25519 key, `u16` payload length, payload and signature over `DS("broadcast") ‖` the frame without hop limit and signature. `unsecured`: kind `0x04`.
- **Evidence:** ADR 0016 decision 1 ("which identity signed a mesh broadcast"); ADR 0015 decision 2 (1 KiB).
- **Alternatives:** carry the certificate (138 to 1,354 B per broadcast).

### D-41 Resolving key update

- **Question:** how and when is the next resolving key sent?
- **Decision:** `RESOLVING_KEY_UPDATE` (`0x0E`): key and `not_after`, sent at least 1 h before use.
- **Evidence:** ADR 0010 decision 4 and amendment (daily key, delivered over links).
- **Alternatives:** send only in handshakes (long links would miss the rotation).

### D-42 Tombstone storage

- **Question:** which tombstones does a node store and for how long?
- **Decision:** store tombstones that verified a held copy or came in a relayed delivery receipt; keep until that receipt's or copy's expiry; at most 1,000.
- **Evidence:** ADR 0014 decision 8 ("follow the receipt's path and are offered on catch-up until the message expires"). Tombstones carry no expiry themselves.
- **Alternatives:** store every tombstone heard (storage abuse); add an expiry to each tombstone (4 B each).

### D-43 Bearer switch

- **Question:** how does a link move from GATT to L2CAP CoC, and what happens if CoC fails?
- **Decision:** `BEARER_SWITCH` (`0x0F`) as the last record on GATT in each direction; cipher states carry over. CoC loss ends the link; a GATT reconnect runs a fresh handshake. See [C-07](#c-07).
- **Evidence:** ADR 0009 decisions 1 and 6.
- **Alternatives:** keep both bearers open and duplicate traffic (airtime); resume the session on GATT (needs resumption, rejected by ADR 0002).

### D-44 Unsecured hello rules

- **Question:** which `PLAIN_HELLO` answers end an `unsecured` link, and what bounds the exchange?
- **Decision:** the peripheral echoes the selected version; a mismatch, the receiver's own NodeId or a second hello closes with `PROTOCOL_ERROR`. `handshake-timeout` (10 s, [D-20](#d-20-handshake-denial-of-service-bounds)) also bounds the hello exchange.
- **Evidence:** ADR 0012 decisions 6 and 12; the secured handshake already fixes `version` across its messages and closes on a step out of order. Found while implementing 0.1.0 ticket 11.
- **Alternatives:** let the peripheral answer with its own highest version (the central's choice would no longer bind both sides); no timeout for `unsecured` (a silent peer would hold a link slot forever).

### D-45 Noise `hfs` protocol names

- **Question:** how do the `hfs` protocol names spell ML-KEM-768 and the gated pattern?
- **Decision:** `MLKEM768`, and `XXhfs+psk3` for the gated pattern: `Noise_XXhfs_25519+MLKEM768_ChaChaPoly_SHA256`, `Noise_XXhfs+psk3_25519+MLKEM768_ChaChaPoly_SHA256` and `Noise_IKhfs_25519+MLKEM768_ChaChaPoly_SHA256`. ADR prose keeps the shorthand `XXhfspsk3` for the pattern. The owner accepted `MLKEM768` on 2026-11-16; the `+` follows from the same rule of following the Noise naming.
- **Evidence:** Noise revision 34 §8.2: "Each algorithm name must consist solely of alphanumeric characters and the forward-slash character". The pinned snow commit `dc18c06` parses `MLKEM768` and rejects `ML-KEM-768` (snow commit `858dc27`, "Rename the KEM name to MLKEM768 for Noise 8.2"). libp2p [specs#727](https://github.com/libp2p/specs/pull/727) uses `Noise_XXhfs_25519+MLKEM768_ChaChaPoly_SHA256`. Noise revision 34 §8.1: "The first modifier added onto a base pattern is simply appended. [...] Additional modifiers are separated with a plus sign", as in `XXfallback+psk0`; modifiers whose order doesn't matter are sorted alphabetically. snow `dc18c06` splits modifiers on `+` and rejects `XXhfspsk3` (`UnsupportedModifier`). The protocol name is hashed into `h`, so the spelling changes every transcript byte. Found while implementing 0.1.0 ticket 20.
- **Alternatives:** keep `ML-KEM-768` or `XXhfspsk3` (each violates the Noise naming rules and needs a patched snow to generate reference vectors; `ML-KEM-768` also diverges from libp2p).

### D-46 Certificate validity edges

- **Question:** does the 30 s clock skew apply to both certificate edges, to every use or only to sealing, and is `not_after + 30 s` itself accepted?
- **Decision:** both edges and every use. A certificate is usable while `not_before − 30 s ≤ now < not_after + 30 s`. The owner decided the skew on both edges on 2026-11-13 and the half-open end on 2026-11-16.
- **Evidence:** `not_after` is an exclusive bound (rule 5 was `now < not_after`), and ADR 0008 tolerates ±30 s of skew. The static private keys are deleted at `not_after + 3,630 s` (ADR 0014 decision 9); a message sealed at the last accepted second, `not_after + 29 s`, expires at most 1 h later, before deletion. The [threat model](../explanation/threat-model.md#certificate-validity-edges) finds no profile guarantee changed. The tier 2 vectors in `certificate-signatures.json` test `not_before − 30`, `− 31`, `not_after + 29` and `+ 30`. Found while implementing 0.1.0 ticket 21.
- **Alternatives:** skew only for sealing (a peer with a slightly fast clock would drop a link to a certificate it may still seal to); an inclusive end at `not_after + 30 s` (a message sealed then expires at the instant its key is deleted, and the bound would no longer read as one half-open interval).

### D-47 GATT credit frame

- **Question:** how does a GATT peripheral pace its notifications so the platform's own requests still get through (ADR 0009 decision 7)?
- **Decision:** a link-local `CREDIT` frame (`0x10`, `u16` count). The central grants `gatt-credit-window` = 8 notifications when the link opens and tops up after receiving 4; one credit is one notification. `CLOSE` is exempt. A grant above the window or a `CREDIT` from the peripheral is `PROTOCOL_ERROR`. CoC is not credited. Owner decision 2026-11-16 (ticket 29); reverses [C-09](#c-09).
- **Evidence:** library rule R14: an Android peripheral's notify burst made it drop an iOS central's GAP name read response, and iOS closed the link 30 s later; `onNotificationSent` means queued, not sent, so the only working signal was an application-level credit from the central. A credit per record would not bound the queue: a 1,024 B record is 52 notifications at ATT MTU 23.
- **Alternatives:** local pacing only (C-09; failed on R14's evidence); a credit per record (unbounded notifications per credit); per-message acknowledgments (more airtime, and they pace messages, not notifications).

### D-48 `REKEY` on an `unsecured` link

- **Question:** what does an `unsecured` receiver do with a `REKEY` frame, which is defined only for encrypted links?
- **Decision:** it drops the frame as a link-local frame of unknown type: it records diagnostic ML3007 `UnknownLinkFrameDropped` and keeps the link open, still delivering later frames. Owner decision 2026-11-13; recorded while implementing 0.1.0 ticket 30.
- **Evidence:** the [Link-local frame summary](link.md#link-local-frame-summary) already requires a receiver to drop an unknown link-local frame without closing the link, and ADR 0020 lists ML3007 for it. An `unsecured` link has no cipher state to rekey, so the frame carries nothing the receiver can act on; closing would let one malformed or future peer frame tear down links that the unknown-type rule keeps open.
- **Alternatives:** close with `PROTOCOL_ERROR` (stricter, but treats a harmless frame more harshly than an unknown one); ignore it without a diagnostic (hides a misbehaving peer from troubleshooting).

### D-49 Bearer switch timing and credits

- **Question:** how do `BEARER_SWITCH` and `CREDIT` interact, how long does a peripheral wait for the central's channel, and what happens when the channel can't open or is lost?
- **Decision:** `BEARER_SWITCH` needs no credit. Once the central has written its `BEARER_SWITCH` it grants no more credits; the peripheral, from receiving it until its own `BEARER_SWITCH`, sends its GATT notifications uncredited, finishing any record in progress first. The peripheral waits `coc-accept-timeout` (5 s) for the central's channel, then closes with `PROTOCOL_ERROR`. `unsecured` links switch too. A channel that fails to open leaves the link on credited GATT and records ML3005 `BearerFallback`, except when the platform has no CoC. A lost channel ends the link; the central links again once, as after a transient loss. Owner decisions 2026-11-18 (ticket 31, grilling Q2, Q3, Q5, Q6, Q9 and Q10).
- **Evidence:** after its `BEARER_SWITCH` the central writes nothing more on GATT, so it can't send the grant a credited peripheral would wait for: crediting the peripheral's last GATT records, or its `BEARER_SWITCH`, could stall the switch forever. CoC setup took about 160–380 ms on top of GATT in the lab ([ADR 0009](../adr/0009-ble-bearers-and-phy-policy.md)); 5 s bounds a central that sent `BEARER_SWITCH` without a channel.
- **Alternatives:** credit `BEARER_SWITCH` (can deadlock with no credits left); keep granting on GATT after the switch (the central no longer writes on GATT); fall back to GATT after a lost channel with the old cipher state (ADR 0009 forbids moving cipher state after a failure, C-07).

### D-50 Reverse routes from duplicate copies

- **Question:** may a relay learn a reverse route from a unicast copy it drops as a duplicate?
- **Decision:** no. A relay drops a copy whose `message_id` (since D-61, whose `(message_id, attempt)` pair) it forwarded within `unicast-seen-window` before it records a reverse route, so only the first copy, normally over the shortest path, teaches the route to `source_tag`. A copy that passes the window still teaches its route even if its hop limit stops it there. Owner decision 2026-11-20 (ticket 42a).
- **Evidence:** in seeded `VirtualAir` meshes of 50 static nodes at low density (mean 6 peers in range), learning from every copy let later, longer flood copies overwrite the first copy's reverse route. Two relays then pointed at each other for the destination, and the seen window dropped the frame between them on every retry: 115 of 123 messages arrived within their 30 min lifetime although sender and destination stayed linked over 6–12 hops. With this rule, 123 of 123 arrived, and every 100- and 200-node cell at low, medium and high density delivered every message. Never using routes at relays also delivered every message, but took 5× the wall time because every frame flooded.
- **Alternatives:** relays never use routes for unicast (floods every frame; loses the point of routes in the medium and high classes); keep the latest copy's route but compare hop counts (the unicast header carries no hop count); a longer seen window (blocks the origin's retries, as D-29 notes).

### D-51 Message ID after a session lapses

- **Question:** when a session frame gets no receipt by its second retry and the sender seals the message again (D-34), does the new frame get a new `message_id`?
- **Decision:** no. The new frame, in another session or as a first message, keeps the message's `message_id`, tombstone secret `k` and commitment. The destination takes the message once; a later frame with the same `message_id` is acknowledged again through the session that carries it. Owner decision 2026-11-24 (ticket 49).
- **Evidence:** in a seeded `VirtualAir` test, a relay held both a Classical first message and a later session frame for a destination that was away over an epoch change. The session frame lapsed, so the sender sealed it again as a first message with a new `message_id`; the relay held both copies, and the returning destination delivered the message twice, against ADR 0013 decision 2. With the same `message_id` it delivers the message once. A relay forwards only the first copy of a `message_id` within `unicast-seen-window` (1 s) and holds only one copy, so the cost is one dropped copy on a relay that sees both within that second. Since D-61 each send carries its own `attempt` and the window is 30 s per `(message_id, attempt)` pair, so the new frame, a later send, passes relays that forwarded the earlier one.
- **Alternatives:** keep a new `message_id` and accept rare duplicates (breaks ADR 0013 decision 2); deduplicate by payload at the destination (equal payloads may be different messages); purge the lapsed copy with a tombstone (only the destination knows `k`, and it never acknowledged that copy).

### D-52 Holder hand-over triggers

- **Question:** when does a relay forward a frame it holds, and what does `CATCH_UP` offer?
- **Decision:** a holder runs no timed retries. It forwards a held unicast on a `GRAFT` claim after `CATCH_UP`, or on a new route to its destination tag (from a `ROUTE_REPLY`, the destination's passing `ROUTE_REQUEST`, or a relayed frame whose `source_tag` is that tag); each held frame goes at most once per link and never back where it came from. `CATCH_UP` offers held broadcasts together with those received in the last `catch-up-window`, newest first up to `catch-up-offered-max`, and a `GRAFT` is answered from either. A receiver claims a held unicast at most once per `catch-up-window` and never one it already took. Owner decisions 2026-11-24 (ticket 49).
- **Evidence:** the origin keeps retrying until expiry (ADR 0014 decision 1), so holder retries would only repeat its airtime while the destination is reachable; a holder matters when the destination comes back and the origin is gone, which a new link or route signals. Seeded `VirtualAir` tests hand held unicasts over in `unsecured` and `classical` through a claim, with the sender gone, and through a passing route request; held broadcasts reach a node that returns after a minute.
- **Alternatives:** holders keep the origin's retry schedule (ADR 0014 decision 7 as first written: airtime from every holder for no gain while a route exists); offer only the last `catch-up-window` of broadcasts (a returning node misses older held broadcasts).

### D-53 When an origin's message is `Held`

- **Question:** when does an origin report `Held` (ADR 0013 decision 5)?
- **Decision:** when a send finds no link, and in the medium and high classes when a flooded send finds no route within `resend-base` + `resend-per-hop` × `u6-flood-radius` (1.6 s). The next send that goes out moves it back to `Sent`. In the low class a flood is the route, so a send that went out stays `Sent`. Owner decision 2026-11-24 (ticket 49).
- **Evidence:** a medium or high class origin waits that long after a flooded send before searching again (Origin behavior), so no route by then means the destination is out of reach for now; a low class origin never searches.
- **Alternatives:** `Held` only with no link (a medium or high class app never learns its message waits); `Held` after the first retry with no receipt (the retry interval reaches 60 s).

### D-54 Tombstones that fail to verify, and held copies of delivered messages

- **Question:** what does a node do with a tombstone whose `k` does not open its held copy's commitment, how does it tell tombstones with the same `message_id` apart, and may a relay hold a message whose tombstone it already stores?
- **Decision:** a tombstone that fails against a held copy deletes nothing and is not stored, even from a relayed `DELIVERY_RECEIPT`; with no held copy, a relayed receipt's tombstone is stored until the receipt's `expiry` (D-42). Stored tombstones are keyed by `{message_id, k}`, so a forged one with the genuine `message_id` cannot displace the genuine one. A relay does not hold a relayed `MESSAGE` that a stored tombstone verifies, but still forwards it. A tombstone does not stop a node claiming a held entry with `GRAFT`. Owner-delegated decisions 2026-11-25 (ticket 51).
- **Evidence:** only the destination learns `k` in the secured profiles, so a failing `k` is a forgery or a different message; storing it would spend `tombstone-store` and be offered on every `CATCH_UP`. Keying by `message_id` alone would let a forged tombstone shadow the genuine one. A claim check against unverified tombstones would let a forger suppress delivery. Seeded `VirtualAir` tests in `unsecured`, `classical` and `post-quantum`: a receipt through one relay deletes its copy, and its `CATCH_UP` deletes another relay's copy, which then offers the tombstone.
- **Alternatives:** store every tombstone from a relayed receipt (forged receipts fill the store); key by `message_id` (a forged tombstone shadows the genuine one); skip claims for tombstoned IDs (unverified tombstones suppress delivery).

### D-55 When a large message waiting for a route is `Held`

- **Question:** a large message is never flooded, so a send without a route sends only a `ROUTE_REQUEST`. When does it stop being `Queued`?
- **Decision:** in every density class, a large message stays `Queued` while its route request searches, and turns `Held` if no route found by route request is active `resend-base` + `resend-per-hop` × `u6-flood-radius` (1.6 s) after the send. A later send that goes out moves it to `Sent`, as in D-53. Owner decision 2026-11-25 (ticket 50).
- **Evidence:** the wait mirrors D-53's flooded send, so both classes of message report "no path now" after the same time. `Sent` would claim a frame left the node when none did.
- **Alternatives:** `Held` at once (most route requests answer within the wait); `Sent` on the route request (no frame of the message was sent).

### D-56 End-to-end Noise messages above 65,535 B

- **Question:** Noise revision 34 §3 limits every Noise message to 65,535 B. A large message carries up to 131,072 B of payload in one `IK` or `IKhfs` message 1 or one transport message. Is that allowed?
- **Decision:** yes, as a documented deviation, end to end only. An end-to-end Noise message is bounded by `frame-max` (139,264 B), not 65,535 B; the containers already carry `u32` lengths (D-30). Link handshake messages keep their 4,096 B bound. ADR 0002 amendment 2026-11-25. Owner decision 2026-11-25 (ticket 50).
- **Evidence:** the limit exists for transports with 16-bit length prefixes; MeshLink frames its own lengths. ChaCha20-Poly1305 (RFC 8439) is safe for messages up to about 256 GiB. MeshLink has no third-party Noise peers whose buffers assume the limit. The largest `post-quantum` first message with a 131,072 B payload is 135,000 B with the D-61 attempt byte (pinned by a test).
- **Alternatives:** split a large message into several Noise messages (a new chunked container with its own ordering and replay rules); cap the unicast payload below 64 KiB (fails ADR 0015's profile-picture requirement).

### D-57 One copy of a large message at a time

- **Question:** a large frame takes seconds per hop. Retries and found routes queue new copies while one is still being written, and every copy costs full airtime on every hop. How are copies limited?
- **Decision:** a link skips a frame of the node's own message that has finished or passed its `expiry` when the frame reaches the head of its queue. An origin makes no new copy of a message while one of its copies is being written; a copy still waiting in a link's queue is replaced in place. Relays still forward a retry's copy. Owner decision 2026-11-25 (ticket 50).
- **Evidence:** seeded `VirtualAir` 5-node line, 131,072 B: before the change each link carried several copies of the message; after, two (the delivered copy and one retry sent while relays still carried the first).
- **Alternatives:** scale the retry and resend timers by measured airtime (left for a follow-up; the timers stay fixed); cancel a copy mid-write (the receiver discards the partial frame anyway, but needs a new frame type to stop early).

### D-58 Fresh routes for large messages

- **Question:** relays forget a found route after `forward-route-timeout`, but the origin's copy stays active while used. A large frame on such a route is dropped at the first relay that lost it, and no frame reports that. Which routes may a large message use?
- **Decision:** a large message uses a route found by route request only if the route was found within the last `route-active-timeout`, however long use has kept it active since. Every timed retry of a large message searches anew: it sends a `ROUTE_REQUEST` and goes on the route that answers. Owner decision 2026-11-25 (ticket 50).
- **Evidence:** seeded `VirtualAir` diamond: after a 4,097 B message finds a route, the destination moves so that route breaks one hop ahead of the origin; a 131,072 B message sent on the old route is dropped by the relay, which neither forwards nor holds it, and the origin's next retry delivers it on the other path. With the retry search disabled, the test fails. The 5-node line delivers in all three profiles in the low and medium classes.
- **Alternatives:** a route-error frame telling the origin which route broke (a new frame type; left for a follow-up); refresh relays' routes on every use (the route-reply sequence would have to travel with every frame).

### D-59 Large-frame reassembly budget

- **Question:** ADR 0015 set 1 MiB of large-frame reassembly per node. Can that budget ever be reached?
- **Decision:** no; it is lowered to `large-reassembly-budget` 524,288 B (512 KiB). A node keeps at most 6 links (link budget d, ADR 0008 decision 1), each reassembles at most one large frame, and a link writes one frame at a time, so at most 6 × `frame-max` = 835,584 B can be reassembling. At 512 KiB the fourth concurrent large frame near `frame-max` gets `BUSY` with `LARGE_BUDGET`. ADR 0015 amendment 2026-11-25. Owner decision 2026-11-25 (ticket 50).
- **Evidence:** seeded `VirtualAir` hub with airtime limited to 50,000 B/s: 5 senders each send a 131,072 B message to the hub at once; it answers `BUSY` with `LARGE_BUDGET` only, and all 5 messages arrive and are delivered. At 1 MiB the hub never answers `BUSY`.
- **Alternatives:** keep 1 MiB (the busy reply for the budget could never be sent, so the rule would be untested); lower to 256 KiB (only one near-full large frame at a time, which serializes a hub).

### D-60 Certificate refresh in sessions

- **Question:** ADR 0014 decision 10 has a node attach its current certificate to `IK` replies and receipts when the peer's copy is past half its validity. The receiver checks that a `SENDER_CERTIFICATE`'s `x25519_key` equals the Noise remote static key, but a rotated certificate carries new static keys, so a refresh would never pass that check. Which check applies to a refresh, and when is it attached?
- **Decision:** the key check applies only to `IK_INITIATOR` plaintexts. A refresh, in an `IK` reply or a session's delivery receipt, is taken if it validates (identity.md "Validation") for the NodeId of the session's authenticated peer; the cache keeps it if its `not_after` is later than the one held. A node attaches its current certificate to every `IK` reply and delivery receipt it seals while the peer's copy (the certificate the peer sealed the first message to, or the one this node sent in it) is past half its validity and the current one is newer. Read receipts carry no refresh; a read receipt sent as a first message already carries the current certificate. Owner decision 2026-11-25 (ticket 52).
- **Evidence:** `StaticKeyRotationTest`: an `IK` reply carries the current certificate only once the sender's copy is past half its validity, and session receipts carry it both ways; the sender's cache then holds the newer certificate. Removing the refresh from receipt handling fails both session tests; caching an unvalidated refresh fails `LookupsTest`.
- **Alternatives:** attach the refresh once per session (a lost receipt would lose it until the next session); check the refresh's `x25519_key` against the session (impossible after rotation); refresh in read receipts too (they need not be confirmed, so the refresh would have no authenticated session behind it when sent as a first message).

### D-61 Unicast retry attempt

- **Question:** the busiest node of every low-density cell wrote more than the 4,700 B/s band (ticket 42b). A frame flooded or routed with `hop_limit` 255 kept echoing: a relay's copy that arrived more than 1,000 ms after the first, delayed in a queue, passed `unicast-seen-window` and was flooded again. How should relays suppress unicast duplicates without blocking the origin's retries?
- **Decision:** the unicast header carries a `u8` `attempt` after `hop_limit`, so it grows from 34 B to 35 B. The origin sets 0 on the first send of a `message_id` and adds 1 (modulo 256) on each later send: timed retry, retry on a new link, route resend or flush. Relays copy it unchanged; like `hop_limit` it is not authenticated. A relay drops a copy whose `(message_id, attempt)` pair it forwarded within `unicast-seen-window`, now 30,000 ms. The destination still deduplicates by `message_id`. `route-flush-gap` stays 1,200 ms, now justified as letting the flooded copy settle rather than as the seen window plus one hop. A low-class node's delivery receipt goes on an active route, else floods to every link with `hop_limit` 255; it never sends a `ROUTE_REQUEST`. Owner decision 2026-11-25 (ticket 42b).
- **Evidence:** seeded `VirtualAir` n50-low, seed 1, 1 s window keyed by ID: relays forwarded 17,002 first copies and 312,149 re-forwards of IDs whose window had lapsed. Prototypes, seed 1, busiest node in B/s (band 4,700), every message delivered unless noted:

  | Variant | n50-low | n200-low |
  |---|---|---|
  | 1 s window by ID (before) | 5,088 | — |
  | A: 5 s window by ID | not recorded | 11,851 (406/407) |
  | E: `attempt`, 30 s window by pair | 1,833 | 5,719 |
  | E + low-class receipts on route or wide flood (adopted) | 1,554 | 5,362 |
  | E + receipts clamped to the flood radius at relays | — | 4,751 (406/407) |
  | Implemented, seeds 1–5, worst seed | 1,605 | 5,187 |

  A also delayed every medium and high message on a found route by 5.2 s, because `route-flush-gap` followed the window, and its relays dropped the origin's routed retry. With E, each retry passes because it carries a new `attempt`. The rest of n200-low's load is low-class message retries, each flooding the whole mesh; ticket 73 tracks it.
- **Consequences:** a holder's flush of a frame within 30 s of the origin's send of the same attempt is dropped by relays that forwarded it. 30 s × 4,096 remembered pairs bounds the window at about 136 sends per second per relay before eviction shortens it.
- **Alternatives:** A, a longer window keyed by ID (blocks retries and delays found-route flushes); B, relays clamp flooded unicast to the flood radius (breaks the low class's network-wide flood); F, a 5 s window for flooded copies only (failed n200-low at 12,818 B/s, 406/407); clamp receipts at relays (cut receipt writes but lost one message and raised message retries to 682,000 relay writes).

### D-62 Low-class retry schedule

- **Question:** after D-61, n200-low was the only cell above the 4,700 B/s busiest-node band while delivering its messages (ticket 73). Its load is low-class traffic that floods the whole mesh: message retries, timed and on new links, and delivery receipts. Which retry rule brings it under the band without losing delivery?
- **Decision:** a low-class origin retries with doubling intervals from `retry-min-low` 5 s to `retry-max-low` 60 s, the high-class schedule. The medium class keeps 2 s to 30 s, now named `retry-min-medium` and `retry-max-medium`. Route lookups follow the same per-class schedule (D-37). New-link retries and `link-retry-gap` are unchanged. Owner decision 2026-11-26 (ticket 73).
- **Evidence:** seeded `VirtualAir` n200-low, seeds 1–5, worst seed's busiest node in B/s (band 4,700); relay writes summed over the five seeds. The busiest node varies by about 600 B/s between seeds of one rule.

  | Rule | Busiest B/s | Delivered | Message retry relays | Receipt relays | All relays |
  |---|---|---|---|---|---|
  | D-61 (before) | 4,755 | 2,032/2,034 | 1.90 M | 2.85 M | 6.17 M |
  | Slow: 5 s to 60 s (adopted) | 4,596 | 2,032/2,034 | 1.73 M | 2.66 M | 5.83 M |
  | Route: resend on an active route, else flood | 4,971 | 2,033/2,034 | 1.94 M | 2.95 M | 6.32 M |
  | No new-link retries | 4,896 | 2,060/2,062 | 1.24 M | 3.35 M | 6.04 M |
  | At most 3 timed retries | 4,735 | 2,008/2,008 | 1.89 M | 2.58 M | 5.86 M |
  | Route and slow | 5,005 | 2,010/2,012 | 1.80 M | 2.86 M | 6.05 M |

  Delivery receipts are about 46 % of relay writes; ticket 77 tracks their cost.
- **Consequences:** a low-class message whose first copy is lost waits 5 s, not 2 s, for its next send, unless a new link retries it sooner.
- **Alternatives:** the route, no-new-link, three-retry and route-and-slow rules above; each left the worst seed above the band.

### D-63 Relay rate limits

- **Question:** the threat model promised per-neighbor rate limits that the relay path lacked (ticket 74). One neighbor can make a relay flood its broadcasts, route and lookup requests, and unicast frames it has no route for, as fast as the link carries them. What may each neighbor make a relay flood?
- **Decision:** a relay keeps a token bucket per link for each kind of flood frame and forwards a frame only with a token from every bucket that applies. Values, as gap per token and burst: `BROADCAST` 250 ms, 20; in a secured profile also 1,000 ms, 5 per verified origin, at most 32 origins per link, least recently used dropped first; unicast flooded without a route 200 ms, 20 in the low class and 100 ms, 40 in the medium and high classes; `ROUTE_REQUEST` and `LOOKUP_REQUEST` 200 ms, 20 each. A frame over budget is still processed and its `message_id` remembered, but it is not forwarded, held or announced. Unicast on an active route and unknown flood types are not limited. Each drop reports the Debug diagnostic `ML5005` `RelayRateLimited`. The values are fixed: there is no public setting. Owner decision 2026-11-27 (tickets 74 and 78).
- **Evidence:** seeded `VirtualAir`, 30 nodes at 10 kB/s, `EmergencyBroadcast`, one member sending at 20/s, worst of seeds 1–3 (prototype branch `prototype/neighbor-rate-limits`, commit `628fd7a`):

  | Cell | Attack | Limits | Unicast p95 | Honest broadcast coverage |
  |---|---|---|---|---|
  | low | broadcasts | none | 30.5 s | 1.000 |
  | low | broadcasts | per link 2/s, burst 10 | 2.1 s | 0.850 |
  | low | broadcasts | per origin 1/s, 5 within per link 4/s, 20 (adopted) | 15.1 s | 1.000 |
  | low | broadcasts, new key each | per origin only | 30.5 s | 1.000 |
  | low | broadcasts, new key each | adopted | 16.2 s | 0.955 |
  | low | flooded unicast | none | 134.7 s, 0.598 delivered | 0.695 |
  | low | flooded unicast | 10/s, 40 | 36.9 s | 1.000 |
  | low | flooded unicast | 5/s, 20 (adopted) | 15.1 s | 1.000 |
  | medium | broadcasts | adopted | 1.4 s | 1.000 |
  | medium | flooded unicast | none | 74.6 s, 0.978 delivered | 0.991 |
  | medium | flooded unicast | 10/s, 40 (adopted) | 1.4 s | 1.000 |

  Request limits and the 32-origin bound were not measured; the high class reuses the medium values, checked with one `density.yml` run of 8 cells and 5 seeds.
- **Consequences:** an honest node that sends more than 20 broadcasts at once, or more than 4 a second for long, loses the rest beyond its neighbors. In `unsecured` one flooding member shares a link's broadcast budget with every honest origin behind it. An attacker with many signed identities is still capped by the link total.
- **Alternatives:** per link 2/s, 10 (cut honest coverage); per origin only (no defense against new keys); per origin in `unsecured` (an origin field anyone can forge drains an honest origin's budget); dropping by priority instead of limiting (ticket 53: no measured benefit).

## Conflicts resolved

Each entry names the ADR text and the problem found while writing the specification, and how the owner resolved it on 2026-11-08.

### C-01

**Fragment header size.** ADR 0012 decision 4 describes a 1 B stream ID and a 2 B index (3 B). Every frame also starts with its 1 B type, so a fragment costs 4 B. The specification uses 4 B and keeps the ADR's fields ([link.md](link.md#fragments)).

**Resolved:** ADR 0012 amended: the fragment header is 4 B including the type byte.

### C-02

**Lookup reply size.** ADR 0018 decision 5 says sealing adds "a 16 B tag". The responder also needs a fresh 32 B X25519 key on the wire for the requester to compute the shared secret, and the variable certificate needs a 2 B length. The reply is 222 B (classical) or 1,438 B (post-quantum) including the 34 B header; 223 B and 1,439 B since D-61 added `attempt` to the unicast header ([routing.md](routing.md#lookup_reply)).

**Resolved:** ADR 0018 amended: the reply carries the responder's ephemeral key, a length and the sealed certificate.

### C-03

**Mesh tag "the same for all members in an epoch".** ADR 0010 decision 1 says so, but ADR 0010 decision 3 and the ADR 0008 amendment make each node rotate at its own phase. At any instant members show tags of two adjacent epochs. The specification keeps own-phase rotation with a global epoch number, and scanners accept e − 1 to e + 1 ([D-02](#d-02-epoch-number-and-acceptance)).

**Resolved:** ADR 0010 amended: the mesh tag is the same for all members within an epoch number.

### C-04

**Rotation period.** v1-parameters.md (row "Tag and advertisement identifier rotation period"), the ADR 0014 context and ADR 0014 decision 7 ("at most 4 epochs at 1 h") still say 15 min. The ADR 0008 amendment of 2026-11-02 and ADR 0010 say 5 min. Amendments win: the specification uses 300 s, so a 1 h message spans up to 13 epochs.

**Resolved:** 5 min everywhere; ADR 0014 and v1-parameters.md amended.

### C-05

**Size classes and relays.** ADR 0015 defines small and large messages by app payload (4 KiB, 128 KiB). Relays cannot see the payload size inside an encrypted container, yet must refuse to flood or hold large messages. The specification adds frame-size thresholds relays can check: 8,192 B and 139,264 B ([D-19](#d-19-record-and-frame-size-thresholds)).

**Resolved:** ADR 0015 amended: relays apply the frame-size thresholds of [D-19](#d-19-record-and-frame-size-thresholds).

### C-06

**Post-quantum first-message overhead.** ADR 0002 says +1,120 B per first or held message. 1,120 B is the X-Wing encapsulation alone; the HPKE AEAD adds a 16 B tag, so the seal adds 1,136 B. `IKhfs` itself adds its ML-KEM fields on top of `IK`.

**Resolved:** ADR 0002 and ADR 0015 amended: the seal adds 1,136 B.

### C-07

**Bearer fallback "without losing the session".** ADR 0009 decision 6 says a failed CoC channel "falls back to GATT without losing the session". Noise transport state cannot be moved safely after bytes may have been lost mid-record, and ADR 0002 rejects resumption. The specification reads "session" as routing and end-to-end state: the link handshake runs again on GATT, routing state is kept by NodeId, end-to-end sessions are unaffected, and in-flight frames are recovered by retries ([D-43](#d-43-bearer-switch)).

**Resolved:** ADR 0009 amended: CoC loss ends the link; GATT reconnects with a fresh handshake.

### C-08

**"Major" version.** ADR 0012 decision 6 rejects "a different major version", but the version byte has no major and minor parts. The specification treats each version byte value as distinct: peers select the highest common value; compatible additions use new types or non-critical TLVs without changing the byte (ADR 0019 decision 3).

**Resolved:** ADR 0012 amended: each version byte value is a distinct version.

### C-09

**GATT flow-control credits.** ADR 0009 decision 7 says the peripheral paces notification bursts "with credits or acknowledgments". If credits were exchanged, both sides would have to agree on a frame. The specification treats pacing as local: the peripheral paces its own notifications and no credit frame exists. If a wire mechanism is wanted, it needs a link-local frame type.

**Resolved:** ADR 0009 amended: pacing is local; there is no credit frame.

**Reversed 2026-11-16 by [D-47](#d-47-gatt-credit-frame):** the bake-off evidence (library rule R14) showed local pacing does not keep platform requests flowing, so the central grants `CREDIT`s.

### C-10

**Next identifier announced over the link.** v1-parameters.md (row "Identifier rotation inflation") assumes linked peers announce their next advertisement identifier over the link. No ADR decided it, and the first version of this specification told peers the new identifier only after the rotation. `NEIGHBOR_LIST` now carries the next identifier and is sent before each rotation ([link.md](link.md#neighbor-list)).

**Resolved:** `NEIGHBOR_LIST` carries the sender's next identifier ([D-24](#d-24-neighbor-list)).
