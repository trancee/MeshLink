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

### D-13 GATT characteristic UUIDs and properties

- **Question:** which UUIDs and properties do the three characteristics use?
- **Decision:** `4D455348-4D4C-4E4B-8001-00000000000{1,2,3}`: capability (read), central to peripheral (write without response), peripheral to central (notify).
- **Evidence:** ADR 0009 decision 1 and ADR 0010 decision 5 (capability read before handshake). "MESH" "ML" "NK" in ASCII keeps them recognizable.
- **Alternatives:** one characteristic with write and notify (mixes directions); indications (slower, acknowledged at ATT).

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
- **Decision:** keep the link whose central has the lower NodeId. Reasons `0x00`–`0x07` as in [data/codes.json](data/codes.json); unknown means `NORMAL`.
- **Evidence:** ADR 0009 decision 4 (one link per neighbor); ADR 0020 (typed diagnostics).
- **Alternatives:** keep the older link (both sides may disagree on "older").

### D-23 Busy reply

- **Question:** how does a receiver refuse a stream?
- **Decision:** `BUSY` (`0x06`): stream ID and reason (`0x01` slots, `0x02` large budget). The sender treats the frame as not sent on that hop.
- **Evidence:** ADR 0015 decision 5 ("busy reply").
- **Alternatives:** silent drop (sender wastes airtime until timeout).

### D-24 Neighbor list

- **Question:** what does a neighbor list carry, how large can it be, and how do linked peers learn a rotated identifier?
- **Decision:** own current 8 B identifier, own next 8 B identifier, `u8` count up to 32, 8 B neighbor identifiers. A node sends a neighbor list before each rotation, so linked peers learn its next identifier in advance; only already-linked peers learn it (owner addition, resolving [C-10](#c-10)).
- **Evidence:** ADR 0016 decision 4 and ADR 0008 amendment (8 B advertisement identifiers, direct neighbors only); v1 parameters "Identifier rotation inflation" (linked peers announce their next identifier). 32 exceeds any realistic link count (3 radios × 20 links is a stress case).
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
- **Decision:** floods by message ID until expiry. Unicast: drop a copy if the same ID was forwarded in the last 1,000 ms. Retries reuse the ID.
- **Evidence:** routing simulation SeenCache (suppresses by ID until expiry for floods); ADR 0013 amendment (duplicates acknowledged again). A short unicast window lets retries pass.
- **Alternatives:** suppress unicast by ID until expiry (blocks retries); include a retry counter in the header.

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
- **Decision:** one outstanding first message per peer; responder answers each message 1 once, caches the message 2 container and resends it for duplicates; initiator matches by receipt message ID. Crossed sessions: keep the one whose initiator has the lower NodeId. No receipt by the second retry of a session frame: end the session and resend as a first message.
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

## Conflicts resolved

Each entry names the ADR text and the problem found while writing the specification, and how the owner resolved it on 2026-11-08.

### C-01

**Fragment header size.** ADR 0012 decision 4 describes a 1 B stream ID and a 2 B index (3 B). Every frame also starts with its 1 B type, so a fragment costs 4 B. The specification uses 4 B and keeps the ADR's fields ([link.md](link.md#fragments)).

**Resolved:** ADR 0012 amended: the fragment header is 4 B including the type byte.

### C-02

**Lookup reply size.** ADR 0018 decision 5 says sealing adds "a 16 B tag". The responder also needs a fresh 32 B X25519 key on the wire for the requester to compute the shared secret, and the variable certificate needs a 2 B length. The reply is 222 B (classical) or 1,438 B (post-quantum) including the 34 B header ([routing.md](routing.md#lookup_reply)).

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

### C-10

**Next identifier announced over the link.** v1-parameters.md (row "Identifier rotation inflation") assumes linked peers announce their next advertisement identifier over the link. No ADR decided it, and the first version of this specification told peers the new identifier only after the rotation. `NEIGHBOR_LIST` now carries the next identifier and is sent before each rotation ([link.md](link.md#neighbor-list)).

**Resolved:** `NEIGHBOR_LIST` carries the sender's next identifier ([D-24](#d-24-neighbor-list)).
