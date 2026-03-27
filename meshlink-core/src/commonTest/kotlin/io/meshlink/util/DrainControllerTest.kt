package io.meshlink.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DrainControllerTest {

    @Test
    fun allItemsDrainBeforeTimeout() = runTest {
        var active = 3
        val controller = DrainController(drainTimeoutMs = 5_000L, pollIntervalMs = 100L)

        val job = launch {
            delay(250)
            active = 0
        }

        val result = controller.drain { active }
        job.join()

        assertEquals(0, result, "All items drained, should return 0")
    }

    @Test
    fun noItemsActiveReturnsImmediately() = runTest {
        val controller = DrainController(drainTimeoutMs = 5_000L, pollIntervalMs = 100L)

        val result = controller.drain { 0 }

        assertEquals(0, result, "No active items should return 0 immediately")
    }

    @Test
    fun itemsNeverDrainReturnsRemainingAfterTimeout() = runTest {
        val controller = DrainController(drainTimeoutMs = 1_000L, pollIntervalMs = 100L)

        val result = controller.drain { 5 }

        assertEquals(5, result, "Items never drained, should return remaining count")
    }

    @Test
    fun partialDrainReturnsRemainingCount() = runTest {
        var active = 10
        val controller = DrainController(drainTimeoutMs = 1_000L, pollIntervalMs = 100L)

        val job = launch {
            delay(300)
            active = 3
        }

        val result = controller.drain { active }
        job.join()

        assertEquals(3, result, "Partial drain should return remaining count")
    }

    @Test
    fun zeroTimeoutReturnsImmediately() = runTest {
        val controller = DrainController(drainTimeoutMs = 0L, pollIntervalMs = 100L)

        val result = controller.drain { 7 }

        assertEquals(7, result, "Zero timeout should return current count immediately")
    }

    @Test
    fun negativeTimeoutReturnsImmediately() = runTest {
        val controller = DrainController(drainTimeoutMs = -1L, pollIntervalMs = 100L)

        val result = controller.drain { 4 }

        assertEquals(4, result, "Negative timeout should return current count immediately")
    }

    @Test
    fun itemsDrainAtVaryingRates() = runTest {
        var active = 5
        val controller = DrainController(drainTimeoutMs = 5_000L, pollIntervalMs = 50L)

        val job = launch {
            delay(100)
            active = 3
            delay(200)
            active = 1
            delay(300)
            active = 0
        }

        val result = controller.drain { active }
        job.join()

        assertEquals(0, result, "All items should eventually drain")
    }

    @Test
    fun enabledPropertyReflectsTimeoutConfiguration() {
        assertTrue(DrainController(drainTimeoutMs = 5_000L).enabled, "Positive timeout → enabled")
        assertTrue(DrainController(drainTimeoutMs = 1L).enabled, "Minimal positive timeout → enabled")
        assertFalse(DrainController(drainTimeoutMs = 0L).enabled, "Zero timeout → disabled")
        assertFalse(DrainController(drainTimeoutMs = -1L).enabled, "Negative timeout → disabled")
        assertFalse(DrainController(drainTimeoutMs = -100L).enabled, "Large negative timeout → disabled")
    }

    @Test
    fun fastDrainOnFirstPoll() = runTest {
        var active = 5
        val controller = DrainController(drainTimeoutMs = 5_000L, pollIntervalMs = 100L)

        val job = launch {
            delay(10)
            active = 0
        }

        val result = controller.drain { active }
        job.join()

        assertEquals(0, result, "Fast drain should return 0")
    }

    @Test
    fun timeoutAccuracyDoesNotWaitMuchLonger() = runTest {
        val timeoutMs = 500L
        val controller = DrainController(drainTimeoutMs = timeoutMs, pollIntervalMs = 50L)

        val startTime = testScheduler.currentTime
        controller.drain { 1 }
        val elapsed = testScheduler.currentTime - startTime

        assertTrue(
            elapsed in timeoutMs..(timeoutMs + 200),
            "Elapsed $elapsed ms should be close to timeout $timeoutMs ms"
        )
    }

    @Test
    fun defaultParameterValues() {
        val controller = DrainController()
        assertTrue(controller.enabled, "Default controller should be enabled")
    }

    @Test
    fun drainWithSingleItemThatCompletes() = runTest {
        var active = 1
        val controller = DrainController(drainTimeoutMs = 2_000L, pollIntervalMs = 50L)

        val job = launch {
            delay(150)
            active = 0
        }

        val result = controller.drain { active }
        job.join()

        assertEquals(0, result, "Single item drained should return 0")
    }
}
