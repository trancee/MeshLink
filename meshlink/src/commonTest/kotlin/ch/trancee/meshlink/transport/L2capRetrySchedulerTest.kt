package ch.trancee.meshlink.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class L2capRetrySchedulerTest {

    // ── Retry sequence ────────────────────────────────────────────────────────

    @Test
    fun retrySequenceIsCorrect() {
        val scheduler = L2capRetryScheduler()
        assertEquals(60_000L, scheduler.nextDelayMs())
        assertEquals(120_000L, scheduler.nextDelayMs())
        assertEquals(300_000L, scheduler.nextDelayMs())
        assertNull(scheduler.nextDelayMs())
    }

    @Test
    fun subsequentCallsAfterExhaustionReturnNull() {
        val scheduler = L2capRetryScheduler()
        scheduler.nextDelayMs()
        scheduler.nextDelayMs()
        scheduler.nextDelayMs()
        assertNull(scheduler.nextDelayMs())
        // Extra call beyond exhaustion also returns null
        assertNull(scheduler.nextDelayMs())
    }

    // ── isExhausted ───────────────────────────────────────────────────────────

    @Test
    fun isExhaustedProgressesThroughRetries() {
        val scheduler = L2capRetryScheduler()
        assertFalse(scheduler.isExhausted) // 0 retries used
        scheduler.nextDelayMs()
        assertFalse(scheduler.isExhausted) // 1 retry used
        scheduler.nextDelayMs()
        assertFalse(scheduler.isExhausted) // 2 retries used
        scheduler.nextDelayMs()
        assertTrue(scheduler.isExhausted) // 3 retries used → exhausted
    }

    // ── reset ─────────────────────────────────────────────────────────────────

    @Test
    fun resetRestartsSequenceFromBeginning() {
        val scheduler = L2capRetryScheduler()
        scheduler.nextDelayMs() // 60_000
        scheduler.nextDelayMs() // 120_000
        scheduler.nextDelayMs() // 300_000
        assertTrue(scheduler.isExhausted)

        scheduler.reset()

        assertFalse(scheduler.isExhausted)
        assertEquals(60_000L, scheduler.nextDelayMs())
        assertEquals(120_000L, scheduler.nextDelayMs())
        assertEquals(300_000L, scheduler.nextDelayMs())
        assertNull(scheduler.nextDelayMs())
    }

    @Test
    fun resetFromPartialStateRestartsCorrectly() {
        val scheduler = L2capRetryScheduler()
        scheduler.nextDelayMs() // consumes first delay
        scheduler.reset()
        // After reset, first delay is available again
        assertEquals(60_000L, scheduler.nextDelayMs())
    }
}
