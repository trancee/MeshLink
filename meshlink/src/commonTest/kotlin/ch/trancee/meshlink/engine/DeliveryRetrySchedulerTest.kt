package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.MeshLinkState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

class DeliveryRetrySchedulerTest {
    private companion object {
        private const val TEST_TIMING_SLACK_MULTIPLIER: Int = 4
    }

    @Test
    fun `awaitRetry returns deadline expired when no retry budget remains`() = runBlocking {
        // Arrange
        val topologyVersion = MutableStateFlow(7L)
        val runtimeSurface = MeshEngineRuntimeSurface()
        val hardRunToken = runtimeSurface.beginHardRun()
        val scheduler =
            DeliveryRetryScheduler(topologyVersion = topologyVersion, random = ZeroRandom)

        // Act
        val result =
            scheduler.awaitRetry(
                attempt = 0,
                remainingBudget = 0.milliseconds,
                lastObservedTopologyVersion = 7L,
                runtimeGate = runtimeSurface.runtimeGate,
                hardRunToken = hardRunToken,
            )

        // Assert
        val expired = assertIs<RetryWakeup.DeadlineExpired>(result)
        assertEquals(7L, expired.topologyVersion)
    }

    @Test
    fun `awaitRetry returns topology changed when the topology advances before the timer elapses`() =
        runBlocking {
            // Arrange
            val topologyVersion = MutableStateFlow(3L)
            val runtimeSurface = MeshEngineRuntimeSurface()
            val hardRunToken = runtimeSurface.beginHardRun()
            val scheduler =
                DeliveryRetryScheduler(topologyVersion = topologyVersion, random = ZeroRandom)
            val resultDeferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    scheduler.awaitRetry(
                        attempt = 0,
                        remainingBudget = testDuration(200),
                        lastObservedTopologyVersion = 3L,
                        runtimeGate = runtimeSurface.runtimeGate,
                        hardRunToken = hardRunToken,
                    )
                }

            // Act
            testDelay(10)
            topologyVersion.value = 4L
            val result = resultDeferred.await()

            // Assert
            val changed = assertIs<RetryWakeup.TopologyChanged>(result)
            assertEquals(4L, changed.topologyVersion)
        }

    @Test
    fun `awaitRetry returns timer elapsed when the retry delay consumes the remaining budget`() =
        runBlocking {
            // Arrange
            val topologyVersion = MutableStateFlow(5L)
            val runtimeSurface = MeshEngineRuntimeSurface()
            val hardRunToken = runtimeSurface.beginHardRun()
            val scheduler =
                DeliveryRetryScheduler(topologyVersion = topologyVersion, random = ZeroRandom)

            // Act
            val result =
                scheduler.awaitRetry(
                    attempt = 0,
                    remainingBudget = testDuration(20),
                    lastObservedTopologyVersion = 5L,
                    runtimeGate = runtimeSurface.runtimeGate,
                    hardRunToken = hardRunToken,
                )

            // Assert
            val elapsed = assertIs<RetryWakeup.TimerElapsed>(result)
            assertEquals(5L, elapsed.topologyVersion)
        }

    @Test
    fun `awaitRetry returns hard run ended when the runtime stops during the wait`() = runBlocking {
        // Arrange
        val topologyVersion = MutableStateFlow(1L)
        val runtimeSurface = MeshEngineRuntimeSurface()
        val hardRunToken = runtimeSurface.beginHardRun()
        val scheduler =
            DeliveryRetryScheduler(topologyVersion = topologyVersion, random = ZeroRandom)
        val resultDeferred =
            async(start = CoroutineStart.UNDISPATCHED) {
                scheduler.awaitRetry(
                    attempt = 0,
                    remainingBudget = testDuration(200),
                    lastObservedTopologyVersion = 1L,
                    runtimeGate = runtimeSurface.runtimeGate,
                    hardRunToken = hardRunToken,
                )
            }

        // Act
        testDelay(10)
        runtimeSurface.setLifecycleState(MeshLinkState.Stopped)
        val result = resultDeferred.await()

        // Assert
        assertEquals(RetryWakeup.HardRunEnded, result)
    }

    private fun testDuration(milliseconds: Int) =
        milliseconds.milliseconds * TEST_TIMING_SLACK_MULTIPLIER

    private suspend fun testDelay(milliseconds: Int): Unit =
        delay(milliseconds.toLong() * TEST_TIMING_SLACK_MULTIPLIER)
}

private object ZeroRandom : Random() {
    override fun nextBits(bitCount: Int): Int = 0
}
