package io.meshlink.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class L2capBackpressureDetectorTest {

    @Test
    fun fastWritesNeverTriggerBackpressure() {
        var now = 0L
        val detector = L2capBackpressureDetector(clock = { now })

        // All writes under 100ms
        repeat(10) {
            now += 500L
            assertFalse(detector.recordWrite(50), "Fast write should not trigger backpressure")
        }
    }

    @Test
    fun threeSlowWritesWithinWindowTriggersBackpressure() {
        var now = 0L
        val detector = L2capBackpressureDetector(clock = { now })

        now = 1000L
        assertFalse(detector.recordWrite(150), "1st slow write → no backpressure yet")
        now = 2000L
        assertFalse(detector.recordWrite(200), "2nd slow write → no backpressure yet")
        now = 3000L
        assertTrue(detector.recordWrite(101), "3rd slow write → backpressure detected")
    }

    @Test
    fun slowWritesOutsideWindowDoNotCount() {
        var now = 0L
        val detector = L2capBackpressureDetector(windowMs = 7_000L, clock = { now })

        now = 1000L
        detector.recordWrite(150)
        now = 2000L
        detector.recordWrite(150)

        // Advance past the window relative to the first two writes
        now = 9000L
        assertFalse(detector.recordWrite(150), "Old slow writes expired; only 1 in window")
    }

    @Test
    fun exactlyAtThresholdIsNotSlow() {
        var now = 0L
        val detector = L2capBackpressureDetector(slowWriteMs = 100, clock = { now })

        // 100ms is not > 100ms, so it should not count as slow
        now = 1000L
        detector.recordWrite(100)
        now = 2000L
        detector.recordWrite(100)
        now = 3000L
        assertFalse(detector.recordWrite(100), "Exactly 100ms should not be considered slow")
    }

    @Test
    fun mixOfFastAndSlowWritesBelowThreshold() {
        var now = 0L
        val detector = L2capBackpressureDetector(clock = { now })

        now = 1000L
        detector.recordWrite(50)   // fast
        now = 1500L
        detector.recordWrite(150)  // slow #1
        now = 2000L
        detector.recordWrite(30)   // fast
        now = 2500L
        detector.recordWrite(200)  // slow #2
        now = 3000L
        assertFalse(detector.recordWrite(10), "Only 2 slow writes → no backpressure")
    }

    @Test
    fun resetClearsHistory() {
        var now = 0L
        val detector = L2capBackpressureDetector(clock = { now })

        now = 1000L
        detector.recordWrite(200)
        now = 2000L
        detector.recordWrite(200)

        detector.reset()

        // After reset, a single slow write should not trigger
        now = 3000L
        assertFalse(detector.recordWrite(200), "After reset only 1 slow write → no backpressure")
    }

    @Test
    fun backpressureClearsWhenOldEntriesExpire() {
        var now = 0L
        val detector = L2capBackpressureDetector(windowMs = 7_000L, clock = { now })

        now = 1000L
        detector.recordWrite(200)
        now = 2000L
        detector.recordWrite(200)
        now = 3000L
        assertTrue(detector.recordWrite(200), "3 slow writes → backpressure")

        // Advance so all three entries expire
        now = 11_000L
        assertFalse(detector.recordWrite(50), "All slow writes expired; fast write → no backpressure")
    }

    @Test
    fun customThresholds() {
        var now = 0L
        val detector = L2capBackpressureDetector(
            slowWriteMs = 50,
            slowThreshold = 2,
            windowMs = 1_000L,
            clock = { now },
        )

        now = 100L
        assertFalse(detector.recordWrite(60), "1st slow → not yet")
        now = 200L
        assertTrue(detector.recordWrite(60), "2nd slow within 1s → backpressure")
    }

    @Test
    fun windowBoundaryExactlyAtCutoff() {
        var now = 0L
        val detector = L2capBackpressureDetector(windowMs = 7_000L, clock = { now })

        now = 1000L
        detector.recordWrite(200) // recorded at t=1000
        now = 2000L
        detector.recordWrite(200) // recorded at t=2000

        // At t=8000, cutoff = 8000-7000 = 1000. Entries at t<=1000 are evicted.
        now = 8000L
        // Only t=2000 remains; this new slow write at t=8000 makes 2 in window
        assertFalse(detector.recordWrite(200), "Entry at t=1000 evicted at cutoff; 2 slow < 3")
    }
}
