package ch.trancee.meshlink.internal

import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.Requirement
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.meshLinkConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
    fun nodeOnACocRadioListensAndOffersItsPsmAndTheFlagUntilStopped() =
        runTest {
            // Arrange
            val platform = air().platform(StandardTestDispatcher(testScheduler), coc = true)
            val node = node(platform, "Festival")

            // Act
            node.start()
            runCurrent()
            val listening = platform.radio.listening
            val offer = offer(platform.radio)
            advanceTimeBy(EPOCH_MILLIS)
            val rotated = offer(platform.radio)
            node.stop()

            // Assert
            assertTrue(listening)
            assertEquals(Offer(VirtualRadio.PSM, Advertisement.COC_AVAILABLE), offer)
            assertEquals(offer, rotated)
            assertEquals(2, platform.radio.advertisements.size)
            assertFalse(platform.radio.listening)
        }

    @Test
    fun nodeWithCocOffOrOnARadioWithoutCocOffersNoPsmAndNoFlag() =
        runTest {
            // Arrange
            val air = air()
            val off = air.platform(StandardTestDispatcher(testScheduler), coc = true)
            val unsupported = air.platform(StandardTestDispatcher(testScheduler))
            val central = air.platform(StandardTestDispatcher(testScheduler), coc = true)
            val nodes =
                listOf(
                    node(off, "Festival").apply { coc = false },
                    node(unsupported, "Festival"),
                    node(central, "Festival").apply { restrictedLinkRole = LinkRole.CENTRAL },
                )

            // Act
            nodes.forEach { it.start() }
            runCurrent()

            // Assert
            assertEquals(Offer(null, 0), offer(off.radio))
            assertFalse(off.radio.listening)
            assertEquals(Offer(null, 0), offer(unsupported.radio))
            assertFalse(central.radio.listening)
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
    fun lateRadioFailureStopsDiscoveryAndIsRecorded() =
        runTest {
            // Arrange
            val platform = air().platform(StandardTestDispatcher(testScheduler))
            val node = node(platform, "Festival")
            node.start()
            runCurrent()

            // Act
            platform.radio.emit(RadioEvent.Failed(RadioFailure.RADIO_ERROR))
            runCurrent()

            // Assert
            assertEquals(RadioFailure.RADIO_ERROR, node.radioFailure())
            assertNull(platform.radio.advertisement)
            assertFalse(platform.radio.scanning)
        }

    @Test
    fun endOfRadioEventsStopsDiscoveryWithoutAFailure() =
        runTest {
            // Arrange
            val virtual = air().platform(StandardTestDispatcher(testScheduler))
            val radio =
                object : RadioPort by virtual.radio {
                    override val events: Flow<RadioEvent> = emptyFlow()
                }
            val node = node(platformWith(virtual, radio), "Festival")

            // Act
            node.start()
            runCurrent()

            // Assert
            assertNull(node.radioFailure())
            assertNull(virtual.radio.advertisement)
            assertFalse(virtual.radio.scanning)
        }

    @Test
    fun runningNodeRecordsNoRadioFailure() =
        runTest {
            val node = node(air().platform(StandardTestDispatcher(testScheduler)), "Festival")

            node.start()
            runCurrent()

            assertNull(node.radioFailure())
        }

    @Test
    fun refusedRotationStopsDiscoveryAndIsRecorded() =
        runTest {
            // Arrange
            val virtual = air().platform(StandardTestDispatcher(testScheduler))
            var advertised = 0
            val radio =
                object : RadioPort by virtual.radio {
                    override fun startAdvertising(payload: ByteArray) =
                        if (advertised++ == 0) {
                            virtual.radio.startAdvertising(payload)
                        } else {
                            RadioResult.Failed(RadioFailure.RADIO_ERROR)
                        }
                }
            val node = node(platformWith(virtual, radio), "Festival")
            node.start()
            runCurrent()
            val firstFailure = node.radioFailure()

            // Act
            advanceTimeBy(EPOCH_MILLIS + 1)

            // Assert
            assertNull(firstFailure)
            assertEquals(RadioFailure.RADIO_ERROR, node.radioFailure())
            assertFalse(virtual.radio.scanning)
        }

    @Test
    fun runningNodeScansFourSecondsInEveryEightSoAtMostFourStartsIn30Seconds() =
        runTest {
            // Arrange
            val platform = air().platform(StandardTestDispatcher(testScheduler))
            val node = node(platform, "Festival")
            node.start()
            runCurrent()
            val start = testScheduler.currentTime

            // Act
            advanceTimeBy(30_000)
            node.stop()

            // Assert
            val windows =
                listOf(0L, 8_000, 16_000, 24_000).flatMap { listOf(start + it to true, start + it + 4_000 to false) }
            assertEquals(windows, platform.radio.scanLog)
        }

    @Test
    fun nodeStoppedWithinAScanWindowStopsScanning() =
        runTest {
            // Arrange
            val platform = air().platform(StandardTestDispatcher(testScheduler))
            val node = node(platform, "Festival")
            node.start()
            runCurrent()
            val start = testScheduler.currentTime

            // Act
            advanceTimeBy(10_000)
            node.stop()

            // Assert
            assertEquals(start + 10_000 to false, platform.radio.scanLog.last())
            assertEquals(4, platform.radio.scanLog.size)
        }

    @Test
    fun refusedScanWindowStopsDiscoveryAndIsRecorded() =
        runTest {
            // Arrange
            val virtual = air().platform(StandardTestDispatcher(testScheduler))
            var scans = 0
            val radio =
                object : RadioPort by virtual.radio {
                    override fun startScanning() =
                        if (scans++ == 0) {
                            virtual.radio.startScanning()
                        } else {
                            RadioResult.Failed(RadioFailure.RADIO_ERROR)
                        }
                }
            val node = node(platformWith(virtual, radio), "Festival")
            node.start()
            runCurrent()

            // Act
            advanceTimeBy(8_001)

            // Assert
            assertEquals(RadioFailure.RADIO_ERROR, node.radioFailure())
            assertNull(virtual.radio.advertisement)
        }

    @Test
    fun nodeOnARadioWithLe2MSetsThePhy2MFlag() =
        runTest {
            // Arrange
            val platform = air().platform(StandardTestDispatcher(testScheduler), le2MPhy = true)
            val node = node(platform, "Festival")

            // Act
            node.start()
            runCurrent()

            // Assert
            assertEquals(Offer(null, Advertisement.PHY_2M), offer(platform.radio))
        }

    /** What [radio] offers peers: the PSM in its capability record and the flags of its latest advertisement. */
    private data class Offer(
        val psm: Int?,
        val flags: Int,
    )

    private fun offer(radio: VirtualRadio): Offer {
        val record = (CapabilityRecord.decode(assertNotNull(radio.capability)) as Decoded.Valid).value
        val advertisement = (Advertisement.decode(radio.advertisements.last().second) as Decoded.Valid).value
        return Offer(record.l2capPsm, advertisement.flags)
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
        // A sighting arrives within one maximum air latency of the first scan window's start.
        val FIRST_SCAN_WINDOW_MILLIS: Long = VirtualAir.DEFAULT_LATENCY_MILLIS.last + 1L
        const val EPOCH_MILLIS = Derivations.EPOCH_SECONDS * 1_000L
    }
}
