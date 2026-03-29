package io.meshlink.delivery

import io.meshlink.diagnostics.DiagnosticCode
import io.meshlink.diagnostics.DiagnosticSink
import io.meshlink.diagnostics.Severity
import io.meshlink.util.DeliveryOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Manages per-message delivery deadline timers.
 *
 * When a message is queued for delivery, [startTimer] launches a coroutine that fires
 * a [DiagnosticCode.DELIVERY_TIMEOUT] event via [DiagnosticSink] if the message is not
 * delivered (ACK received) before [deadlineMillis] elapses.
 *
 * Timers are cancelled when delivery is confirmed, the message is explicitly cancelled,
 * or [cancelAll] is called (e.g. on MeshLink stop).
 */
internal class DeliveryDeadlineTimer(
    private val diagnosticSink: DiagnosticSink,
    private val deliveryTracker: DeliveryTracker,
) {
    private val timers = mutableMapOf<String, Job>()

    /**
     * Start a deadline timer for [messageKey] (hex message ID).
     * If delivery is not confirmed within [deadlineMillis], emits a DELIVERY_TIMEOUT diagnostic
     * and records a FAILED_DELIVERY_TIMEOUT outcome on the [deliveryTracker].
     *
     * The [onTimeout] callback is invoked when the deadline fires, allowing the caller
     * to emit transfer failure events.
     */
    fun startTimer(
        scope: CoroutineScope,
        messageKey: String,
        deadlineMillis: Long,
        onTimeout: ((String) -> Unit)? = null,
    ) {
        // Don't start duplicate timers
        if (timers.containsKey(messageKey)) return

        timers[messageKey] = scope.launch {
            delay(deadlineMillis)
            // Deadline expired — record failure and emit diagnostic
            val outcome = deliveryTracker.recordOutcome(messageKey, DeliveryOutcome.FAILED_DELIVERY_TIMEOUT)
            if (outcome != null) {
                diagnosticSink.emit(
                    DiagnosticCode.DELIVERY_TIMEOUT,
                    Severity.WARN,
                    "messageId=$messageKey, deadlineMillis=$deadlineMillis",
                )
                onTimeout?.invoke(messageKey)
            }
            timers.remove(messageKey)
        }
    }

    /** Cancel the deadline timer for a specific message (e.g. on ACK or explicit cancel). */
    fun cancel(messageKey: String) {
        timers.remove(messageKey)?.cancel()
    }

    /** Cancel all outstanding deadline timers (e.g. on MeshLink stop). */
    fun cancelAll() {
        for ((_, job) in timers) {
            job.cancel()
        }
        timers.clear()
    }

    /** Number of active timers (for testing). */
    val activeCount: Int get() = timers.size
}
