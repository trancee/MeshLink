# Link layer

Status: Draft. Part of [MeshLink protocol 1](README.md).

A link joins two directly connected nodes. It carries one ordered byte stream in each direction, cut into records. Records carry frames, or fragments of frames.

## Roles and bearers

(ADR 0009 decisions 1, 2, 5 and 6; ADR 0012 decision 3; [D-15].)

- The GATT central is the link initiator: it reads the capability record, selects the version and sends the first handshake message. The peripheral is the responder.
- Every link starts on GATT ([discovery.md](discovery.md#gatt-service)). The central writes its stream to the central-to-peripheral characteristic with write without response; the peripheral sends its stream as notifications. Each write or notification carries the next bytes of the stream, up to the ATT payload size. Record boundaries need not align with writes.
- When the capability record has `L2CAP_PSM` and the central supports CoC, the central MAY open an unbonded L2CAP CoC channel to that PSM after the handshake completes. The stream then moves to CoC with `BEARER_SWITCH` ([below](#bearer-switch)).

## Link records

(ADR 0012 decision 3; ADR 0015 decision 5; [D-19].)

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 2 | length | `u16` | 1 to 1,024 |
| 2 | var | body | `bytes` | Before handshake completion and in `unsecured`: one frame in clear. After: one Noise transport message |

- In the secured profiles, a body after the handshake is a Noise transport message: the ChaCha20-Poly1305 encryption of one frame, with the 16 B tag. The record plaintext is therefore at most 1,008 B. Nonces are Noise's implicit counters; the stream is ordered.
- Each record carries exactly one frame or one `FRAGMENT` frame.
- A record whose length is 0 or above 1,024, or whose body fails to decrypt, is a fatal link error: the node closes the bearer. It MAY first send `CLOSE` with `PROTOCOL_ERROR`.

<!-- data: limits.json -->

| Name | Value | Unit | Decision |
|---|---|---|---|
| record-body-min | 1 | B | D-19 |
| record-body-max | 1,024 | B | - |
| aead-tag-length | 16 | B | - |
| frame-small-max | 8,192 | B | D-19 |
| frame-max | 139,264 | B | D-19 |

- A frame is at most `frame-max` bytes: 128 KiB of payload plus room for headers, the `post-quantum` first-message overhead and padding (ADR 0015 decision 3).
- A frame of at most `frame-small-max` bytes is a small frame. Larger frames are large frames: they carry large messages and are never flooded or held by relays ([routing.md](routing.md#size-classes)).

Vectors: `records.json`.

## Version selection

(ADR 0012 decision 6 and amendment; ADR 0019 decision 3.)

The central reads the capability record, picks the highest version both sides list, and puts it in the `version` field of its first `HANDSHAKE` or `PLAIN_HELLO` frame. The peripheral MUST check that it speaks that version. The central's list is not sent. The prologue binds the peripheral's list and the selected version, so a tampered capability read fails the handshake (see [Prologue](#prologue)). If no common version exists, the central closes the bearer. If the peripheral does not speak the chosen version, it sends `CLOSE` with `UNSUPPORTED_VERSION`.

## Link handshake (secured profiles)

(ADR 0002 decision and amendment; [D-15], [D-16].)

### Patterns

<!-- data: constants.json -->

| Name | Value | Decision |
|---|---|---|
| noise-xx | Noise_XX_25519_ChaChaPoly_SHA256 | - |
| noise-xxpsk3 | Noise_XXpsk3_25519_ChaChaPoly_SHA256 | - |
| noise-xxhfs | Noise_XXhfs_25519+MLKEM768_ChaChaPoly_SHA256 | D-45 |
| noise-xxhfspsk3 | Noise_XXhfs+psk3_25519+MLKEM768_ChaChaPoly_SHA256 | D-45 |

| Profile | Open mesh | Gated mesh |
|---|---|---|
| classical | `XX` | `XXpsk3` |
| post-quantum | `XXhfs` | `XXhfs+psk3` |

- The `hfs` patterns are byte-compatible at the Noise layer with the libp2p proposal and the Noise HFS specification (ADR 0002 amendment): same protocol name, same `e1`/`ekem1` token placement, same mixing order. If they diverge, the Noise HFS specification wins. The KEM name is `MLKEM768`, because Noise §8.2 allows only alphanumerics and `/` in an algorithm name. A second pattern modifier follows a `+`, so the gated pattern is `XXhfs+psk3` (Noise §8.1); ADRs call it `XXhfspsk3` ([D-45]).
- A fresh handshake runs on every new link connection. There is no resumption.

### Pre-shared key

(ADR 0002 decision; [D-06].)

```text
psk = KDF(mesh_key, MeshIdDigest, "mesh-psk", "", 32)
```

Vectors: `derivations.json`, derivation `mesh-psk`.

### Prologue

(ADR 0012 decision 6; [D-15].)

Both sides call Noise `Initialize` with this prologue:

```text
prologue = DS("link-prologue") ‖ u8(n) ‖ versions[n] ‖ u8(selected_version) ‖ u8(profile) ‖ MeshIdDigest
```

`versions` is the list from the peripheral's capability record, in record order. A tampered capability read, a different mesh identifier or a different profile therefore fails the handshake.

Vectors: `derivations.json`, derivation `link-prologue`.

### HANDSHAKE frame

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 1 | type | `u8` | `0x02` |
| 1 | 1 | version | `u8` | Selected protocol version, ≥ 1 |
| 2 | 1 | step | `u8` | 1, 2 or 3 |
| 3 | var | noise_message | `rest` | 1 to 4,096 B |

<!-- data: codes.json registry=handshake-step -->

| Code | Name | Decision |
|---|---|---|
| 0x01 | initiator message 1 | D-15 |
| 0x02 | responder message 2 | D-15 |
| 0x03 | initiator message 3 | D-15 |

- `HANDSHAKE` frames travel in cleartext records. A handshake message larger than one record is fragmented with `FRAGMENT` frames, also in cleartext.
- `version` MUST be the same in all three messages. `step` MUST be the next expected step.
- Before its handshake completes, a node MUST accept only `HANDSHAKE`, `FRAGMENT` (carrying `HANDSHAKE`) and `CLOSE` frames. Anything else fails the handshake.
- The initiator encrypts every record after message 3. The responder encrypts every record after it has processed message 3.

### Handshake payload

(ADR 0010 decision 4; ADR 0012 decision 7; [D-16].)

Message 1 has an empty payload. Messages 2 and 3 each carry a TLV sequence, encrypted by Noise:

<!-- data: tlv-types.json context=handshake -->

| Code | Name | Critical | Length |
|---|---|---|---|
| 0x02 | RESOLVING_KEY | no | 36 |
| 0x81 | CERTIFICATE | yes | variable |

- `CERTIFICATE` (required): the sender's current certificate ([identity.md](identity.md#identity-certificate)).
- `RESOLVING_KEY` (gated meshes only): the sender's current resolving key (32 B) followed by its `not_after` (`u32`). A node in an open mesh MUST NOT send it and MUST ignore it.

After message 3, each side MUST check the peer's certificate ([identity.md](identity.md#validation)) and that the Noise remote static key equals the certificate's `x25519_key`. The peer is then known by the NodeId of the certificate's identity key. Failure closes the link with `HANDSHAKE_FAILED`.

Vectors: `handshake-payload.json` (framing only; certificate bytes are placeholders). Handshake vectors are tier 2.

### Handshake limits

(ADR 0002 decision "Handshake denial-of-service bounds"; [D-20].)

<!-- data: limits.json -->

| Name | Value | Unit | Decision |
|---|---|---|---|
| handshake-message-max | 4,096 | B | D-20 |
| handshake-half-open-per-link | 1 | handshakes | D-20 |
| handshake-half-open-global | 8 | handshakes | D-20 |
| handshake-timeout | 10 | s | D-20 |
| handshake-neighbor-interval | 2 | s | D-20 |

- A node reassembles at most `handshake-message-max` bytes of Noise message per `HANDSHAKE` frame; a longer one fails the handshake. The largest legal message is `XXhfs` message 2 with a post-quantum certificate, about 2.6 KB.
- A handshake not complete `handshake-timeout` after its first message closes the bearer.
- A node runs at most one handshake per bearer connection and at most `handshake-half-open-global` handshakes at once. It accepts a new handshake from the same remote device address at most once per `handshake-neighbor-interval`. Excess connections are closed without a reply, with a typed diagnostic.

## Unsecured link

(ADR 0002 decision "One framework"; ADR 0012 decision 12; [D-17].)

`unsecured` uses no Noise. The central sends `PLAIN_HELLO`; the peripheral answers with its own. All records stay in clear.

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 1 | type | `u8` | `0x03` |
| 1 | 1 | version | `u8` | Selected protocol version |
| 2 | 1 | profile | `u8` | `0x00` |
| 3 | 32 | mesh_id_digest | `bytes[32]` | MeshIdDigest of the sender's mesh |
| 35 | 32 | node_id | `bytes[32]` | Claimed NodeId of the sender |

Total: 67 B. A node that receives a different `mesh_id_digest` sends `CLOSE` with `WRONG_MESH`.

([D-44].)

- The peripheral answers with the version the central selected. A central that receives another version sends `CLOSE` with `PROTOCOL_ERROR`.
- A node that receives its own NodeId, or a second `PLAIN_HELLO` on one link, sends `CLOSE` with `PROTOCOL_ERROR`.
- `handshake-timeout` also bounds the hello exchange: a link with no accepted `PLAIN_HELLO` `handshake-timeout` after the bearer opened is closed.

## Keep one link per neighbor

(ADR 0009 decision 5; [D-22].)

Two nodes may connect to each other in both directions at once. When a node has two completed links to the same NodeId, it keeps the link whose central has the lower NodeId (bytewise comparison) and sends `CLOSE` with `DUPLICATE_LINK` on the other. Both sides reach the same result without coordination. Frames already received on the closed link are processed normally; frames still queued on it are moved to the kept link.

The losing link drains: after its `CLOSE` it writes nothing more, but keeps reading and processing the peer's frames until the peer's `CLOSE` arrives, the bearer drops, or `duplicate-drain-timeout` passes; then it disconnects. A peer may have written frames on that link before it saw the duplicate, and the drain delivers them instead of dropping them.

<!-- data: limits.json -->

| Name | Value | Unit | Decision |
|---|---|---|---|
| duplicate-drain-timeout | 10,000 | ms | D-22 |

## Connect retries and link caps

(ADR 0009 decisions 4 and 6.)

- A central whose connect fails with a transient cause (Android GATT status 133 or 147) retries it up to `connect-retries` times, waiting `connect-retry-backoff` × n before retry n. A link that closes with no error about 30 s after it opened (an unanswered ATT request; library rule R8) is a transient loss: its central reconnects through the same retries. Other failures are not retried.
- A node whose platform caps its peripheral-role links (every iPhone: 3) holds at most that many bearers as peripheral, counting those still opening. It answers a bearer beyond the cap with `CLOSE` `RESOURCE_LIMIT` before any other frame, keeps advertising, and links to further peers as central.
- A central whose bearer is closed with `RESOURCE_LIMIT` before the link opens does not connect to that address again while it stays a link candidate; the capped peer connects to it instead.

<!-- data: limits.json -->

| Name | Value | Unit | Decision |
|---|---|---|---|
| connect-retries | 3 | - | - |
| connect-retry-backoff | 500 | ms | - |

## Fragments

(ADR 0012 decision 4; ADR 0015 decisions 3 and 5; [D-19].)

A frame larger than one record's plaintext is split into `FRAGMENT` frames, each sent in its own record.

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 1 | type | `u8` | `0x01` |
| 1 | 1 | stream_id | `u8` | Chosen by the sender; unique among its open streams on this link |
| 2 | 2 | index | `u16` | Bit 15: last fragment. Bits 14–0: fragment number from 0 |
| 4 | var | data | `rest` | 1 to 1,020 B in clear records; 1 to 1,004 B in encrypted records |

Header: 4 B, the 3 B header of ADR 0012 plus the type byte ([conflict C-01](decisions.md#c-01)).

- Fragments of one stream are sent in order, numbered from 0, with no gaps. Fragments of different streams MAY interleave (ADR 0015 decision 5). The stream is the concatenation of the fragment data; its first byte is the frame type.
- A sender MUST fragment only frames that do not fit in one record, and MUST NOT nest `FRAGMENT` frames.
- A receiver MUST treat these as fatal link errors: a fragment number that is not the next one for its stream, a fragment after the last one, a fragment number above 32,767, or a reassembled frame above `frame-max` bytes.
- A reassembly stream whose next fragment does not arrive within `stream-idle-timeout` is discarded. A `stream_id` is free again after its last fragment or after its stream is discarded.

<!-- data: limits.json -->

| Name | Value | Unit | Decision |
|---|---|---|---|
| fragment-index-max | 32,767 | - | - |
| reassemblies-per-link | 4 | streams | - |
| large-reassemblies-per-link | 1 | streams | - |
| large-reassembly-budget | 1,048,576 | B | - |
| stream-idle-timeout | 10 | s | - |

- A receiver reassembles at most `reassemblies-per-link` streams per link, of which at most `large-reassemblies-per-link` may become large frames. All large reassemblies on a node share `large-reassembly-budget` bytes.
- A stream counts as large once its reassembled bytes pass `frame-small-max`.

Vectors: `link-frames.json` (`FRAGMENT` cases).

## Busy reply

(ADR 0015 decision 5; [D-23].)

When a receiver cannot accept a new stream, it discards the stream's fragments and answers once with `BUSY`:

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 1 | type | `u8` | `0x06` |
| 1 | 1 | stream_id | `u8` | The refused stream |
| 2 | 1 | reason | `u8` | See below |

Total: 3 B.

<!-- data: codes.json registry=busy-reason -->

| Code | Name | Decision |
|---|---|---|
| 0x01 | REASSEMBLY_SLOTS | D-23 |
| 0x02 | LARGE_BUDGET | D-23 |

The sender stops sending that stream and treats the frame as not sent on this hop: its normal retry applies. An unknown reason is treated as `REASSEMBLY_SLOTS`.

## Close

(ADR 0009 decision 5; ADR 0020 decision; [D-22].)

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 1 | type | `u8` | `0x04` |
| 1 | 1 | reason | `u8` | See below |

Total: 2 B. After sending `CLOSE` a node sends nothing more and disconnects the bearer. A receiver of `CLOSE` disconnects.

<!-- data: codes.json registry=close-reason -->

| Code | Name | Decision |
|---|---|---|
| 0x00 | NORMAL | D-22 |
| 0x01 | DUPLICATE_LINK | D-22 |
| 0x02 | PROTOCOL_ERROR | D-22 |
| 0x03 | HANDSHAKE_FAILED | D-22 |
| 0x04 | UNSUPPORTED_VERSION | D-22 |
| 0x05 | WRONG_MESH | D-22 |
| 0x06 | RESOURCE_LIMIT | D-22 |
| 0x07 | SHUTTING_DOWN | D-22 |

An unknown reason is accepted and treated as `NORMAL`. Reasons are diagnostic only; they never change the receiver's protocol behavior, except that `DUPLICATE_LINK` keeps the peer's routing state and `RESOURCE_LIMIT` before the link opens stops the central reconnecting ([Connect retries and link caps](#connect-retries-and-link-caps)).

## Rekey

(ADR 0002 decision "Rekey"; [D-21].)

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 1 | type | `u8` | `0x05` |

Total: 1 B. Encrypted links only.

- After sending the record that carries `REKEY`, the sender calls Noise `Rekey()` on its sending cipher state. After processing that record, the receiver calls `Rekey()` on its receiving cipher state. Each direction rekeys independently.
- A sender MUST rekey a direction after `rekey-interval` or after `rekey-records` records in that direction, whichever comes first. A receiver MUST close the link with `PROTOCOL_ERROR` if a direction exceeds twice `rekey-records` without a rekey.
- The interval runs on the sender's monotonic clock from the handshake's completion or the direction's last `REKEY`. When it ends, the sender sends `REKEY` as its next record, whether or not it has other frames to send.
- One key encrypts at most `rekey-records` records, its `REKEY` included: when one record is left, the sender sends `REKEY` before its next frame. The receiver closes the link at the record after twice `rekey-records`, the 131,073rd under one key, without decrypting it.
- A `REKEY` before the handshake completes fails the handshake, as any frame other than the next handshake message does. A record encrypted with the old key after a `REKEY` fails to decrypt and closes the bearer ([Link records](#link-records)).
- An `unsecured` receiver drops a `REKEY` frame as it drops a link-local frame of unknown type: it records diagnostic ML3007 `UnknownLinkFrameDropped` and keeps the link open ([D-48]).

<!-- data: limits.json -->

| Name | Value | Unit | Decision |
|---|---|---|---|
| rekey-interval | 3,600 | s | D-21 |
| rekey-records | 65,536 | records | D-21 |

## Bearer switch

(ADR 0009 decisions 1 and 6; [D-43]; [D-49](decisions.md#d-49-bearer-switch-timing-and-credits).)

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 1 | type | `u8` | `0x0F` |

Total: 1 B.

- To move a link from GATT to an L2CAP CoC channel, the central opens the channel, then sends `BEARER_SWITCH` as its last record on GATT. The peripheral, after receiving it, sends `BEARER_SWITCH` as its last record on GATT. Each side continues its stream on CoC with the next record; cipher states and nonces carry over. Bytes received on CoC before the peer's `BEARER_SWITCH` are buffered.
- A node MUST discard any GATT stream bytes after the peer's `BEARER_SWITCH`.
- Every profile switches, `unsecured` included. The central switches once its link is open; a central whose channel fails to open stays on GATT and records ML3005 `BearerFallback`, unless the platform has no CoC.
- A peripheral that receives the central's `BEARER_SWITCH` waits up to `coc-accept-timeout` for the central's channel, then closes with `PROTOCOL_ERROR`. A channel no open link claims, and a second channel from the same peer, are closed.
- `BEARER_SWITCH` before the link opens, a peripheral's `BEARER_SWITCH` before the central's, and a second `BEARER_SWITCH` from the same peer are protocol errors: the receiver closes with `PROTOCOL_ERROR`.
- If the CoC channel fails, the link ends. The nodes MAY reconnect over GATT; that is a new link with a fresh handshake. A central whose kept link ended this way links again once, as after a transient loss. Routing state is kept by NodeId, and frames lost in flight are recovered by the normal retries ([conflict C-07](decisions.md#c-07)).
- GATT links use `CREDIT` flow control ([below](#flow-control)) until the switch; CoC has its own credits and uses none.

<!-- data: limits.json -->

| Name | Value | Unit | Decision |
|---|---|---|---|
| coc-accept-timeout | 5,000 | ms | D-49 |

## Neighbor list

(ADR 0016 decision 3 and amendment; ADR 0008 decisions 2 and 4; [D-24]; [conflict C-10](decisions.md#c-10).)

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 1 | type | `u8` | `0x07` |
| 1 | 8 | own_id | `bytes[8]` | Sender's current advertisement identifier (payload bytes 5–12) |
| 9 | 8 | next_id | `bytes[8]` | Identifier the sender will advertise after its next rotation |
| 17 | 1 | count | `u8` | 0 to 32 |
| 18 | 8 × count | neighbors | `bytes[8]` each | Current advertisement identifiers of the sender's link peers |

Size: 18 to 274 B.

- A node chooses its next advertisement identifier one rotation ahead: in a gated mesh it is the resolvable tag for the next epoch; otherwise 8 random bytes drawn in advance. At rotation it MUST advertise exactly the `next_id` it announced.
- A node sends its neighbor list after the handshake, again when its link set changes, and before each rotation of its own identifier, at most once per `neighbor-list-min-interval`. Linked peers thus learn the next identifier in advance. After the rotation, `own_id` is the new identifier and `next_id` the one after.
- Only already-linked peers learn `next_id`; it is never advertised before the rotation. A receiver keeps it to recognize the sender's advertisements after rotation (v1 parameters, "Identifier rotation inflation").
- A receiver uses the list only for local decisions (density estimate, redundant-link avoidance and the bridge rule). Lists are local hints; nothing is forwarded.

<!-- data: limits.json -->

| Name | Value | Unit | Decision |
|---|---|---|---|
| neighbor-list-max | 32 | entries | D-24 |
| neighbor-list-min-interval | 2 | s | - |

## Shuffle

(ADR 0008 decision 1 and amendment "Partial view".)

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 1 | type | `u8` | `0x08` `SHUFFLE` or `0x09` `SHUFFLE_REPLY` |
| 1 | 1 | count | `u8` | `SHUFFLE`: 1 to 5. `SHUFFLE_REPLY`: 0 to 5 |
| 2 | 8 × count | entries | `bytes[8]` each | Advertisement identifiers from the sender's passive view |

- Every `shuffle-interval` a node sends `SHUFFLE` on one random link. The receiver answers with `SHUFFLE_REPLY` on the same link and merges the received entries into its passive view. Which entries go in and which are evicted is local.

<!-- data: limits.json -->

| Name | Value | Unit | Decision |
|---|---|---|---|
| shuffle-interval | 10 | s | - |
| shuffle-entries-max | 5 | entries | - |

## Catch-up

(ADR 0014 decision 7 and amendment v3; [D-25].)

When a link comes up, each side sends one `CATCH_UP` frame. It lists what the sender can offer, so the peer can request missing broadcasts with `GRAFT` and receive held unicasts for destinations it can reach.

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 1 | type | `u8` | `0x0A` |
| 1 | 2 | offered_count | `u16` | 0 to 128 |
| 3 | 12 × n | offered | `bytes[12]` each | Message IDs of broadcasts received in the last `catch-up-window` |
| var | 2 | held_count | `u16` | 0 to 100 |
| var | 20 × n | held | entries | Each: message ID (12 B) then destination tag (8 B) |
| var | 2 | tombstone_count | `u16` | 0 to 128 |
| var | 28 × n | tombstones | entries | Each: message ID (12 B) then `k` (16 B) |

Maximum: 7,127 B, so `CATCH_UP` is always a small frame.

- The receiver requests a missing offered broadcast with `GRAFT` ([routing.md](routing.md#plumtree)).
- For each held entry whose destination tag is one of the receiver's own tags for any epoch within the last `message-lifetime-max`, or that it has an active route to, the receiver MAY request it with `GRAFT`. The holder then forwards the held frame unchanged except for the hop limit (ADR 0014 decision 7 and amendment).
- Each tombstone lets the receiver verify `H(k)` against a held copy's commitment and delete it ([end-to-end.md](end-to-end.md#tombstones)).
- If a list would exceed its maximum, the sender keeps the newest entries.

<!-- data: limits.json -->

| Name | Value | Unit | Decision |
|---|---|---|---|
| catch-up-offered-max | 128 | entries | D-25 |
| catch-up-held-max | 100 | entries | D-25 |
| catch-up-tombstones-max | 128 | entries | D-25 |
| catch-up-window | 60 | s | - |

## Resolving key update

(ADR 0010 decision 4 and amendment; [D-41].)

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 1 | type | `u8` | `0x0E` |
| 1 | 32 | resolving_key | `bytes[32]` | The sender's next resolving key |
| 33 | 4 | not_after | `u32` | Unix seconds when that key expires |

Total: 37 B. Gated meshes only, on encrypted links.

A node sends its next resolving key on every link at least `resolving-key-lead` before it starts using it, and, once it has one, to each new link as the first encrypted frame after the handshake. A receiver stores it with its `not_after` and starts precomputing its tags ([discovery.md](discovery.md#resolvable-tag)). A receiver drops the frame without storing anything on a cleartext link, in an open mesh, or when `not_after` has passed. A frame of the wrong length closes the link with `PROTOCOL_ERROR`, like any malformed frame.

## Flow control

(ADR 0009 decision 7; [D-47].)

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 1 | type | `u8` | `0x10` |
| 1 | 2 | credits | `u16` | Credits granted, 1 to 65,535 |

Total: 3 B.

On a GATT link the central grants the peripheral notification credits; one credit allows one GATT notification, which carries up to ATT MTU − 3 bytes of the peripheral's stream. Platform requests such as an iOS GAP name read then still get through the stack's queue (library rule R14).

- The central sends `CREDIT` granting `gatt-credit-window` as soon as the link opens: after the peer's `PLAIN_HELLO` in `unsecured`, after its handshake completes otherwise. Each time it has received half the window in notifications since its last grant, it grants that many again, restoring the peripheral's outstanding credits to the full window.
- From the moment its link opens, the peripheral never has more notifications sent than credits granted. With none left it queues, never drops, and keeps reading the central's frames, so a grant behind a waiting reply such as `BUSY` still arrives. Grants add to its outstanding credits.
- `CLOSE` needs no credit: a peripheral with none left still sends it as one notification, then disconnects.
- A grant that raises the outstanding credits above `gatt-credit-window`, and a `CREDIT` from a peripheral, are protocol errors: the receiver closes with `PROTOCOL_ERROR`.
- The central's writes, the handshake before the link opens, and every frame on an L2CAP CoC bearer are not credited.
- `BEARER_SWITCH` needs no credit. Once the central has written its `BEARER_SWITCH` it grants no more; the peripheral, from receiving it until its own `BEARER_SWITCH`, sends its notifications uncredited, finishing any record in progress first ([D-49](decisions.md#d-49-bearer-switch-timing-and-credits)).

<!-- data: limits.json -->

| Name | Value | Unit | Decision |
|---|---|---|---|
| gatt-credit-window | 8 | - | D-47 |

## Link-local frame summary

| Code | Name | Size | Section |
|---|---|---|---|
| `0x01` | `FRAGMENT` | 5 to 1,024 B | [Fragments](#fragments) |
| `0x02` | `HANDSHAKE` | 4 to 4,099 B | [HANDSHAKE frame](#handshake-frame) |
| `0x03` | `PLAIN_HELLO` | 67 B | [Unsecured link](#unsecured-link) |
| `0x04` | `CLOSE` | 2 B | [Close](#close) |
| `0x05` | `REKEY` | 1 B | [Rekey](#rekey) |
| `0x06` | `BUSY` | 3 B | [Busy reply](#busy-reply) |
| `0x07` | `NEIGHBOR_LIST` | 18 to 274 B | [Neighbor list](#neighbor-list) |
| `0x08`, `0x09` | `SHUFFLE`, `SHUFFLE_REPLY` | 2 to 42 B | [Shuffle](#shuffle) |
| `0x0A` | `CATCH_UP` | 7 to 7,127 B | [Catch-up](#catch-up) |
| `0x0B`–`0x0D` | `IHAVE`, `GRAFT`, `PRUNE` | | [routing.md](routing.md#plumtree) |
| `0x0E` | `RESOLVING_KEY_UPDATE` | 37 B | [Resolving key update](#resolving-key-update) |
| `0x0F` | `BEARER_SWITCH` | 1 B | [Bearer switch](#bearer-switch) |
| `0x10` | `CREDIT` | 3 B | [Flow control](#flow-control) |

A receiver MUST drop a link-local frame of unknown type and SHOULD record a diagnostic (ADR 0020). It MUST NOT close the link for it.

Vectors: `link-frames.json`.
