# Contract: MeshLink Wire Envelope and Control Plane

## Purpose

Define the stable inter-node contract required for discovery, routing, secure
transport, and large-payload delivery. This is the peer-to-peer contract between
MeshLink runtimes, not the app-facing SDK API.

## Discovery Contract

### Advertisement payload

MeshLink advertisements expose a fixed 16-byte payload:

```text
[ProtocolVersion: 1B] [MeshHash: 2B] [PsmHint: 1B] [Pseudonym: 12B]
```

**Rules**
- `ProtocolVersion` gates backward-incompatible wire changes.
- `MeshHash` isolates applications into separate meshes.
- `PsmHint = 0` means GATT fallback only.
- `Pseudonym` rotates and MUST NOT expose stable identity material.

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
- Retry windows are bounded and in-memory only.
- Transfer state does not survive restart.
- ACK state must support contiguous progress plus bounded selective ranges so
  missing chunks can be identified without redefining the public API later.

## Compatibility Rules

- The binary envelope remains FlatBuffers-compatible.
- New fields are append-only.
- Removed fields are deprecated in schema terms, not deleted from deployed wire versions.
- Unknown fields must be ignored safely by older decoders.
- Wire-format breaks require a major version bump and migration period.
