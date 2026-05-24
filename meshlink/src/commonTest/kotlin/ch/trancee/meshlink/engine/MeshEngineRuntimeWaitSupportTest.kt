package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.MeshLinkState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class MeshEngineRuntimeWaitSupportTest {
    @Test
    fun `waitWithRuntimeGate returns completed when the awaited change completes while running`() =
        runBlocking {
            // Arrange
            val runtimeSurface = MeshEngineRuntimeSurface()
            val hardRunToken = runtimeSurface.beginHardRun()

            // Act
            val result =
                waitWithRuntimeGate(
                    runtimeGate = runtimeSurface.runtimeGate,
                    hardRunToken = hardRunToken,
                    maximumActiveWait = 250.milliseconds,
                    awaitChange = { "completed" },
                )

            // Assert
            val completed = assertCompleted(result)
            assertEquals("completed", completed)
        }

    @Test
    fun `waitWithRuntimeGate returns timed out when the awaited change returns null`() =
        runBlocking {
            // Arrange
            val runtimeSurface = MeshEngineRuntimeSurface()
            val hardRunToken = runtimeSurface.beginHardRun()

            // Act
            val result =
                waitWithRuntimeGate(
                    runtimeGate = runtimeSurface.runtimeGate,
                    hardRunToken = hardRunToken,
                    maximumActiveWait = 250.milliseconds,
                    awaitChange = { null },
                )

            // Assert
            assertEquals(MeshEngineRuntimeTimedWaitResult.TimedOut, result)
        }

    @Test
    fun `waitWithRuntimeGate returns hard run ended when the hard run is already inactive`() =
        runBlocking {
            // Arrange
            val runtimeSurface = MeshEngineRuntimeSurface()
            val hardRunToken = runtimeSurface.beginHardRun()
            runtimeSurface.setLifecycleState(MeshLinkState.Stopped)

            // Act
            val result =
                waitWithRuntimeGate(
                    runtimeGate = runtimeSurface.runtimeGate,
                    hardRunToken = hardRunToken,
                    maximumActiveWait = 250.milliseconds,
                    awaitChange = { error("awaitChange should not be called") },
                )

            // Assert
            assertEquals(MeshEngineRuntimeTimedWaitResult.HardRunEnded, result)
        }

    @Test
    fun `waitWithRuntimeGate resumes after a pause and retries the awaited change`() = runBlocking {
        // Arrange
        val runtimeSurface = MeshEngineRuntimeSurface()
        val hardRunToken = runtimeSurface.beginHardRun()
        val firstAttemptStarted = CompletableDeferred<Unit>()
        var attempts = 0
        val resultDeferred =
            async(start = CoroutineStart.UNDISPATCHED) {
                waitWithRuntimeGate(
                    runtimeGate = runtimeSurface.runtimeGate,
                    hardRunToken = hardRunToken,
                    maximumActiveWait = 500.milliseconds,
                    awaitChange = {
                        attempts += 1
                        if (attempts == 1) {
                            firstAttemptStarted.complete(Unit)
                            delay(1_000)
                            "first-attempt"
                        } else {
                            "second-attempt"
                        }
                    },
                )
            }

        // Act
        firstAttemptStarted.await()
        runtimeSurface.setLifecycleState(MeshLinkState.Paused)
        delay(10)
        runtimeSurface.setLifecycleState(MeshLinkState.Running)
        val result = resultDeferred.await()

        // Assert
        val completed = assertCompleted(result)
        assertEquals("second-attempt", completed)
        assertEquals(2, attempts)
    }
}

private fun <T> assertCompleted(result: MeshEngineRuntimeTimedWaitResult<T>): T {
    return when (result) {
        is MeshEngineRuntimeTimedWaitResult.Completed -> result.value
        MeshEngineRuntimeTimedWaitResult.TimedOut -> error("Expected Completed but was TimedOut")
        MeshEngineRuntimeTimedWaitResult.HardRunEnded ->
            error("Expected Completed but was HardRunEnded")
    }
}
