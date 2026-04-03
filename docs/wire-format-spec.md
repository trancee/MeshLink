# MeshLink Wire Format Specification

> **Canonical binary specification.** For design rationale and protocol context, see [design.md](design.md).

## Version

Protocol version: 1.0

## Overview

MeshLink uses a compact binary wire protocol designed for Bluetooth Low Energy
(BLE) mesh networking. Every framed message begins with a single **type byte**
(offset 0) that acts as the message discriminator. Separate from the framed
messages, the protocol defines a **10-byte BLE advertisement payload** and a
**5-byte Noise XX handshake payload**.

## Conventions

| Convention | Rule |
|---|---|
| **Byte ordering** | Varies per field — each field table states **BE** (big-endian / network order) or **LE** (little-endian). |
| **Type discriminator** | All framed messages share byte 0 as the type code. |
| **Sizes** | All sizes are in bytes unless stated otherwise. Multi-byte integers are unsigned unless noted. |
| **Key hashes** | Peer identifiers and visited-list entries are 12-byte values (truncated SHA-256, 96-bit collision resistance). |
| **Message IDs** | 16-byte random identifiers generated from platform CSPRNG (128-bit entropy). |
| **Signature blocks** | Ed25519 signatures are 64 bytes; Ed25519 public keys are 32 bytes. |

---

## BLE Advertisement Payload (10 bytes)

Broadcast as BLE service data in the scan response for peer discovery. Not
framed with a type byte — this is a standalone payload carried inside a
Service Data AD structure with the MeshLink 128-bit service UUID.

**Source:** `AdvertisementCodec.kt` · `SIZE = 10`

```
Byte:   0               1               2                           9
       +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-- ... --+-+-+
       |MajVer |PwrMode|  VersionMinor |   Truncated SHA-256        |
       |4 bits |4 bits |   (8 bits)    |   of X25519 public key     |
       +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-- ... --+-+-+
       |<--- 1 byte --->|<-- 1 byte -->|<------- 8 bytes ---------->|
```

| Offset | Size | Field | Type | Endianness | Description |
|--------|------|-------|------|------------|-------------|
| 0 [7:4] | 4 bits | `versionMajor` | uint | — | Protocol major version (0–15). |
| 0 [3:0] | 4 bits | `powerMode` | uint | — | Power mode indicator (0–15). |
| 1 | 1 | `versionMinor` | uint8 | — | Protocol minor version (0–255). |
| 2–9 | 8 | `keyHash` | bytes | — | First 8 bytes of `SHA-256(X25519_public_key)`. |

**Encoding:** `byte0 = (versionMajor << 4) | powerMode`. Both nibble values are
clamped to their respective ranges via `coerceIn`.

**Decoding:** `versionMajor = byte0 >>> 4`, `powerMode = byte0 & 0x0F`.

See [design.md §3](design.md) for rationale on field sizes.

---

## Noise XX Handshake Payload (5 bytes)

Carried **inside** the Noise XX handshake messages for protocol version
negotiation and capability exchange. This is not a standalone framed message; it
is embedded in the `noiseMessage` field of a Handshake message (type `0x00`).

**Source:** `HandshakePayload.kt` · `SIZE = 5`

```
Byte:   0       1       2       3       4
       +-------+-------+-------+-------+-------+
       | protocolVersion (BE)  | caps  | l2capPsm (BE) |
       |      UShort           | UByte |    UShort      |
       +-------+-------+-------+-------+-------+
```

| Offset | Size | Field | Type | Endianness | Description |
|--------|------|-------|------|------------|-------------|
| 0–1 | 2 | `protocolVersion` | UShort | **BE** | Protocol version number. |
| 2 | 1 | `capabilityFlags` | UByte | — | Bitfield. Bit 0 (`0x01`) = L2CAP support (`CAP_L2CAP`). |
| 3–4 | 2 | `l2capPsm` | UShort | **BE** | L2CAP PSM value; `0` if L2CAP is not supported. |

---

## Message Types

All framed messages begin with a 1-byte type code at offset 0.

| Code | Name | Constant | Fixed Size | Description |
|------|------|----------|------------|-------------|
| `0x00` | Handshake | `TYPE_HANDSHAKE` | Variable (min 2) | Noise XX handshake step. |
| `0x01` | Keepalive | `TYPE_KEEPALIVE` | 12+ | Link liveness probe. Has [TLV extensions](#tlv-extension-area). |
| `0x02` | Rotation Announcement | `TYPE_ROTATION` | 201 | Key rotation broadcast. |
| `0x03` | Hello / Route Request | `TYPE_HELLO` / `TYPE_ROUTE_REQUEST` | 33+ | Babel Hello / legacy AODV RREQ. Neighbor liveness and route discovery. Has [TLV extensions](#tlv-extension-area). |
| `0x04` | Update / Route Reply | `TYPE_UPDATE` / `TYPE_ROUTE_REPLY` | 32+ | Babel Update / legacy AODV RREP. Route propagation with public key. Has [TLV extensions](#tlv-extension-area). |
| `0x05` | Chunk | `TYPE_CHUNK` | Variable (min 19) | Fragment of a chunked transfer. First chunk (seq=0) has 21-byte header; subsequent chunks have 19-byte header. |
| `0x06` | Chunk ACK | `TYPE_CHUNK_ACK` | 37+ | Selective acknowledgment of chunks. Has [TLV extensions](#tlv-extension-area). |
| `0x07` | NACK | `TYPE_NACK` | 20+ | Negative acknowledgment with reason code. Has [TLV extensions](#tlv-extension-area). |
| `0x08` | Resume Request | `TYPE_RESUME_REQUEST` | 23+ | Request to resume a chunked transfer. Has [TLV extensions](#tlv-extension-area). |
| `0x09` | Broadcast | `TYPE_BROADCAST` | Variable (min 40) | Flood-fill broadcast to all nodes. |
| `0x0A` | Routed Message | `TYPE_ROUTED_MESSAGE` | Variable (min 52) | Unicast message forwarded along a route. Includes priority field. |
| `0x0B` | Delivery ACK | `TYPE_DELIVERY_ACK` | Variable (min 32) | End-to-end delivery confirmation. Has [TLV extensions](#tlv-extension-area). |

---

## TLV Extension Area

Seven fixed/known-length message types support a trailing **TLV (Type-Length-Value)
extension area** for backward-compatible schema evolution. The extension area is
defined and parsed by `TlvCodec.kt`.

### Wire Layout

The extension area is appended immediately after the fixed body of a message:

```
Byte:   +0              +1              +2
       +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-- ... --+
       |   extensionLength (2, LE)     | TLV entries ...        |
       +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-- ... --+
```

- **extensionLength** (UShort LE, 2 bytes) — total byte length of all TLV
  entries that follow. When no extensions are present, this is `0x00 0x00`
  (2 bytes of zero-overhead).
- Each TLV entry has a 3-byte header plus its value:

```
       +-------+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+--- ... ---+
       |  tag  |     length (2, LE)            |  value    |
       | 1 byte|        UShort                 | len bytes |
       +-------+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+--- ... ---+
```

| Field | Size | Type | Endianness | Description |
|-------|------|------|------------|-------------|
| `extensionLength` | 2 | UShort | **LE** | Total bytes of TLV entries (0 = no extensions). |
| `tag` | 1 | UByte | — | Extension tag identifier. |
| `length` | 2 | UShort | **LE** | Byte length of `value`. |
| `value` | `length` | bytes | — | Extension data. |

### Tag Ranges

| Range | Purpose |
|-------|---------|
| `0x00`–`0x7F` | Reserved for protocol-defined extensions. |
| `0x80`–`0xFF` | Available for application use. |

### Messages with TLV Extensions

The following 7 message types include a TLV extension area after their fixed body:

| Message | Type Code | Fixed Body Size | Extension Area Follows |
|---------|-----------|-----------------|----------------------|
| Keepalive | `0x01` | 10 bytes | After byte 9 |
| Chunk ACK | `0x06` | 35 bytes | After byte 34 |
| NACK | `0x07` | 18 bytes | After byte 17 |
| Resume Request | `0x08` | 21 bytes | After byte 20 |
| Route Request | `0x03` | 31 bytes | After byte 30 |
| Route Reply | `0x04` | 30 bytes | After byte 29 |
| Delivery ACK | `0x0B` | 30 bytes (+ optional 96-byte signature) | After body end |

### Messages WITHOUT TLV Extensions

| Message | Type Code | Reason |
|---------|-----------|--------|
| Chunk | `0x05` | Variable-length payload at end. |
| Broadcast | `0x09` | Variable-length payload at end. |
| Routed Message | `0x0A` | Variable-length payload at end. |
| Handshake | `0x00` | Noise protocol opaque bytes — structure is not MeshLink-defined. |
| Rotation Announcement | `0x02` | Fixed 201-byte signed payload; adding extensions would invalidate the signature. |

### Forward Compatibility

- **Unknown tags are preserved**, not dropped. A decoder encountering an
  unrecognized tag includes it in the parsed extension list so callers can
  forward it to other peers unchanged.
- **Encoding:** New encoders always append the TLV area (minimum 2 bytes
  `0x00 0x00` when empty).
- **Decoding:** If `data.size > FIXED_SIZE`, the decoder parses trailing TLV
  extensions. If `data.size == FIXED_SIZE`, the message is treated as a legacy
  frame with no extensions (backward compatible with pre-TLV peers).

---

## Message Formats

### 0x09 — Broadcast

Flood-fill message propagated through the mesh. Optionally signed with Ed25519.

**Source:** `WireCodec.kt` · `BROADCAST_HEADER_SIZE = 31`

```
Byte:   0       1                              12  13                 20
       +-------+---------- ... ----------------+---+--- ... ---------+
       | 0x09  |         messageId (12)         |    origin (8)      |
       +-------+---------- ... ----------------+---+--- ... ---------+

       21      22                     29  30
       +-------+---------- ... --------+-------+
       | rHops |     appIdHash (8)     | flags |
       +-------+---------- ... --------+-------+

       If flags bit 0 set (HAS_SIGNATURE):
       31                                      94  95                             126
       +---------- ... ------------------------+---------- ... ------------------+
       |         signature (64)                |       signerPublicKey (32)      |
       +---------- ... ------------------------+---------- ... ------------------+

       Followed by: payload (remaining bytes)
```

| Offset | Size | Field | Type | Endianness | Description |
|--------|------|-------|------|------------|-------------|
| 0 | 1 | `type` | byte | — | `0x09` |
| 1–16 | 16 | `messageId` | bytes | — | 16-byte random message identifier. |
| 17–28 | 12 | `origin` | bytes | — | Originator peer ID (truncated key hash). |
| 29 | 1 | `remainingHops` | UByte | — | TTL / remaining hop count. |
| 30–37 | 8 | `appIdHash` | bytes | — | Application identifier hash (zero-filled if unused). |
| 38 | 1 | `flags` | UByte | — | Bit 0: `HAS_SIGNATURE` (signature + signerPublicKey present). Bits 1–7: reserved. |
| 39 | 1 | `priority` | Byte (signed) | — | Message priority: `-1` = low, `0` = normal (default), `1` = high. |
| 40–103 | 64 | `signature` | bytes | — | Ed25519 signature (present only if `flags & 0x01`). |
| 104–135 | 32 | `signerPublicKey` | bytes | — | Ed25519 public key of signer (present only if `flags & 0x01`). |
| … | variable | `payload` | bytes | — | Application payload (remaining bytes). |

**Validation:**
- Minimum message size: 40 bytes (header with priority, `flags = 0x00`, empty payload).
- If `flags & 0x01`, the message must contain at least `40 + 64 + 32 = 136` bytes before the payload.

---

### 0x00 — Handshake

Wraps a single step of the Noise XX handshake protocol.

**Source:** `WireCodec.kt` · `HANDSHAKE_HEADER_SIZE = 2`

```
Byte:   0       1       2                              N
       +-------+-------+---------- ... ----------------+
       | 0x00  | step  |     noiseMessage (variable)    |
       +-------+-------+---------- ... ----------------+
```

| Offset | Size | Field | Type | Endianness | Description |
|--------|------|-------|------|------------|-------------|
| 0 | 1 | `type` | byte | — | `0x00` |
| 1 | 1 | `step` | UByte | — | Handshake step: `0`, `1`, or `2`. |
| 2–N | variable | `noiseMessage` | bytes | — | Noise protocol message for this step. |

**Validation:**
- `step` must be `0`, `1`, or `2`.
- Minimum message size: 2 bytes.

**Noise XX Handshake Payload (embedded in `noiseMessage`):**

See [Noise XX Handshake Payload](#noise-xx-handshake-payload-5-bytes) above for
the 5-byte payload carried inside the Noise framework messages.

---

### 0x03 — Route Request (RREQ)

Babel Hello or legacy AODV route discovery request. Flooded by the originator (or an intermediate
peer with no cached route) to discover a path to a destination. Each peer
that receives an RREQ records the reverse path back to the origin and
rebroadcasts unless it has already seen the same `requestId` from the same
origin.

**Source:** `WireCodec.kt` · Fixed body: **31 bytes** (+ TLV extension area)

```
Byte:   0       1            12  13           24 25          28 29   30  31  32
       +-------+---- ... ----+--+---- ... ----+--+--- ... ---+-----+-----+---+---+-- ... --+
       | 0x03  | origin (12) |  |  dest (12)  |  | reqId(4LE)| hops|limit|extLen | TLV ...  |
       +-------+---- ... ----+--+---- ... ----+--+--- ... ---+-----+-----+---+---+-- ... --+
```

| Offset | Size | Field | Type | Endianness | Description |
|--------|------|-------|------|------------|-------------|
| 0 | 1 | `type` | byte | — | `0x03` |
| 1–12 | 12 | `origin` | bytes | — | Peer ID of the originator. |
| 13–24 | 12 | `destination` | bytes | — | Peer ID of the desired destination. |
| 25–28 | 4 | `requestId` | UInt | **LE** | Unique request identifier (scoped to origin). |
| 29 | 1 | `hopCount` | UByte | — | Number of hops traversed so far. |
| 30 | 1 | `hopLimit` | UByte | — | Maximum hops (derived from `maxHops` config). |
| 31… | 2+ | `extensions` | [TLV](#tlv-extension-area) | **LE** | TLV extension area (minimum 2 bytes). |

**Validation:**
- Minimum size: 33 bytes (31-byte fixed body + 2-byte empty extension area).
- `hopCount` must be < `hopLimit`; otherwise the RREQ is dropped.
- Duplicate RREQs (same `origin` + `requestId`) are dropped.

---

### 0x04 — Route Reply (RREP)

Babel Update or legacy AODV route reply. Unicast back along the reverse path recorded during the
RREQ flood. Sent by the destination peer or by an intermediate peer that
already has a cached route to the destination.

**Source:** `WireCodec.kt` · Fixed body: **30 bytes** (+ TLV extension area)

```
Byte:   0       1            12  13           24 25          28 29  30  31
       +-------+---- ... ----+--+---- ... ----+--+--- ... ---+-----+---+---+-- ... --+
       | 0x04  | origin (12) |  |  dest (12)  |  | reqId(4LE)| hops|extLen | TLV ...  |
       +-------+---- ... ----+--+---- ... ----+--+--- ... ---+-----+---+---+-- ... --+
```

| Offset | Size | Field | Type | Endianness | Description |
|--------|------|-------|------|------------|-------------|
| 0 | 1 | `type` | byte | — | `0x04` |
| 1–12 | 12 | `origin` | bytes | — | Peer ID of the original originator. |
| 13–24 | 12 | `destination` | bytes | — | Peer ID of the destination. |
| 25–28 | 4 | `requestId` | UInt | **LE** | Request ID from the corresponding request. |
| 29 | 1 | `hopCount` | UByte | — | Number of hops (incremented at each relay). |
| 30… | 2+ | `extensions` | [TLV](#tlv-extension-area) | **LE** | TLV extension area (minimum 2 bytes). |

**Validation:**
- Minimum size: 32 bytes (30-byte fixed body + 2-byte empty extension area).

---

### 0x05 — Chunk

Fragment of a chunked (multi-part) message transfer. The first chunk
(`sequenceNumber = 0`) carries the `totalChunks` field; subsequent chunks omit
it, saving 2 bytes per chunk.

**Source:** `WireCodec.kt` · `CHUNK_HEADER_SIZE_FIRST = 21`, `CHUNK_HEADER_SIZE_SUBSEQUENT = 19`

**First chunk (seq = 0):**

```
Byte:   0       1                              16  17      18  19      20  21       N
       +-------+---------- ... ----------------+---+-------+---+-------+---+-- ... --+
       | 0x05  |         messageId (16)         | seqNum(LE)  |totalChk(LE)| payload  |
       +-------+---------- ... ----------------+---+-------+---+-------+---+-- ... --+
```

**Subsequent chunks (seq > 0):**

```
Byte:   0       1                              16  17      18  19       N
       +-------+---------- ... ----------------+---+-------+---+-- ... --+
       | 0x05  |         messageId (16)         | seqNum(LE)  | payload  |
       +-------+---------- ... ----------------+---+-------+---+-- ... --+
```

| Offset | Size | Field | Type | Endianness | Description |
|--------|------|-------|------|------------|-------------|
| 0 | 1 | `type` | byte | — | `0x05` |
| 1–16 | 16 | `messageId` | bytes | — | Identifies the overall message this chunk belongs to (16-byte random). |
| 17–18 | 2 | `sequenceNumber` | UShort | **LE** | Zero-based chunk index. |
| 19–20 | 2 | `totalChunks` | UShort | **LE** | Total number of chunks in this message. **Present only when `sequenceNumber = 0`.** |
| 19 or 21 | variable | `payload` | bytes | — | Chunk payload data. Starts at offset 21 for first chunk, offset 19 for subsequent chunks. |

**Validation:**
- When `sequenceNumber = 0`: `totalChunks` must be present and `sequenceNumber < totalChunks`.
- Minimum message size: 21 bytes for first chunk, 19 bytes for subsequent chunks.
- The receiver must store `totalChunks` from the first chunk and use it for all subsequent chunks of the same transfer.

---

### 0x06 — Chunk ACK

Selective acknowledgment for a chunked transfer, using a cumulative ACK
sequence number plus a 128-bit SACK bitmask (two ULong fields) for
out-of-order reception. Covers up to 128 chunks beyond `ackSequence`.

**Source:** `WireCodec.kt` · `CHUNK_ACK_SIZE = 35` (+ TLV extension area)

```
Byte:   0       1                              16  17      18  19                     26  27                     34  35  36
       +-------+---------- ... ----------------+---+-------+---+---------- ... --------+---+---------- ... --------+---+---+-- ... --+
       | 0x06  |         messageId (16)         |ackSeq (LE)  | sackBitmask (8, LE)    | sackBitmaskHigh (8, LE)  |extLen | TLV ...  |
       +-------+---------- ... ----------------+---+-------+---+---------- ... --------+---+---------- ... --------+---+---+-- ... --+
```

| Offset | Size | Field | Type | Endianness | Description |
|--------|------|-------|------|------------|-------------|
| 0 | 1 | `type` | byte | — | `0x06` |
| 1–16 | 16 | `messageId` | bytes | — | Message ID being acknowledged. |
| 17–18 | 2 | `ackSequence` | UShort | **LE** | Cumulative ACK: all chunks up to this number received. |
| 19–26 | 8 | `sackBitmask` | ULong | **LE** | Low 64 bits: SACK for chunks at offsets 0–63 beyond `ackSequence`. |
| 27–34 | 8 | `sackBitmaskHigh` | ULong | **LE** | High 64 bits: SACK for chunks at offsets 64–127 beyond `ackSequence`. |
| 35… | 2+ | `extensions` | [TLV](#tlv-extension-area) | **LE** | TLV extension area (minimum 2 bytes). |

**Minimum size:** 37 bytes (35-byte fixed body + 2-byte empty extension area).

---

### 0x0A — Routed Message

Unicast message forwarded hop-by-hop along a computed route. Includes a visited
list for loop detection.

**Source:** `WireCodec.kt` · `ROUTED_HEADER_SIZE = 39`

```
Byte:   0       1                 12  13         20  21         28
       +-------+------ ... ------+---+-- ... ---+---+-- ... ---+
       | 0x0A  |  messageId (12) |  origin (8)  | destination(8)|
       +-------+------ ... ------+---+-- ... ---+---+-- ... ---+

       29      30                             37  38
       +-------+---------- ... ----------------+-------+
       | hLim  |    replayCounter (8, LE)      | vCnt  |
       +-------+---------- ... ----------------+-------+

       For each of `vCnt` visited entries (8 bytes each):
       +------- ... --------+
       |  visited peer (8)  |
       +------- ... --------+

       Followed by: payload (remaining bytes)
```

| Offset | Size | Field | Type | Endianness | Description |
|--------|------|-------|------|------------|-------------|
| 0 | 1 | `type` | byte | — | `0x0A` |
| 1–16 | 16 | `messageId` | bytes | — | 16-byte random message identifier. |
| 17–28 | 12 | `origin` | bytes | — | Originator peer ID (truncated SHA-256). |
| 29–40 | 12 | `destination` | bytes | — | Destination peer ID (truncated SHA-256). |
| 41 | 1 | `hopLimit` | UByte | — | Maximum remaining hops. |
| 42–49 | 8 | `replayCounter` | ULong | **LE** | Monotonic counter for replay protection. Default `0`. |
| 50 | 1 | `visitedCount` | UByte | — | Number of visited-list entries (0–255). |
| 51… | `vCnt × 12` | `visitedList` | bytes | — | Peer IDs already visited (loop detection). |
| … | 1 | `priority` | Byte (signed) | — | Message priority: `-1` = low, `0` = normal (default), `1` = high. |
| … | variable | `payload` | bytes | — | Application payload (remaining bytes). |

**Validation:**
- Minimum message size: 39 bytes (zero visited entries, empty payload).
- `visitedCount` entries must fit within the remaining data.

---

### 0x0B — Delivery ACK

End-to-end delivery confirmation, optionally signed for non-repudiation.

**Source:** `WireCodec.kt` · `DELIVERY_ACK_HEADER_SIZE = 30` (+ optional signature + TLV extension area)

```
Byte:   0       1                              16  17                 28  29
       +-------+---------- ... ----------------+---+--- ... ---------+-------+
       | 0x0B  |         messageId (16)         |   recipientId (12) | flags |
       +-------+---------- ... ----------------+---+--- ... ---------+-------+

       If flags bit 0 set (HAS_SIGNATURE):
       30                                      93  94                             125
       +---------- ... ------------------------+---------- ... ------------------+
       |         signature (64)                |     signerPublicKey (32)        |
       +---------- ... ------------------------+---------- ... ------------------+

       After body end (offset 30 or 126):
       +-------+-- ... --+
       |extLen | TLV ... |
       +-------+-- ... --+
```

| Offset | Size | Field | Type | Endianness | Description |
|--------|------|-------|------|------------|-------------|
| 0 | 1 | `type` | byte | — | `0x0B` |
| 1–16 | 16 | `messageId` | bytes | — | ID of the message being acknowledged. |
| 17–28 | 12 | `recipientId` | bytes | — | Peer ID of the recipient confirming delivery. |
| 29 | 1 | `flags` | UByte | — | Bit 0: `HAS_SIGNATURE`. Bits 1–7: reserved. |
| 30–93 | 64 | `signature` | bytes | — | Ed25519 signature (present only if `flags & 0x01`). |
| 94–125 | 32 | `signerPublicKey` | bytes | — | Ed25519 public key (present only if `flags & 0x01`). |
| … | 2+ | `extensions` | [TLV](#tlv-extension-area) | **LE** | TLV extension area (minimum 2 bytes). Starts at offset 30 (unsigned) or 126 (signed). |

**Validation:**
- Minimum message size: 32 bytes (30-byte header + 2-byte empty extension area).
- If `flags & 0x01`, message must contain at least `30 + 64 + 32 + 2 = 128` bytes.

---

### 0x08 — Resume Request

Requests resumption of an interrupted chunked transfer, indicating how many
bytes have already been received.

**Source:** `WireCodec.kt` · `RESUME_REQUEST_SIZE = 21` (+ TLV extension area)

```
Byte:   0       1                              16  17                  20  21  22
       +-------+---------- ... ----------------+---+-------+-------+---+---+---+-- ... --+
       | 0x08  |         messageId (16)         | bytesReceived (4,LE) |extLen | TLV ...  |
       +-------+---------- ... ----------------+---+-------+-------+---+---+---+-- ... --+
```

| Offset | Size | Field | Type | Endianness | Description |
|--------|------|-------|------|------------|-------------|
| 0 | 1 | `type` | byte | — | `0x08` |
| 1–16 | 16 | `messageId` | bytes | — | ID of the message to resume. |
| 17–20 | 4 | `bytesReceived` | UInt | **LE** | Number of bytes already received. |
| 21… | 2+ | `extensions` | [TLV](#tlv-extension-area) | **LE** | TLV extension area (minimum 2 bytes). |

**Minimum size:** 23 bytes (21-byte fixed body + 2-byte empty extension area).

---

### 0x01 — Keepalive

Lightweight link liveness probe with a timestamp and optional flags.

**Source:** `WireCodec.kt` · `KEEPALIVE_SIZE = 10` (+ TLV extension area)

```
Byte:   0       1       2                                       9  10  11
       +-------+-------+-------+-------+-------+-------+-------+-------+-------+-------+---+---+-- ... --+
       | 0x01  | flags |              timestampMillis (8, LE)                            |extLen | TLV ...  |
       +-------+-------+-------+-------+-------+-------+-------+-------+-------+-------+---+---+-- ... --+
```

| Offset | Size | Field | Type | Endianness | Description |
|--------|------|-------|------|------------|-------------|
| 0 | 1 | `type` | byte | — | `0x01` |
| 1 | 1 | `flags` | UByte | — | Reserved flags. Default `0x00`. |
| 2–9 | 8 | `timestampMillis` | ULong | **LE** | Unix timestamp in milliseconds. |
| 10… | 2+ | `extensions` | [TLV](#tlv-extension-area) | **LE** | TLV extension area (minimum 2 bytes). |

**Minimum size:** 12 bytes (10-byte fixed body + 2-byte empty extension area).

---

### 0x07 — NACK

Negative acknowledgment indicating that a message could not be delivered or
processed. Includes a reason code so the sender can distinguish failure modes.

**Source:** `WireCodec.kt` · `NACK_SIZE = 18` (+ TLV extension area)

```
Byte:   0       1                              16      17  18  19
       +-------+---------- ... ----------------+-------+---+---+-- ... --+
       | 0x07  |         messageId (16)         |reason |extLen | TLV ...  |
       +-------+---------- ... ----------------+-------+---+---+-- ... --+
```

| Offset | Size | Field | Type | Endianness | Description |
|--------|------|-------|------|------------|-------------|
| 0 | 1 | `type` | byte | — | `0x07` |
| 1–16 | 16 | `messageId` | bytes | — | ID of the message being negatively acknowledged. |
| 17 | 1 | `reason` | UByte | — | Reason code: `0` = unknown, `1` = buffer full, `2` = unknown destination, `3` = decryption failed, `4` = rate limited. |
| 18… | 2+ | `extensions` | [TLV](#tlv-extension-area) | **LE** | TLV extension area (minimum 2 bytes). |

**Minimum size:** 20 bytes (18-byte fixed body + 2-byte empty extension area).

---

### 0x02 — Rotation Announcement

Announces a key rotation event. The old identity signs the announcement to prove
the transition is authorized.

**Source:** `RotationAnnouncement.kt` · `SIZE = 201`

```
Byte:   0       1                              32  33                             64
       +-------+---------- ... ----------------+---+---------- ... ----------------+
       | 0x02  |     oldX25519Key (32)          |      newX25519Key (32)           |
       +-------+---------- ... ----------------+---+---------- ... ----------------+

       65                             96  97                            128
       +---------- ... ----------------+---+---------- ... ----------------+
       |      oldEd25519Key (32)       |      newEd25519Key (32)          |
       +---------- ... ----------------+---+---------- ... ----------------+

       129                           136  137                           200
       +---------- ... ----------------+---+---------- ... ----------------+
       | timestampMillis (8, BE ULong)     |        signature (64)            |
       +---------- ... ----------------+---+---------- ... ----------------+
```

| Offset | Size | Field | Type | Endianness | Description |
|--------|------|-------|------|------------|-------------|
| 0 | 1 | `type` | byte | — | `0x02` |
| 1–32 | 32 | `oldX25519Key` | bytes | — | Previous X25519 (Diffie-Hellman) public key. |
| 33–64 | 32 | `newX25519Key` | bytes | — | New X25519 public key. |
| 65–96 | 32 | `oldEd25519Key` | bytes | — | Previous Ed25519 (signing) public key. |
| 97–128 | 32 | `newEd25519Key` | bytes | — | New Ed25519 public key. |
| 129–136 | 8 | `timestampMillis` | ULong | **BE** | Rotation timestamp in milliseconds since Unix epoch. |
| 137–200 | 64 | `signature` | bytes | — | Ed25519 signature over bytes 1–136 (the signable payload). |

**Signable payload:** bytes 1–136 of the wire format (4 keys + timestamp, 136
bytes total), excluding the type byte and the signature itself.

**Signature verification:** The signature is produced and verified using the
**old** Ed25519 key (`oldEd25519Key`), proving that the holder of the previous
identity authorized the rotation.

**Fixed size:** 201 bytes.

---

## Reserved Type Codes

Type codes `0x0C`–`0xFF` are **reserved** for future use. Implementations
receiving a message with a reserved type code must drop the message and emit an
`UNKNOWN_MESSAGE_TYPE` diagnostic.

---

## Compression Envelope (Inside Encrypted Payload)

The compression envelope is **not** a wire-level message type — it sits
**inside** the encrypted payload of Routed Messages (type `0x0A`). Compression
is applied **before** encryption (`compress → encrypt` on send;
`decrypt → decompress` on receive) and is therefore transparent to relays and
the wire format byte tables above.

When `compressionEnabled = true` (the default), every payload is wrapped in a
1-byte envelope prefix that signals whether the content is compressed:

### Uncompressed Envelope (`0x00`)

Used when the payload size is below `compressionMinBytes` (default 128) or when
compression did not reduce the payload size.

```
Byte:  0       1                    N
      +-------+------- ... --------+
      | 0x00  |     payload (N-1)  |
      +-------+------- ... --------+
```

| Offset | Size | Field | Type | Endianness | Description |
|--------|------|-------|------|------------|-------------|
| 0 | 1 | `envelopeType` | byte | — | `0x00` — payload is uncompressed. |
| 1… | variable | `payload` | bytes | — | Original uncompressed payload. |

### Compressed Envelope (`0x01`)

Used when the payload is ≥ `compressionMinBytes` and DEFLATE reduces its size.

```
Byte:  0       1                  4   5                          N
      +-------+------ ... -------+---+----------- ... ----------+
      | 0x01  | originalSize(4,LE)|   | DEFLATE compressed data |
      +-------+------ ... -------+---+----------- ... ----------+
```

| Offset | Size | Field | Type | Endianness | Description |
|--------|------|-------|------|------------|-------------|
| 0 | 1 | `envelopeType` | byte | — | `0x01` — payload is DEFLATE-compressed. |
| 1–4 | 4 | `originalSize` | UInt | **LE** | Original uncompressed payload size in bytes. |
| 5… | variable | `compressedData` | bytes | — | Raw DEFLATE (RFC 1951) compressed payload. |

### Backward Compatibility

When `compressionEnabled = false`, no envelope is added — the payload is the
raw application data, identical to pre-compression protocol behavior. Peers
that do not support compression will not encounter the envelope prefix because
the envelope is inside the E2E encrypted payload and only visible after
decryption by peers running the compression-aware version.

---

## Security Considerations

### Ed25519 Signatures

- **Broadcast** and **Delivery ACK** messages support optional Ed25519
  signatures. When present, the signature block contains the 64-byte signature
  followed by the 32-byte signer public key.
- **Rotation Announcement** messages are always signed by the old Ed25519 key
  to authorize key transitions.

### Noise XX Handshake

The Handshake message (type `0x00`) wraps the Noise XX protocol, which provides:

- Mutual authentication of both peers.
- Forward secrecy via ephemeral Diffie-Hellman key exchange.
- Encrypted transport after the 3-step handshake completes (steps 0, 1, 2).

The 5-byte `HandshakePayload` embedded within Noise messages enables protocol
version negotiation and capability exchange (e.g., L2CAP support).

### Replay Protection

- **Routed Messages** include an 8-byte `replayCounter` (little-endian ULong)
  that should be monotonically increasing per origin. Receivers should reject
  messages with a replay counter at or below the last accepted value.
- **Rotation Announcements** include a `timestampMillis` field that can be used to
  reject stale announcements.

### Loop Detection

- **Routed Messages** maintain a `visitedList` of peer IDs (up to 255 entries)
  that have forwarded the message. Nodes must not forward a message if their own
  ID appears in the visited list.

### Key Identity

- Peer identity is derived from the **first 8 bytes of SHA-256(X25519 public
  key)** in BLE advertisements (see Advertisement Payload).
- Framed messages use **12-byte** truncated key hashes as peer identifiers.

---

## Appendix: Byte-Order Helper Functions

The wire codec uses the following byte-ordering utilities. Both big-endian and
little-endian helpers are used depending on the field.

### Big-Endian (Network Order)

Used for: Rotation `timestampMillis`, Handshake
payload fields.

| Function | Width | Description |
|----------|-------|-------------|
| `putUIntBE(offset, value)` | 4 bytes | Writes a `UInt` in big-endian order: MSB at `offset`. |
| `getUIntBE(offset)` | 4 bytes | Reads a `UInt` in big-endian order. |
| `putULongBE(offset, value)` | 8 bytes | Writes a `ULong` in big-endian order (Rotation Announcement). |
| `getULongBE(offset)` | 8 bytes | Reads a `ULong` in big-endian order. |

### Little-Endian

Used for: Chunk `sequenceNumber`/`totalChunks`, Chunk ACK `ackSequence`/
`sackBitmask`/`sackBitmaskHigh`, Routed Message `replayCounter`,
Route Request/Reply `requestId`, Resume
Request `bytesReceived`, Keepalive `timestampMillis`.

| Function | Width | Description |
|----------|-------|-------------|
| `putUShortLE(offset, value)` | 2 bytes | Writes a `UShort` in little-endian order: LSB at `offset`. |
| `getUShortLE(offset)` | 2 bytes | Reads a `UShort` in little-endian order. |
| `putUIntLE(offset, value)` | 4 bytes | Writes a `UInt` in little-endian order. |
| `getUIntLE(offset)` | 4 bytes | Reads a `UInt` in little-endian order. |
| `putULongLE(offset, value)` | 8 bytes | Writes a `ULong` in little-endian order. |
| `getULongLE(offset)` | 8 bytes | Reads a `ULong` in little-endian order. |
| `putDoubleBitsLE(offset, value)` | 8 bytes | Writes an IEEE 754 `Double` as raw bits in little-endian order. |
| `getDoubleBitsLE(offset)` | 8 bytes | Reads an IEEE 754 `Double` from raw bits in little-endian order. |

---

## Appendix: Endianness Summary

Quick reference for the byte order of every multi-byte field in the protocol.

| Message | Field | Width | Endianness |
|---------|-------|-------|------------|
| Advertisement | `versionMajor` / `powerMode` | 4+4 bits | N/A (bit fields) |
| Handshake Payload | `protocolVersion` | 2 | **BE** |
| Handshake Payload | `l2capPsm` | 2 | **BE** |
| Chunk | `sequenceNumber` | 2 | **LE** |
| Chunk | `totalChunks` | 2 | **LE** (seq=0 only) |
| Chunk ACK | `ackSequence` | 2 | **LE** |
| Chunk ACK | `sackBitmask` | 8 | **LE** |
| Chunk ACK | `sackBitmaskHigh` | 8 | **LE** |
| Routed Message | `replayCounter` | 8 | **LE** |
| Route Request | `requestId` | 4 | **LE** |
| Route Reply | `requestId` | 4 | **LE** |
| Resume Request | `bytesReceived` | 4 | **LE** |
| Keepalive | `timestampMillis` | 8 | **LE** |
| Rotation | `timestampMillis` | 8 | **BE** |
| TLV Extension Area | `extensionLength` | 2 | **LE** |
| TLV Entry | `length` | 2 | **LE** |
