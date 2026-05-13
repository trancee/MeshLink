# Contract: MeshLink Wire Envelope and Control Plane

## Purpose

Define the stable inter-node contract required for discovery, routing, secure
transport, and large-payload delivery. This is the peer-to-peer contract between
MeshLink runtimes, not the app-facing SDK API.

## Discovery Contract

### Advertisement UUIDs

MeshLink advertisements always expose two service UUIDs in a single
advertisement packet with no scan response:

- Discovery service UUID: 32-bit `4d455348`
  (Bluetooth-base expanded form `4d455348-0000-1000-8000-00805f9b34fb`)
- Discovery payload UUID: 16 raw payload bytes encoded as a second 128-bit
  service UUID

### Advertisement payload UUID layout

The second 128-bit service UUID carries this 16-byte payload:

| Offset | Size | Field | Description |
|--------|------|-------|-------------|
| 0 [7:5] | 3 bits | `protocolVersion` | Major protocol version only (0–7). Pre-connection filter for incompatible generations. Full major.minor exchanges during Noise XX. |
| 0 [4:3] | 2 bits | `powerMode` | `0=Performance`, `1=Balanced`, `2=PowerSaver`, `3=reserved` |
| 0 [2:0] | 3 bits | reserved | MUST be `0`; ignored by receivers for forward compatibility |
| 1–2 | 2 bytes | `meshHash` | 16-bit FNV-1a of `appId` (XOR-folded, little-endian). `0x0000` means no filter; a computed `0x0000` is replaced with `0x0001`. |
| 3 | 1 byte | `l2capPsm` | L2CAP PSM (`128–255`). `0x00` means L2CAP unsupported and GATT fallback only. |
| 4–15 | 12 bytes | `keyHash` | First 12 bytes of `SHA-256(Ed25519Pub || X25519Pub)` |

**Rules**
- `protocolVersion` gates backward-incompatible wire changes before any connection attempt; unsupported major versions are rejected before participation.
- `meshHash` isolates applications into separate meshes before session establishment.
- `l2capPsm = 0x00` means GATT fallback only. In the current normative L2CAP-first MeshLink scope, such peers are treated as incompatible transport participants rather than silently accepted as reachable mesh peers.
- `keyHash` is the advertised identity hint used for discovery and deterministic initiation decisions.
- The advertisement MUST fit in a single 27-byte packet and MUST NOT rely on a scan response.

### GATT fallback UUID layout

When L2CAP is unavailable, MeshLink exposes this runtime-private GATT service:

- Service UUID: `4d455348-0001-1000-8000-000000000000`
- Characteristic UUIDs:
  - `4d455348-0002-1000-8000-000000000000`
  - `4d455348-0003-1000-8000-000000000000`
  - `4d455348-0004-1000-8000-000000000000`
  - `4d455348-0005-1000-8000-000000000000`
  - `4d455348-0006-1000-8000-000000000000`

## Session Establishment Contract

### Hop-to-hop handshake
- Pattern: `Noise_XX_25519_ChaChaPoly_SHA256`
- Each adjacent hop derives its own transport session keys.
- Identity mismatches fail closed and surface trust-failure diagnostics.

### End-to-end envelope
- Inner payloads are sealed for the final destination using Noise K semantics on
  trusted static keys.
- Relay nodes may re-encrypt hop envelopes, but they do not decrypt the inner
  end-to-end payload body.

## Routing Control-Plane Frames

MeshLink nodes exchange routing frames in shared binary envelopes.

### Frame families
- `HELLO`
- `IHU` / link-quality acknowledgement
- `ROUTE_UPDATE`
- `ROUTE_RETRACTION`
- `SEQNO_REQUEST`
- `ROUTE_DIGEST`

**Rules**
- Routes are identified by destination peer id plus freshness seqno.
- Feasibility distance is enforced before a route becomes selected.
- Retractions propagate explicitly; unreachable state is not inferred solely from expiry.

## Delivery Frames

### Direct/routed message envelope
Fields:
- `messageId`
- `originPeerId`
- `destinationPeerId`
- `priority`
- `ttlMillis`
- `encryptedPayload`

### Large-transfer frames
Fields or frame families:
- `TRANSFER_START`
- `TRANSFER_CHUNK`
- `TRANSFER_ACK`
- `TRANSFER_COMPLETE`
- `TRANSFER_ABORT`

**Rules**
- Large-payload sessions are bounded to 64 KiB in v1.
- Oversized payloads are rejected before any transfer frame is emitted.
- Retry windows are bounded, in-memory only, and use bounded, jittered
  exponential backoff while no valid route exists.
- When topology updates reveal a valid route before the retry deadline expires,
  the sender retries immediately without requiring the original send request to
  be recreated.
- Transfer state does not survive restart.
- ACK state must support contiguous progress plus bounded selective ranges so
  missing chunks can be identified without redefining the public API later.

## Compatibility Rules

- The binary envelope remains FlatBuffers-compatible.
- New fields are append-only.
- Removed fields are deprecated in schema terms, not deleted from deployed wire versions.
- Unknown fields must be ignored safely by older decoders.
- The repository retains deployed-wire baseline fixtures under
  `meshlink/src/commonTest/resources/wire-compat/`, and
  `meshlink/src/commonTest/kotlin/ch/trancee/meshlink/wire/WireEnvelopeContractTest.kt`
  replays them as byte-for-byte compatibility checks.
- `WireEnvelope.decode` rejects unsupported future major versions before payload decode.
- Wire-format breaks require a major version bump and migration period.
