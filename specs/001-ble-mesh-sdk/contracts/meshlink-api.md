# Contract: MeshLink Public SDK API

## Purpose

Define the stable public contract exposed to Android and iOS consumers. The API
shape must remain identical across platforms.

## Public Surface

### Factory entry points

```kotlin
object MeshLink {
    fun create(config: MeshLinkConfig): MeshLinkApi
    fun create(config: MeshLinkConfig, context: Any): MeshLinkApi

    @Deprecated("Use create(config, context) instead")
    fun createAndroid(context: Any, config: MeshLinkConfig): MeshLinkApi

    @Deprecated("Use create(config) instead")
    fun createIos(config: MeshLinkConfig): MeshLinkApi
}
```

**Contract notes**
- `create(config)` is the primary entry point for platforms that do not
  require extra bootstrap input.
- `create(config, context)` is the Android bootstrap overload.
- Deprecated aliases stay available for compatibility during the migration
  window.
- Platform-specific parameters stay in factory overloads, not in the shared
  DSL.
- Android-specific bootstrap handles, iOS/CoreBluetooth handles, and other
  platform objects MUST NOT become builder fields on `MeshLinkConfigBuilder`.

**Factory-boundary guarantees**
- The same `MeshLinkConfig` value MUST have the same semantic meaning on
  Android and iOS.
- Factory overloads are the only public place where platform bootstrap inputs
  may differ by target.
- Calling a factory overload with an invalid or incomplete platform bootstrap
  input MUST fail as `MeshLinkException.InvalidConfiguration` or the closest
  matching `MeshLinkException` subtype rather than leaking a platform-native
  exception.

### Configuration DSL

```kotlin
fun meshLinkConfig(block: MeshLinkConfigBuilder.() -> Unit): MeshLinkConfig

@MeshLinkDsl
class MeshLinkConfigBuilder {
    var appId: String
    var regulatoryRegion: RegulatoryRegion = RegulatoryRegion.DEFAULT
    var powerMode: PowerMode = PowerMode.Automatic
    var deliveryRetryDeadline: Duration = 15.seconds
}
```

**Contract notes**
- `deliveryRetryDeadline` bounds only in-memory retry scheduling while no valid
  route exists.
- While no route exists, the runtime uses bounded, jittered exponential
  backoff internally and retries immediately when topology updates reveal a
  valid path.
- Backoff coefficients remain internal in v1; only the deadline is public
  configuration.
- `deliveryRetryDeadline` MUST be greater than `Duration.ZERO`.

**Field semantics**

| Field | Required | Default | Contract |
|-------|----------|---------|----------|
| `appId` | Yes | None | Non-blank logical mesh/application identifier used to isolate proof and host-app discovery domains. Peers intended to interoperate in the same run use the same value. |
| `regulatoryRegion` | No | `RegulatoryRegion.DEFAULT` | Selects shared policy clamps and region-specific radio limits without changing the public API shape by platform. |
| `powerMode` | No | `PowerMode.Automatic` | Uses shared commonMain power-policy logic unless the host explicitly pins a fixed tier. |
| `deliveryRetryDeadline` | No | `15.seconds` | Bounds only the in-memory no-route retry window; retries do not survive restart. |

**Builder invariants**
- Android and iOS expose the same builder fields, defaults, validation rules,
  and resulting `MeshLinkConfig` semantics.
- Builder field order MUST NOT affect the resulting configuration.
- The builder MUST remain free of platform-branching options such as
  Android-only or iOS-only DSL fields.
- Validation failures in `meshLinkConfig { ... }` MUST fail as
  `MeshLinkException.InvalidConfiguration` or the closest matching public
  configuration error rather than a platform-native exception.

### Main interface

```kotlin
interface MeshLinkApi {
    val state: StateFlow<MeshLinkState>
    val peerEvents: Flow<PeerEvent>
    val diagnosticEvents: Flow<DiagnosticEvent>
    val messages: Flow<InboundMessage>

    suspend fun start(): StartResult
    suspend fun pause(): PauseResult
    suspend fun resume(): ResumeResult
    suspend fun stop(): StopResult

    suspend fun send(
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority = DeliveryPriority.NORMAL,
    ): SendResult

    suspend fun forgetPeer(peerId: PeerId): ForgetPeerResult

    fun updateBattery(level: Float, isCharging: Boolean)
}
```

**`updateBattery` contract notes**
- `level` is clamped into the inclusive `0.0..1.0` range before policy evaluation.
- `PowerMode.Automatic` uses shared commonMain policy logic with bootstrap,
  hysteresis, and regulatory-region clamping.
- Fixed `PowerMode` values keep the requested tier regardless of battery level,
  while still exposing the current effective policy snapshot through diagnostics.
- Each call emits a `POWER_MODE_CHANGED` diagnostic whose metadata keys are:
  `level`, `isCharging`, `tier`, `advertisementIntervalMillis`,
  `connectionIntervalMillis`, `scanDutyCyclePercent`, `maxConnections`,
  `chunkBudgetBytes`, and `region`. `clampWarnings` appears only when a
  regional clamp was applied.

## Public Types

### `MeshLinkState`
- `Uninitialized`
- `Running`
- `Paused`
- `Stopped`

### `DeliveryPriority`
- `HIGH`
- `NORMAL`
- `LOW`

### `SendResult`
- `Sent`
- `NotSent(PAYLOAD_TOO_LARGE)`
- `NotSent(TRANSFER_TIMED_OUT)`
- `NotSent(TRANSFER_ABORTED)`
- `NotSent(UNREACHABLE)`
- `NotSent(TRUST_FAILURE)`

### `PeerEvent`
- `Found(peerId, state)`
- `StateChanged(peerId, state)`
- `Lost(peerId)`

### `InboundMessage`
- `originPeerId`
- `payload`
- `receivedAt`
- `priority`

### `DiagnosticCode`

The SDK exposes one shared cross-platform catalog of exactly 26 diagnostic
codes:

- `MESH_STARTED`
- `MESH_PAUSED`
- `MESH_RESUMED`
- `MESH_STOPPED`
- `TRUST_ESTABLISHED`
- `TRUST_FAILURE`
- `HOP_SESSION_ESTABLISHED`
- `HOP_SESSION_FAILED`
- `ROUTE_DISCOVERED`
- `ROUTE_UPDATED`
- `ROUTE_RETRACTED`
- `ROUTE_EXPIRED`
- `ROUTE_CONVERGED`
- `NO_ROUTE_AVAILABLE`
- `DELIVERY_QUEUED`
- `DELIVERY_RETRY_SCHEDULED`
- `DELIVERY_RETRYING`
- `DELIVERY_SUCCEEDED`
- `DELIVERY_UNREACHABLE`
- `TRANSFER_STARTED`
- `TRANSFER_PROGRESS`
- `TRANSFER_COMPLETED`
- `TRANSFER_FAILED`
- `SIZE_LIMIT_REJECTED`
- `TRANSPORT_MODE_CHANGED`
- `POWER_MODE_CHANGED`

**Severity mapping**
- `INFO`: `MESH_STARTED`, `MESH_PAUSED`, `MESH_RESUMED`, `MESH_STOPPED`,
  `TRUST_ESTABLISHED`, `ROUTE_DISCOVERED`, `ROUTE_CONVERGED`,
  `DELIVERY_QUEUED`, `DELIVERY_SUCCEEDED`, `TRANSFER_STARTED`,
  `TRANSFER_COMPLETED`, `TRANSPORT_MODE_CHANGED`, `POWER_MODE_CHANGED`
- `DEBUG`: `HOP_SESSION_ESTABLISHED`, `ROUTE_UPDATED`, `TRANSFER_PROGRESS`
- `WARN`: `HOP_SESSION_FAILED`, `ROUTE_RETRACTED`, `ROUTE_EXPIRED`,
  `NO_ROUTE_AVAILABLE`, `DELIVERY_RETRY_SCHEDULED`, `DELIVERY_RETRYING`,
  `SIZE_LIMIT_REJECTED`
- `ERROR`: `TRUST_FAILURE`, `DELIVERY_UNREACHABLE`, `TRANSFER_FAILED`

### `MeshLinkException`

All thrown public exceptions derive from one sealed hierarchy defined in
`commonMain`.

- `InvalidConfiguration`
- `InvalidStateTransition`
- `PermissionDenied`
- `TransportFailure`
- `StorageFailure`
- `CryptoFailure`
- `PlatformFailure`

**Contract notes**
- Expected delivery outcomes remain modeled by `SendResult`, not exceptions.
- Platform exceptions from Android and iOS APIs MUST be wrapped in the matching
  `MeshLinkException` subtype before crossing the public API surface.
- Wrapped exceptions preserve the original cause for diagnostics and debugging.

### `DiagnosticEvent`
- `code`: one value from the shared 26-code `DiagnosticCode` catalog
- `severity`: fixed per code across all targets
- `stage`
- `peerSuffix?`
- `reason?`: stable typed failure or state category
- `metadata`: bounded, redacted, shape-stable payload

## Behavioral Guarantees

- Public API semantics are identical across Android and iOS.
- `diagnosticEvents` use the full shared 26-code `DiagnosticCode` catalog with
  identical severities and payload shapes across Android and iOS.
- `POWER_MODE_CHANGED` carries the same metadata keys and tier names on Android
  and iOS for identical power-policy inputs, including
  `connectionIntervalMillis` for LOW-tier verification.
- All thrown public exceptions derive from `MeshLinkException`; platform
  exceptions are wrapped and MUST NOT leak directly to consumers.
- `send()` never silently downgrades trust, routing, or payload-size behavior.
- End-to-end plaintext is available only to the origin and final destination peers.
- Relay nodes operate only on hop-to-hop protected envelopes and MUST NOT receive end-to-end plaintext for forwarded messages.
- Payloads above 64 KiB fail before transfer starts.
- Identity mismatches fail closed and emit trust-failure diagnostics.
- When no valid route exists, `send()` may remain pending until delivery
  succeeds or `deliveryRetryDeadline` expires; retry progress remains observable
  through diagnostics.
- Pending retries do not survive restart; callers must resubmit after restart
  even if the prior `deliveryRetryDeadline` had not yet expired.
- Public API does not expose platform BLE classes directly.
- The selected underlying BLE bearer remains an internal transport decision.
  Future releases may choose different internal bearers for different platform
  pairs or payload classes, but that MUST NOT change the public API shape,
  result semantics, or diagnostic contract without an explicit canonical-doc
  amendment.

## Compatibility Rules

- New public API additions must preserve BCV compatibility.
- Public configuration uses one shared `meshLinkConfig` DSL builder, not
  boolean-heavy function overloads.
- Platform bootstrap differences stay in factory overloads and MUST NOT leak
  into the shared builder surface.
- Public exceptions and result types must stay consistent across targets.
- Public docs and examples must be updated for Android and iOS in the same change.
