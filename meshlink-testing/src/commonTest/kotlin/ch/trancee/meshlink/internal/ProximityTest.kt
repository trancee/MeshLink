package ch.trancee.meshlink.internal

import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.ProximityBucket
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A node's peers carry their proximity from the radio's RSSI (ADR 0007 decision 10, ticket 32 Q5 and Q6). */
@OptIn(ExperimentalCoroutinesApi::class, InternalMeshLinkApi::class)
class ProximityTest {
    @Test
    fun aCentralMeasuresItsPeerAndRepublishesWhenThePeerMovesAway() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val bob = air.platform(StandardTestDispatcher(testScheduler))
            air.setRssi(alice.radio, bob.radio, NEAR_DBM)
            val nodes = listOf(node(alice), node(bob).apply { restrictedLinkRole = LinkRole.PERIPHERAL })
            nodes.forEach { it.start() }

            // Act
            advanceTimeBy(SETTLE_MILLIS + POLL_MILLIS)
            val near =
                nodes[0]
                    .peers.value
                    .single()
                    .proximity
            air.setRssi(alice.radio, bob.radio, FAR_DBM)
            advanceTimeBy(MOVE_MILLIS)
            val far =
                nodes[0]
                    .peers.value
                    .single()
                    .proximity

            // Assert
            assertEquals(NEAR_DBM.toDouble(), near?.rssi)
            assertEquals(ProximityBucket.Near, near?.bucket)
            assertEquals(ProximityBucket.Far, far?.bucket)
        }

    @Test
    fun aPeripheralThatDoesNotScanHasNoMeasurement() =
        runTest {
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val bob = air.platform(StandardTestDispatcher(testScheduler))
            val nodes = listOf(node(alice), node(bob).apply { restrictedLinkRole = LinkRole.PERIPHERAL })
            nodes.forEach { it.start() }

            advanceTimeBy(SETTLE_MILLIS + POLL_MILLIS)
            val proximity =
                nodes[1]
                    .peers.value
                    .single()
                    .proximity

            assertNull(proximity?.rssi)
            assertEquals(ProximityBucket.Unknown, proximity?.bucket)
        }

    private companion object {
        const val NEAR_DBM = -70
        const val FAR_DBM = -95
        const val POLL_MILLIS = 10_000L

        /** Long enough for several readings to pull the average below −75 dBm. */
        const val MOVE_MILLIS = 40_000L
    }
}
