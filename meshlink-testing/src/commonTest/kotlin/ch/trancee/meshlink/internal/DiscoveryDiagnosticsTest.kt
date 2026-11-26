package ch.trancee.meshlink.internal

import ch.trancee.meshlink.Density
import ch.trancee.meshlink.DensityClass
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The discovery diagnostics (ADR 0020 decision 7, `ML2xxx`). */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryDiagnosticsTest {
    @Test
    fun eachScanWindowIsReportedAndTheRepeatsOfTenSecondsCoalesce() =
        runTest {
            // Arrange
            val node = node(air().platform(StandardTestDispatcher(testScheduler)))
            val seen = diagnostics(node, "ML2001")

            // Act
            node.start()
            runCurrent()
            val first = seen.map { it.toString() }
            advanceTimeBy(COALESCING_MILLIS + 1)

            // Assert
            assertEquals(listOf("ML2001 ScanWindow(phase=Started) x1"), first)
            // The first window stops at 4 s, and the next starts after a low-class rest of 4 s.
            assertEquals(first + "ML2001 ScanWindow(phase=Started) x2", seen.map { it.toString() })
        }

    @Test
    fun anAddressThatFailsVettingIsReportedStaleOnce() =
        runTest {
            // Arrange
            val air = air()
            val node = node(air.platform(StandardTestDispatcher(testScheduler)))
            val seen = diagnostics(node, "ML2006")
            val target = air.radio().also { it.background = true }
            target.startAdvertising(ByteArray(RadioPort.ADVERTISEMENT_LENGTH))

            // Act
            node.start()
            advanceTimeBy(MINUTE_MILLIS)

            // Assert
            assertEquals(listOf("ML2006 StaleAddressMarked() x1"), seen.map { it.toString() })
        }

    @Test
    fun aCrowdSwitchesTheDensityClassUnlessItIsPinned() =
        runTest {
            // Arrange
            val air = air()
            val automatic = node(air.platform(StandardTestDispatcher(testScheduler)))
            val pinned =
                node(air.platform(StandardTestDispatcher(testScheduler))) {
                    density = Density.Pinned(DensityClass.Low)
                }
            val crowd = List(CROWD) { node(air.platform(StandardTestDispatcher(testScheduler))) }
            val switches = listOf(automatic, pinned).map { diagnostics(it, "ML2007") }

            // Act
            (crowd + automatic + pinned).forEach { it.start() }
            advanceTimeBy(FIRST_WINDOW_MILLIS)

            // Assert
            val expected = listOf(listOf("ML2007 DensityClassChanged(from=Low, to=Medium) x1"), emptyList())
            assertEquals(expected, switches.map { events -> events.map { it.toString() } })
        }

    @Test
    fun eachRefusedStartIsReportedWithItsPlatformCode() =
        runTest {
            // Arrange
            val error = RadioFailure.RADIO_ERROR
            val starts =
                listOf<(RadioPort) -> RadioPort>(
                    { virtual ->
                        object : RadioPort by virtual {
                            override fun startScanning() = RadioResult.Failed(error, platformCode = 6)
                        }
                    },
                    { virtual ->
                        object : RadioPort by virtual {
                            override fun startAdvertising(payload: ByteArray) = RadioResult.Failed(error, 3)
                        }
                    },
                    { virtual ->
                        object : RadioPort by virtual {
                            override fun serveCapability(record: ByteArray) = RadioResult.Failed(error)
                        }
                    },
                )

            // Act
            val seen =
                starts.map { refuse ->
                    val virtual = air().platform(StandardTestDispatcher(testScheduler))
                    val node = node(platformWith(virtual, refuse(virtual.radio)))
                    diagnostics(node, "ML2002", "ML2005", "ML2008", "ML8001").also {
                        node.start()
                        runCurrent()
                    }
                }

            // Assert
            val expected =
                listOf(
                    listOf("ML2002 ScanFailed(platformCode=6) x1"),
                    listOf("ML2005 AdvertisingFailed(platformCode=3) x1"),
                    listOf("ML2008 GattServiceFailed() x1"),
                )
            assertEquals(expected, seen.map { events -> events.map { it.toString() } })
        }

    @Test
    fun theEmittedEventsAreWrittenToThePlatformLogOnlyWhenItIsOn() =
        runTest {
            // Arrange
            val platforms = List(2) { air().platform(StandardTestDispatcher(testScheduler)) }
            val nodes = platforms.mapIndexed { on, platform -> node(platform) { diagnostics.platformLog = on == 1 } }

            // Act
            nodes.forEach { it.start() }
            runCurrent()
            platforms.forEach { it.radio.emit(RadioEvent.ScanFailed(RadioFailure.RADIO_ERROR, platformCode = 6)) }
            runCurrent()

            // Assert
            val expected =
                listOf(
                    "Info|ML1001 Started count=1|profile=Unsecured preset=OfflineChat",
                    "Warning|ML2002 ScanFailed count=1 platformCode=6|",
                )
            val logged = platforms.map { platform -> platform.logged.filter { "ML1001" in it || "ML2002" in it } }
            assertEquals(listOf(emptyList(), expected), logged)
        }

    @Test
    fun aBusyRetriedGattRequestIsReportedWithItsPlatformCode() =
        runTest {
            // Arrange
            val platform = air().platform(StandardTestDispatcher(testScheduler))
            val node = node(platform)
            val seen = diagnostics(node, "ML3006")
            node.start()
            runCurrent()

            // Act
            platform.radio.emit(RadioEvent.GattRetried(platformCode = 201))
            runCurrent()

            // Assert
            assertEquals(listOf("ML3006 GattOperationRetried(platformCode=201) x1"), seen.map { it.toString() })
        }

    @Test
    fun eachLateRadioFailureIsReportedAndALeakedScannerRegistrationIsNamed() =
        runTest {
            // Arrange
            val failures =
                listOf(
                    RadioEvent.ScanFailed(RadioFailure.RADIO_ERROR, platformCode = 2, registrationFailed = true),
                    RadioEvent.ScanFailed(RadioFailure.RADIO_ERROR),
                    RadioEvent.AdvertisingFailed(RadioFailure.RADIO_ERROR, platformCode = 4),
                    RadioEvent.ServeFailed(RadioFailure.RADIO_ERROR, platformCode = 133),
                )

            // Act
            val seen =
                failures.map { failure ->
                    val platform = air().platform(StandardTestDispatcher(testScheduler))
                    val node = node(platform)
                    diagnostics(node, "ML2002", "ML2005", "ML2008", "ML8001").also {
                        node.start()
                        runCurrent()
                        platform.radio.emit(failure)
                        runCurrent()
                    }
                }

            // Assert
            val expected =
                listOf(
                    listOf("ML2002 ScanFailed(platformCode=2) x1", "ML8001 ScannerRegistrationFailed() x1"),
                    listOf("ML2002 ScanFailed() x1"),
                    listOf("ML2005 AdvertisingFailed(platformCode=4) x1"),
                    listOf("ML2008 GattServiceFailed(platformCode=133) x1"),
                )
            assertEquals(expected, seen.map { events -> events.map { it.toString() } })
        }

    private companion object {
        const val COALESCING_MILLIS = 10_000L
        const val MINUTE_MILLIS = 60_000L

        /** The first scan window and a margin. */
        const val FIRST_WINDOW_MILLIS = 5_000L

        /** Peers enough for the medium class: at least 10. */
        const val CROWD = 12
    }
}
