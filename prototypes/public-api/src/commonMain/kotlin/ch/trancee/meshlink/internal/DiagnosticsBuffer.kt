package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DiagnosticCode
import ch.trancee.meshlink.DiagnosticEvent
import ch.trancee.meshlink.Severity
import kotlinx.coroutines.channels.Channel
import kotlin.time.Clock

/**
 * Bounded diagnostics buffer that drops the oldest events and reports the number dropped as one
 * summary event. Not thread-safe: serial dispatcher only.
 */
internal class DiagnosticsBuffer(
    private val capacity: Int,
    private val debug: Boolean,
    private val clock: Clock,
) {
    private val queue = ArrayDeque<DiagnosticEvent>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var dropped = 0
    private var closed = false

    fun emit(code: DiagnosticCode, fields: Map<String, String> = emptyMap()) {
        if (code.severity == Severity.Debug && !debug) return
        if (queue.size == capacity) {
            queue.removeFirst()
            dropped++
        }
        queue.addLast(DiagnosticEvent(code, fields, clock.now()))
        signal.trySend(Unit)
    }

    suspend fun take(): DiagnosticEvent? {
        while (true) {
            if (dropped > 0) {
                val summary = DiagnosticEvent(DiagnosticCode.DiagnosticsOverflow, mapOf("dropped" to "$dropped"), clock.now())
                dropped = 0
                return summary
            }
            queue.removeFirstOrNull()?.let { return it }
            if (closed) return null
            signal.receive()
        }
    }

    fun close() {
        closed = true
        signal.trySend(Unit)
    }
}
