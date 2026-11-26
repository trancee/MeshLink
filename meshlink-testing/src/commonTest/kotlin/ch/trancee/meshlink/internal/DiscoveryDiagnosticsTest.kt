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

    private companion object {
        const val COALESCING_MILLIS = 10_000L
        const val MINUTE_MILLIS = 60_000L

        /** The first scan window and a margin. */
        const val FIRST_WINDOW_MILLIS = 5_000L

        /** Peers enough for the medium class: at least 10. */
        const val CROWD = 12
    }
}
