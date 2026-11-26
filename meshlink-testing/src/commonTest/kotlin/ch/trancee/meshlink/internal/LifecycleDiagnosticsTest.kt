package ch.trancee.meshlink.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The lifecycle diagnostics (ADR 0020 decision 7, `ML2004`, `ML8002` and `ML8003`). */
@OptIn(ExperimentalCoroutinesApi::class)
class LifecycleDiagnosticsTest {
    @Test
    fun aNodeReportsTheBackgroundWhenItStartsThereAndEachTimeTheAppReturnsToIt() =
        runTest {
            // Arrange
            val platform = air().platform(StandardTestDispatcher(testScheduler))
            platform.background.value = true
            val node = node(platform)
            val seen = diagnostics(node, "ML2004")

            // Act
            node.start()
            advanceTimeBy(COALESCING_MILLIS + 1)
            platform.background.value = false
            runCurrent()
            platform.background.value = true
            runCurrent()

            // Assert
            val expected = listOf("ML2004 BackgroundDiscoveryDegraded() x1", "ML2004 BackgroundDiscoveryDegraded() x1")
            assertEquals(expected, seen.map { it.toString() })
        }

    @Test
    fun aNodeReportsDozeWhenItStartsInItAndEachTimeTheDeviceEntersIt() =
        runTest {
            // Arrange
            val platform = air().platform(StandardTestDispatcher(testScheduler))
            platform.doze.value = true
            val node = node(platform)
            val seen = diagnostics(node, "ML8003")

            // Act
            node.start()
            advanceTimeBy(COALESCING_MILLIS + 1)
            platform.doze.value = false
            runCurrent()
            platform.doze.value = true
            runCurrent()

            // Assert
            assertEquals(listOf("ML8003 DozeActive() x1", "ML8003 DozeActive() x1"), seen.map { it.toString() })
        }

    @Test
    fun aRunningNodeReportsEachSuspensionWithItsLength() =
        runTest {
            // Arrange
            val platform = air().platform(StandardTestDispatcher(testScheduler))
            val node = node(platform)
            val seen = diagnostics(node, "ML8002")
            node.start()
            runCurrent()

            // Act
            platform.suspensions.emit(SUSPENDED_MILLIS)
            runCurrent()

            // Assert
            assertEquals(listOf("ML8002 AppSuspendedByOs(suspendedMillis=45000) x1"), seen.map { it.toString() })
        }

    @Test
    fun aStoppedNodeReportsNoLifecycleChange() =
        runTest {
            // Arrange
            val platform = air().platform(StandardTestDispatcher(testScheduler))
            val node = node(platform)
            val seen = diagnostics(node, "ML2004", "ML8002", "ML8003")

            // Act
            platform.background.value = true
            platform.doze.value = true
            platform.suspensions.emit(SUSPENDED_MILLIS)
            runCurrent()

            // Assert
            assertEquals(emptyList(), seen.map { it.toString() })
        }

    private companion object {
        const val COALESCING_MILLIS = 10_000L
        const val SUSPENDED_MILLIS = 45_000L
    }
}
