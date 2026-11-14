package ch.trancee.meshlink.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * A [Clock] that reads the coroutine test scheduler, so time advances only in virtual time: a test
 * that waits ten minutes finishes in milliseconds (ADR 0017 decision 2). Wall time starts at
 * [wallClockStartMillis].
 *
 * It is the only reader of the experimental `TestCoroutineScheduler.currentTime`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class TestClock(
    private val scheduler: TestCoroutineScheduler,
    private val wallClockStartMillis: Long = WALL_CLOCK_START_MILLIS,
) : Clock {
    override fun monotonicMillis(): Long = scheduler.currentTime

    override fun wallClockMillis(): Long = wallClockStartMillis + scheduler.currentTime

    companion object {
        /** 2026-01-01T00:00:00Z. */
        const val WALL_CLOCK_START_MILLIS: Long = 1_767_225_600_000
    }
}
