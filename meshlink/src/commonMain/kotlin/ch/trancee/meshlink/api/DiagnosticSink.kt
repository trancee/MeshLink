package ch.trancee.meshlink.api

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// ── DiagnosticSinkApi ────────────────────────────────────────────────────────

/**
 * Internal contract for diagnostic event emission. [DiagnosticSink] is the live implementation;
 * [NoOpDiagnosticSink] provides zero-overhead opt-out when diagnostics are disabled.
 */
internal interface DiagnosticSinkApi {
    /**
     * Emits a diagnostic event. [payloadProvider] is called lazily — only when the sink is active.
     */
    fun emit(code: DiagnosticCode, payloadProvider: () -> DiagnosticPayload)

    /** Hot flow of emitted [DiagnosticEvent] values. */
    val events: SharedFlow<DiagnosticEvent>
}

// ── DiagnosticSink ───────────────────────────────────────────────────────────

/**
 * Live [DiagnosticSinkApi] implementation. Wraps a [MutableSharedFlow] ring buffer with
 * [BufferOverflow.DROP_OLDEST] overflow, lazy payload construction, dropped-event counting, and
 * optional peer-ID redaction.
 *
 * **Not thread-safe.** Callers must serialise concurrent [emit] calls (e.g. via
 * `kotlinx.coroutines.sync.Mutex`).
 *
 * @param bufferCapacity Ring-buffer capacity (extra slots beyond the replay=1 cache).
 * @param redactFn When non-null, applied to every [PeerIdHex] in the payload before emission. In
 *   production this should be a truncated-SHA-256 function; pass `null` to disable.
 * @param clock Monotonic clock in milliseconds.
 * @param wallClock Wall-clock in milliseconds.
 */
internal class DiagnosticSink(
    private val bufferCapacity: Int,
    private val redactFn: ((PeerIdHex) -> PeerIdHex)?,
    private val clock: () -> Long,
    private val wallClock: () -> Long,
) : DiagnosticSinkApi {

    private val _flow =
        MutableSharedFlow<DiagnosticEvent>(
            replay = 1,
            extraBufferCapacity = bufferCapacity,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    override val events: SharedFlow<DiagnosticEvent> = _flow.asSharedFlow()

    // Total emits issued (1-indexed at first emit).
    private var _totalEmitted: Long = 0L

    // Cumulative dropped count already stamped on a previous event.
    private var _totalDroppedReported: Long = 0L

    // Buffer size = replay(1) + extra(bufferCapacity).
    private val bufferSize: Long = (bufferCapacity + 1).toLong()

    override fun emit(code: DiagnosticCode, payloadProvider: () -> DiagnosticPayload) {
        _totalEmitted++
        val totalDropped = maxOf(0L, _totalEmitted - bufferSize)
        val droppedDelta = (totalDropped - _totalDroppedReported).toInt().coerceAtLeast(0)
        _totalDroppedReported = totalDropped

        val rawPayload = payloadProvider()
        val fn = redactFn
        val payload = if (fn != null) rawPayload.redacted(fn) else rawPayload

        _flow.tryEmit(
            DiagnosticEvent(
                code = code,
                severity = code.severity,
                monotonicMillis = clock(),
                wallClockMillis = wallClock(),
                droppedCount = droppedDelta,
                payload = payload,
            )
        )
    }
}

// ── Payload redaction ────────────────────────────────────────────────────────

/**
 * Returns a copy of this payload with all [PeerIdHex] fields transformed by [fn]. Payload types
 * that carry no [PeerIdHex] are returned unchanged (same instance).
 */
private fun DiagnosticPayload.redacted(fn: (PeerIdHex) -> PeerIdHex): DiagnosticPayload =
    when (this) {
        is DiagnosticPayload.DuplicateIdentity -> DiagnosticPayload.DuplicateIdentity(fn(peerId))
        is DiagnosticPayload.BleStackUnresponsive -> this
        is DiagnosticPayload.DecryptionFailed ->
            DiagnosticPayload.DecryptionFailed(fn(peerId), reason)
        is DiagnosticPayload.RotationFailed -> this
        is DiagnosticPayload.ReplayRejected -> DiagnosticPayload.ReplayRejected(fn(peerId))
        is DiagnosticPayload.RateLimitHit ->
            DiagnosticPayload.RateLimitHit(fn(peerId), limit, windowMillis)
        is DiagnosticPayload.BufferPressure -> this
        is DiagnosticPayload.MemoryPressure -> this
        is DiagnosticPayload.NexthopUnreliable ->
            DiagnosticPayload.NexthopUnreliable(fn(peerId), failureRate)
        is DiagnosticPayload.LoopDetected -> DiagnosticPayload.LoopDetected(fn(destination))
        is DiagnosticPayload.HopLimitExceeded -> this
        is DiagnosticPayload.MessageAgeExceeded -> this
        is DiagnosticPayload.RouteChanged -> DiagnosticPayload.RouteChanged(fn(destination), cost)
        is DiagnosticPayload.PeerPresenceEvicted ->
            DiagnosticPayload.PeerPresenceEvicted(fn(peerId))
        is DiagnosticPayload.TransportModeChanged -> this
        is DiagnosticPayload.L2capFallback -> DiagnosticPayload.L2capFallback(fn(peerId))
        is DiagnosticPayload.GossipTrafficReport -> this
        is DiagnosticPayload.HandshakeEvent -> DiagnosticPayload.HandshakeEvent(fn(peerId), stage)
        is DiagnosticPayload.LateDeliveryAck -> this
        is DiagnosticPayload.SendFailed -> DiagnosticPayload.SendFailed(fn(peerId), reason)
        is DiagnosticPayload.DeliveryTimeout -> this
        is DiagnosticPayload.AppIdRejected -> this
        is DiagnosticPayload.UnknownMessageType -> this
        is DiagnosticPayload.MalformedData -> this
        is DiagnosticPayload.PeerDiscovered -> DiagnosticPayload.PeerDiscovered(fn(peerId))
        is DiagnosticPayload.ConfigClamped -> this
        is DiagnosticPayload.InvalidStateTransition -> this
        is DiagnosticPayload.TextMessage -> this
    }
