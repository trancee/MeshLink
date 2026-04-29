# Wire Protocol Reference

## Frame Structure

All frames on an L2CAP or GATT channel use a 3-byte header:

```
[FrameType: 1 byte] [Length: 2 bytes LE] [Payload: Length bytes]
```

## Message Encoding

Each message payload uses a FlatBuffers-compatible binary format with a 1-byte type prefix:

```
[TypeByte: 1 byte] [FlatBuffer table data]
```

The pure-Kotlin codec (ReadBuffer/WriteBuffer) produces binary output identical to the official FlatBuffers encoder.

## Message Types

| Type Byte | Name | Direction | Description |
|-----------|------|-----------|-------------|
| 0x01 | Handshake | Bidirectional | Noise XX handshake messages (3 rounds) |
| 0x02 | Keepalive | Bidirectional | Connection liveness probe |
| 0x03 | RotationAnnouncementMsg | Outbound flood | Identity key rotation signed by old key |
| 0x04 | Hello | Bidirectional | Babel routing: neighbor discovery + link quality |
| 0x05 | Update | Bidirectional | Babel routing: route advertisement/retraction |
| 0x06 | Chunk | Sender → Receiver | Transfer: one fragment of a large message |
| 0x07 | ChunkAck | Receiver → Sender | Transfer: selective acknowledgment (SACK bitmap) |
| 0x08 | Nack | Receiver → Sender | Transfer: negative ack with reason (bufferFull, timeout) |
| 0x09 | ResumeRequest | Sender → Receiver | Transfer: resume from byte offset after interruption |
| 0x0A | Broadcast | Flood-fill | TTL-bounded flood message, Ed25519 signed |
| 0x0B | RoutedMessage | Hop-by-hop | Unicast via Babel routing, Noise K E2E sealed |
| 0x0C | DeliveryAck | Return path | Confirms delivery at final destination |

## Field Encoding Rules

- Integers: little-endian
- ByteArray fields: length-prefixed (4-byte LE length + raw bytes)
- Optional fields: absent from vtable if not set (FlatBuffers default value semantics)
- Forward compatibility: new optional fields added via vtable slot extension — old decoders skip unknown slots

## InboundValidator

Before any field access, `InboundValidator` performs structural validation:

| Rejection | Meaning |
|-----------|---------|
| BUFFER_TOO_SHORT | Frame shorter than minimum valid message |
| UNKNOWN_MESSAGE_TYPE | Type byte not in 0x01–0x0C range |
| MALFORMED_FLATBUFFER | vtable offset invalid, table structure broken |
| INVALID_FIELD_SIZE | Field length exceeds remaining buffer |

## Advertisement Payload (16 bytes)

```
Offset  Size  Field
0       1     Protocol version (0x01)
1       2     Mesh hash (FNV-1a of appId, identifies mesh network)
3       1     L2CAP PSM (0x00 = GATT-only, 128-255 = L2CAP dynamic PSM)
4       12    Pseudonym (HMAC-SHA256(keyHash, epoch)[0:12], rotates every 15 min)
```

## Noise XX Handshake Messages

The Handshake message (0x01) carries raw Noise protocol bytes — not FlatBuffers encoded internally. The 3-message pattern:

1. Initiator → Responder: `e` (ephemeral public key)
2. Responder → Initiator: `e, ee, s, es` (ephemeral + static keys)
3. Initiator → Responder: `s, se` (static key + payload)

After completion, both sides derive two CipherState instances for bidirectional transport encryption.

## Noise K E2E Payload (inside RoutedMessage)

The `data` field of a RoutedMessage contains:

```
[Noise K ciphertext: plaintext + 16-byte Poly1305 tag]
```

Sealed with `Noise_K_25519_ChaChaPoly_SHA256`. Only the destination peer (holder of the recipient's static X25519 key) can decrypt.
