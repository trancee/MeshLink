package ch.trancee.meshlink.transport

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WriteLatencyTrackerTest {

    // ── Threshold behaviour ───────────────────────────────────────────────────

    @Test
    fun belowThresholdReturnsFalse() {
        var time = 0L
        val tracker =
            WriteLatencyTracker(
                windowMillis = 5_000L,
                threshold = 5,
                latencyThresholdMillis = 200L,
                clock = { time },
            )
        for (t in 1..4) {
            time = (t * 100).toLong()
            assertFalse(tracker.recordWrite(300L))
        }
    }

    @Test
    fun atThresholdReturnsTrue() {
        var time = 0L
        val tracker =
            WriteLatencyTracker(
                windowMillis = 5_000L,
                threshold = 5,
                latencyThresholdMillis = 200L,
                clock = { time },
            )
        for (t in 1..4) {
            time = (t * 100).toLong()
            tracker.recordWrite(300L)
        }
        time = 500L
        assertTrue(tracker.recordWrite(300L))
    }

    // ── Sliding window expiry ─────────────────────────────────────────────────

    @Test
    fun windowExpiryPrunesExpiredEntries() {
        var time = 0L
        val tracker =
            WriteLatencyTracker(
                windowMillis = 1_000L,
                threshold = 3,
                latencyThresholdMillis = 200L,
                clock = { time },
            )
        time = 0L
        tracker.recordWrite(300L) // will expire: 1100 - 0 = 1100 > 1000

        time = 500L
        tracker.recordWrite(300L) // stays valid: 1100 - 500 = 600 ≤ 1000

        // At t=1100: the t=0 entry is pruned (true branch of removeAll predicate),
        //            the t=500 entry is kept (false branch).
        //            New entry added at 1100 → entries=[500, 1100], count=2 < threshold=3 → false.
        time = 1_100L
        assertFalse(tracker.recordWrite(300L))
    }

    @Test
    fun entriesWithinWindowAreKept() {
        var time = 0L
        val tracker =
            WriteLatencyTracker(
                windowMillis = 5_000L,
                threshold = 3,
                latencyThresholdMillis = 200L,
                clock = { time },
            )
        time = 0L
        tracker.recordWrite(300L)
        time = 100L
        tracker.recordWrite(300L)
        // Both entries are still in window; new entry makes count=3 == threshold=3 → true
        time = 200L
        assertTrue(tracker.recordWrite(300L))
    }

    // ── reset ─────────────────────────────────────────────────────────────────

    @Test
    fun resetClearsAllEntries() {
        var time = 0L
        val tracker =
            WriteLatencyTracker(
                windowMillis = 5_000L,
                threshold = 2,
                latencyThresholdMillis = 200L,
                clock = { time },
            )
        time = 0L
        tracker.recordWrite(300L)
        time = 100L
        tracker.recordWrite(300L)
        tracker.reset()
        // After reset, only 1 slow write → count=1 < threshold=2 → false
        time = 200L
        assertFalse(tracker.recordWrite(300L))
    }

    // ── Latency threshold boundary ────────────────────────────────────────────

    @Test
    fun exactlyAtLatencyThresholdNotCounted() {
        var time = 0L
        val tracker =
            WriteLatencyTracker(
                windowMillis = 5_000L,
                threshold = 1,
                latencyThresholdMillis = 200L,
                clock = { time },
            )
        // durationMillis = 200 is NOT strictly > 200 → not counted → false
        assertFalse(tracker.recordWrite(200L))
    }

    @Test
    fun fastWriteBelowLatencyThresholdNotCounted() {
        var time = 0L
        val tracker =
            WriteLatencyTracker(
                windowMillis = 5_000L,
                threshold = 1,
                latencyThresholdMillis = 200L,
                clock = { time },
            )
        assertFalse(tracker.recordWrite(100L))
    }

    @Test
    fun oneAboveLatencyThresholdCountedImmediately() {
        var time = 0L
        val tracker =
            WriteLatencyTracker(
                windowMillis = 5_000L,
                threshold = 1,
                latencyThresholdMillis = 200L,
                clock = { time },
            )
        // durationMillis = 201 > 200 → counted; threshold=1 → true
        assertTrue(tracker.recordWrite(201L))
    }
}
