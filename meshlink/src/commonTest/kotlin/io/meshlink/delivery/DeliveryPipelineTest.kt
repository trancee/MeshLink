package io.meshlink.delivery

import io.meshlink.diagnostics.DiagnosticSink
import io.meshlink.util.DeliveryOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DeliveryPipelineTest {

    private fun pipeline(
        clock: () -> Long = { 0L },
        tombstoneWindowMs: Long = 120_000L,
    ) = DeliveryPipeline(
        clock = clock,
        tombstoneWindowMs = tombstoneWindowMs,
        diagnosticSink = DiagnosticSink(clock = clock),
    )

    // ── 1. ACK processing ─────────────────────────────────────────

    @Test
    fun confirmDeliveryAfterRegister() = runTest {
        val dp = pipeline()
        dp.registerOutbound(this, "msg1", deadlineMs = 0)
        val result = dp.processAck("msg1")
        assertIs<AckResult.Confirmed>(result)
        assertEquals("msg1", result.messageId)
    }

    @Test
    fun duplicateAckIsLate() = runTest {
        val dp = pipeline()
        dp.registerOutbound(this, "msg1", deadlineMs = 0)
        dp.processAck("msg1") // first ACK
        val result = dp.processAck("msg1") // second ACK
        assertIs<AckResult.Late>(result)
    }

    @Test
    fun ackForUnregisteredMessageIsConfirmed() = runTest {
        val dp = pipeline()
        // External/untracked ACK — should still confirm (for relay ACKs)
        val result = dp.processAck("external")
        assertIs<AckResult.Confirmed>(result)
    }

    @Test
    fun ackWithReversePathReturnsRelayResult() = runTest {
        val dp = pipeline()
        val relaySource = byteArrayOf(1, 2, 3)
        dp.recordReversePath("msg1", relaySource)
        val result = dp.processAck("msg1")
        assertIs<AckResult.ConfirmedAndRelay>(result)
        assertTrue(relaySource.contentEquals(result.relayTo))
    }

    // ── 2. Failure recording ──────────────────────────────────────

    @Test
    fun recordFailureForTrackedMessage() = runTest {
        val dp = pipeline()
        dp.registerOutbound(this, "msg1", deadlineMs = 0)
        assertTrue(dp.recordFailure("msg1", DeliveryOutcome.FAILED_ACK_TIMEOUT))
        // After failure, ACK should be late (tombstoned)
        val result = dp.processAck("msg1")
        assertIs<AckResult.Late>(result)
    }

    @Test
    fun recordFailureForUnknownReturnsFalse() {
        val dp = pipeline()
        assertFalse(dp.recordFailure("unknown", DeliveryOutcome.FAILED_ACK_TIMEOUT))
    }

    // ── 3. Replay guard ───────────────────────────────────────────

    @Test
    fun replayGuardAcceptsNewCounter() {
        val dp = pipeline()
        assertTrue(dp.checkReplay("origin1", 1u))
        assertTrue(dp.checkReplay("origin1", 2u))
    }

    @Test
    fun replayGuardRejectsReplayedCounter() {
        val dp = pipeline()
        dp.checkReplay("origin1", 5u)
        assertFalse(dp.checkReplay("origin1", 5u))
    }

    @Test
    fun replayGuardAllowsZeroCounter() {
        val dp = pipeline()
        // Counter 0 means unprotected/legacy — always allowed
        assertTrue(dp.checkReplay("origin1", 0u))
        assertTrue(dp.checkReplay("origin1", 0u))
    }

    // ── 4. Inbound rate limiting ──────────────────────────────────

    @Test
    fun inboundRateAllowsUnderLimit() {
        val dp = pipeline()
        assertTrue(dp.checkInboundRate("sender1", 3))
        assertTrue(dp.checkInboundRate("sender1", 3))
        assertTrue(dp.checkInboundRate("sender1", 3))
    }

    @Test
    fun inboundRateRejectsOverLimit() {
        val dp = pipeline()
        dp.checkInboundRate("sender1", 2)
        dp.checkInboundRate("sender1", 2)
        assertFalse(dp.checkInboundRate("sender1", 2))
    }

    @Test
    fun inboundRateDisabledWhenZero() {
        val dp = pipeline()
        // limitPerMinute = 0 means disabled — always allow
        assertTrue(dp.checkInboundRate("sender1", 0))
    }

    @Test
    fun inboundRateSlidingWindowExpires() {
        var now = 0L
        val dp = pipeline(clock = { now })
        dp.checkInboundRate("sender1", 1)
        // After 60s window, the old timestamp expires
        now = 61_000L
        assertTrue(dp.checkInboundRate("sender1", 1))
    }

    // ── 5. Store-and-forward ──────────────────────────────────────

    @Test
    fun bufferAndFlushPendingMessages() {
        val dp = pipeline()
        val result = dp.bufferPending("peer1", byteArrayOf(1), byteArrayOf(10, 20), 10)
        assertIs<BufferResult.Buffered>(result)
        assertEquals(1, dp.pendingCount)

        val flushed = dp.flushPending("peer1", ttlMs = 0)
        assertEquals(1, flushed.size)
        assertTrue(byteArrayOf(10, 20).contentEquals(flushed[0].payload))
        assertEquals(0, dp.pendingCount)
    }

    @Test
    fun bufferEvictsOldestWhenOverCapacity() {
        val dp = pipeline()
        dp.bufferPending("peer1", byteArrayOf(1), byteArrayOf(1), 2)
        dp.bufferPending("peer1", byteArrayOf(1), byteArrayOf(2), 2)
        val result = dp.bufferPending("peer1", byteArrayOf(1), byteArrayOf(3), 2)
        assertIs<BufferResult.Evicted>(result)
        assertEquals(2, dp.pendingCount)
        // Flush and verify oldest was evicted
        val flushed = dp.flushPending("peer1", ttlMs = 0)
        assertEquals(2, flushed.size)
        assertTrue(byteArrayOf(2).contentEquals(flushed[0].payload))
    }

    @Test
    fun flushPendingRespectsttl() {
        var now = 0L
        val dp = pipeline(clock = { now })
        dp.bufferPending("peer1", byteArrayOf(1), byteArrayOf(10), 10)
        now = 5000L
        dp.bufferPending("peer1", byteArrayOf(1), byteArrayOf(20), 10)
        now = 6000L
        // TTL 5500ms: first message (age 6000ms) expired, second (age 1000ms) still valid
        val flushed = dp.flushPending("peer1", ttlMs = 5500L)
        assertEquals(1, flushed.size)
        assertTrue(byteArrayOf(20).contentEquals(flushed[0].payload))
    }

    @Test
    fun sweepExpiredPendingMessages() {
        var now = 0L
        val dp = pipeline(clock = { now })
        dp.bufferPending("peer1", byteArrayOf(1), byteArrayOf(10), 10)
        dp.bufferPending("peer2", byteArrayOf(2), byteArrayOf(20), 10)
        now = 10_000L
        val expired = dp.sweepExpiredPending(ttlMs = 5000L)
        assertEquals(2, expired)
        assertEquals(0, dp.pendingCount)
    }

    // ── 6. Deadline timer integration ─────────────────────────────

    @Test
    fun deadlineTimerFiresOnTimeout() = runTest {
        var timedOut = false
        val dp = DeliveryPipeline(
            clock = { testScheduler.currentTime },
            diagnosticSink = DiagnosticSink(clock = { testScheduler.currentTime }),
        )
        dp.registerOutbound(this, "msg1", deadlineMs = 1000L) { timedOut = true }
        advanceTimeBy(1001L)
        assertTrue(timedOut)
    }

    @Test
    fun cancelDeadlinePreventsTimeout() = runTest {
        var timedOut = false
        val dp = DeliveryPipeline(
            clock = { testScheduler.currentTime },
            diagnosticSink = DiagnosticSink(clock = { testScheduler.currentTime }),
        )
        dp.registerOutbound(this, "msg1", deadlineMs = 1000L) { timedOut = true }
        dp.cancelDeadline("msg1")
        advanceTimeBy(2000L)
        assertFalse(timedOut)
    }

    // ── 7. Clear ──────────────────────────────────────────────────

    @Test
    fun clearResetsAllState() = runTest {
        val dp = pipeline()
        dp.registerOutbound(this, "msg1", deadlineMs = 0)
        dp.recordReversePath("msg2", byteArrayOf(1))
        dp.checkReplay("origin1", 5u)
        dp.checkInboundRate("sender1", 10)
        dp.bufferPending("peer1", byteArrayOf(1), byteArrayOf(10), 10)

        dp.clear()

        assertEquals(0, dp.pendingCount)
        // Replay guard cleared — same counter now accepted
        assertTrue(dp.checkReplay("origin1", 5u))
        // Reverse path cleared
        val result = dp.processAck("msg2")
        // Should be Confirmed (not ConfirmedAndRelay) since reverse path was cleared
        assertIs<AckResult.Confirmed>(result)
    }
}
