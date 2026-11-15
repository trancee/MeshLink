package ch.trancee.meshlink.internal

import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshLink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class, InternalMeshLinkApi::class)
class LinkRoleTest {
    @Test
    fun nodeRestrictedToPeripheralAdvertisesWithoutScanningAndIsAlwaysThePeripheral() {
        for (seed in 1L..SEEDS) {
            runTest {
                // Arrange
                val air = air(seed)
                val alice = air.platform(StandardTestDispatcher(testScheduler))
                val bob = air.platform(StandardTestDispatcher(testScheduler))
                val aliceNode = node(alice).apply { restrictedLinkRole = LinkRole.PERIPHERAL }
                val bobNode = node(bob)

                // Act
                aliceNode.start()
                bobNode.start()
                advanceTimeBy(SETTLE_MILLIS)

                // Assert
                assertNotNull(alice.radio.advertisement, "seed $seed")
                assertFalse(alice.radio.scanning, "seed $seed")
                assertEquals(mapOf(nodeId(bob).toList() to LinkRole.PERIPHERAL), aliceNode.roles(), "seed $seed")
                assertEquals(mapOf(nodeId(alice).toList() to LinkRole.CENTRAL), bobNode.roles(), "seed $seed")
            }
        }
    }

    @Test
    fun nodeRestrictedToCentralScansWithoutServingOrAdvertisingAndIsAlwaysTheCentral() {
        for (seed in 1L..SEEDS) {
            runTest {
                // Arrange
                val air = air(seed)
                val alice = air.platform(StandardTestDispatcher(testScheduler))
                val bob = air.platform(StandardTestDispatcher(testScheduler))
                val aliceNode = node(alice).apply { restrictedLinkRole = LinkRole.CENTRAL }
                val bobNode = node(bob)

                // Act
                aliceNode.start()
                bobNode.start()
                advanceTimeBy(SETTLE_MILLIS)

                // Assert
                assertNull(alice.radio.advertisement, "seed $seed")
                assertNull(alice.radio.capability, "seed $seed")
                assertTrue(alice.radio.scanning, "seed $seed")
                assertEquals(mapOf(nodeId(bob).toList() to LinkRole.CENTRAL), aliceNode.roles(), "seed $seed")
                assertEquals(mapOf(nodeId(alice).toList() to LinkRole.PERIPHERAL), bobNode.roles(), "seed $seed")
            }
        }
    }

    @Test
    fun twoNodesRestrictedToTheSameRoleNeverLink() =
        runTest {
            // Arrange
            val air = air()
            val nodes =
                List(2) { node(air.platform(StandardTestDispatcher(testScheduler))) }
                    .onEach { it.restrictedLinkRole = LinkRole.CENTRAL }

            // Act
            nodes.forEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertTrue(nodes.all { it.peers.value.isEmpty() && it.linkRoles.isEmpty() })
        }

    @Test
    fun liftedRestrictionAppliesFromTheNextStart() =
        runTest {
            // Arrange
            val platform = air().platform(StandardTestDispatcher(testScheduler))
            val node = node(platform).apply { restrictedLinkRole = LinkRole.CENTRAL }
            node.start()
            runCurrent()
            node.stop()

            // Act
            node.restrictedLinkRole = null
            node.start()
            runCurrent()

            // Assert
            assertNotNull(platform.radio.advertisement)
            assertTrue(platform.radio.scanning)
        }

    @Test
    fun linkRolesForgetALinkWhenItCloses() =
        runTest {
            // Arrange
            val air = air()
            val nodes = List(2) { node(air.platform(StandardTestDispatcher(testScheduler))) }
            nodes.forEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)
            val linked = nodes[0].linkRoles.size

            // Act
            nodes[1].stop()
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(1, linked)
            assertEquals(emptyMap(), nodes[0].linkRoles)
            assertEquals(emptyMap(), nodes[1].linkRoles)
        }

    private fun MeshLink.roles(): Map<List<Byte>, LinkRole> = linkRoles.mapKeys { it.key.toByteArray().toList() }

    private companion object {
        const val SEEDS = 10L
    }
}
