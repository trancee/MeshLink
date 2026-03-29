# MeshLink Wire Format Specification

## Version

Protocol version: 1.0

## Overview

MeshLink uses a compact binary wire protocol designed for Bluetooth Low Energy
(BLE) mesh networking. Every framed message begins with a single **type byte**
(offset 0) that acts as the message discriminator. Separate from the framed
messages, the protocol defines a **17-byte BLE advertisement payload** and a
**5-byte Noise XX handshake payload**.

## Conventions

| Convention | Rule |
|---|---|
| **Byte ordering** | Varies per field — each field table states **BE** (big-endian / network order) or **LE** (little-endian). |
| **Type discriminator** | All framed messages share byte 0 as the type code. |
| **Sizes** | All sizes are in bytes unless stated otherwise. Multi-byte integers are unsigned unless noted. |
| **Key hashes** | Node identifiers and visited-list entries are 16-byte values (truncated SHA-256). |
| **Message IDs** | 16-byte random or pseudo-random identifiers. |
| **Signature blocks** | Ed25519 signatures are 64 bytes; Ed25519 public keys are 32 bytes. |

---

## BLE Advertisement Payload (17 bytes)

Broadcast over BLE advertising channels for peer discovery. Not framed with a
type byte — this is a standalone payload.

**Source:** `AdvertisementCodec.kt` · `SIZE = 17`

```
Byte:   0               1               2                              16
       +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-- ... --+-+-+-+-+
       |MajVer |PwrMode|  VersionMinor |       Truncated SHA-256        |
       |4 bits |4 bits |   (8 bits)    |   of X25519 public key         |
       +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-- ... --+-+-+-+-+
       |<--- 1 byte --->|<-- 1 byte -->|<-------- 15 bytes ------------>|
```

| Offset | Size | Field | Type | Endianness | Description |
|--------|------|-------|------|------------|-------------|
| 0 [7:4] | 4 bits | `versionMajor` | uint | — | Protocol major version (0–15). |
| 0 [3:0] | 4 bits | `powerMode` | uint | — | Power mode indicator (0–15). |
| 1 | 1 | `versionMinor` | uint8 | — | Protocol minor version (0–255). |
| 2–16 | 15 | `keyHash` | bytes | — | First 15 bytes of `SHA-256(X25519_public_key)`. |

**Encoding:** `byte0 = (versionMajor << 4) | powerMode`. Both nibble values are
clamped to their respective ranges via `coerceIn`.

**Decoding:** `versionMajor = byte0 >>> 4`, `powerMode = byte0 & 0x0F`.

**Why 15 bytes for the key hash?** The advertisement payload is constrained to 17
bytes so it fits within the BLE 4.0 advertising data limit of 31 bytes (leaving
room for the mandatory AD structure overhead — length byte, AD type, flags). The
first 2 bytes carry version and power mode metadata, leaving exactly 15 bytes for
the key hash. A 15-byte (120-bit) truncation of SHA-256 still provides ~2⁶⁰
collision resistance (birthday bound), which is far beyond the practical mesh
sizes MeshLink targets. The hash serves as a probabilistic identifier for peer
key matching — full key exchange happens during the Noise XX handshake, where the
complete 32-byte X25519 public key is verified.

---

## Noise XX Handshake Payload (5 bytes)

Carried **inside** the Noise XX handshake messages for protocol version
negotiation and capability exchange. This is not a standalone framed message; it
is embedded in the `noiseMessage` field of a Handshake message (type `0x01`).

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
| `0x00` | Broadcast | `TYPE_BROADCAST` | Variable (min 51) | Flood-fill broadcast to all nodes. |
| `0x01` | Handshake | `TYPE_HANDSHAKE` | Variable (min 2) | Noise XX handshake step. |
| `0x02` | Route Update | `TYPE_ROUTE_UPDATE` | Variable (min 18) | Distance-vector routing table exchange. |
| `0x03` | Chunk | `TYPE_CHUNK` | Variable (min 21) | Fragment of a chunked transfer. |
| `0x04` | Chunk ACK | `TYPE_CHUNK_ACK` | 27 | Selective acknowledgment of chunks. |
| `0x05` | Routed Message | `TYPE_ROUTED_MESSAGE` | Variable (min 59) | Unicast message forwarded along a route. |
| `0x06` | Delivery ACK | `TYPE_DELIVERY_ACK` | Variable (min 34) | End-to-end delivery confirmation. |
| `0x07` | Resume Request | `TYPE_RESUME_REQUEST` | 21 | Request to resume a chunked transfer. |
| `0x08` | Keepalive | `TYPE_KEEPALIVE` | 6 | Link liveness probe. |
| `0x09` | NACK | `TYPE_NACK` | 17 | Negative acknowledgment. |
| `0x0A` | Rotation Announcement | `TYPE_ROTATION` | 201 | Key rotation broadcast. |

---

## Message Formats

### 0x00 — Broadcast

Flood-fill message propagated through the mesh. Optionally signed with Ed25519.

**Source:** `WireCodec.kt` · `BROADCAST_HEADER_SIZE = 51`

```
Byte:   0       1                              16  17                             32
       +-------+---------- ... ----------------+---+---------- ... ----------------+
       | 0x00  |         messageId (16)         |          origin (16)             |
       +-------+---------- ... ----------------+---+---------- ... ----------------+

       33      34                             49  50
       +-------+---------- ... ----------------+-------+
       | rHops |        appIdHash (16)         | sigLen|
       +-------+---------- ... ----------------+-------+

       If sigLen > 0:
       51                             51+sigLen-1  51+sigLen                  51+sigLen+31
       +---------- ... ----------------+-----------+---------- ... ----------+
       |       signature (sigLen)      |       signerPublicKey (32)         |
       +---------- ... ----------------+-----------+---------- ... ----------+

       Followed by: payload (remaining bytes)
```

| Offset | Size | Field | Type | Endianness | Description |
|--------|------|-------|------|------------|-------------|
| 0 | 1 | `type` | byte | — | `0x00` |
| 1–16 | 16 | `messageId` | bytes | — | Unique message identifier. |
| 17–32 | 16 | `origin` | bytes | — | Originator node ID (truncated key hash). |
| 33 | 1 | `remainingHops` | UByte | — | TTL / remaining hop count. |
| 34–49 | 16 | `appIdHash` | bytes | — | Application identifier hash (zero-filled if unused). |
| 50 | 1 | `sigLen` | UByte | — | Length of the signature field. `0` = unsigned. Typically `64` for Ed25519. |
| 51… | `sigLen` | `signature` | bytes | — | Ed25519 signature (present only if `sigLen > 0`). |
| 51+sigLen… | 32 | `signerPublicKey` | bytes | — | Ed25519 public key of signer (present only if `sigLen > 0`). |
| … | variable | `payload` | bytes | — | Application payload (remaining bytes). |

**Validation:**
- Minimum message size: 51 bytes (header only, `sigLen = 0`, empty payload).
- If `sigLen > 0`, the message must contain at least `51 + sigLen + 32` bytes before the payload.

---

### 0x01 — Handshake

Wraps a single step of the Noise XX handshake protocol.

**Source:** `WireCodec.kt` · `HANDSHAKE_HEADER_SIZE = 2`

```
Byte:   0       1       2                              N
       +-------+-------+---------- ... ----------------+
       | 0x01  | step  |     noiseMessage (variable)    |
       +-------+-------+---------- ... ----------------+
```

| Offset | Size | Field | Type | Endianness | Description |
|--------|------|-------|------|------------|-------------|
| 0 | 1 | `type` | byte | — | `0x01` |
| 1 | 1 | `step` | UByte | — | Handshake step: `0`, `1`, or `2`. |
| 2–N | variable | `noiseMessage` | bytes | — | Noise protocol message for this step. |

**Validation:**
- `step` must be `0`, `1`, or `2`.
- Minimum message size: 2 bytes.

**Noise XX Handshake Payload (embedded in `noiseMessage`):**

See [Noise XX Handshake Payload](#noise-xx-handshake-payload-5-bytes) above for
the 5-byte payload carried inside the Noise framework messages.

---

### 0x02 — Route Update

Distance-vector routing table exchange. Comes in two variants: unsigned and
signed.

**Source:** `WireCodec.kt` · `ROUTE_UPDATE_HEADER_SIZE = 18`, `ROUTE_ENTRY_SIZE = 29`

#### Unsigned Variant

```
Byte:   0       1                              16  17
       +-------+---------- ... ----------------+-------+
       | 0x02  |         senderId (16)         | count |
       +-------+---------- ... ----------------+-------+

       For each of `count` entries (29 bytes each):
       +---------- ... ----+---------- ... ----+-------+-------+-------+
       | destination (16)  |   cost (8, LE)    | seqNum (4, LE)| hops  |
       +---------- ... ----+---------- ... ----+-------+-------+-------+
```

#### Signed Variant

The signed variant appends the signer's public key and signature after all
entries:

```
       ... entries ...
       +---------- ... ----------+---------- ... ----------+
       |  signerPublicKey (32)   |     signature (64)      |
       +---------- ... ----------+---------- ... ----------+
```

**Total signed size:** `18 + (count × 29) + 32 + 64`

| Offset | Size | Field | Type | Endianness | Description |
|--------|------|-------|------|------------|-------------|
| 0 | 1 | `type` | byte | — | `0x02` |
| 1–16 | 16 | `senderId` | bytes | — | Sender node ID. |
| 17 | 1 | `entryCount` | UByte | — | Number of route entries (0–255). |

**Route Entry (29 bytes each):**

| Entry Offset | Size | Field | Type | Endianness | Description |
|--------------|------|-------|------|------------|-------------|
| +0 | 16 | `destination` | bytes | — | Destination node ID. |
| +16 | 8 | `cost` | Double | **LE** | Route cost (IEEE 754 double, bit-cast). Must be finite and ≥ 0. |
| +24 | 4 | `sequenceNumber` | UInt | **LE** | Route sequence number. |
| +28 | 1 | `hopCount` | UByte | — | Number of hops to destination. |

**Trailing signature (signed variant only):**

| Offset | Size | Field | Type | Endianness | Description |
|--------|------|-------|------|------------|-------------|
| end of entries | 32 | `signerPublicKey` | bytes | — | Ed25519 public key of signer. |
| +32 | 64 | `signature` | bytes | — | Ed25519 signature over the unsigned message bytes. |

**Validation:**
- Minimum message size: 18 bytes (zero entries).
- Route cost must be finite and non-negative.
- Signed variant is detected by checking if `remaining bytes ≥ 96` after all entries.

---

### 0x03 — Chunk

Fragment of a chunked (multi-part) message transfer.

**Source:** `WireCodec.kt` · `CHUNK_HEADER_SIZE = 21`

```
Byte:   0       1                              16  17      18  19      20  21       N
       +-------+---------- ... ----------------+---+-------+---+-------+---+-- ... --+
       | 0x03  |         messageId (16)         | seqNum(LE)  |totalChk(LE)| payload  |
       +-------+---------- ... ----------------+---+-------+---+-------+---+-- ... --+
```

| Offset | Size | Field | Type | Endianness | Description |
|--------|------|-------|------|------------|-------------|
| 0 | 1 | `type` | byte | — | `0x03` |
| 1–16 | 16 | `messageId` | bytes | — | Identifies the overall message this chunk belongs to. |
| 17–18 | 2 | `sequenceNumber` | UShort | **LE** | Zero-based chunk index. |
| 19–20 | 2 | `totalChunks` | UShort | **LE** | Total number of chunks in this message. |
| 21–N | variable | `payload` | bytes | — | Chunk payload data. |

**Validation:**
- `sequenceNumber` must be strictly less than `totalChunks`.
- Minimum message size: 21 bytes (empty payload).

---

### 0x04 — Chunk ACK

Selective acknowledgment for a chunked transfer, using a cumulative ACK
sequence number plus a 64-bit SACK bitmask for out-of-order reception.

**Source:** `WireCodec.kt` · `CHUNK_ACK_SIZE = 27`

```
Byte:   0       1                              16  17      18  19                     26
       +-------+---------- ... ----------------+---+-------+---+---------- ... --------+
       | 0x04  |         messageId (16)         |ackSeq (LE)  |   sackBitmask (8, LE)  |
       +-------+---------- ... ----------------+---+-------+---+---------- ... --------+
```

| Offset | Size | Field | Type | Endianness | Description |
|--------|------|-------|------|------------|-------------|
| 0 | 1 | `type` | byte | — | `0x04` |
| 1–16 | 16 | `messageId` | bytes | — | Message ID being acknowledged. |
| 17–18 | 2 | `ackSequence` | UShort | **LE** | Cumulative ACK: all chunks up to this number received. |
| 19–26 | 8 | `sackBitmask` | ULong | **LE** | Selective ACK bitmask for chunks beyond `ackSequence`. |

**Fixed size:** 27 bytes.

---

### 0x05 — Routed Message

Unicast message forwarded hop-by-hop along a computed route. Includes a visited
list for loop detection.

**Source:** `WireCodec.kt` · `ROUTED_HEADER_SIZE = 59`

```
Byte:   0       1                 16  17                32  33                48
       +-------+------ ... ------+---+------ ... ------+---+------ ... ------+
       | 0x05  |  messageId (16) |     origin (16)     |   destination (16)  |
       +-------+------ ... ------+---+------ ... ------+---+------ ... ------+

       49      50                             57  58
       +-------+---------- ... ----------------+-------+
       | hLim  |    replayCounter (8, LE)      | vCnt  |
       +-------+---------- ... ----------------+-------+

       For each of `vCnt` visited entries (16 bytes each):
       +---------- ... ----------+
       |    visited node (16)    |
       +---------- ... ----------+

       Followed by: payload (remaining bytes)
```

| Offset | Size | Field | Type | Endianness | Description |
|--------|------|-------|------|------------|-------------|
| 0 | 1 | `type` | byte | — | `0x05` |
| 1–16 | 16 | `messageId` | bytes | — | Unique message identifier. |
| 17–32 | 16 | `origin` | bytes | — | Originator node ID. |
| 33–48 | 16 | `destination` | bytes | — | Destination node ID. |
| 49 | 1 | `hopLimit` | UByte | — | Maximum remaining hops. |
| 50–57 | 8 | `replayCounter` | ULong | **LE** | Monotonic counter for replay protection. Default `0`. |
| 58 | 1 | `visitedCount` | UByte | — | Number of visited-list entries (0–255). |
| 59… | `vCnt × 16` | `visitedList` | bytes | — | Node IDs already visited (loop detection). |
| … | variable | `payload` | bytes | — | Application payload (remaining bytes). |

**Validation:**
- Minimum message size: 59 bytes (zero visited entries, empty payload).
- `visitedCount` entries must fit within the remaining data.

---

### 0x06 — Delivery ACK

End-to-end delivery confirmation, optionally signed for non-repudiation.

**Source:** `WireCodec.kt` · `DELIVERY_ACK_HEADER_SIZE = 34`

```
Byte:   0       1                              16  17                             32  33
       +-------+---------- ... ----------------+---+---------- ... ----------------+-------+
       | 0x06  |         messageId (16)         |       recipientId (16)           | sigLen|
       +-------+---------- ... ----------------+---+---------- ... ----------------+-------+

       If sigLen > 0:
       34                        34+sigLen-1  34+sigLen              34+sigLen+31
       +---------- ... ----------+-----------+---------- ... --------+
       |    signature (sigLen)   |     signerPublicKey (32)          |
       +---------- ... ----------+-----------+---------- ... --------+
```

| Offset | Size | Field | Type | Endianness | Description |
|--------|------|-------|------|------------|-------------|
| 0 | 1 | `type` | byte | — | `0x06` |
| 1–16 | 16 | `messageId` | bytes | — | ID of the message being acknowledged. |
| 17–32 | 16 | `recipientId` | bytes | — | Node ID of the recipient confirming delivery. |
| 33 | 1 | `sigLen` | UByte | — | Signature length. `0` = unsigned. Typically `64`. |
| 34… | `sigLen` | `signature` | bytes | — | Ed25519 signature (present only if `sigLen > 0`). |
| 34+sigLen… | 32 | `signerPublicKey` | bytes | — | Ed25519 public key (present only if `sigLen > 0`). |

**Validation:**
- Minimum message size: 34 bytes.
- If `sigLen > 0`, message must contain at least `34 + sigLen + 32` bytes.

---

### 0x07 — Resume Request

Requests resumption of an interrupted chunked transfer, indicating how many
bytes have already been received.

**Source:** `WireCodec.kt` · `RESUME_REQUEST_SIZE = 21`

```
Byte:   0       1                              16  17                  20
       +-------+---------- ... ----------------+---+-------+-------+---+
       | 0x07  |         messageId (16)         | bytesReceived (4,LE) |
       +-------+---------- ... ----------------+---+-------+-------+---+
```

| Offset | Size | Field | Type | Endianness | Description |
|--------|------|-------|------|------------|-------------|
| 0 | 1 | `type` | byte | — | `0x07` |
| 1–16 | 16 | `messageId` | bytes | — | ID of the message to resume. Must be exactly 16 bytes. |
| 17–20 | 4 | `bytesReceived` | UInt | **LE** | Number of bytes already received. |

**Fixed size:** 21 bytes.

---

### 0x08 — Keepalive

Lightweight link liveness probe with a timestamp and optional flags.

**Source:** `WireCodec.kt` · `KEEPALIVE_SIZE = 6`

```
Byte:   0       1       2                       5
       +-------+-------+-------+-------+-------+
       | 0x08  | flags | timestampSeconds (4,BE)|
       +-------+-------+-------+-------+-------+
```

| Offset | Size | Field | Type | Endianness | Description |
|--------|------|-------|------|------------|-------------|
| 0 | 1 | `type` | byte | — | `0x08` |
| 1 | 1 | `flags` | UByte | — | Reserved flags. Default `0x00`. |
| 2–5 | 4 | `timestampSeconds` | UInt | **BE** | Unix timestamp in seconds. |

**Fixed size:** 6 bytes.

---

### 0x09 — NACK

Negative acknowledgment indicating that a message could not be delivered or
processed.

**Source:** `WireCodec.kt` · `NACK_SIZE = 17`

```
Byte:   0       1                              16
       +-------+---------- ... ----------------+
       | 0x09  |         messageId (16)         |
       +-------+---------- ... ----------------+
```

| Offset | Size | Field | Type | Endianness | Description |
|--------|------|-------|------|------------|-------------|
| 0 | 1 | `type` | byte | — | `0x09` |
| 1–16 | 16 | `messageId` | bytes | — | ID of the message being negatively acknowledged. Must be exactly 16 bytes. |

**Fixed size:** 17 bytes.

---

### 0x0A — Rotation Announcement

Announces a key rotation event. The old identity signs the announcement to prove
the transition is authorized.

**Source:** `RotationAnnouncement.kt` · `SIZE = 201`

```
Byte:   0       1                              32  33                             64
       +-------+---------- ... ----------------+---+---------- ... ----------------+
       | 0x0A  |     oldX25519Key (32)          |      newX25519Key (32)           |
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
| 0 | 1 | `type` | byte | — | `0x0A` |
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

Type codes `0x0B`–`0xFF` are **reserved** for future use. Implementations
receiving a message with a reserved type code must drop the message and emit an
`UNKNOWN_MESSAGE_TYPE` diagnostic.

---

## Security Considerations

### Ed25519 Signatures

- **Broadcast** and **Delivery ACK** messages support optional Ed25519
  signatures. When present, the signature block contains the 64-byte signature
  followed by the 32-byte signer public key.
- **Route Update** messages support a signed variant with the signer public key
  (32 bytes) and signature (64 bytes) appended after all route entries.
- **Rotation Announcement** messages are always signed by the old Ed25519 key
  to authorize key transitions.

### Noise XX Handshake

The Handshake message (type `0x01`) wraps the Noise XX protocol, which provides:

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

- **Routed Messages** maintain a `visitedList` of node IDs (up to 255 entries)
  that have forwarded the message. Nodes must not forward a message if their own
  ID appears in the visited list.

### Key Identity

- Node identity is derived from the **first 15 bytes of SHA-256(X25519 public
  key)** in BLE advertisements (see Advertisement Payload).
- Framed messages use **16-byte** truncated key hashes as node identifiers.

---

## Appendix: Byte-Order Helper Functions

The wire codec uses the following byte-ordering utilities. Both big-endian and
little-endian helpers are used depending on the field.

### Big-Endian (Network Order)

Used for: Keepalive `timestampSeconds`, Rotation `timestampMillis`, Handshake
payload fields.

| Function | Width | Description |
|----------|-------|-------------|
| `putUIntBE(offset, value)` | 4 bytes | Writes a `UInt` in big-endian order: MSB at `offset`. |
| `getUIntBE(offset)` | 4 bytes | Reads a `UInt` in big-endian order. |
| `putULongBE(offset, value)` | 8 bytes | Writes a `ULong` in big-endian order (Rotation Announcement). |
| `getULongBE(offset)` | 8 bytes | Reads a `ULong` in big-endian order. |

### Little-Endian

Used for: Chunk `sequenceNumber`/`totalChunks`, Chunk ACK `ackSequence`/
`sackBitmask`, Routed Message `replayCounter`, Route Update `cost`/
`sequenceNumber`, Resume Request `bytesReceived`.

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
| Chunk | `totalChunks` | 2 | **LE** |
| Chunk ACK | `ackSequence` | 2 | **LE** |
| Chunk ACK | `sackBitmask` | 8 | **LE** |
| Routed Message | `replayCounter` | 8 | **LE** |
| Route Update Entry | `cost` | 8 | **LE** |
| Route Update Entry | `sequenceNumber` | 4 | **LE** |
| Resume Request | `bytesReceived` | 4 | **LE** |
| Keepalive | `timestampSeconds` | 4 | **BE** |
| Rotation | `timestampMillis` | 8 | **BE** |
