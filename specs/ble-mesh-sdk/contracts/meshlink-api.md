# Contract: MeshLink public SDK API

## Purpose

Define the stable public contract exposed to Android and iOS consumers. The API
shape must stay identical across platforms.

## Public surface

### Factory entry points

```kotlin
fun meshLink(config: MeshLinkConfig): MeshLink
fun meshLink(config: MeshLinkConfig, bootstrap: MeshLinkBootstrap): MeshLink
abstract class MeshLinkBootstrap
fun meshLinkBootstrap(context: Context): MeshLinkBootstrap // Android only
```

**Contract notes**
- `meshLink(config)` is the primary entry point for platforms that do not need
  extra bootstrap input.
- `meshLink(config, bootstrap)` is the typed bootstrap overload.
- Android obtains `bootstrap` from `meshLinkBootstrap(context)`.
- Platform-specific parameters belong in factory overloads, not in the shared
  DSL.
- Android-specific handles, iOS/CoreBluetooth handles, and other platform
  objects must not become `MeshLinkConfigBuilder` fields.

**Factory-boundary guarantees**
- The same `MeshLinkConfig` value has the same semantic meaning on Android and
  iOS.
- Factory overloads are the only public place where platform bootstrap inputs
  may differ by target.
- New instances are created in `MeshLinkState.Uninitialized`.
- Factory creation must not start transport activity, emit lifecycle
  diagnostics, or begin peer/session work before `start()` is called.
- Factory creation may still load or create local identity material so the
  runtime can derive its stable peer identity before the first hard run.
- Invalid bootstrap input must fail as `MeshLinkException.InvalidConfiguration`
  or the closest matching public exception type rather than leaking a
  platform-native exception.

### iOS native bridge entry points

```kotlin
object CryptoBridge {
    fun install(...)
}

object BleTransportBridge {
    fun enableGattNotifyBearer()
    fun disableGattNotifyBearer()
}
```

**Contract notes**
- `CryptoBridge` remains the required iOS-native cryptography install point.
- `BleTransportBridge` is currently optional and only needed for the
  iPhone-hosted GATT-notify side bearer. The real GATT-notify send path
  already uses native CoreBluetooth types internally, so there is no callback
  to install and no opaque handles cross the public Kotlin API.
- `enableGattNotifyBearer()` opts in to the GATT-notify bearer;
  `disableGattNotifyBearer()` opts back out. The bearer defaults to disabled.
- If the transport bridge is not enabled, MeshLink continues on the existing
  transport path.

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
- `deliveryRetryDeadline` bounds only the in-memory retry window while no valid
  route exists.
- While no route exists, the runtime uses bounded, jittered exponential
  backoff internally and retries immediately when topology updates reveal a
  valid path.
- Backoff coefficients remain internal in v1; only the deadline is public.
- `deliveryRetryDeadline` must be greater than `Duration.ZERO`.

**Field semantics**

| Field | Required | Default | Contract |
|---|---|---|---|
| `appId` | Yes | None | Non-blank logical mesh/application identifier used to isolate discovery domains |
| `regulatoryRegion` | No | `RegulatoryRegion.DEFAULT` | Selects shared policy clamps and region-specific limits without changing API shape by platform |
| `powerMode` | No | `PowerMode.Automatic` | Uses shared commonMain power-policy logic unless the host pins a fixed tier |
| `deliveryRetryDeadline` | No | `15.seconds` | Bounds the in-memory no-route retry window only |

**Builder invariants**
- Android and iOS expose the same builder fields, defaults, validation rules,
  and resulting `MeshLinkConfig` semantics.
- Builder field order does not affect the resulting configuration.
- The builder must remain free of platform-branching options.
- Validation failures in `meshLinkConfig { ... }` must fail as
  `MeshLinkException.InvalidConfiguration` or the closest matching public error.

### Main interface

```kotlin
interface MeshLink {
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
}
```

**Lifecycle contract notes**
- `start()` is valid from `Uninitialized` or `Stopped`.
- `pause()` is valid only from `Running`.
- `resume()` is valid only from `Paused`.
- `stop()` is valid from `Uninitialized`, `Running`, or `Paused`.
- Wrong-state lifecycle calls use the documented `InvalidState(currentState)` result variants rather than throwing.
- Failed lifecycle transitions roll back to the pre-call lifecycle state.

**`send` contract notes**
- `send()` is valid only while MeshLink is `Running`.
- Calling `send()` from `Uninitialized`, `Paused`, or `Stopped` fails as `MeshLinkException.InvalidStateTransition`.
- `SendResult.Sent` marks local protocol completion for the send attempt, not a destination-app receipt acknowledgement.
- `stop()` is a hard delivery boundary for in-flight send attempts in the current hard run.

**Automatic power observation contract notes**
- `PowerMode.Automatic` uses shared commonMain policy logic with bootstrap,
  hysteresis, and regulatory-region clamping.
- Android supplies battery snapshots from system battery broadcasts; iOS supplies
  them from `UIDevice` battery notifications.
- Fixed `PowerMode` values keep the requested tier regardless of battery level,
  while still exposing the effective policy snapshot through diagnostics when
  battery observations arrive.
- Each observed battery update emits a `POWER_MODE_CHANGED` diagnostic whose
  metadata keys are: `level`, `isCharging`, `tier`,
  `advertisementIntervalMillis`, `connectionIntervalMillis`,
  `scanDutyCyclePercent`, `maxConnections`, `chunkBudgetBytes`, and `region`.
  `clampWarnings` appears only when a regional clamp was applied.

## Public types

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
- `Sent` (local protocol completion boundary only; not a destination-app receipt acknowledgement)
- `NotSent(PAYLOAD_TOO_LARGE)`
- `NotSent(TRANSFER_TIMED_OUT)`
- `NotSent(TRANSFER_ABORTED)`
- `NotSent(UNREACHABLE)`
- `NotSent(TRUST_FAILURE)`

### `StartResult`
- `Started`
- `AlreadyRunning`
- `InvalidState(currentState)`

### `PauseResult`
- `Paused`
- `AlreadyPaused`
- `InvalidState(currentState)`

### `ResumeResult`
- `Resumed`
- `AlreadyRunning`
- `InvalidState(currentState)`

### `StopResult`
- `Stopped`
- `AlreadyStopped`

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

### `MeshLinkException`

All thrown public exceptions derive from one sealed hierarchy defined in
`commonMain`. Platform-native exceptions must be wrapped before they cross the
public API boundary.
