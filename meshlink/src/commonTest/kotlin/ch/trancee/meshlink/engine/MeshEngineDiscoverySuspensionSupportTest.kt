package ch.trancee.meshlink.engine

import ch.trancee.meshlink.engine.lifecycle.MeshEngineDiscoverySuspensionSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class MeshEngineDiscoverySuspensionSupportTest {
    @Test
    fun `withDiscoverySuspended returns the block result without toggling when suspension is disabled`() =
        runBlocking<Unit> {
            // Arrange
            val calls = mutableListOf<Boolean>()
            val support = discoverySuspensionSupport(calls)

            // Act
            val result =
                support.withDiscoverySuspended(shouldSuspend = false) { "result-from-block" }

            // Assert
            assertEquals("result-from-block", result)
            assertEquals(emptyList(), calls)
        }

    @Test
    fun `withDiscoverySuspended toggles discovery around the block when suspension is enabled`() =
        runBlocking<Unit> {
            // Arrange
            val calls = mutableListOf<Boolean>()
            val support = discoverySuspensionSupport(calls)

            // Act
            val result = support.withDiscoverySuspended { "result-from-block" }

            // Assert
            assertEquals("result-from-block", result)
            assertEquals(listOf(true, false), calls)
        }

    @Test
    fun `withDiscoverySuspended resumes discovery when the block throws`() =
        runBlocking<Unit> {
            // Arrange
            val calls = mutableListOf<Boolean>()
            val support = discoverySuspensionSupport(calls)

            // Act
            val error =
                assertFailsWith<IllegalStateException> {
                    support.withDiscoverySuspended { throw IllegalStateException("boom") }
                }

            // Assert
            assertEquals("boom", error.message)
            assertEquals(listOf(true, false), calls)
        }
}

private fun discoverySuspensionSupport(
    calls: MutableList<Boolean>
): MeshEngineDiscoverySuspensionSupport {
    return MeshEngineDiscoverySuspensionSupport { suspended -> calls += suspended }
}
