package ch.trancee.meshlink.internal

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

class TestClockTest {
    // runTest fails after 60 s of real time, so passing proves the 10 minutes were virtual.
    @Test
    fun tenVirtualMinutesPassOnBothTimelines() =
        runTest {
            val clock = TestClock(testScheduler, wallClockStartMillis = 1_000)

            delay(10.minutes)

            assertEquals(600_000, clock.monotonicMillis())
            assertEquals(601_000, clock.wallClockMillis())
        }

    @Test
    fun wallTimeStartsAtTheFixedDefault() =
        runTest {
            assertEquals(TestClock.WALL_CLOCK_START_MILLIS, TestClock(testScheduler).wallClockMillis())
        }
}
