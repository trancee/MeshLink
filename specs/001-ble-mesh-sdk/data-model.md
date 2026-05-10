# Data Model: MeshLink Offline BLE Mesh SDK

## Overview

MeshLink is a Kotlin Multiplatform library with a shared domain model in
`commonMain`. Platform source sets provide BLE transport and secure-storage glue,
but the core entities below remain platform-neutral.

## Entities

### LocalIdentity

Represents the local device identity used for trust, transport, and addressing.

**Fields**
- `peerId`: 20-byte stable key hash used as the canonical node identifier
- `ed25519PublicKey`: public signing key
- `x25519PublicKey`: public DH key
- `currentPseudonym`: 12-byte rotating advertisement pseudonym
- `pseudonymEpoch`: 15-minute epoch counter used to derive the current pseudonym

**Rules**
- `peerId` is stable across restarts unless the device identity is reset.
- `currentPseudonym` rotates by epoch and never appears in the trust store as the
  canonical identity.

### TrustRecord

Represents the persisted TOFU trust decision for a remote peer.

**Fields**
- `peerId`: canonical remote identifier
- `ed25519PublicKey`: pinned signing key
- `x25519PublicKey`: pinned DH key
- `firstSeenAt`: timestamp of first successful contact
- `lastVerifiedAt`: timestamp of last successful verified handshake
- `status`: `Pinned` | `Rejected`

**Rules**
- First successful contact creates a `Pinned` record.
- An unexpected key change does not mutate the pinned keys; it produces a
  trust-failure diagnostic until the record is reset or revoked.

### PeerPresence

Represents the runtime view of a peer’s availability.

**Fields**
- `peerId`
- `state`: `Connected` | `Disconnected` | `Gone` (internal)
- `transportMode`: `L2CAP` | `GATT`
- `lastSeenAt`
- `sweepCount`: bounded disconnect grace tracking
- `powerTierAtLastContact`: power policy snapshot during last active contact

**Rules**
- Public API exposes `Connected` and `Disconnected`; `Gone` is an internal
  cleanup state that results in removal from peer listings.
- Disconnect grace is bounded; peers do not remain indefinitely recoverable.

### RouteEntry

Represents a proactive route to a destination peer.

**Fields**
- `destinationPeerId`
- `nextHopPeerId`
- `metric`: hop-count-based cost in v1
- `seqNo`: source freshness counter
- `feasibilityMetric`: last feasible metric snapshot used for loop avoidance
- `expiresAt`
- `state`: `Live` | `Degraded` | `Retracted`

**Rules**
- Acceptance is controlled by feasibility distance and seqno freshness.
- Retractions clear selection and propagate with explicit route withdrawal.

### MessageEnvelope

Represents an application-visible payload prepared for delivery.

**Fields**
- `messageId`: stable message identifier
- `originPeerId`
- `destinationPeerId`
- `priority`: `High` | `Normal` | `Low`
- `ttlMillis`
- `createdAt`
- `payloadSizeBytes`
- `endToEndCiphertextDigest`: redacted/debug-only digest

**Rules**
- Payloads larger than 64 KiB are rejected before transfer starts.
- TTL is derived from priority and governs mesh propagation bounds.

### TransferSession

Represents an in-flight large-payload delivery attempt.

**Fields**
- `transferId`
- `messageId`
- `peerId`: current next-hop or recipient peer
- `totalBytes`
- `totalChunks`
- `maxChunkPayloadBytes`
- `highestContiguousAck`
- `selectiveRanges`: missing/received chunk scoreboard for bounded retransmission
- `retryBudgetRemaining`
- `deadlineAt`
- `state`: `PendingStart` | `InProgress` | `Completing` | `Completed` |
  `Failed` | `Aborted`

**Rules**
- Session state is in-memory only and never survives app or SDK restart.
- When no route exists, retries continue only inside the bounded local window.
- Oversized payloads never create a `TransferSession`.

### DiagnosticEvent

Represents a structured diagnostic emitted by any subsystem.

**Fields**
- `code`: stable diagnostic code
- `severity`: `Debug` | `Info` | `Warn` | `Error`
- `stage`: subsystem or lifecycle stage
- `peerSuffix`: redacted peer reference when applicable
- `reason`: typed failure category
- `metadata`: bounded, redacted contextual fields
- `emittedAt`

**Rules**
- Trust failures, transfer failures, route expiry, and power-mode changes all map
  to explicit codes.
- Diagnostics must stay redacted; they must not expose full peer ids or plaintext.

### PowerPolicy

Represents the shared power-management policy snapshot.

**Fields**
- `tier`: `PERFORMANCE` | `BALANCED` | `POWER_SAVER`
- `advertisementIntervalMillis`
- `scanDutyCyclePercent`
- `maxConnections`
- `chunkBudgetBytes`
- `region`: `DEFAULT` | `EU`
- `clampWarnings`: list of regulatory or policy clamps applied

**Rules**
- Shared policy code computes tiers, hysteresis, bootstrap behavior, and
  regional clamping; platform code only applies the resulting settings.

## Relationships

- `LocalIdentity` owns the local key material used to create `peerId` and
  pseudonyms.
- `TrustRecord` binds a remote `peerId` to pinned keys.
- `PeerPresence` references a `TrustRecord` and may own zero or more `RouteEntry`
  instances through that peer.
- `RouteEntry` identifies the next hop used by a `MessageEnvelope` or
  `TransferSession`.
- A `MessageEnvelope` may create zero or one `TransferSession` depending on size.
- `DiagnosticEvent` can reference any entity through redacted metadata.
- `PowerPolicy` influences `PeerPresence` capacity limits and `TransferSession`
  chunk sizing.

## State Transitions

### Public lifecycle
- `Uninitialized -> Running -> Paused -> Running -> Stopped`
- Internal transitional states may exist inside `MeshEngine`, but public state
  semantics must stay identical across Android and iOS.

### Peer presence
- `Connected -> Disconnected -> Gone`
- `Disconnected -> Connected` is allowed when the peer returns before cleanup.

### Trust
- `Unknown -> Pinned`
- `Pinned -> Rejected` only through explicit reset/revocation plus mismatch
  handling; mismatches alone do not overwrite pinned keys.

### Transfer session
- `PendingStart -> InProgress -> Completing -> Completed`
- `InProgress -> Failed`
- `InProgress -> Aborted`
- `InProgress -> Failed` on retry exhaustion or route expiry
- All non-terminal states collapse on restart because retry state is not persisted

## Validation Notes

- `peerId`, `messageId`, and `transferId` must be stable, serializable, and safe
  to compare across platforms.
- Route, trust, and transfer entities must be representable in pure Kotlin types
  that compile in `commonMain`.
- Public API entities avoid exposing mutable internals directly.
