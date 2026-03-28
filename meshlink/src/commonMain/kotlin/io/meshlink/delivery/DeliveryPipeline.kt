package io.meshlink.delivery

import io.meshlink.crypto.ReplayGuard
import io.meshlink.diagnostics.DiagnosticSink
import io.meshlink.util.DeliveryDeadlineTimer
import io.meshlink.util.DeliveryOutcome
import io.meshlink.util.DeliveryTracker
import io.meshlink.util.TombstoneSet
import io.meshlink.util.currentTimeMillis
import kotlinx.coroutines.CoroutineScope

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
    val enqueueTimeMs: Long,
)

/**
 * Facade consolidating delivery tracking, tombstones, deadline timers,
 * reverse-path relay, replay guards, inbound rate limiting, and
 * store-and-forward buffering behind sealed result types.
 *
 * MeshLink delegates all delivery-outcome decisions to this pipeline
 * and pattern-matches on [AckResult] to drive wire-level actions and
 * Flow emissions.
 */
class DeliveryPipeline(
    private val clock: () -> Long = { currentTimeMillis() },
    tombstoneWindowMs: Long = 120_000L,
    diagnosticSink: DiagnosticSink,
) {
    private val deliveryTracker = DeliveryTracker()
    private val tombstoneSet = TombstoneSet(windowMs = tombstoneWindowMs, clock = clock)
    val deadlineTimer = DeliveryDeadlineTimer(
        diagnosticSink = diagnosticSink,
        deliveryTracker = deliveryTracker,
    )
    private val routedMsgSources = mutableMapOf<String, ByteArray>()
    private val inboundReplayGuards = mutableMapOf<String, ReplayGuard>()
    private val inboundRateCounts = mutableMapOf<String, List<Long>>()
    private val pendingMessages = mutableMapOf<String, MutableList<PendingMessage>>()

    // ── Outbound delivery registration ────────────────────────────

    fun registerOutbound(
        scope: CoroutineScope,
        key: String,
        deadlineMs: Long,
        onTimeout: ((String) -> Unit)? = null,
    ) {
        deliveryTracker.register(key)
        if (deadlineMs > 0) {
            deadlineTimer.startTimer(scope, key, deadlineMs, onTimeout)
        }
    }

    // ── ACK processing ────────────────────────────────────────────

    fun processAck(key: String): AckResult {
        if (tombstoneSet.contains(key)) return AckResult.Late(key)
        val source = routedMsgSources.remove(key)
        val outcome = deliveryTracker.recordOutcome(key, DeliveryOutcome.CONFIRMED)
        if (outcome != null || !deliveryTracker.isTracked(key)) {
            deadlineTimer.cancel(key)
            if (outcome != null) tombstoneSet.add(key)
            return if (source != null) {
                AckResult.ConfirmedAndRelay(key, source)
            } else {
                AckResult.Confirmed(key)
            }
        }
        // Already resolved → late
        tombstoneSet.add(key)
        return AckResult.Late(key)
    }

    // ── Failure recording ─────────────────────────────────────────

    fun recordFailure(key: String, outcome: DeliveryOutcome): Boolean {
        val result = deliveryTracker.recordOutcome(key, outcome)
        if (result != null) tombstoneSet.add(key)
        return result != null
    }

    fun cancelDeadline(key: String) = deadlineTimer.cancel(key)
    fun cancelAllDeadlines() = deadlineTimer.cancelAll()

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

    fun flushPending(recipientHex: String, ttlMs: Long): List<PendingMessage> {
        val msgs = pendingMessages.remove(recipientHex) ?: return emptyList()
        val now = clock()
        return if (ttlMs > 0) msgs.filter { now - it.enqueueTimeMs < ttlMs } else msgs
    }

    fun sweepExpiredPending(ttlMs: Long): Int {
        if (ttlMs <= 0) return 0
        val now = clock()
        var count = 0
        val emptyKeys = mutableListOf<String>()
        for ((peerHex, messages) in pendingMessages) {
            val before = messages.size
            messages.removeAll { (now - it.enqueueTimeMs) >= ttlMs }
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
        tombstoneSet.clear()
    }
}
