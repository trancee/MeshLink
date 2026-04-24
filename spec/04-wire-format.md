# 04 — Wire Format & Binary Protocol

> **Covers:** §5 (Wire Format & Binary Protocol)
> **Dependencies:** `01-vision-and-domain-model.md` (Message ID, Key Hash, Type Prefix), `03-transport-ble.md` (GATT characteristics)
> **Key exports:** Message type table, all wire layouts, protocol versioning
> **Changes from original:** M1 (FlatBuffers for all messages, TLV removed), S1 (Bloom filter removed from Routed Message), S2 (ratchet wire format removed), S5 (compression envelope removed), A1 (maxMessageAge field added). Review fixes: origination_time corrected to wall-clock, Delivery ACK restored as standalone 0x0B, handshake payload exception documented, metric wire encoding specified.

---

## 1. Design Philosophy

**All messages use FlatBuffers.** One codec strategy, one parsing library, one schema evolution mechanism. FlatBuffers adds ~12–20 bytes overhead per message (~5–8% on GATT, negligible on L2CAP). Forward-compatible schema evolution via adding optional fields to tables.

**Exception:** The Noise XX handshake payload (§4) is raw byte offsets inside the Noise protocol message, not FlatBuffers. At 5–37 bytes, FlatBuffers overhead would be disproportionate. This is the only non-FlatBuffers wire format.

**Conventions:**
- 1-byte type discriminator at offset 0 of every message (precedes FlatBuffers payload)
- All FlatBuffers scalar fields are little-endian natively
- 12-byte peer IDs, 16-byte message IDs, 64-byte Ed25519 signatures, 32-byte public keys
- All messages validated via FlatBuffers' `Verifier` API before field access. Validation failure → drop + `MALFORMED_DATA` diagnostic.

## 2. Message Type Table

| Code | Name | Description |
|------|------|-------------|
| `0x00` | Handshake | Noise XX handshake step |
| `0x01` | Keepalive | Link liveness probe |
| `0x02` | Rotation Announcement | Key rotation, signed by old key |
| `0x03` | Hello (Babel) | Periodic neighbor liveness + route digest |
| `0x04` | Update (Babel) | Route advertisement: dest, metric, seqNo, both public keys |
| `0x05` | Chunk | Transfer fragment |
| `0x06` | Chunk ACK (SACK) | Selective ack with 64-bit bitmask |
| `0x07` | NACK | Negative ack with reason code |
| `0x08` | Resume Request | Resume from byte offset |
| `0x09` | Broadcast | Flood-fill, Ed25519-signed by default |
| `0x0A` | Routed Message | Unicast with visited list |
| `0x0B` | Delivery ACK | Ed25519-signed receipt confirmation |

Codes `0x0C`–`0xFF` reserved. Unknown → drop + `UNKNOWN_MESSAGE_TYPE`.

## 3. Message Schemas (FlatBuffers)

### Handshake (`0x00`)
```flatbuffers
table Handshake {
  step: ubyte;              // 0, 1, or 2 (Noise XX 3-message flow)
  noise_message: [ubyte];   // Opaque Noise protocol bytes
}
```

### Keepalive (`0x01`)
```flatbuffers
table Keepalive {
  flags: ubyte;               // Reserved (must be zero)
  timestamp_millis: ulong;    // Monotonic timestamp
  proposed_chunk_size: ushort; // Optional. Non-zero = propose new L2CAP chunk size on power mode change.

}
```

### Rotation Announcement (`0x02`)
```flatbuffers
table RotationAnnouncement {
  old_x25519_key: [ubyte];    // 32B
  new_x25519_key: [ubyte];    // 32B
  old_ed25519_key: [ubyte];   // 32B
  new_ed25519_key: [ubyte];   // 32B
  rotation_nonce: ulong;      // Monotonic counter
  timestamp_millis: ulong;
  signature: [ubyte];         // 64B, signed with OLD Ed25519 key over all fields above
}
```
Signable payload: all fields except `signature`. Recipients MUST reject nonce ≤ last-seen from that peer.

### Hello — Babel (`0x03`)
```flatbuffers
table Hello {
  sender: [ubyte];            // 12B Key Hash
  seq_no: ushort;
  route_digest: uint;         // 4B XOR-fold for lost-update detection
}
```
New neighbor → triggers full routing table dump. Known neighbors → keepalive.

### Update — Babel (`0x04`)
```flatbuffers
table Update {
  destination: [ubyte];       // 12B Key Hash
  metric: ushort;             // Fixed-point: floor(cost × 100). 0xFFFF = retraction.
  seq_no: ushort;
  ed25519_public_key: [ubyte]; // 32B
  x25519_public_key: [ubyte];  // 32B
}
```
**Metric encoding:** Internal route costs are `Double` (see `07-routing.md` §4). On wire: `metric = floor(cost × 100)`, clamped to `[0, 65534]`. Decode: `cost = metric / 100.0`. This gives 0.01 resolution over a 0–655.34 range. Practical costs are ~1.0–30.0 so the range is ample. `0xFFFF` (65535) is reserved as the retraction sentinel.

### Chunk (`0x05`)
```flatbuffers
table Chunk {
  message_id: [ubyte];       // 16B
  seq_num: ushort;
  total_chunks: ushort;      // Present only in first chunk (seq=0); 0 in subsequent
  payload: [ubyte];
}
```
`total_chunks = 0` means "not the first chunk" (subsequent chunks omit it). uint16 seqnums → max transfer size depends on chunk size: 64 MB at 1,024B chunks (Power Saver), 512 MB at 8,192B chunks (Performance).

### Chunk ACK / SACK (`0x06`)
```flatbuffers
table ChunkAck {
  message_id: [ubyte];       // 16B
  ack_sequence: ushort;
  sack_bitmask: ulong;       // 64-bit, covers offsets 0–63 beyond ack_sequence
}
```

### NACK (`0x07`)
```flatbuffers
table Nack {
  message_id: [ubyte];       // 16B
  reason: ubyte;             // 0=unknown, 1=bufferFull, 2=unknownDestination,
                             // 3=decryptFailed, 4=rateLimited
}
```

### Resume Request (`0x08`)
```flatbuffers
table ResumeRequest {
  message_id: [ubyte];       // 16B
  bytes_received: uint;
}
```
**Byte-offset alignment:** On transport mode change, sender rounds `bytesReceived` **down** to nearest chunk boundary.

### Broadcast (`0x09`)
```flatbuffers
table Broadcast {
  message_id: [ubyte];       // 16B
  origin: [ubyte];           // 12B Key Hash
  remaining_hops: ubyte;
  app_id_hash: ushort;       // 2B truncated SHA-256 of appId
  flags: ubyte;              // bit 0 = HAS_SIGNATURE
  priority: byte;            // -1 low, 0 normal, 1 high
  signature: [ubyte];        // 64B if signed, absent if unsigned
  signer_key: [ubyte];       // 32B if signed, absent if unsigned
  payload: [ubyte];
}
```
`remaining_hops` NOT signed (mutable for relay decrement). `app_id_hash` is a filter, not authentication.

### Routed Message (`0x0A`)
```flatbuffers
table RoutedMessage {
  message_id: [ubyte];       // 16B
  origin: [ubyte];           // 12B Key Hash
  destination: [ubyte];      // 12B Key Hash
  hop_limit: ubyte;
  visited_list: [ubyte];     // N × 12B peer IDs (loop prevention)
  priority: byte;            // -1 low, 0 normal, 1 high
  origination_time: ulong;   // Wall-clock epoch millis at origin. Relays reject if age > maxMessageAge.
  payload: [ubyte];          // E2E sealed message
}
```

**Visited List** is the sole loop prevention mechanism — variable-length array of 12-byte peer IDs. Each relay appends own ID before forwarding. Own ID found → DROP. Zero false positives.

**`origination_time`**: Set by the sender using **wall-clock epoch millis** (not monotonic — monotonic clocks are device-local and incomparable across peers). Each relay compares `now_epoch - origination_time` against `maxMessageAge` (default 30 min). Expired → drop + diagnostic. Prevents messages bouncing indefinitely through the mesh. Wall clocks across BLE peers typically agree within seconds to minutes — a 30-minute cap tolerates significant drift. NTP manipulation could extend message lifetime, but the visited list + dedup set bound re-forwarding independently.

Replay counter is **inside** E2E encrypted payload (not in cleartext). `message_id` handles relay-level dedup.

### Delivery ACK (`0x0B`)
```flatbuffers
table DeliveryAck {
  message_id: [ubyte];       // 16B
  recipient_id: [ubyte];     // 12B Key Hash
  flags: ubyte;              // bit 0 = HAS_SIGNATURE
  signature: [ubyte];        // 64B if signed, absent if unsigned
}
```
No `signerPublicKey` — verified against pinned Ed25519 key (saves 32B per ACK).

## 4. Noise XX Handshake Payload

Embedded inside Noise XX messages (not a standalone wire type).

| Offset | Size | Field |
|--------|------|-------|
| 0–1 | 2 | `protocolVersion` (LE) — encoding: low byte = minor, high byte = major. E.g., version 1.3 → `0x03 0x01` on wire. |
| 2 | 1 | `capabilityFlags` (bit 0 = L2CAP) |
| 3–4 | 2 | `l2capPsm` (LE) — confirms PSM from advertisement; used by GATT-path peers who didn't see the ad PSM |
| 5–36 | 32 | `ed25519PublicKey` (optional — absent in legacy 5-byte payloads) |

Receivers MUST accept ≥5 bytes and ignore trailing (forward-compatible).

## 5. Sealed Message Layout

Full Noise K seal per message (no ratchet mode):
```
[ephemeralPubkey: 32B][ciphertext: N + 16B AEAD tag]
```

Inside the ciphertext (after decryption):
```
[replayCounter: 8B LE][application payload...]
```

## 6. Endianness

**Everything is little-endian.** FlatBuffers is LE natively. No exceptions.

## 7. Relay Forward Compatibility

Relays never inspect inner message type of routed messages — forward opaquely. Unknown types dropped only at final destination. Enables incremental mesh upgrades (v2 through v1 relays).

## 8. Protocol Versioning

- Major.minor integer, exchanged during Noise XX handshake
- Peers compute `min(local, remote)`. Gap > 2 major → disconnect.
- **N-2 backward compatibility** required per major version
- Library version (semver) decoupled from protocol version
- Wire format changes require RFC-style review + migration guide
