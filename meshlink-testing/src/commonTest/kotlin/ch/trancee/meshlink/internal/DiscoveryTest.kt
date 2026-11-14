package ch.trancee.meshlink.internal

import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.Requirement
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.meshLinkConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class, InternalMeshLinkApi::class)
class DiscoveryTest {
    @Test
    fun nodesOfOneMeshFindEachOtherWithinTheFirstScanWindowAndIgnoreAnotherMesh() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val bob = air.platform(StandardTestDispatcher(testScheduler))
            val carol = air.platform(StandardTestDispatcher(testScheduler))
            val nodes = listOf(node(alice, "Festival"), node(bob, "Festival"), node(carol, "Elsewhere"))

            // Act
            nodes.forEach { it.start() }
            advanceTimeBy(FIRST_SCAN_WINDOW_MILLIS)

            // Assert
            assertEquals(setOf<PeerHandle>(bob.radio.handle), nodes[0].linkCandidates())
            assertEquals(setOf<PeerHandle>(alice.radio.handle), nodes[1].linkCandidates())
            assertEquals(emptySet(), nodes[2].linkCandidates())
        }

    @Test
    fun runningNodeAdvertisesAndScansAndStopsBothWhenStopped() =
        runTest {
            // Arrange
            val platform = air().platform(StandardTestDispatcher(testScheduler))
            val node = node(platform, "Festival")

            // Act
            node.start()
            runCurrent()
            val whileRunning = platform.radio.advertisement != null && platform.radio.scanning
            node.stop()

            // Assert
            assertTrue(whileRunning)
            assertNull(platform.radio.advertisement)
            assertFalse(platform.radio.scanning)
        }

    @Test
    fun blockedNodeStopsDiscoveryAndForgetsItsCandidates() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val bob = air.platform(StandardTestDispatcher(testScheduler))
            val node = node(alice, "Festival")
            node(bob, "Festival").start()
            node.start()
            advanceTimeBy(FIRST_SCAN_WINDOW_MILLIS)

            // Act
            alice.radio.report(setOf(Requirement.BluetoothOff))
            runCurrent()

            // Assert
            assertNull(alice.radio.advertisement)
            assertFalse(alice.radio.scanning)
            assertEquals(emptySet(), node.linkCandidates())
        }

    @Test
    fun failureToAdvertiseStopsDiscoveryAndIsRecorded() =
        runTest {
            // Arrange
            val virtual = air().platform(StandardTestDispatcher(testScheduler))
            val radio =
                object : RadioPort by virtual.radio {
                    override fun startAdvertising(payload: ByteArray) = RadioResult.Failed(RadioFailure.UNSUPPORTED)
                }
            val node = node(platformWith(virtual, radio), "Festival")

            // Act
            node.start()
            runCurrent()

            // Assert
            assertEquals(RadioFailure.UNSUPPORTED, node.radioFailure())
            assertFalse(virtual.radio.scanning)
        }

    @Test
    fun failureToServeTheCapabilityRecordStopsDiscoveryAndIsRecorded() =
        runTest {
            // Arrange
            val virtual = air().platform(StandardTestDispatcher(testScheduler))
            val radio =
                object : RadioPort by virtual.radio {
                    override fun serveCapability(record: ByteArray) = RadioResult.Failed(RadioFailure.UNSUPPORTED)
                }
            val node = node(platformWith(virtual, radio), "Festival")

            // Act
            node.start()
            runCurrent()

            // Assert
            assertEquals(RadioFailure.UNSUPPORTED, node.radioFailure())
            assertNull(virtual.radio.advertisement)
            assertFalse(virtual.radio.scanning)
        }

    @Test
    fun failureToScanStopsDiscoveryAndIsRecorded() =
        runTest {
            // Arrange
            val virtual = air().platform(StandardTestDispatcher(testScheduler))
            val radio =
                object : RadioPort by virtual.radio {
                    override fun startScanning() = RadioResult.Failed(RadioFailure.UNSUPPORTED)
                }
            val node = node(platformWith(virtual, radio), "Festival")

            // Act
            node.start()
            runCurrent()

            // Assert
            assertEquals(RadioFailure.UNSUPPORTED, node.radioFailure())
            assertNull(virtual.radio.advertisement)
        }

    @Test
    fun runningNodeRecordsNoRadioFailure() =
        runTest {
            val node = node(air().platform(StandardTestDispatcher(testScheduler)), "Festival")

            node.start()
            runCurrent()

            assertNull(node.radioFailure())
        }

    private fun TestScope.air(): VirtualAir = VirtualAir(backgroundScope, TestClock(testScheduler), SeededRandom(1))

    private fun platformWith(
        virtual: VirtualPlatform,
        radio: RadioPort,
    ): PlatformPort =
        object : PlatformPort by virtual {
            override val radio: RadioPort = radio
        }

    private fun node(
        platform: PlatformPort,
        meshId: String,
    ): MeshLink =
        MeshLink.create(
            meshLinkConfig(Preset.OfflineChat, SecurityProfile.Unsecured) { this.meshId = meshId }.build(),
            MeshPlatform.of(platform),
        )

    private companion object {
        // VirtualAir models no scan windows yet: a sighting arrives within one maximum air latency.
        val FIRST_SCAN_WINDOW_MILLIS: Long = VirtualAir.DEFAULT_LATENCY_MILLIS.last + 1L
    }
}
