package io.meshlink.delivery

import io.meshlink.util.DeliveryOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeliveryTrackerTest {

    @Test
    fun firstTerminalCallbackWinsSecondIgnored() {
        val tracker = DeliveryTracker()

        // Register a pending delivery
        tracker.register("msg1")

        // First terminal signal → accepted
        val result1 = tracker.recordOutcome("msg1", DeliveryOutcome.CONFIRMED)
        assertEquals(DeliveryOutcome.CONFIRMED, result1, "First outcome should be accepted")

        // Second terminal signal (different outcome) → ignored
        val result2 = tracker.recordOutcome("msg1", DeliveryOutcome.FAILED_ACK_TIMEOUT)
        assertNull(result2, "Second outcome should be ignored (single-signal rule)")

        // Same outcome again → also ignored
        val result3 = tracker.recordOutcome("msg1", DeliveryOutcome.CONFIRMED)
        assertNull(result3, "Repeated outcome should be ignored")
    }

    @Test
    fun lateAckAfterFailureTombstoneSilentlyDropped() {
        val tracker = DeliveryTracker()

        tracker.register("msg1")

        // Record failure first
        val result1 = tracker.recordOutcome("msg1", DeliveryOutcome.FAILED_ACK_TIMEOUT)
        assertEquals(DeliveryOutcome.FAILED_ACK_TIMEOUT, result1)

        // Late ACK arrives → tombstoned, returns null
        val result2 = tracker.recordOutcome("msg1", DeliveryOutcome.CONFIRMED)
        assertNull(result2, "Late ACK after failure should be silently dropped")

        // Unregistered message → also returns null (not tracked)
        val result3 = tracker.recordOutcome("unknown", DeliveryOutcome.CONFIRMED)
        assertNull(result3, "Unregistered message should return null")
    }

    // --- Batch 11 Cycle 6: isTracked ---

    @Test
    fun isTrackedReturnsFalseForUnknownTrueForRegistered() {
        val tracker = DeliveryTracker()

        assertFalse(tracker.isTracked("unknown"), "Unknown message should not be tracked")

        tracker.register("msg1")
        assertTrue(tracker.isTracked("msg1"), "Registered message should be tracked")

        // After resolution, still tracked (tombstoned)
        tracker.recordOutcome("msg1", DeliveryOutcome.CONFIRMED)
        assertTrue(tracker.isTracked("msg1"), "Resolved message should still be tracked (tombstone)")
    }
}
