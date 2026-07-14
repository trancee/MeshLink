package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.engine.assembly.MeshEngineRuntimeSurface
import ch.trancee.meshlink.engine.transfer.DeliveryRetryScheduler
import ch.trancee.meshlink.engine.transfer.RetryWakeup
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

class DeliveryRetrySchedulerTest {
    @Test
    fun `awaitRetry returns deadline expired when no retry budget remains`() =
        runBlocking<Unit> {
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
        runBlocking<Unit> {
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
                        remainingBudget = 200.milliseconds,
                        lastObservedTopologyVersion = 3L,
                        runtimeGate = runtimeSurface.runtimeGate,
                        hardRunToken = hardRunToken,
                    )
                }

            // Act
            topologyVersion.value = 4L
            val result = resultDeferred.await()

            // Assert
            val changed = assertIs<RetryWakeup.TopologyChanged>(result)
            assertEquals(4L, changed.topologyVersion)
        }

    @Test
    fun `awaitRetry returns timer elapsed when the retry delay consumes the remaining budget`() =
        runBlocking<Unit> {
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
                    remainingBudget = 20.milliseconds,
                    lastObservedTopologyVersion = 5L,
                    runtimeGate = runtimeSurface.runtimeGate,
                    hardRunToken = hardRunToken,
                )

            // Assert
            val elapsed = assertIs<RetryWakeup.TimerElapsed>(result)
            assertEquals(5L, elapsed.topologyVersion)
        }

    @Test
    fun `awaitRetry returns hard run ended when the runtime stops during the wait`() =
        runBlocking<Unit> {
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
                        remainingBudget = 200.milliseconds,
                        lastObservedTopologyVersion = 1L,
                        runtimeGate = runtimeSurface.runtimeGate,
                        hardRunToken = hardRunToken,
                    )
                }

            // Act
            runtimeSurface.setLifecycleState(MeshLinkState.Stopped)
            val result = resultDeferred.await()

            // Assert
            assertEquals(RetryWakeup.HardRunEnded, result)
        }
}

private object ZeroRandom : Random() {
    override fun nextBits(bitCount: Int): Int = 0
}
