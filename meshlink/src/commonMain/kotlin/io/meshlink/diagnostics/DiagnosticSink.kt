package io.meshlink.diagnostics

import io.meshlink.util.currentTimeMillis
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class Severity { INFO, WARN, ERROR }

enum class DiagnosticCode {
    L2CAP_FALLBACK,
    RATE_LIMIT_HIT,
    ROUTE_CHANGED,
    PEER_EVICTED,
    BUFFER_PRESSURE,
    TRANSPORT_MODE_CHANGED,
    GOSSIP_TRAFFIC_REPORT,
    BLE_STACK_UNRESPONSIVE,
    MEMORY_PRESSURE,
    LATE_DELIVERY_ACK,
    DECRYPTION_FAILED,
    HOP_LIMIT_EXCEEDED,
    LOOP_DETECTED,
    REPLAY_REJECTED,
    MALFORMED_DATA,
    SEND_FAILED,
    NEXTHOP_UNRELIABLE,
    APP_ID_REJECTED,
    UNKNOWN_MESSAGE_TYPE,
    DELIVERY_TIMEOUT,
    HANDSHAKE_EVENT,
}

data class DiagnosticEvent(
    val code: DiagnosticCode,
    val severity: Severity,
    val monotonicMillis: Long,
    val droppedCount: Int,
    val payload: String?,
)

class DiagnosticSink(
    private val bufferCapacity: Int = 256,
    private val clock: () -> Long = { currentTimeMillis() },
) {
    private val _events = MutableSharedFlow<DiagnosticEvent>(
        extraBufferCapacity = bufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<DiagnosticEvent> = _events.asSharedFlow()

    // Backward-compat buffer for deprecated drainTo()
    private val _legacyBuffer = ArrayDeque<DiagnosticEvent>(bufferCapacity)

    fun emit(code: DiagnosticCode, severity: Severity, payload: String? = null) {
        val event = DiagnosticEvent(
            code = code,
            severity = severity,
            monotonicMillis = clock(),
            droppedCount = 0,
            payload = payload,
        )
        _events.tryEmit(event)
        if (_legacyBuffer.size >= bufferCapacity) {
            _legacyBuffer.removeFirst()
        }
        _legacyBuffer.addLast(event)
    }

    @Deprecated("Use events SharedFlow instead", replaceWith = ReplaceWith("events"))
    fun drainTo(out: MutableList<DiagnosticEvent>) {
        out.addAll(_legacyBuffer)
        _legacyBuffer.clear()
    }
}
