# MeshLink SDK API reference

This page describes the public MeshLink SDK API.

The Kotlin signatures on this page are the source of truth. Swift and
Objective-C consumers use the same public model through the generated Kotlin
Multiplatform bridge.

For integration steps, use
[How to integrate MeshLink into a host app](../how-to/integrate-meshlink-into-a-host-app.md).
For Swift examples, use
[How to use MeshLink from Swift](../how-to/use-meshlink-from-swift.md).
For rationale and best practices, use
[About integrating MeshLink well](../explanation/about-integrating-meshlink.md).
For a completeness appendix rendered from the public API dump, use
[Generated public API symbol tables](generated-public-api.md).

## Swift naming notes

These notes matter when you consume the generated Apple framework from Swift.

| Kotlin surface | Swift-facing shape |
|---|---|
| `meshLink(config)` | `meshLink(config:)` |
| `meshLink(config, context)` | `meshLink(config:context:)` |
| `meshLinkConfig { ... }` | `meshLinkConfig { ... }` |
| suspend functions like `start()` | `try await api.start()` |
| `StateFlow` / `Flow` values | `AsyncSequence` values collected with `for await` |
| sealed results and events | exhaustive switching through `onEnum(of: ...)` |
| `ByteArray` payloads | still bridged as `KotlinByteArray` |


## Factory entry points

### Top-level runtime helpers

```kotlin
fun meshLink(config: MeshLinkConfig): MeshLinkApi
fun meshLink(config: MeshLinkConfig, context: Any): MeshLinkApi
```

| API | Use | Notes |
|---|---|---|
| `meshLink(config)` | iOS and platforms that do not need extra bootstrap input | Recommended default for Swift and Kotlin callers that do not need platform bootstrap input. |
| `meshLink(config, context)` | Android | `context` must be an Android `Context`. Use the application context. |

Factory instances are created in `MeshLinkState.Uninitialized`. Construction does not start transport activity, emit lifecycle diagnostics, or begin peer/session work before `start()` is called. The current implementation may still load or create local identity material during construction so the runtime can derive its stable peer identity.

### Example

```kotlin
val runtime = meshLink(
    config = meshLinkConfig { appId = "com.example.chat" },
    context = applicationContext,
)
```

## Configuration

### `meshLinkConfig`

```kotlin
fun meshLinkConfig(block: MeshLinkConfigBuilder.() -> Unit): MeshLinkConfig
```

### `MeshLinkConfigBuilder`

| Field | Type | Required | Default | Description |
|---|---|---:|---|---|
| `appId` | `String` | Yes | none | Non-blank mesh/application identifier. Peers must use the same value to interoperate. |
| `regulatoryRegion` | `RegulatoryRegion` | No | `DEFAULT` | Region clamp selection for shared power policy behavior. |
| `powerMode` | `PowerMode` | No | `Automatic` | Requested power policy tier. |
| `deliveryRetryDeadline` | `Duration` | No | `15.seconds` | Maximum in-memory retry window while no route exists. |

### `MeshLinkConfig`

```kotlin
class MeshLinkConfig(
    val appId: String,
    val regulatoryRegion: RegulatoryRegion,
    val powerMode: PowerMode,
    val deliveryRetryDeadline: Duration,
)
```

### Validation rules

- `appId` must not be blank.
- `deliveryRetryDeadline` must be greater than zero.
- Builder field order does not change the resulting config.

### Example

```kotlin
val config = meshLinkConfig {
    appId = "com.example.chat.prod"
    regulatoryRegion = RegulatoryRegion.DEFAULT
    powerMode = PowerMode.Automatic
    deliveryRetryDeadline = 15.seconds
}
```

### `RegulatoryRegion`

- `DEFAULT`
- `EU`

### `PowerMode`

- `Automatic`
- `Performance`
- `Balanced`
- `PowerSaver`

## Main runtime interface

### `MeshLinkApi`

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

### Stream semantics

MeshLink exposes one hot state stream and three hot event streams.

| Stream | Kind | Replay | Integration guidance |
|---|---|---:|---|
| `state` | `StateFlow` | latest value only | Safe to collect at any time. New collectors receive the current lifecycle state immediately. |
| `peerEvents` | hot `Flow` backed by an internal shared event stream | none | Collect before `start()` if you need the full discovery session. Collectors attached later can miss earlier events. |
| `diagnosticEvents` | hot `Flow` backed by an internal shared event stream | none | Collect before `start()` for full-session visibility. Diagnostics are best-effort and use bounded in-memory buffering, so sustained backlog can drop events. |
| `messages` | hot `Flow` backed by an internal shared event stream | none | Collect before `start()` if you need the full inbound-message stream. Collectors attached later can miss earlier deliveries. |

Practical rules for host apps:

- attach collectors from one app-owned lifecycle boundary before you call `start()`
- do not expect `peerEvents`, `diagnosticEvents`, or `messages` to replay earlier events for late subscribers
- keep collectors long-lived and app-owned rather than screen-local when you want full operator visibility

## Runtime methods

### `start()`

| Returns | Description |
|---|---|
| `StartResult.Started` | MeshLink started a new hard run from `Uninitialized` or `Stopped`. |
| `StartResult.AlreadyRunning` | MeshLink was already running. |
| `StartResult.InvalidState(Paused)` | `start()` is not valid while MeshLink is paused. Use `resume()` instead. |

### `pause()`

| Returns | Description |
|---|---|
| `PauseResult.Paused` | MeshLink moved from `Running` into the paused state. |
| `PauseResult.AlreadyPaused` | MeshLink was already paused. |
| `PauseResult.InvalidState(currentState)` | `pause()` was called from `Uninitialized` or `Stopped`. |

### `resume()`

| Returns | Description |
|---|---|
| `ResumeResult.Resumed` | MeshLink resumed from `Paused` back to `Running`. |
| `ResumeResult.AlreadyRunning` | MeshLink was already running. |
| `ResumeResult.InvalidState(currentState)` | `resume()` was called from `Uninitialized` or `Stopped`. |

### `stop()`

| Returns | Description |
|---|---|
| `StopResult.Stopped` | MeshLink stopped and cleared the current hard run's in-memory runtime state. |
| `StopResult.AlreadyStopped` | MeshLink was already stopped. |

### `send(peerId, payload, priority)`

Sends up to 64 KiB of payload data toward `peerId` while MeshLink is `Running`.

| Parameter | Type | Required | Description |
|---|---|---:|---|
| `peerId` | `PeerId` | Yes | Target peer handle. |
| `payload` | `ByteArray` | Yes | Payload bytes. Maximum supported size is 64 KiB. |
| `priority` | `DeliveryPriority` | No | Delivery priority. Defaults to `NORMAL`. |

**Returns:** `SendResult`

**Throws:** `MeshLinkException.InvalidStateTransition` when `send()` is called while MeshLink is not `Running`.

### Example

```kotlin
val result = meshLink.send(
    peerId = peerId,
    payload = "hello mesh".encodeToByteArray(),
    priority = DeliveryPriority.NORMAL,
)
```

### `forgetPeer(peerId)`

Removes the pinned trust state for a peer.

| Returns | Description |
|---|---|
| `ForgetPeerResult.Forgotten` | Trust state existed and was removed. |
| `ForgetPeerResult.NotFound` | No trust state existed for that peer. |

### `updateBattery(level, isCharging)`

Feeds battery information into the shared power-policy logic.

| Parameter | Type | Description |
|---|---|---|
| `level` | `Float` | Battery level in the inclusive `0.0..1.0` range. Values are clamped. |
| `isCharging` | `Boolean` | Whether the device is currently charging. |

When `powerMode` is `Automatic`, this call can change the effective power tier and emits `DiagnosticCode.POWER_MODE_CHANGED`.

## Streams and state models

### `MeshLinkState`

- `Uninitialized`
- `Running`
- `Paused`
- `Stopped`

### `PeerId`

```kotlin
class PeerId(val value: String)
```

Rules:

- `value` must not be blank
- `toString()` redacts the full identifier and prints only the suffix

### `DeliveryPriority`

- `HIGH`
- `NORMAL`
- `LOW`

### `SendFailureReason`

- `PAYLOAD_TOO_LARGE`
- `TRANSFER_TIMED_OUT`
- `TRANSFER_ABORTED`
- `UNREACHABLE`
- `TRUST_FAILURE`

### `SendResult`

```kotlin
sealed class SendResult {
    data object Sent : SendResult()
    class NotSent(val reason: SendFailureReason) : SendResult()
}
```

`SendResult.Sent` means MeshLink completed its local protocol boundary for that send attempt. It does not imply a final application-level receipt acknowledgement from the destination app.

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

### `ForgetPeerResult`

- `Forgotten`
- `NotFound`

### `PeerConnectionState`

- `CONNECTED`
- `DISCONNECTED`

### `PeerEvent`

```kotlin
sealed class PeerEvent {
    class Found(val peerId: PeerId, val state: PeerConnectionState) : PeerEvent()
    class StateChanged(val peerId: PeerId, val state: PeerConnectionState) : PeerEvent()
    class Lost(val peerId: PeerId) : PeerEvent()
}
```

### `InboundMessage`

```kotlin
class InboundMessage(
    val originPeerId: PeerId,
    val payload: ByteArray,
    val receivedAtEpochMillis: Long,
    val priority: DeliveryPriority,
)
```

Notes:

- `payload` is copied on construction
- `receivedAtEpochMillis` records when MeshLink emitted the inbound message to the host app using the current platform epoch clock

## Diagnostics

### `DiagnosticEvent`

```kotlin
class DiagnosticEvent(
    val code: DiagnosticCode,
    val severity: DiagnosticSeverity,
    val stage: String,
    val peerSuffix: String? = null,
    val reason: DiagnosticReason? = null,
    val metadata: Map<String, String> = emptyMap(),
)
```

### `DiagnosticSeverity`

- `DEBUG`
- `INFO`
- `WARN`
- `ERROR`

### `DiagnosticReason`

- `STATE_CHANGE`
- `TRUST_FAILURE`
- `ROUTE_CHANGE`
- `DELIVERY_RETRY`
- `DELIVERY_FAILURE`
- `TRANSFER_FAILURE`
- `POWER_CHANGE`
- `SIZE_LIMIT`
- `TRANSPORT_CHANGE`

### `DiagnosticCode`

#### Lifecycle

- `MESH_STARTED`
- `MESH_PAUSED`
- `MESH_RESUMED`
- `MESH_STOPPED`

#### Trust and sessions

- `TRUST_ESTABLISHED`
- `TRUST_FAILURE`
- `HOP_SESSION_ESTABLISHED`
- `HOP_SESSION_FAILED`

#### Routing

- `ROUTE_DISCOVERED`
- `ROUTE_UPDATED`
- `ROUTE_RETRACTED`
- `ROUTE_EXPIRED`
- `ROUTE_CONVERGED`
- `NO_ROUTE_AVAILABLE`

#### Delivery and transfer

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

#### Transport and power

- `TRANSPORT_MODE_CHANGED`
- `POWER_MODE_CHANGED`

### `POWER_MODE_CHANGED` metadata keys

`POWER_MODE_CHANGED` uses these metadata keys:

- `level`
- `isCharging`
- `tier`
- `advertisementIntervalMillis`
- `connectionIntervalMillis`
- `scanDutyCyclePercent`
- `maxConnections`
- `chunkBudgetBytes`
- `region`

`clampWarnings` is present only when MeshLink applied a regional clamp.

## Exceptions

### `MeshLinkException`

All thrown public exceptions derive from `MeshLinkException`.

- `InvalidConfiguration`
- `InvalidStateTransition`
- `PermissionDenied`
- `TransportFailure`
- `StorageFailure`
- `CryptoFailure`
- `PlatformFailure`

Rules:

- expected delivery-path outcomes use `SendResult`, not exceptions
- repeated lifecycle calls use the corresponding `Already*` result variants, not `InvalidStateTransition`
- documented wrong-state lifecycle calls use the corresponding `InvalidState(currentState)` result variants
- `send()` called while MeshLink is not `Running` throws `InvalidStateTransition`
- `InvalidStateTransition` is reserved for invalid API usage or protocol / invariant breaches that are not modeled as public result types
- platform-native failures must be wrapped before crossing the public API boundary

## iOS bridge entry points

### `IosCryptoRawKeyPair`

```kotlin
class IosCryptoRawKeyPair(
    privateKey: ByteArray,
    publicKey: ByteArray,
) {
    val privateKey: ByteArray
    val publicKey: ByteArray
}
```

Notes:

- both keys must be 32 bytes
- MeshLink stores defensive copies and returns defensive copies from both properties

### Grouped iOS crypto callback types

```kotlin
class IosHashCallbacks(
    val sha256: (ByteArray) -> ByteArray,
    val hmacSha256: (ByteArray, ByteArray) -> ByteArray,
)

class IosKeyGenerationCallbacks(
    val generateX25519KeyPair: () -> IosCryptoRawKeyPair,
    val generateEd25519KeyPair: () -> IosCryptoRawKeyPair,
)

class IosEd25519Callbacks(
    val sign: (ByteArray, ByteArray) -> ByteArray,
    val verify: (ByteArray, ByteArray, ByteArray) -> Boolean,
)

class IosChaCha20Poly1305Callbacks(
    val seal: (ByteArray, ByteArray, ByteArray, ByteArray) -> ByteArray,
    val open: (ByteArray, ByteArray, ByteArray, ByteArray) -> ByteArray,
)

class IosCryptoCallbacks(
    val randomBytes: (Int) -> ByteArray,
    val hashes: IosHashCallbacks,
    val keyGeneration: IosKeyGenerationCallbacks,
    val x25519: (ByteArray, ByteArray) -> ByteArray,
    val ed25519: IosEd25519Callbacks,
    val chacha20Poly1305: IosChaCha20Poly1305Callbacks,
)
```

Use these grouped callback types when you want the iOS bridge install call site to stay organized by responsibility rather than passing one long flat parameter list.

### `IosCryptoBridge.install(...)`

```kotlin
object IosCryptoBridge {
    fun install(callbacks: IosCryptoCallbacks)

    fun install(
        randomBytes: (Int) -> ByteArray,
        sha256: (ByteArray) -> ByteArray,
        hmacSha256: (ByteArray, ByteArray) -> ByteArray,
        generateX25519KeyPair: () -> IosCryptoRawKeyPair,
        generateEd25519KeyPair: () -> IosCryptoRawKeyPair,
        x25519: (ByteArray, ByteArray) -> ByteArray,
        ed25519Sign: (ByteArray, ByteArray) -> ByteArray,
        ed25519Verify: (ByteArray, ByteArray, ByteArray) -> Boolean,
        chacha20Poly1305Seal: (ByteArray, ByteArray, ByteArray, ByteArray) -> ByteArray,
        chacha20Poly1305Open: (ByteArray, ByteArray, ByteArray, ByteArray) -> ByteArray,
    )
}
```

Notes:

- required for real iOS cryptographic operation
- intended to be backed by app-owned Apple-native implementations such as CryptoKit
- the grouped `install(callbacks)` overload is the easier default for long-lived app-owned bridge glue
- ChaCha20-Poly1305 uses `ciphertext || tag`

### `IosBleTransportBridge`

```kotlin
object IosBleTransportBridge {
    fun install(gattNotifySend: (Any, Any, Any, ByteArray) -> Boolean)
    fun installData(gattNotifySendData: (Any, Any, Any, Any) -> Boolean)
}
```

Notes:

- optional bridge for iPhone-hosted bearer paths that still need native CoreBluetooth bridging
- opaque arguments are native iOS handles and must only be cast in app-owned iOS code
- `installData(...)` avoids copying into a Kotlin `ByteArray` when the host app can work directly with Swift `Data` or `NSData`
- when the side bearer is enabled, prefer `installData(...)` unless the host app truly needs the Kotlin `ByteArray` form

## Public limits and guarantees

- maximum supported payload size is 64 KiB
- retry scheduling is in memory only and does not survive restart
- while no route exists, MeshLink uses bounded retry scheduling and reacts immediately when topology updates reveal a valid route
- Android and iOS expose the same public configuration fields, states, events, diagnostics, and error categories

## Related docs

- [Your first MeshLink exchange](../tutorials/your-first-meshlink-exchange.md)
- [How to integrate MeshLink into a host app](../how-to/integrate-meshlink-into-a-host-app.md)
- [About integrating MeshLink well](../explanation/about-integrating-meshlink.md)
