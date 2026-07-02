# MeshLink SDK API reference

This page describes the public MeshLink SDK surface.

Use it to look up:

- runtime factory entry points
- configuration fields and validation rules
- public streams and method results
- core public types, diagnostics, and exceptions
- iOS bridge entry points

For setup steps, use
[How to integrate MeshLink into a host app](../how-to/integrate-meshlink-into-a-host-app.md).
For Swift usage examples, use
[How to use MeshLink from Swift](../how-to/use-meshlink-from-swift.md).
For runtime semantics and side effects, use
[MeshLink runtime behavior reference](meshlink-runtime-behavior.md).
For rationale and best practices, use
[About integrating MeshLink well](../explanation/about-integrating-meshlink.md).
For a completeness appendix rendered from the public API dump, use
[Generated public API symbol tables](generated-public-api.md).

## Quick lookup

| Need | Start here |
|---|---|
| create a runtime | [Factory entry points](#factory-entry-points) |
| build config | [Configuration](#configuration) |
| inspect the main interface | [MeshLink](#meshlink) |
| check stream behavior | [Streams](#streams) |
| check method results | [Runtime methods](#runtime-methods) |
| check public model types | [Core public types](#core-public-types) |
| inspect diagnostics | [Diagnostics](#diagnostics) |
| check thrown errors | [Exceptions](#exceptions) |
| wire iOS bridges | [iOS bridge entry points](#ios-bridge-entry-points) |

## Swift naming notes

These notes matter when you consume the generated Apple framework from Swift.

| Kotlin surface | Swift-facing shape |
|---|---|
| `MeshLink` | `MeshLink.MeshLinkRuntime` |
| `meshLink(config)` | `meshLink(config:)` |
| `meshLink(config, bootstrap)` | `meshLink(config:bootstrap:)` |
| `meshLinkConfig { ... }` | `meshLinkConfig { ... }` |
| suspend functions like `start()` | `try await api.start()` |
| `StateFlow` / `Flow` values | `AsyncSequence` values collected with `for await` |
| sealed results and events | exhaustive switching through `onEnum(of: ...)` |
| `ByteArray` payloads | still bridged as `KotlinByteArray` |

## Factory entry points

```kotlin
fun meshLink(config: MeshLinkConfig): MeshLink
fun meshLink(config: MeshLinkConfig, bootstrap: MeshLinkBootstrap): MeshLink
abstract class MeshLinkBootstrap

// Android only
fun meshLink(config: MeshLinkConfig, context: Context): MeshLink
fun meshLinkBootstrap(context: Context): MeshLinkBootstrap
```

| API | Use | Notes |
|---|---|---|
| `meshLink(config)` | iOS and platforms that do not need extra bootstrap input | Recommended default for Swift and Kotlin callers that do not need platform bootstrap input. |
| `meshLink(config, context)` | Android | **Recommended default on Android.** One-step factory backed by an application `Context`. |
| `meshLink(config, bootstrap)` | Android (advanced) | Two-step form for callers that must construct the bootstrap handle ahead of the runtime, e.g. dependency-injection graphs. Obtain `bootstrap` from `meshLinkBootstrap(context)`. Produces an identical runtime to `meshLink(config, context)`. |
| `MeshLinkBootstrap` | platform bootstrap carrier | Abstract public type used by platform-specific helpers. |

Construction begins with `MeshLinkState.Uninitialized` and returns a runtime
in `MeshLinkState.Configured`. It does not start transport activity, emit
lifecycle diagnostics, or begin peer or session work before `start()` is
called. Construction may still load or create local identity material so
MeshLink can derive its stable peer identity.

### Example

```kotlin
val runtime = meshLink(
    config = meshLinkConfig { appId = "com.example.chat" },
    bootstrap = meshLinkBootstrap(applicationContext),
)
```

## Configuration

### `meshLinkConfig`

```kotlin
fun meshLinkConfig(block: MeshLinkConfigBuilder.() -> Unit): MeshLinkConfig
```

### `MeshLinkConfig`

```kotlin
class MeshLinkConfig(
    val appId: String,
    val regulatoryRegion: RegulatoryRegion,
    val powerMode: PowerMode,
    val deliveryRetryDeadline: Duration,
)
```

### Fields

| Field | Type | Required | Default | Description |
|---|---|---:|---|---|
| `appId` | `String` | Yes | none | Non-blank mesh or application identifier. Peers must use the same value to interoperate. |
| `regulatoryRegion` | `RegulatoryRegion` | No | `DEFAULT` | Region clamp selection for shared power-policy behavior. |
| `powerMode` | `PowerMode` | No | `Automatic` | Requested power-policy tier. |
| `deliveryRetryDeadline` | `Duration` | No | `15.seconds` | Maximum in-memory retry window while no route exists. |

### Validation rules

- `appId` must not be blank.
- `deliveryRetryDeadline` must be greater than zero.
- Builder field order does not change the resulting config.

### Enum values

| Type | Values |
|---|---|
| `RegulatoryRegion` | `DEFAULT`, `EU` |
| `PowerMode` | `Automatic`, `Performance`, `Balanced`, `PowerSaver` |

### Example

```kotlin
val config = meshLinkConfig {
    appId = "com.example.chat.prod"
    regulatoryRegion = RegulatoryRegion.DEFAULT
    powerMode = PowerMode.Automatic
    deliveryRetryDeadline = 15.seconds
}
```

## `MeshLink`

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

    fun updateBattery(snapshot: BatterySnapshot)
}
```

### Streams

MeshLink exposes one hot state stream and three hot event streams.

| Stream | Kind | Replay | Integration guidance |
|---|---|---:|---|
| `state` | `StateFlow` | latest value only | Safe to collect at any time. New collectors receive the current lifecycle state immediately. |
| `peerEvents` | hot `Flow` backed by an internal shared event stream | none | Collect before `start()` if you need the full discovery session. Late collectors can miss earlier events. |
| `diagnosticEvents` | hot `Flow` backed by an internal shared event stream | none | Collect before `start()` for full-session visibility. Diagnostics are best-effort and use bounded in-memory buffering, so sustained backlog can drop events. |
| `messages` | hot `Flow` backed by an internal shared event stream | none | Collect before `start()` if you need the full inbound-message stream. Late collectors can miss earlier deliveries. |

Practical rule: keep event collectors app-owned and long-lived rather than
screen-local when you need full operator visibility.

## Runtime methods

### Lifecycle methods

| Method | Success result | Other result(s) | Notes |
|---|---|---|---|
| `start()` | `StartResult.Started` | `StartResult.AlreadyRunning`; `StartResult.InvalidState(Paused)` | Starts a new hard run from `Configured` or `Stopped`. |
| `pause()` | `PauseResult.Paused` | `PauseResult.AlreadyPaused`; `PauseResult.InvalidState(currentState)` | Valid only from `Running`. |
| `resume()` | `ResumeResult.Resumed` | `ResumeResult.AlreadyRunning`; `ResumeResult.InvalidState(currentState)` | Valid only from `Paused`. |
| `stop()` | `StopResult.Stopped` | `StopResult.AlreadyStopped` | Stops MeshLink from `Configured`, `Running`, or `Paused`, and clears the current hard run's in-memory runtime state. |

### `send(peerId, payload, priority)`

Sends up to 64 KiB of payload data toward `peerId` while MeshLink is `Running`.

| Parameter | Type | Required | Description |
|---|---|---:|---|
| `peerId` | `PeerId` | Yes | Target peer handle. |
| `payload` | `ByteArray` | Yes | Payload bytes. Maximum supported size is 64 KiB. |
| `priority` | `DeliveryPriority` | No | Delivery priority. Defaults to `NORMAL`. |

- **Returns:** `SendResult`
- **Throws:** `MeshLinkException.InvalidStateTransition` when `send()` is called while MeshLink is not `Running`

### `forgetPeer(peerId)`

Removes the pinned trust state for one peer.

| Result | Meaning |
|---|---|
| `ForgetPeerResult.Forgotten` | Trust state existed and was removed. |
| `ForgetPeerResult.NotFound` | No trust state existed for that peer. |

### `updateBattery(snapshot)`

Feeds battery information into shared power-policy logic.

| Parameter | Type | Notes |
|---|---|---|
| `snapshot` | `BatterySnapshot` | `snapshot.level` is clamped into the inclusive `0.0..1.0` range during construction. |

When `powerMode` is `Automatic`, this call can change the effective power tier
and emits `DiagnosticCode.POWER_MODE_CHANGED`.

### Example

```kotlin
val result = meshLink.send(
    peerId = peerId,
    payload = "hello mesh".encodeToByteArray(),
    priority = DeliveryPriority.NORMAL,
)
```

## Core public types

### Value and enum-like types

| Type | Shape or values | Notes |
|---|---|---|
| `MeshLinkState` | `Uninitialized`, `Configured`, `Running`, `Paused`, `Stopped` | Public lifecycle state. `Uninitialized` is the construction-start state; `Configured` is the constructed-but-not-started state returned after creation completes. |
| `PeerId` | `class PeerId(val value: String)` | `value` must not be blank. `toString()` redacts the full identifier and prints only the suffix. |
| `BatterySnapshot` | `class BatterySnapshot(level: Float, val isCharging: Boolean)` | `level` is clamped into `0.0..1.0`. |
| `DeliveryPriority` | `HIGH`, `NORMAL`, `LOW` | Public delivery priority. |
| `SendFailureReason` | `PAYLOAD_TOO_LARGE`, `TRANSFER_TIMED_OUT`, `TRANSFER_ABORTED`, `UNREACHABLE`, `TRUST_FAILURE` | Reason carried by `SendResult.NotSent`. |
| `StartResult` | `Started`, `AlreadyRunning`, `InvalidState(currentState)` | Returned by `start()`. |
| `PauseResult` | `Paused`, `AlreadyPaused`, `InvalidState(currentState)` | Returned by `pause()`. |
| `ResumeResult` | `Resumed`, `AlreadyRunning`, `InvalidState(currentState)` | Returned by `resume()`. |
| `StopResult` | `Stopped`, `AlreadyStopped` | Returned by `stop()`. |
| `ForgetPeerResult` | `Forgotten`, `NotFound` | Returned by `forgetPeer()`. |
| `PeerConnectionState` | `CONNECTED`, `DISCONNECTED` | Public peer state. |

### `SendResult`

```kotlin
sealed class SendResult {
    data object Sent : SendResult()
    class NotSent(val reason: SendFailureReason) : SendResult()
}
```

`SendResult.Sent` means MeshLink completed its local protocol boundary for that
send attempt. It does not imply a final application-level receipt from the
destination app.

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
- `receivedAtEpochMillis` records when MeshLink emitted the inbound message to
  the host app using the current platform epoch clock

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

### Supporting enums

| Type | Values |
|---|---|
| `DiagnosticSeverity` | `DEBUG`, `INFO`, `WARN`, `ERROR` |
| `DiagnosticReason` | `STATE_CHANGE`, `TRUST_FAILURE`, `ROUTE_CHANGE`, `DELIVERY_RETRY`, `DELIVERY_FAILURE`, `TRANSFER_FAILURE`, `POWER_CHANGE`, `SIZE_LIMIT`, `TRANSPORT_CHANGE` |

### `DiagnosticCode` by area

| Area | Codes |
|---|---|
| Lifecycle | `MESH_STARTED`, `MESH_PAUSED`, `MESH_RESUMED`, `MESH_STOPPED` |
| Trust and sessions | `TRUST_ESTABLISHED`, `TRUST_FAILURE`, `HOP_SESSION_ESTABLISHED`, `HOP_SESSION_FAILED` |
| Routing | `ROUTE_DISCOVERED`, `ROUTE_UPDATED`, `ROUTE_RETRACTED`, `ROUTE_EXPIRED`, `ROUTE_CONVERGED`, `NO_ROUTE_AVAILABLE` |
| Delivery and transfer | `DELIVERY_QUEUED`, `DELIVERY_RETRY_SCHEDULED`, `DELIVERY_RETRYING`, `DELIVERY_SUCCEEDED`, `DELIVERY_UNREACHABLE`, `TRANSFER_STARTED`, `TRANSFER_PROGRESS`, `TRANSFER_COMPLETED`, `TRANSFER_FAILED`, `SIZE_LIMIT_REJECTED` |
| Transport and power | `TRANSPORT_MODE_CHANGED`, `POWER_MODE_CHANGED` |

### `POWER_MODE_CHANGED` metadata keys

`POWER_MODE_CHANGED` can include these metadata keys:

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

Public subtypes:

- `InvalidConfiguration`
- `InvalidStateTransition`
- `PermissionDenied`
- `TransportFailure`
- `StorageFailure`
- `CryptoFailure`
- `PlatformFailure`

Rules:

- expected delivery-path outcomes use `SendResult`, not exceptions
- repeated lifecycle calls use the matching `Already*` result variants, not
  `InvalidStateTransition`
- documented wrong-state lifecycle calls use `InvalidState(currentState)` result
  variants
- `send()` called while MeshLink is not `Running` throws
  `InvalidStateTransition`
- `InvalidStateTransition` is reserved for invalid API usage or protocol or
  invariant breaches that are not modeled as public result types
- X25519 low-order or otherwise invalid peer public keys must surface as
  `CryptoFailure`; providers may detect that either as an all-zero shared secret
  or as a lower-level key-agreement failure, but MeshLink normalizes the public
  contract
- platform-native failures must be wrapped before crossing the public API
  boundary

## iOS bridge entry points

### `CryptoRawKeyPair`

```kotlin
class CryptoRawKeyPair(
    privateKey: ByteArray,
    publicKey: ByteArray,
) {
    val privateKey: ByteArray
    val publicKey: ByteArray
}
```

Notes:

- both keys must be 32 bytes
- MeshLink stores defensive copies and returns defensive copies from both
  properties

### Grouped callback types

```kotlin
class HashCallbacks(
    val sha256: (ByteArray) -> ByteArray,
    val hmacSha256: (ByteArray, ByteArray) -> ByteArray,
)

class KeyGenerationCallbacks(
    val generateX25519KeyPair: () -> CryptoRawKeyPair,
    val generateEd25519KeyPair: () -> CryptoRawKeyPair,
)

class Ed25519Callbacks(
    val sign: (ByteArray, ByteArray) -> ByteArray,
    val verify: (ByteArray, ByteArray, ByteArray) -> Boolean,
)

class ChaCha20Poly1305Callbacks(
    val seal: (ByteArray, ByteArray, ByteArray, ByteArray) -> ByteArray,
    val open: (ByteArray, ByteArray, ByteArray, ByteArray) -> ByteArray,
)

class CryptoCallbacks(
    val randomBytes: (Int) -> ByteArray,
    val hashes: HashCallbacks,
    val keyGeneration: KeyGenerationCallbacks,
    val x25519: (ByteArray, ByteArray) -> ByteArray,
    val ed25519: Ed25519Callbacks,
    val chacha20Poly1305: ChaCha20Poly1305Callbacks,
)
```

Use these grouped callback types when you want the iOS bridge install call site
to stay organized by responsibility rather than passing one long flat parameter
list.

### `CryptoBridge.install(...)`

```kotlin
object CryptoBridge {
    fun install(callbacks: CryptoCallbacks)

    fun install(
        randomBytes: (Int) -> ByteArray,
        sha256: (ByteArray) -> ByteArray,
        hmacSha256: (ByteArray, ByteArray) -> ByteArray,
        generateX25519KeyPair: () -> CryptoRawKeyPair,
        generateEd25519KeyPair: () -> CryptoRawKeyPair,
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
- intended to be backed by app-owned Apple-native implementations such as
  CryptoKit
- the grouped `install(callbacks)` overload is the easier default for long-lived
  app-owned bridge glue
- ChaCha20-Poly1305 uses `ciphertext || tag`

### `BleTransportBridge`

```kotlin
object BleTransportBridge {
    fun install(gattNotifySend: (Any, Any, Any, ByteArray) -> Boolean)
    fun installData(gattNotifySendData: (Any, Any, Any, Any) -> Boolean)
}
```

Notes:

- optional bridge for iPhone-hosted bearer paths that still need native
  CoreBluetooth bridging
- opaque arguments are native iOS handles and must only be cast in app-owned
  iOS code
- `installData(...)` avoids copying into a Kotlin `ByteArray` when the host app
  can work directly with Swift `Data` or `NSData`
- when the side bearer is enabled, prefer `installData(...)` unless the host
  app truly needs the Kotlin `ByteArray` form
- GATT and L2CAP are both expected to stay active when the platform supports
  them; L2CAP remains the preferred connection path and GATT remains available
  for older peers or fallback delivery

## Public limits and guarantees

- maximum supported payload size is 64 KiB
- retry scheduling is in memory only and does not survive restart
- while no route exists, MeshLink uses bounded retry scheduling and reacts
  immediately when topology updates reveal a valid route
- Android and iOS expose the same public configuration fields, states, events,
  diagnostics, and error categories
- platform bootstrap/setup mechanics are intentionally platform-specific: Android uses a
  `Context`-based factory (see [Factory entry points](#factory-entry-points)) and iOS uses the
  bridge helpers (see [iOS bridge entry points](#ios-bridge-entry-points)); this asymmetry is
  expected and does not affect runtime behavior once construction succeeds

## Related docs

- [Your first MeshLink exchange](../tutorials/your-first-meshlink-exchange.md)
- [How to integrate MeshLink into a host app](../how-to/integrate-meshlink-into-a-host-app.md)
- [How to use MeshLink from Swift](../how-to/use-meshlink-from-swift.md)
- [MeshLink runtime behavior reference](meshlink-runtime-behavior.md)
- [About integrating MeshLink well](../explanation/about-integrating-meshlink.md)
