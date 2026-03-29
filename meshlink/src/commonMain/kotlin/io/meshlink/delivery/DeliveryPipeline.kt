package io.meshlink.delivery

import io.meshlink.crypto.ReplayGuard
import io.meshlink.diagnostics.DiagnosticCode
import io.meshlink.diagnostics.DiagnosticSink
import io.meshlink.diagnostics.Severity
import io.meshlink.util.DeliveryOutcome
import io.meshlink.util.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface AckResult {
    data class Confirmed(val messageId: String) : AckResult
    data class ConfirmedAndRelay(val messageId: String, val relayTo: ByteArray) : AckResult
    data class Late(val messageId: String) : AckResult
}

sealed interface BufferResult {
    data object Buffered : BufferResult
    data object Evicted : BufferResult
}

data class PendingMessage(
    val recipient: ByteArray,
    val payload: ByteArray,
    val enqueueTimeMillis: Long,
)

/**
 * Consolidated delivery pipeline owning: delivery state tracking,
 * tombstones (late-ACK detection), deadline timers, reverse-path relay,
 * replay guards, inbound rate limiting, and store-and-forward buffering.
 *
 * MeshLink delegates all delivery-outcome decisions to this pipeline
 * and pattern-matches on [AckResult] to drive wire-level actions and
 * Flow emissions.
 */
class DeliveryPipeline(
    private val clock: () -> Long = { currentTimeMillis() },
    private val tombstoneWindowMillis: Long = 120_000L,
    private val diagnosticSink: DiagnosticSink,
) {
    // -- Delivery tracking (was DeliveryTracker) --
    private enum class DeliveryState { PENDING, RESOLVED }

    private val deliveryStates = mutableMapOf<String, DeliveryState>()

    // -- Tombstone set (was TombstoneSet) --
    private val tombstoneEntries = mutableMapOf<String, Long>()

    // -- Deadline timers (was DeliveryDeadlineTimer) --
    private val deadlineTimers = mutableMapOf<String, Job>()

    // -- Other state --
    private val routedMsgSources = mutableMapOf<String, ByteArray>()
    private val inboundReplayGuards = mutableMapOf<String, ReplayGuard>()
    private val inboundRateCounts = mutableMapOf<String, List<Long>>()
    private val pendingMessages = mutableMapOf<String, MutableList<PendingMessage>>()

    // ── Outbound delivery registration ────────────────────────────

    fun registerOutbound(
        scope: CoroutineScope,
        key: String,
        deadlineMillis: Long,
        onTimeout: ((String) -> Unit)? = null,
    ) {
        deliveryStates[key] = DeliveryState.PENDING
        if (deadlineMillis > 0) {
            startDeadlineTimer(scope, key, deadlineMillis, onTimeout)
        }
    }

    // ── ACK processing ────────────────────────────────────────────

    fun processAck(key: String): AckResult {
        if (isTombstoned(key)) return AckResult.Late(key)
        val source = routedMsgSources.remove(key)
        val outcome = recordOutcome(key, DeliveryOutcome.CONFIRMED)
        if (outcome != null || !deliveryStates.containsKey(key)) {
            cancelDeadline(key)
            if (outcome != null) addTombstone(key)
            return if (source != null) {
                AckResult.ConfirmedAndRelay(key, source)
            } else {
                AckResult.Confirmed(key)
            }
        }
        // Already resolved → late
        addTombstone(key)
        return AckResult.Late(key)
    }

    // ── Failure recording ─────────────────────────────────────────

    fun recordFailure(key: String, outcome: DeliveryOutcome): Boolean {
        val result = recordOutcome(key, outcome)
        if (result != null) addTombstone(key)
        return result != null
    }

    fun cancelDeadline(key: String) {
        deadlineTimers.remove(key)?.cancel()
    }

    fun cancelAllDeadlines() {
        for ((_, job) in deadlineTimers) job.cancel()
        deadlineTimers.clear()
    }

    // ── Reverse path tracking ─────────────────────────────────────

    fun recordReversePath(messageId: String, fromPeerId: ByteArray) {
        routedMsgSources[messageId] = fromPeerId
    }

    // ── Replay guard ──────────────────────────────────────────────

    fun checkReplay(originHex: String, counter: ULong): Boolean {
        if (counter == 0uL) return true
        val guard = inboundReplayGuards.getOrPut(originHex) { ReplayGuard() }
        return guard.check(counter)
    }

    // ── Inbound rate limiting ─────────────────────────────────────

    fun checkInboundRate(originHex: String, limitPerMinute: Int): Boolean {
        if (limitPerMinute <= 0) return true
        val now = clock()
        val pruned = (inboundRateCounts[originHex] ?: emptyList())
            .filter { now - it <= 60_000L }
        if (pruned.size >= limitPerMinute) {
            inboundRateCounts[originHex] = pruned
            return false
        }
        inboundRateCounts[originHex] = pruned + now
        return true
    }

    // ── Store-and-forward ─────────────────────────────────────────

    fun bufferPending(
        recipientHex: String,
        recipient: ByteArray,
        payload: ByteArray,
        capacity: Int,
    ): BufferResult {
        val list = pendingMessages.getOrPut(recipientHex) { mutableListOf() }
        list.add(PendingMessage(recipient, payload, clock()))
        return if (list.size > capacity) {
            list.removeAt(0)
            BufferResult.Evicted
        } else {
            BufferResult.Buffered
        }
    }

    fun flushPending(recipientHex: String, ttlMillis: Long): List<PendingMessage> {
        val msgs = pendingMessages.remove(recipientHex) ?: return emptyList()
        val now = clock()
        return if (ttlMillis > 0) msgs.filter { now - it.enqueueTimeMillis < ttlMillis } else msgs
    }

    fun sweepExpiredPending(ttlMillis: Long): Int {
        if (ttlMillis <= 0) return 0
        val now = clock()
        var count = 0
        val emptyKeys = mutableListOf<String>()
        for ((peerHex, messages) in pendingMessages) {
            val before = messages.size
            messages.removeAll { (now - it.enqueueTimeMillis) >= ttlMillis }
            count += before - messages.size
            if (messages.isEmpty()) emptyKeys.add(peerHex)
        }
        for (key in emptyKeys) pendingMessages.remove(key)
        return count
    }

    val pendingCount: Int
        get() = pendingMessages.values.sumOf { it.size }

    // ── Cleanup ───────────────────────────────────────────────────

    fun clear() {
        routedMsgSources.clear()
        inboundReplayGuards.clear()
        inboundRateCounts.clear()
        pendingMessages.clear()
        tombstoneEntries.clear()
    }

    // ── Private: delivery state tracking ──────────────────────────

    private fun recordOutcome(key: String, outcome: DeliveryOutcome): DeliveryOutcome? {
        val state = deliveryStates[key] ?: return null
        if (state != DeliveryState.PENDING) return null
        deliveryStates[key] = DeliveryState.RESOLVED
        return outcome
    }

    // ── Private: tombstone set ────────────────────────────────────

    private fun addTombstone(messageId: String) {
        tombstoneEntries[messageId] = clock() + tombstoneWindowMillis
    }

    private fun isTombstoned(messageId: String): Boolean {
        val expiry = tombstoneEntries[messageId] ?: return false
        if (clock() >= expiry) {
            tombstoneEntries.remove(messageId)
            return false
        }
        return true
    }

    // ── Private: deadline timers ──────────────────────────────────

    private fun startDeadlineTimer(
        scope: CoroutineScope,
        messageKey: String,
        deadlineMillis: Long,
        onTimeout: ((String) -> Unit)? = null,
    ) {
        if (deadlineTimers.containsKey(messageKey)) return

        deadlineTimers[messageKey] = scope.launch {
            delay(deadlineMillis)
            val outcome = recordOutcome(messageKey, DeliveryOutcome.FAILED_DELIVERY_TIMEOUT)
            if (outcome != null) {
                diagnosticSink.emit(
                    DiagnosticCode.DELIVERY_TIMEOUT,
                    Severity.WARN,
                    "messageId=$messageKey, deadlineMillis=$deadlineMillis",
                )
                onTimeout?.invoke(messageKey)
            }
            deadlineTimers.remove(messageKey)
        }
    }
}
