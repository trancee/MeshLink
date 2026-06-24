# Data model: MeshLink offline BLE mesh SDK

## Overview

MeshLink is a Kotlin Multiplatform library with a shared domain model in
`commonMain`. Platform source sets provide BLE transport and secure-storage
glue, but the core entities below remain platform-neutral.

## Entities

### `LocalIdentity`

Represents the local device identity used for trust, transport, and
addressing.

**Fields**
- `peerId`: 20-byte stable key hash used as the canonical node identifier
- `ed25519PublicKey`: public signing key
- `x25519PublicKey`: public DH key
- `advertisementKeyHash`: first 12 bytes of `SHA-256(Ed25519Pub || X25519Pub)`
- `meshHash`: 16-bit FNV-1a app-identifier hash used for pre-connection
  filtering

**Rules**
- `peerId` is stable across restarts unless the device identity is reset.
- `advertisementKeyHash` is a discovery hint, not the canonical trust-store
  identity.
- `meshHash` isolates applications into separate meshes and substitutes
  `0x0001` when the computed folded hash would otherwise be `0x0000`.

### `TrustRecord`

Represents the persisted TOFU trust decision for a remote peer.

**Fields**
- `peerId`: canonical remote identifier
- `ed25519PublicKey`: pinned signing key
- `x25519PublicKey`: pinned DH key
- `firstSeenAt`: timestamp of first successful contact
- `lastVerifiedAt`: timestamp of last successful verified handshake
- `status`: `Pinned` or `Rejected`

**Rules**
- First successful contact creates a `Pinned` record.
- An unexpected key change does not overwrite pinned keys. It produces a trust
  failure until the record is reset or revoked.

### `PeerPresence`

Represents the runtime view of a peer's availability.

**Fields**
- `peerId`
- `state`: `Connected`, `Disconnected`, or internal `Gone`
- `transportMode`: `L2CAP` or `GATT`
- `lastSeenAt`
- `sweepCount`: bounded disconnect-grace tracking
- `powerTierAtLastContact`: power snapshot during last active contact

**Rules**
- The public API exposes only `Connected` and `Disconnected`.
- `Gone` is an internal cleanup state that removes the peer from active
  listings.
- Disconnect grace is bounded.

### `RouteEntry`

Represents a proactive route to a destination peer.

**Fields**
- `destinationPeerId`
- `nextHopPeerId`
- `metric`
- `seqNo`
- `feasibilityMetric`
- `expiresAt`
- `state`: `Live`, `Degraded`, or `Retracted`

**Rules**
- Route acceptance is controlled by feasibility distance and seqno freshness.
- Retractions clear selection and propagate with explicit withdrawal.

### `MessageEnvelope`

Represents an application-visible payload prepared for delivery.

**Fields**
- `messageId`
- `originPeerId`
- `destinationPeerId`
- `priority`: `High`, `Normal`, or `Low`
- `ttlMillis`
- `createdAt`
- `payloadSizeBytes`
- `endToEndCiphertextDigest`: redacted/debug-only digest

**Rules**
- Payloads larger than 64 KiB are rejected before transfer starts.
- TTL is derived from priority and bounds mesh propagation.

### `TransferSession`

Represents an in-flight large-payload delivery attempt.

**Fields**
- `transferId`
- `messageId`
- `peerId`: current next hop or recipient
- `totalBytes`
- `totalChunks`
- `maxChunkPayloadBytes`
- `highestContiguousAck`
- `selectiveRanges`: missing/received scoreboard
- `retryAttempt`: no-route retry counter
- `nextRetryAt`: next scheduled retry instant while no valid route exists
- `deadlineAt`: absolute expiry derived from `deliveryRetryDeadline`
- `state`: `PendingStart`, `InProgress`, `Completing`, `Completed`, `Failed`,
  or `Aborted`

**Rules**
- Session state is in-memory only and never survives restart.
- When no route exists, retries continue only until `deadlineAt`.
- Oversized payloads never create a `TransferSession`.

### `DiagnosticEvent`

Represents a structured diagnostic emitted by any subsystem.

**Fields**
- `code`: one value from the shared 26-code catalog
- `severity`: `Debug`, `Info`, `Warn`, or `Error`
- `stage`: subsystem or lifecycle stage
- `peerSuffix`: redacted peer reference when applicable
- `reason`: stable typed failure or state category
- `metadata`: bounded, redacted, shape-stable contextual fields
- `emittedAt`

**Rules**
- Trust failures, transfer failures, route expiry, and power-mode changes all
  map to explicit codes.
- Diagnostics stay redacted and do not expose full peer IDs or plaintext.

### `PowerPolicy`

Represents the shared power-management policy snapshot.

**Fields**
- `tier`: `PERFORMANCE`, `BALANCED`, or `POWER_SAVER`
- `advertisementIntervalMillis`
- `scanDutyCyclePercent`
- `maxConnections`
- `chunkBudgetBytes`
- `region`: `DEFAULT` or `EU`
- `clampWarnings`: list of applied clamps

**Rules**
- Shared policy code computes tiers, hysteresis, bootstrap behavior, and
  regional clamping.
- Platform code only applies the resulting settings.

## Relationships

- `LocalIdentity` owns the local key material used to create `peerId` and the
  discovery key hash.
- `TrustRecord` binds a remote `peerId` to pinned keys.
- `PeerPresence` references a `TrustRecord` and may own zero or more
  `RouteEntry` values through that peer.
- `RouteEntry` identifies the next hop used by a `MessageEnvelope` or
  `TransferSession`.
- A `MessageEnvelope` may create zero or one `TransferSession` depending on
  size.
- `DiagnosticEvent` can reference any entity through redacted metadata.
- `PowerPolicy` influences peer capacity limits and transfer chunk sizing.

## State transitions

### Public lifecycle
- `Uninitialized -> Running`
- `Running -> Paused -> Running`
- `Running -> Stopped`
- `Paused -> Stopped`
- `Stopped -> Running`
- `Uninitialized -> Stopped`

### Peer presence
- `Connected -> Disconnected -> Gone`
- `Disconnected -> Connected` is allowed when the peer returns before cleanup.

### Trust
- `Unknown -> Pinned`
- `Pinned -> Rejected` only through explicit reset/revocation plus mismatch
  handling

### Transfer session
- `PendingStart -> InProgress -> Completing -> Completed`
- `InProgress -> Failed`
- `InProgress -> Aborted`
- all non-terminal state collapses on restart because retry state is not
  persisted

## Validation notes

- `peerId`, `messageId`, and `transferId` must be stable, serializable, and
  safe to compare across platforms.
- Route, trust, and transfer entities must be representable in pure Kotlin
  types that compile in `commonMain`.
- Public API entities avoid exposing mutable internals directly.
