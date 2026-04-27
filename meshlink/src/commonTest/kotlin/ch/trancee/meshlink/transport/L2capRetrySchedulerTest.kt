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
        assertEquals(60_000L, scheduler.nextDelayMillis())
        assertEquals(120_000L, scheduler.nextDelayMillis())
        assertEquals(300_000L, scheduler.nextDelayMillis())
        assertNull(scheduler.nextDelayMillis())
    }

    @Test
    fun subsequentCallsAfterExhaustionReturnNull() {
        val scheduler = L2capRetryScheduler()
        scheduler.nextDelayMillis()
        scheduler.nextDelayMillis()
        scheduler.nextDelayMillis()
        assertNull(scheduler.nextDelayMillis())
        // Extra call beyond exhaustion also returns null
        assertNull(scheduler.nextDelayMillis())
    }

    // ── isExhausted ───────────────────────────────────────────────────────────

    @Test
    fun isExhaustedProgressesThroughRetries() {
        val scheduler = L2capRetryScheduler()
        assertFalse(scheduler.isExhausted) // 0 retries used
        scheduler.nextDelayMillis()
        assertFalse(scheduler.isExhausted) // 1 retry used
        scheduler.nextDelayMillis()
        assertFalse(scheduler.isExhausted) // 2 retries used
        scheduler.nextDelayMillis()
        assertTrue(scheduler.isExhausted) // 3 retries used → exhausted
    }

    // ── reset ─────────────────────────────────────────────────────────────────

    @Test
    fun resetRestartsSequenceFromBeginning() {
        val scheduler = L2capRetryScheduler()
        scheduler.nextDelayMillis() // 60_000
        scheduler.nextDelayMillis() // 120_000
        scheduler.nextDelayMillis() // 300_000
        assertTrue(scheduler.isExhausted)

        scheduler.reset()

        assertFalse(scheduler.isExhausted)
        assertEquals(60_000L, scheduler.nextDelayMillis())
        assertEquals(120_000L, scheduler.nextDelayMillis())
        assertEquals(300_000L, scheduler.nextDelayMillis())
        assertNull(scheduler.nextDelayMillis())
    }

    @Test
    fun resetFromPartialStateRestartsCorrectly() {
        val scheduler = L2capRetryScheduler()
        scheduler.nextDelayMillis() // consumes first delay
        scheduler.reset()
        // After reset, first delay is available again
        assertEquals(60_000L, scheduler.nextDelayMillis())
    }
}
