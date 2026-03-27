package io.meshlink.util

import io.meshlink.diagnostics.DiagnosticCode
import io.meshlink.diagnostics.DiagnosticSink
import io.meshlink.diagnostics.Severity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class DeliveryDeadlineTimerTest {

    private fun createFixture(): Triple<DiagnosticSink, DeliveryTracker, DeliveryDeadlineTimer> {
        val sink = DiagnosticSink()
        val tracker = DeliveryTracker()
        val timer = DeliveryDeadlineTimer(sink, tracker)
        return Triple(sink, tracker, timer)
    }

    @Test
    fun timerFiresOnExpiry() = runTest {
        val (sink, tracker, timer) = createFixture()
        val messageKey = "aabbccdd"
        tracker.register(messageKey)

        var timeoutFired = false
        timer.startTimer(
            scope = this,
            messageKey = messageKey,
            deadlineMs = 1_000L,
        ) { timeoutFired = true }

        // Collect the diagnostic event in the background
        val eventJob = launch {
            val event = sink.events.first()
            assertEquals(DiagnosticCode.DELIVERY_TIMEOUT, event.code)
            assertEquals(Severity.WARN, event.severity)
            assertEquals("messageId=$messageKey, deadlineMs=1000", event.payload)
        }

        // Advance past the deadline
        advanceTimeBy(1_001L)
        advanceUntilIdle()

        assertEquals(true, timeoutFired, "onTimeout callback should have fired")
        assertEquals(0, timer.activeCount, "Timer should be cleaned up after firing")

        eventJob.join()
    }

    @Test
    fun timerCancelledOnAck() = runTest {
        val (sink, tracker, timer) = createFixture()
        val messageKey = "aabbccdd"
        tracker.register(messageKey)

        var timeoutFired = false
        timer.startTimer(
            scope = this,
            messageKey = messageKey,
            deadlineMs = 5_000L,
        ) { timeoutFired = true }

        assertEquals(1, timer.activeCount)

        // Simulate ACK received before deadline
        advanceTimeBy(2_000L)
        timer.cancel(messageKey)

        assertEquals(0, timer.activeCount)

        // Advance past original deadline
        advanceTimeBy(5_000L)
        advanceUntilIdle()

        assertEquals(false, timeoutFired, "onTimeout should NOT fire after cancel")

        // Verify the tracker still shows PENDING (not resolved by timer)
        val outcome = tracker.recordOutcome(messageKey, DeliveryOutcome.CONFIRMED)
        assertEquals(DeliveryOutcome.CONFIRMED, outcome, "Manual ACK should still resolve to CONFIRMED")
    }

    @Test
    fun timerCancelledOnStop() = runTest {
        val (sink, tracker, timer) = createFixture()

        val key1 = "msg001"
        val key2 = "msg002"
        tracker.register(key1)
        tracker.register(key2)

        var timeoutCount = 0
        timer.startTimer(this, key1, 5_000L) { timeoutCount++ }
        timer.startTimer(this, key2, 10_000L) { timeoutCount++ }

        assertEquals(2, timer.activeCount)

        // Simulate MeshLink stop — cancel all timers
        advanceTimeBy(2_000L)
        timer.cancelAll()

        assertEquals(0, timer.activeCount)

        // Advance past both deadlines
        advanceTimeBy(15_000L)
        advanceUntilIdle()

        assertEquals(0, timeoutCount, "No timeouts should fire after cancelAll")
    }

    @Test
    fun duplicateTimerNotStarted() = runTest {
        val (_, tracker, timer) = createFixture()
        val key = "dup001"
        tracker.register(key)

        timer.startTimer(this, key, 5_000L)
        timer.startTimer(this, key, 10_000L) // Should be ignored

        assertEquals(1, timer.activeCount)

        timer.cancelAll()
    }

    @Test
    fun timerDoesNotFireIfAlreadyResolved() = runTest {
        val (_, tracker, timer) = createFixture()
        val key = "resolved001"
        tracker.register(key)

        // Resolve the tracker before the timer fires
        tracker.recordOutcome(key, DeliveryOutcome.CONFIRMED)

        var timeoutFired = false
        timer.startTimer(this, key, 1_000L) { timeoutFired = true }

        advanceTimeBy(1_001L)
        advanceUntilIdle()

        assertEquals(false, timeoutFired, "onTimeout should NOT fire for already-resolved messages")
    }

    @Test
    fun cancelNonexistentKeyIsNoOp() = runTest {
        val (_, _, timer) = createFixture()
        // Should not throw
        timer.cancel("nonexistent")
        assertEquals(0, timer.activeCount)
    }
}
