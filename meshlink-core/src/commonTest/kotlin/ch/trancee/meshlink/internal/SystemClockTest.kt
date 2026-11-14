package ch.trancee.meshlink.internal

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock as KotlinClock

class SystemClockTest {
    @Test
    fun wallClockReadsUnixMilliseconds() {
        val before = KotlinClock.System.now().toEpochMilliseconds()

        val wall = SystemClock.wallClockMillis()

        val after = KotlinClock.System.now().toEpochMilliseconds()
        assertTrue(wall in before..after, "$wall not in $before..$after")
    }

    @Test
    fun monotonicClockNeverGoesBackward() {
        val readings = List(READINGS) { SystemClock.monotonicMillis() }

        assertTrue(readings.zipWithNext().all { (earlier, later) -> later >= earlier })
        assertTrue(readings.first() >= 0)
    }

    private companion object {
        const val READINGS = 1_000
    }
}
