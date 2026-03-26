package io.meshlink.diagnostics

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
    APP_ID_REJECTED,
}

data class DiagnosticEvent(
    val code: DiagnosticCode,
    val severity: Severity,
    val monotonicMs: Long,
    val droppedCount: Int,
    val payload: String?,
)

class DiagnosticSink(
    private val bufferCapacity: Int = 256,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val buffer = ArrayDeque<DiagnosticEvent>(bufferCapacity)
    private var droppedSinceLastEmit = 0

    fun emit(code: DiagnosticCode, severity: Severity, payload: String? = null) {
        if (buffer.size >= bufferCapacity) {
            buffer.removeFirst()
            droppedSinceLastEmit++
        }
        buffer.addLast(
            DiagnosticEvent(
                code = code,
                severity = severity,
                monotonicMs = clock(),
                droppedCount = droppedSinceLastEmit,
                payload = payload,
            )
        )
        droppedSinceLastEmit = 0
    }

    fun drainTo(out: MutableList<DiagnosticEvent>) {
        out.addAll(buffer)
        buffer.clear()
    }
}
