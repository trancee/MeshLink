# End-to-end layer

Status: Draft. Part of [MeshLink protocol 1](README.md).

The end-to-end layer protects unicast messages and receipts between origin and destination, across relays. In the secured profiles it uses Noise `IK` (`classical`) or `IKhfs` (`post-quantum`). `unsecured` carries plaintext with a claimed sender (ADR 0012 decision 12).

## Patterns

(ADR 0002 decision "End-to-end layer" and "Post-quantum first and held messages"; [D-15].)

<!-- data: constants.json -->

| Name | Value | Decision |
|---|---|---|
| noise-ik | Noise_IK_25519_ChaChaPoly_SHA256 | - |
| noise-ikhfs | Noise_IKhfs_25519+ML-KEM-768_ChaChaPoly_SHA256 | - |

- The sender of the first message of a session is the initiator; its peer is the responder. The initiator's pre-message `s` is the responder's certified `x25519_key`.
- Both sides use this prologue:

  ```text
  prologue = DS("e2e-prologue") ‖ u8(protocol_version) ‖ u8(profile)
  ```

- Message 1 carries the first message (0-RTT, no forward secrecy). Message 2 completes the session. Later frames are Noise transport messages with forward secrecy.

Vectors: `derivations.json`, derivation `e2e-prologue`. Handshake vectors are tier 2.

## Containers

(ADR 0012 decisions 5 and 12; [D-30].)

Routed frames that carry end-to-end content end with a container. Its first byte is the kind.

<!-- data: codes.json registry=container-kind -->

| Code | Name | Decision |
|---|---|---|
| 0x01 | IK_INITIATOR | D-30 |
| 0x02 | IK_RESPONDER | D-30 |
| 0x03 | TRANSPORT | D-30 |
| 0x04 | UNSECURED | D-30 |
| 0x05 | SIGNED_BROADCAST | D-40 |

| Kind | Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|---|
| `0x01`, `0x02` | 0 | 1 | kind | `u8` | |
| | 1 | 4 | length | `u32` | ≥ 1 |
| | 5 | var | noise_message | `bytes` | `IK`/`IKhfs` message 1 or 2 |
| `0x03` | 0 | 1 | kind | `u8` | |
| | 1 | 8 | nonce | `u64` | Explicit Noise transport nonce |
| | 9 | 4 | length | `u32` | ≥ 17 |
| | 13 | var | ciphertext | `bytes` | Encrypted plaintext plus 16 B tag |
| `0x04` | 0 | 1 | kind | `u8` | |
| | 1 | 32 | sender_node_id | `bytes[32]` | Claimed sender |
| | 33 | 4 | length | `u32` | |
| | 37 | var | plaintext | `bytes` | Unpadded plaintext (below), or the broadcast payload |

Kind `0x05` is in [routing.md](routing.md#broadcast). Allowed kinds per frame:

| Frame | Secured profiles | `unsecured` |
|---|---|---|
| `MESSAGE` | `0x01`, `0x03` | `0x04` |
| `DELIVERY_RECEIPT` | `0x02`, `0x03` | `0x04` |
| `READ_RECEIPT` | `0x01`, `0x03` | `0x04` |
| `BROADCAST` | `0x05` | `0x04` |

A kind not allowed for the frame makes the frame malformed (`unknown-kind`). A node MUST drop a frame whose kind does not match its mesh's profile.

Vectors: `routed-frames.json`.

## Plaintext

(ADR 0012 decision 11; ADR 0014 decision 8; [D-31].)

Every container except a broadcast carries this plaintext, encrypted in kinds `0x01` to `0x03`:

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 4 | content_length | `u32` | |
| 4 | var | content | see below | |
| var | var | padding | zero bytes | Secured: makes the total a multiple of 32 B, 0 to 31 B. `unsecured`: none |

Message content (`MESSAGE`):

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 12 | message_id | `bytes[12]` | MUST equal the routing header's |
| 12 | 4 | expiry | `u32` | MUST equal the routing header's |
| 16 | 16 | k | `bytes[16]` | Tombstone secret; `H(k)` MUST equal the frame's commitment |
| 32 | 2 | extensions_length | `u16` | |
| 34 | var | extensions | TLVs | End-to-end TLVs |
| var | var | payload | `rest` | App payload, 0 to 131,072 B |

Receipt content (`DELIVERY_RECEIPT`, `READ_RECEIPT`):

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 12 | message_id | `bytes[12]` | MUST equal the routing header's |
| 12 | 4 | expiry | `u32` | MUST equal the routing header's |
| 16 | 2 | extensions_length | `u16` | |
| 18 | var | extensions | TLVs | Includes `MESSAGE_IDS` |

- The receiver MUST drop a frame whose plaintext fails any MUST above, or whose secured padding is not all zero, not shorter than 32 B or not aligned (`bad-padding`).
- Repeating `message_id` and `expiry` inside binds the clear header to the authenticated content, so a relay cannot change them.

<!-- data: limits.json -->

| Name | Value | Unit | Decision |
|---|---|---|---|
| padding-block | 32 | B | - |

Vectors: `inner-plaintext.json`.

### Plaintext extensions

(ADR 0012 decision 7; ADR 0014 decision 10; ADR 0013 decision 3; ADR 0016 decision 2; [D-31].)

<!-- data: tlv-types.json context=e2e -->

| Code | Name | Critical | Length |
|---|---|---|---|
| 0x01 | SENDER_CERTIFICATE | no | variable |
| 0x04 | READ_RECEIPT_REQUESTED | no | 0 |
| 0x82 | CONVERSATION_SEED | yes | 32 |
| 0x83 | MESSAGE_IDS | yes | variable |

- `SENDER_CERTIFICATE`: the sender's current certificate. REQUIRED in every `IK_INITIATOR` plaintext, because the responder may not know the sender. Otherwise sent when the peer's copy is past half its validity (ADR 0014 decision 10). The receiver MUST validate it and check that its `x25519_key` equals the Noise remote static key.
- `READ_RECEIPT_REQUESTED`: the sender asks for a read receipt (ADR 0013 decision 3). Messages only.
- `CONVERSATION_SEED`: 32 random bytes. REQUIRED in every `IK_INITIATOR` plaintext of the secured profiles, and only there.
- `MESSAGE_IDS`: a list of 12 B message IDs (length a non-zero multiple of 12). REQUIRED in receipts, and only there.

## First messages

(ADR 0002 decision; ADR 0016 decision 2; ADR 0018 decision 8; [D-32], [D-35].)

A first message opens a session. It is a `MESSAGE` (or a `READ_RECEIPT`, see [Read receipts](#read-receipts)) with container kind `0x01`:

- Routing header: `destination_tag` is the responder's public tag for its newest valid certificate ([routing.md](routing.md#routing-tags)); `source_tag` is the new conversation's tag for direction `0x02`.
- `classical`: the `IK` message 1 payload is the padded plaintext.
- `post-quantum`: the `IKhfs` message 1 payload is an HPKE seal of the padded plaintext:

  ```text
  info    = DS("hpke-first-message") ‖ initiator_x25519_static_key ‖ responder_NodeId
  enc, ct = HPKE SealBase(pk = certificate.xwing_key, info, aad = empty, pt = padded plaintext)
  payload = enc (1,120 B) ‖ ct
  ```

<!-- data: constants.json -->

| Name | Value | Decision |
|---|---|---|
| hpke-kem | 0x647A | - |
| hpke-kdf | 0x0001 | D-35 |
| hpke-aead | 0x0003 | D-35 |
| hpke-mode | 0x00 | D-35 |

HPKE is RFC 9180 base mode with KEM X-Wing (`0x647A`, draft-ietf-hpke-pq), KDF HKDF-SHA256 and AEAD ChaCha20Poly1305. The initiator's static key is used in `info` because the responder learns it from `IKhfs` before it opens the seal, and learns the initiator's NodeId only from the sealed `SENDER_CERTIFICATE`. The seal adds 1,136 B ([conflict C-06](decisions.md#c-06)).

Vectors: `derivations.json`, derivation `hpke-info`. Seal vectors are tier 2.

## Sessions

(ADR 0002 decision "Sessions"; [D-33], [D-34].)

- **Opening.** An initiator has at most one first message outstanding per peer. Further messages to that peer wait until the session opens or the first message expires; then the next waiting message becomes a first message. Retries of a first message resend the same frame bytes.
- **Answering.** The responder processes each message 1 once (by `message_id`). It sends `IK` message 2 in the `DELIVERY_RECEIPT` for that message, container kind `0x02`; the plaintext is the receipt. It keeps that container and resends it unchanged in the receipt for every later copy. After message 2, the responder sends with the session.
- **Adopting.** The initiator matches a message 2 to its first message by the receipt's tombstone `message_id`. It processes the first such message 2 and ignores later copies.
- **Transport.** Each side keeps a `u64` send counter per session, starting at 0, and sends each frame as kind `0x03` with that counter as the Noise nonce (`SetNonce`). Associated data is empty. The receiver accepts each nonce once, within a window of the highest nonce seen.
- **Crossed sessions.** If two sessions with one peer open (both sides sent a first message), both sides send with the session whose initiator has the lower NodeId (bytewise), and drop the other once its outstanding frames are answered.
- **Ending.** A session ends after `e2e-session-max-age` or after `e2e-session-max-messages` frames in either direction, or when evicted from the local session cache. The next message is a first message.
- **Failure.** If a frame sent with a session gets no receipt by its second retry, the sender ends the session and resends the message as a first message with a new `message_id`. The responder may have lost its session state.

<!-- data: limits.json -->

| Name | Value | Unit | Decision |
|---|---|---|---|
| e2e-session-max-age | 21,600 | s | D-33 |
| e2e-session-max-messages | 4,096 | messages | D-33 |
| e2e-replay-window | 1,024 | nonces | D-33 |

The session cache size is per preset and local (ADR 0002 decision; ADR 0007).

## Conversation tags

(ADR 0016 decision 2; [D-32].)

```text
secret = KDF(conversation_seed, "", "conversation", initiator_NodeId ‖ responder_NodeId, 32)
tag(d) = trunc_8(H("conversation-tag", u8(d) ‖ u32(epoch); key = secret))
```

<!-- data: codes.json registry=conversation-direction -->

| Code | Name | Decision |
|---|---|---|
| 0x01 | initiator to responder | D-32 |
| 0x02 | responder to initiator | D-32 |

- `tag(d)` is the destination tag of frames traveling in direction `d`. A frame from the initiator has destination `tag(0x01)` and source `tag(0x02)`; a frame from the responder the reverse. The first message is the only exception: its destination is the responder's public tag.
- Both ends recognize the tags of their active sessions for epochs e − 1, e and e + 1, and claim held unicasts addressed to them ([link.md](link.md#catch-up)).
- `unsecured` has no sessions or conversation tags. Frames use the unsecured tags of destination and source ([routing.md](routing.md#routing-tags)).

Vectors: `derivations.json`, derivations `conversation-secret` and `conversation-tag`.

## Delivery receipts

(ADR 0013 decisions 1 and 2 and amendments; ADR 0014 decision 8; [D-38].)

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 34 | header | unicast header | Type `0x41` |
| 34 | 1 | count | `u8` | 1 to 32 |
| 35 | 28 × count | tombstones | entries | Each: message ID (12 B) then `k` (16 B), in clear |
| var | var | container | container | Kind `0x02`, `0x03` or `0x04` |

- The destination sends a delivery receipt for every unicast `MESSAGE` once its app takes it, and again for every later copy. It never delivers a message twice.
- A receipt acknowledges up to `receipt-batch-max` messages from one sender in one session. A destination waits at most `receipt-batch-delay` to fill a batch.
- The plaintext's `MESSAGE_IDS` MUST list the same IDs as the tombstones, in the same order. The sender MUST ignore a receipt whose lists differ.
- The receipt's routing header has its own fresh `message_id` and an `expiry` equal to the latest expiry of the acknowledged messages.

<!-- data: limits.json -->

| Name | Value | Unit | Decision |
|---|---|---|---|
| receipt-batch-max | 32 | entries | D-38 |
| receipt-batch-delay | 500 | ms | D-38 |

## Read receipts

(ADR 0013 decisions 3 and 4 and amendments; ADR 0016 amendment to ADR 0013; [D-39].)

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 34 | header | unicast header | Type `0x42` |
| 34 | var | container | container | Kind `0x01`, `0x03` or `0x04` |

- Sent only for messages that carried `READ_RECEIPT_REQUESTED`, and only if the recipient allows read receipts and its app marked the message read.
- One read receipt lists up to `read-receipt-batch-max` IDs in `MESSAGE_IDS`. A recipient waits at most `read-receipt-batch-delay` to fill a batch.
- Its expiry is the recipient's default message lifetime from now. Nothing acknowledges it.
- If the session has ended, the read receipt is a first message (kind `0x01`) that opens a new session, addressed to the original sender's public tag.

<!-- data: limits.json -->

| Name | Value | Unit | Decision |
|---|---|---|---|
| read-receipt-batch-max | 64 | entries | D-39 |
| read-receipt-batch-delay | 2,000 | ms | D-39 |
| read-tracking | 1,000 | entries | - |

`read-tracking` bounds how many sent messages a node tracks while waiting for `Read` (ADR 0013 decision 6); it is local.

## Tombstones

(ADR 0014 decision 8 and amendments; [D-10], [D-42].)

```text
commitment = trunc_16(H("tombstone", k))           k: 16 random bytes per unicast message
```

- The origin draws `k`, puts it in the plaintext and `H(k)` in the `MESSAGE` commitment. Retries reuse both.
- A tombstone is `{message_id, k}`. Any node that holds a `MESSAGE` with that `message_id` and verifies `H(k)` against its commitment deletes its copy.
- A node stores a tombstone if it verified a held copy, or if it relayed the `DELIVERY_RECEIPT` that carried it. It ignores other tombstones from `CATCH_UP`.
- A tombstone from a receipt is kept until that receipt's `expiry`; one that verified a held copy until that copy's `expiry`. At most `tombstone-store` entries are kept, oldest dropped first. Stored tombstones are offered in `CATCH_UP`.
- In `unsecured`, `k` is plaintext in the message, so anyone on the path can forge tombstones.

<!-- data: limits.json -->

| Name | Value | Unit | Decision |
|---|---|---|---|
| tombstone-store | 1,000 | entries | - |

Vectors: `derivations.json`, derivation `tombstone-commitment`.
