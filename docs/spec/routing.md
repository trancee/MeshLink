# Routing

Status: Draft. Part of [MeshLink protocol 1](README.md).

Routed frames cross several hops. Unicast frames (`0x40`–`0x7F`) go to one routing tag. Flood frames (`0x80`–`0xBF`) go to the whole mesh. Every node relays every routed frame type, including types it does not know, so nodes in different density classes interoperate (ADR 0008 decision 2).

## Routing tags

(ADR 0016 decisions 1 and 2 and amendments; ADR 0008 decision 6 and amendment; [D-08].)

A routing tag is 8 B. A node recognizes these tags as its own:

- **Public tags**, one per epoch per static key it still holds ([identity.md](identity.md#static-key-rotation)):

  ```text
  public_tag = trunc_8(H("public-tag", x25519_key ‖ u32(epoch)))
  ```

  Only first messages of an end-to-end session, and route requests for them, use public tags.
- **Conversation tags**, two per active end-to-end session ([end-to-end.md](end-to-end.md#conversation-tags)).
- **Unsecured tags** (`unsecured` only; there are no public or conversation tags):

  ```text
  unsecured_tag = trunc_8(H("unsecured-tag", NodeId ‖ u32(epoch)))
  ```

A node recognizes each of its tags for epochs e − 1, e and e + 1 (README, "Epochs"). It accepts the public tags of a static key until it deletes that key (`static-key-grace` after the certificate's `not_after`).

Vectors: `derivations.json`, derivations `public-tag`, `unsecured-tag`, `conversation-tag`.

## Routing headers

(ADR 0012 decision 5; ADR 0008 decisions 5 and 6; [D-27].)

Unicast header, at the start of every frame of types `0x40`–`0x7F`:

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 1 | type | `u8` | `0x40`–`0x7F` |
| 1 | 1 | hop_limit | `u8` | 1 to 255 |
| 2 | 12 | message_id | `bytes[12]` | Random, chosen by the origin |
| 14 | 4 | expiry | `u32` | Unix seconds |
| 18 | 8 | destination_tag | `bytes[8]` | |
| 26 | 8 | source_tag | `bytes[8]` | |

Total: 34 B.

Flood header, at the start of every frame of types `0x80`–`0xBF`:

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 1 | type | `u8` | `0x80`–`0xBF` |
| 1 | 1 | hop_limit | `u8` | 1 to 255 |
| 2 | 12 | message_id | `bytes[12]` | Random, chosen by the origin |
| 14 | 4 | expiry | `u32` | Unix seconds |

Total: 18 B.

- The origin sets `hop_limit` (default 255) and `expiry`. A frame with `hop_limit` 0 is malformed.
- Before forwarding, a relay decrements `hop_limit`. It forwards the frame only if the result is at least 1. The hop limit is the only header field a relay changes.
- A node MUST NOT forward or hold a frame whose `expiry` has passed, allowing `clock-skew`. It MUST drop a frame whose `expiry` is more than `message-lifetime-max` plus `clock-skew` in the future.
- The origin of a message sets `expiry` between `message-lifetime-min` and `message-lifetime-max` after the current time.

<!-- data: limits.json -->

| Name | Value | Unit | Decision |
|---|---|---|---|
| hop-limit-default | 255 | hops | - |
| message-lifetime-min | 120 | s | - |
| message-lifetime-max | 3,600 | s | - |

## Size classes

(ADR 0015 decisions 1 to 3 and amendments; ADR 0008 amendment "Large messages"; [conflict C-05](decisions.md#c-05).)

<!-- data: limits.json -->

| Name | Value | Unit | Decision |
|---|---|---|---|
| unicast-payload-small-max | 4,096 | B | - |
| unicast-payload-max | 131,072 | B | - |
| broadcast-payload-max | 1,024 | B | - |

- An app payload of at most 4 KiB is a small message; up to 128 KiB is a large message. Broadcast payloads are at most 1 KiB.
- Relays see only frame sizes. A routed frame longer than `frame-small-max` ([link.md](link.md#link-records)) is a large frame. A relay MUST NOT flood or hold a large frame; it forwards it only on an active route and drops it otherwise. Only the origin retries large messages.
- An origin sends a large message only on a direct link or on a route found by route request.

## Unicast forwarding

(ADR 0008 decisions 3 to 5 and amendments; ADR 0013 amendment to ADR 0008; [D-29].)

When a node receives a unicast frame:

1. If `destination_tag` is one of its own tags, it processes the frame. It does not forward it.
2. Otherwise, it records or refreshes a route to `source_tag` through the incoming link (a reverse route, `reverse-route-timeout`).
3. If it forwarded the same `message_id` within the last `unicast-seen-window`, it drops the frame.
4. If it has an active route to `destination_tag`, it forwards on that link only, and refreshes that route.
5. Otherwise, if the frame is small, it forwards on every other link (flooding, bounded by the hop limit). It MAY also hold the frame ([Holding](#holding)).
6. A large frame without a route is dropped.

The origin's retries reuse the `message_id`, so a retry passes relays once the seen window has elapsed. A destination processes each `message_id` once until its expiry but answers every copy with a delivery receipt (ADR 0013 decision 3).

<!-- data: limits.json -->

| Name | Value | Unit | Decision |
|---|---|---|---|
| unicast-seen-window | 1,000 | ms | D-29 |
| route-active-timeout | 30 | s | - |
| forward-route-timeout | 60 | s | - |
| reverse-route-timeout | 6 | s | - |

- An active route expires `route-active-timeout` after it was last used. A route learned from a route reply first holds `forward-route-timeout`. A reverse route holds `reverse-route-timeout` unless used.
- The delivery receipt travels the reverse route and refreshes the route on every hop (ADR 0008 amendment, "K2 delivery ack").

### Origin behavior

(ADR 0008 decision 3 and amendments; v1 parameters "Unicast".)

How the origin sends depends on its local density class. These are local choices, but they set the load other nodes see:

- **Low class:** send with `hop_limit` 255 (flood).
- **Medium and high class:** with an active route, send on it. Without one, send with `hop_limit` `u6-flood-radius` and at once send a `ROUTE_REQUEST` with `hop_limit` `route-request-radius`.
- After sending on a route, if no delivery receipt arrives within `resend-base` + `resend-per-hop` × the route's hop count, send once more by a new search.
- Then retry with doubling intervals: from `retry-min-low` to `retry-max-low` in the low and medium classes, from `retry-min-high` to `retry-max-high` in the high class. Low and medium also retry on each new link.
- A retry keeps `message_id`, `expiry`, the commitment and the container, but uses the destination's and the origin's tags for the current epoch. Relays cannot re-tag (ADR 0014 decision 7).
- A direct-only send (ADR 0013 decision 8) uses `hop_limit` 1, so no relay forwards or holds it [D-27].
- Running out of retries does not fail a message: it expires at `expiry` plus `clock-skew` (ADR 0013 amendment to ADR 0008).

<!-- data: limits.json -->

| Name | Value | Unit | Decision |
|---|---|---|---|
| u6-flood-radius | 3 | hops | - |
| route-request-radius | 64 | hops | - |
| resend-base | 1,000 | ms | - |
| resend-per-hop | 200 | ms | - |
| retry-min-low | 2 | s | - |
| retry-max-low | 30 | s | - |
| retry-min-high | 5 | s | - |
| retry-max-high | 60 | s | - |

### Holding

(ADR 0014 decision 4 and amendments; ADR 0015 decision 7; ADR 0008 decision 5.)

A relay MAY hold a small unicast or broadcast frame for others, and forwards it again on a new link or a new route. Holding is local, but a node MUST respect these bounds:

<!-- data: limits.json -->

| Name | Value | Unit | Decision |
|---|---|---|---|
| hold-messages | 100 | messages | - |
| hold-bytes | 524,288 | B | - |
| hold-link-share-divisor | 4 | - | - |
| hold-message-max | 4,096 | B | - |
| hold-min-remaining | 10 | s | - |

- At most `hold-messages` frames and `hold-bytes` bytes, and at most 1/`hold-link-share-divisor` of each from one incoming link.
- Only small frames with at least `hold-min-remaining` of lifetime left that arrived with `hop_limit` 2 or more. `hold-message-max` is the app payload bound that makes a message small (ADR 0015 amendment to ADR 0014).
- A held message is deleted at its expiry, or when a verified tombstone for it arrives ([end-to-end.md](end-to-end.md#tombstones)).

## MESSAGE

(ADR 0012 decision 5; ADR 0014 decision 6; [D-30].)

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 34 | header | unicast header | Type `0x40` |
| 34 | 16 | commitment | `bytes[16]` | `H(k)`, see [Tombstones](end-to-end.md#tombstones) |
| 50 | var | container | container | Kinds `0x01`–`0x04`; see [end-to-end.md](end-to-end.md#containers) |

Relays use only the header and the commitment.

Vectors: `routed-frames.json` (containers have placeholder ciphertext).

## Route discovery

(ADR 0008 decision 4 and amendments; v1 parameters "Routing"; [D-28].)

The metric is hop count with destination sequence numbers, in the style of AODV (RFC 3561).

### ROUTE_REQUEST

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 18 | header | flood header | Type `0x81` |
| 18 | 8 | target_tag | `bytes[8]` | Tag the origin looks for |
| 26 | 8 | origin_tag | `bytes[8]` | Origin's tag for this conversation (its reply-to tag) |
| 34 | 4 | origin_sequence | `u32` | ≥ 1 |
| 38 | 4 | target_sequence | `u32` | Last known sequence for the target; 0 = unknown |
| 42 | 1 | hop_count | `u8` | Hops traveled; the origin sends 0 |

Total: 43 B.

### ROUTE_REPLY

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 34 | header | unicast header | Type `0x43`. Destination: `origin_tag`. Source: the replier's tag for this route |
| 34 | 8 | target_tag | `bytes[8]` | |
| 42 | 4 | target_sequence | `u32` | ≥ 1 |
| 46 | 1 | hop_count | `u8` | Hops from the replier's side to the target; the target sends 0 |

Total: 47 B.

### Rules

- Each node keeps one `u32` sequence number, starting at 1. It increments it before each route request it originates and before each reply it sends as target. Sequence numbers are compared with serial-number arithmetic (RFC 1982), and 0 is skipped.
- The origin sets the request's `expiry` to `message-lifetime-min` after the current time.
- A relay that receives a request floods it on (flood rules below), after incrementing `hop_count`, and records a reverse route to `origin_tag`.
- The node that owns `target_tag` answers with a `ROUTE_REPLY` and does not forward the request.
- An intermediate node with an active route to `target_tag` whose sequence is at least `target_sequence` MAY answer instead, with its route's sequence and hop count, and then does not forward the request.
- Each relay of a reply increments `hop_count` and records a forward route to `target_tag` through the link the reply came from. A node prefers the route with the newest sequence number, then the fewest hops.
- v1 has no route error message. Broken routes expire, and the origin's resend uses a new search.
- v1 has no route-request rate limit (ADR 0008 amendment, "Route-request rate limit").

Vectors: `routed-frames.json`.

## Flood forwarding

(ADR 0008 decision 3; ADR 0018 decision 3; [D-29].)

A node that receives a flood frame:

1. Drops it if it has already seen `message_id` and that entry has not expired. A node remembers each flood `message_id` until the frame's `expiry`.
2. Processes it if it is addressed to the node (a route request for its tag, a lookup for its NodeId or a cached certificate, any broadcast).
3. Unless it answered a route request or lookup, forwards it on every other link if the hop limit allows. Broadcasts in the medium and high classes use [Plumtree](#plumtree) instead of plain flooding.

## BROADCAST

(ADR 0012 decision 5; ADR 0015 decision 2; ADR 0016 decision 1; [D-40].)

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 18 | header | flood header | Type `0x80` |
| 18 | var | container | container | Kind `0x05` (secured profiles) or `0x04` (`unsecured`) |

Container kind `0x05`, signed broadcast:

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 1 | kind | `u8` | `0x05` |
| 1 | 32 | origin_key | `bytes[32]` | Origin's Ed25519 identity key; its NodeId is `H("node-id", origin_key)` |
| 33 | 2 | payload_length | `u16` | 0 to 1,024 |
| 35 | var | payload | `bytes` | App payload |
| var | 64 | signature | `bytes[64]` | Ed25519 by `origin_key` |

The signature covers `DS("broadcast")` followed by the whole frame without the `hop_limit` byte and without the signature:

```text
DS("broadcast") ‖ frame[0] ‖ frame[2 .. length − 65]
```

A receiver in a secured profile MUST verify the signature before delivering and MUST NOT deliver or relay a broadcast with kind other than `0x05`. In `unsecured`, kind `0x04` carries the claimed sender NodeId and the payload with no padding ([end-to-end.md](end-to-end.md#containers)). Broadcasts are not encrypted end to end. In a gated mesh they are confidential to members because links are. Broadcasts never get receipts (ADR 0013 decision 1).

Vectors: `routed-frames.json` (placeholder signatures), `derivations.json` derivation `broadcast-signing-input`.

## Plumtree

(ADR 0008 decision 3 and amendments; ADR 0014 amendment v3; [D-26].)

Plumtree (Leitão et al.) builds a broadcast tree: eager links carry full frames, lazy links carry only IDs. Plumtree frames are link-local.

| Frame | Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|---|
| `IHAVE` | 0 | 1 | type | `u8` | `0x0B` |
| | 1 | 1 | count | `u8` | 1 to 32 |
| | 2 | 12 × count | message_ids | `bytes[12]` each | Broadcasts the sender holds |
| `GRAFT` | 0 | 1 | type | `u8` | `0x0C` |
| | 1 | 12 | message_id | `bytes[12]` | Requested frame |
| `PRUNE` | 0 | 1 | type | `u8` | `0x0D` |

Sizes: `IHAVE` 14 to 386 B; `GRAFT` 13 B; `PRUNE` 1 B.

- A node pushes a new broadcast on its eager links and announces its ID in `IHAVE` on its lazy links. All links start eager.
- A node that receives a duplicate broadcast on an eager link sends `PRUNE` on it; the link becomes lazy in both directions.
- A node that learns an ID from `IHAVE` and does not receive the frame within `graft-timeout-base` sends `GRAFT` to the first announcer, then to each further announcer after `graft-timeout-per-announcer`. A `GRAFT` makes the link eager and the receiver sends the frame.
- `GRAFT` also requests offered broadcasts and held unicasts from `CATCH_UP` ([link.md](link.md#catch-up)). A node answers a `GRAFT` for an ID it no longer holds with nothing.

<!-- data: limits.json -->

| Name | Value | Unit | Decision |
|---|---|---|---|
| ihave-ids-max | 32 | entries | D-26 |
| graft-timeout-base | 500 | ms | - |
| graft-timeout-per-announcer | 250 | ms | - |

Vectors: `link-frames.json`.

## Certificate lookup

(ADR 0018 decisions 3 to 8 and amendment; [D-09], [D-36], [D-37].)

Secured profiles only. A node that must seal to a NodeId without a valid certificate floods a lookup request.

### LOOKUP_REQUEST

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 18 | header | flood header | Type `0x82` |
| 18 | 8 | salt | `bytes[8]` | Random, fresh per request |
| 26 | 8 | lookup_tag | `bytes[8]` | See below |
| 34 | 32 | ephemeral_key | `bytes[32]` | Requester's fresh X25519 public key |

Total: 66 B (the 48 B of ADR 0018 decision 3 plus the flood header).

```text
lookup_tag = trunc_8(H("lookup-tag", salt ‖ NodeId))
```

- The request floods under the route-request rules: `hop_limit` `route-request-radius`, `expiry` `message-lifetime-min` ahead, suppression by `message_id`. Relays record a reverse route keyed by `salt`.
- Each node compares `lookup_tag` with its own NodeId and with the NodeIds of its cached certificates. A match answers and stops forwarding.
- A requester has at most one lookup outstanding per NodeId. Each retry uses a new salt, a new ephemeral key and a new `message_id`, on the origin's unicast retry schedule [D-37].

### LOOKUP_REPLY

| Offset | Size | Name | Encoding | Notes |
|---|---|---|---|---|
| 0 | 34 | header | unicast header | Type `0x44`. Destination: the request's `salt`. Source: 8 random bytes |
| 34 | 32 | ephemeral_key | `bytes[32]` | Responder's fresh X25519 public key |
| 66 | 2 | sealed_length | `u16` | 154 or 1,370 |
| 68 | var | sealed_certificate | `bytes` | Certificate sealed as below, with a 16 B tag |

Total: 222 B classical, 1,438 B post-quantum ([conflict C-02](decisions.md#c-02)).

The responder seals the certificate with the latest `not_after` it holds for the NodeId:

```text
shared = X25519(responder_ephemeral_private, request.ephemeral_key)
key    = KDF(shared, salt ‖ lookup_tag, "lookup-reply", request.ephemeral_key ‖ reply.ephemeral_key, 32)
sealed = ChaCha20-Poly1305(key, nonce = 12 zero bytes, aad = empty, certificate)
```

The key is used once, so the zero nonce is safe. A classical seal is used in both profiles (ADR 0018 decision 5). The responder MUST reject an all-zero `shared`.

- The reply travels the reverse route of its salt. A relay passes on only the first reply per salt and drops the rest (ADR 0018 amendment).
- The requester opens the first reply, validates the certificate ([identity.md](identity.md#validation)) for the NodeId it looked up, caches it, and drops later replies.
- The certificate cache holds at most `certificate-cache` certificates of other nodes, filled only from links, `IK` senders and the node's own lookups.

<!-- data: limits.json -->

| Name | Value | Unit | Decision |
|---|---|---|---|
| certificate-cache | 64 | entries | - |

Vectors: `routed-frames.json` (placeholder sealed bytes), `derivations.json` derivations `lookup-tag` and `lookup-reply-key`. Sealing vectors are tier 2.

## Routed frame summary

| Code | Name | Size | Section |
|---|---|---|---|
| `0x40` | `MESSAGE` | ≥ 56 B | [MESSAGE](#message) |
| `0x41` | `DELIVERY_RECEIPT` | ≥ 69 B | [end-to-end.md](end-to-end.md#delivery-receipts) |
| `0x42` | `READ_RECEIPT` | ≥ 40 B | [end-to-end.md](end-to-end.md#read-receipts) |
| `0x43` | `ROUTE_REPLY` | 47 B | [ROUTE_REPLY](#route_reply) |
| `0x44` | `LOOKUP_REPLY` | 222 or 1,438 B | [LOOKUP_REPLY](#lookup_reply) |
| `0x80` | `BROADCAST` | ≥ 55 B | [BROADCAST](#broadcast) |
| `0x81` | `ROUTE_REQUEST` | 43 B | [ROUTE_REQUEST](#route_request) |
| `0x82` | `LOOKUP_REQUEST` | 66 B | [LOOKUP_REQUEST](#lookup_request) |

A relay that receives a routed frame of unknown type forwards it by its header like any other routed frame and never delivers it. Vectors with result `acceptable` cover this.
