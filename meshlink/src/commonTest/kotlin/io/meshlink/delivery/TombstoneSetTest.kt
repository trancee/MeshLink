package io.meshlink.delivery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TombstoneSetTest {

    @Test
    fun addAndContainsWithinWindow() {
        var now = 1000L
        val set = TombstoneSet(windowMillis = 120_000L, clock = { now })

        set.add("msg1")
        assertTrue(set.contains("msg1"), "Should contain recently added messageId")
        assertEquals(1, set.size)
    }

    @Test
    fun containsReturnsFalseForUnknownId() {
        val set = TombstoneSet(windowMillis = 120_000L, clock = { 1000L })
        assertFalse(set.contains("unknown"), "Should not contain unknown messageId")
    }

    @Test
    fun tombstoneExpiresAfterWindow() {
        var now = 1000L
        val set = TombstoneSet(windowMillis = 5000L, clock = { now })

        set.add("msg1")
        assertTrue(set.contains("msg1"), "Should contain within window")

        // Advance past the window
        now = 7000L
        assertFalse(set.contains("msg1"), "Should expire after window elapses")
    }

    @Test
    fun tombstoneExpiresExactlyAtBoundary() {
        var now = 0L
        val set = TombstoneSet(windowMillis = 100L, clock = { now })

        set.add("msg1")
        now = 99L
        assertTrue(set.contains("msg1"), "Should still be valid at window - 1")

        now = 100L
        assertFalse(set.contains("msg1"), "Should expire at exactly windowMillis")
    }

    @Test
    fun sweepRemovesExpiredEntries() {
        var now = 0L
        val set = TombstoneSet(windowMillis = 100L, clock = { now })

        set.add("msg1")
        set.add("msg2")
        now = 50L
        set.add("msg3")

        assertEquals(3, set.size)

        // Advance past msg1 and msg2 expiry but not msg3
        now = 110L
        val removed = set.sweep()
        assertEquals(2, removed, "Should sweep 2 expired entries")
        assertEquals(1, set.size)
        assertFalse(set.contains("msg1"))
        assertFalse(set.contains("msg2"))
        assertTrue(set.contains("msg3"), "msg3 should still be valid")
    }

    @Test
    fun sweepReturnsZeroWhenNothingExpired() {
        var now = 0L
        val set = TombstoneSet(windowMillis = 1000L, clock = { now })

        set.add("msg1")
        now = 500L
        val removed = set.sweep()
        assertEquals(0, removed, "Nothing should be swept before expiry")
        assertEquals(1, set.size)
    }

    @Test
    fun clearRemovesAllEntries() {
        val set = TombstoneSet(windowMillis = 120_000L, clock = { 0L })

        set.add("msg1")
        set.add("msg2")
        assertEquals(2, set.size)

        set.clear()
        assertEquals(0, set.size)
        assertFalse(set.contains("msg1"))
        assertFalse(set.contains("msg2"))
    }

    @Test
    fun normalAckNotAffectedByTombstoneSet() {
        val set = TombstoneSet(windowMillis = 120_000L, clock = { 0L })

        // A message ID that was never tombstoned should not be found
        assertFalse(set.contains("normal-msg"), "Non-tombstoned message should not be in set")
    }

    @Test
    fun reAddAfterExpiryWorks() {
        var now = 0L
        val set = TombstoneSet(windowMillis = 100L, clock = { now })

        set.add("msg1")
        now = 200L
        assertFalse(set.contains("msg1"), "Should have expired")

        // Re-add
        set.add("msg1")
        assertTrue(set.contains("msg1"), "Re-added entry should be found")
    }

    @Test
    fun multipleMessagesTrackedIndependently() {
        var now = 0L
        val set = TombstoneSet(windowMillis = 100L, clock = { now })

        set.add("msg1")
        now = 50L
        set.add("msg2")

        now = 110L
        assertFalse(set.contains("msg1"), "msg1 should have expired")
        assertTrue(set.contains("msg2"), "msg2 should still be valid")
    }
}
