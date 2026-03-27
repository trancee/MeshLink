package io.meshlink.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplayGuardTest {

    @Test
    fun advanceReturnsMonotonicallyIncreasingCounters() {
        val guard = ReplayGuard()
        val first = guard.advance()
        val second = guard.advance()
        val third = guard.advance()

        assertEquals(1u.toULong(), first)
        assertEquals(2u.toULong(), second)
        assertEquals(3u.toULong(), third)
    }

    @Test
    fun checkAcceptsFreshCounterRejectsDuplicate() {
        val guard = ReplayGuard()

        assertTrue(guard.check(1u), "First time seeing counter 1 — should accept")
        assertFalse(guard.check(1u), "Second time seeing counter 1 — should reject")
        assertTrue(guard.check(2u), "First time seeing counter 2 — should accept")
    }

    @Test
    fun checkAcceptsOutOfOrderWithinWindow() {
        val guard = ReplayGuard()

        // Receive counters out of order
        assertTrue(guard.check(5u), "counter 5 fresh")
        assertTrue(guard.check(3u), "counter 3 within window, fresh")
        assertTrue(guard.check(1u), "counter 1 within window, fresh")
        assertTrue(guard.check(4u), "counter 4 within window, fresh")
        assertTrue(guard.check(2u), "counter 2 within window, fresh")

        // All should now be rejected as duplicates
        assertFalse(guard.check(1u), "counter 1 duplicate")
        assertFalse(guard.check(3u), "counter 3 duplicate")
        assertFalse(guard.check(5u), "counter 5 duplicate")
    }

    @Test
    fun snapshotRestorePreservesState() {
        val original = ReplayGuard()
        // Build up some state
        original.advance()  // outbound = 1
        original.advance()  // outbound = 2
        original.check(10u)
        original.check(7u)
        original.check(3u)

        val snapshot = original.snapshot()
        val restored = ReplayGuard.restore(snapshot)

        // Outbound counter continues from where we left off
        assertEquals(3u.toULong(), restored.advance())

        // Inbound window remembers what we saw
        assertFalse(restored.check(10u), "10 was already seen before snapshot")
        assertFalse(restored.check(7u), "7 was already seen before snapshot")
        assertFalse(restored.check(3u), "3 was already seen before snapshot")

        // Fresh counters still accepted
        assertTrue(restored.check(11u), "11 is new after restore")
    }

    @Test
    fun checkRejectsCounterOlderThanWindow() {
        val guard = ReplayGuard()

        // Advance to counter 100
        assertTrue(guard.check(100u))

        // Counter 36 is exactly at the edge (100-64 = 36, so 36 is the oldest valid)
        assertTrue(guard.check(37u), "counter 37 is within 64-entry window of 100")

        // Counter 36 is 64 positions back — should be rejected (age >= 64)
        assertFalse(guard.check(36u), "counter 36 is outside 64-entry window of 100")

        // Counter 1 is way too old
        assertFalse(guard.check(1u), "counter 1 is way outside window")
    }

    // --- Batch 15 Cycle 3: Counter zero always rejected ---

    @Test
    fun counterZeroAlwaysRejected() {
        val guard = ReplayGuard()

        // Zero is rejected even as the first counter
        assertFalse(guard.check(0u), "Counter 0 is sentinel — always rejected")

        // Normal counters still work
        assertTrue(guard.check(1u))
        assertTrue(guard.check(2u))

        // Zero still rejected after state accumulates
        assertFalse(guard.check(0u), "Counter 0 still rejected after other counters accepted")
    }
}
