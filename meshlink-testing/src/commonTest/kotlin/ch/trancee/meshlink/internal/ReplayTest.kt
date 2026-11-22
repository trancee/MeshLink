package ch.trancee.meshlink.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** One seed replays one virtual mesh byte for byte (ADR 0017 decision 1), so a failing density cell can be rerun. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplayTest {
    @Test
    fun theSameSeedWritesTheSameBytesAtTheSameTimes() {
        // Arrange
        val first = trace(SEED)

        // Act
        val second = trace(SEED)

        // Assert
        assertTrue(first.isNotEmpty())
        assertEquals(first, second)
    }

    @Test
    fun anotherSeedWritesOtherBytes() {
        // Arrange
        val first = trace(SEED)

        // Act
        val other = trace(SEED + 1)

        // Assert
        assertNotEquals(first, other)
    }

    /** The writes of a three-node line in which the first node sends one message to the last. */
    private fun trace(seed: Long): List<String> {
        val scope = TestScope()
        val mesh = VirtualMesh(scope, seed, listOf(0.0 to 0.0, 25.0 to 0.0, 50.0 to 0.0))
        scope.launch { mesh.start() }
        scope.testScheduler.advanceTimeBy(SETTLE_MILLIS)
        scope.launch { mesh.nodes[0].send(checkNotNull(mesh.idOf(2)), "Hello".encodeToByteArray()) }
        scope.testScheduler.advanceTimeBy(SETTLE_MILLIS)
        assertEquals(listOf("Hello"), mesh.inboxes[2].map { it.message.payload.decodeToString() })
        return mesh.trace().also {
            mesh.close()
            scope.backgroundScope.cancel()
            scope.cancel()
        }
    }

    private companion object {
        const val SEED = 42L
        const val SETTLE_MILLIS = 60_000L
    }
}
