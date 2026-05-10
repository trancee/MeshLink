# Contract: MeshLink Public SDK API

## Purpose

Define the stable public contract exposed to Android and iOS consumers. The API
shape must remain identical across platforms.

## Public Surface

### Factory entry points

```kotlin
object MeshLink {
    fun createAndroid(context: Any, config: MeshLinkConfig): MeshLinkApi
    fun createIos(config: MeshLinkConfig): MeshLinkApi
}
```

**Contract notes**
- Platform factories return the same `MeshLinkApi` contract.
- Platform-specific parameters stay in factory methods, not in the shared DSL.

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

### `DiagnosticEvent`
- `code`
- `severity`
- `stage`
- `peerSuffix?`
- `reason?`
- `metadata`

## Behavioral Guarantees

- Public API semantics are identical across Android and iOS.
- `send()` never silently downgrades trust or payload-size behavior.
- Payloads above 64 KiB fail before transfer starts.
- Identity mismatches fail closed and emit trust-failure diagnostics.
- Pending retries do not survive restart; callers must resubmit after restart.
- Public API does not expose platform BLE classes directly.

## Compatibility Rules

- New public API additions must preserve BCV compatibility.
- Public configuration uses a DSL builder, not boolean-heavy function overloads.
- Public exceptions and result types must stay consistent across targets.
- Public docs and examples must be updated for Android and iOS in the same change.
