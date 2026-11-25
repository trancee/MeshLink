package ch.trancee.meshlink.internal

import ch.trancee.meshlink.CentralOnlyReason
import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.Limitation
import ch.trancee.meshlink.MeshState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class, InternalMeshLinkApi::class)
class CentralOnlyTest {
    @Test
    fun nodeOnAPortThatCannotAdvertiseRunsCentralOnlyAndLinksAsCentral() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val bob = air.platform(StandardTestDispatcher(testScheduler))
            alice.limitations.value = NO_ADVERTISER
            val aliceNode = node(alice)
            val bobNode = node(bob)

            // Act
            aliceNode.start()
            bobNode.start()
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(MeshState.Running(NO_ADVERTISER), aliceNode.state.value)
            assertEquals(emptyList(), alice.radio.advertisements)
            assertNull(alice.radio.capability)
            assertTrue(alice.radio.scanning)
            assertEquals(listOf(LinkRole.CENTRAL), aliceNode.linkRoles.values.toList())
            assertEquals(MeshState.Running(emptySet()), bobNode.state.value)
        }

    @Test
    fun nodeWhosePortRegainsItsAdvertiserAdvertisesAgain() =
        runTest {
            // Arrange
            val platform = air().platform(StandardTestDispatcher(testScheduler))
            platform.limitations.value = NO_ADVERTISER
            val node = node(platform)
            node.start()
            runCurrent()

            // Act
            platform.limitations.value = emptySet()
            runCurrent()

            // Assert
            assertEquals(MeshState.Running(emptySet()), node.state.value)
            assertNotNull(platform.radio.advertisement)
            assertTrue(platform.radio.scanning)
        }

    @Test
    fun lateAdvertisingFailureLeavesTheNodeCentralOnlyForTheRestOfTheRun() =
        runTest {
            // Arrange
            val platform = air().platform(StandardTestDispatcher(testScheduler))
            val node = node(platform)
            node.start()
            runCurrent()

            // Act
            platform.radio.emit(RadioEvent.AdvertisingFailed(RadioFailure.RADIO_ERROR))
            advanceTimeBy(EPOCH_MILLIS * 2)

            // Assert
            assertEquals(MeshState.Running(ADVERTISING_FAILED), node.state.value)
            assertNull(platform.radio.advertisement)
            assertEquals(1, platform.radio.advertisements.size)
            assertNull(node.radioFailure)
            assertTrue(
                platform.radio.scanLog
                    .last()
                    .first > EPOCH_MILLIS,
            )
        }

    @Test
    fun theNextRunAfterAnAdvertisingFailureAdvertisesAgain() =
        runTest {
            // Arrange
            val platform = air().platform(StandardTestDispatcher(testScheduler))
            val node = node(platform)
            node.start()
            runCurrent()
            platform.radio.emit(RadioEvent.AdvertisingFailed(RadioFailure.RADIO_ERROR))
            runCurrent()
            node.stop()

            // Act
            node.start()
            runCurrent()

            // Assert
            assertEquals(MeshState.Running(emptySet()), node.state.value)
            assertNotNull(platform.radio.advertisement)
        }

    private companion object {
        val NO_ADVERTISER: Set<Limitation> = setOf(Limitation.CentralOnly(CentralOnlyReason.NoAdvertiser))
        val ADVERTISING_FAILED: Set<Limitation> = setOf(Limitation.CentralOnly(CentralOnlyReason.AdvertisingFailed))
        const val EPOCH_MILLIS = Derivations.EPOCH_SECONDS * 1_000L
    }
}
