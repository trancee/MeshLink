package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DiagnosticEvent
import ch.trancee.meshlink.DiagnosticEvent.ConnectFailed
import ch.trancee.meshlink.DiagnosticEvent.DiagnosticsDropped
import ch.trancee.meshlink.DiagnosticEvent.LinkCapReached
import ch.trancee.meshlink.LinkRef
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsHubTest {
    @Test
    fun everyCollectorReceivesEveryEvent() =
        runTest {
            // Arrange
            val hub = DiagnosticsHub(backgroundScope, debug = false, crypto = SodiumCryptoProvider)
            val first = collect(hub)
            val second = collect(hub)

            // Act
            hub.report(ConnectFailed(LinkRef(1), ConnectFailed.Reason.Transient, 1))

            // Assert
            assertEquals(listOf("ML3003 link-1"), first.map(::label))
            assertEquals(listOf("ML3003 link-1"), second.map(::label))
        }

    @Test
    fun debugEventsAreDroppedUnlessDebugIsOn() =
        runTest {
            // Arrange
            val quiet = DiagnosticsHub(backgroundScope, debug = false, crypto = SodiumCryptoProvider)
            val verbose = DiagnosticsHub(backgroundScope, debug = true, crypto = SodiumCryptoProvider)
            val quietEvents = collect(quiet)
            val verboseEvents = collect(verbose)

            // Act
            quiet.report(LinkCapReached(4))
            verbose.report(LinkCapReached(4))

            // Assert
            assertEquals(emptyList(), quietEvents)
            assertEquals(listOf("ML3004"), verboseEvents.map(::label))
        }

    @Test
    fun theLogShowsTheNumbersAndHidesTheOtherFieldsAndLeavesOutAMissingPlatformCode() =
        runTest {
            // Arrange
            val logged = mutableListOf<String>()
            val log = PlatformLog { severity, shown, hidden -> logged += "$severity|$shown|$hidden" }
            val hub = DiagnosticsHub(backgroundScope, debug = true, crypto = SodiumCryptoProvider, log = log)

            // Act
            hub.report(ConnectFailed(LinkRef(1), ConnectFailed.Reason.Transient, 1, platformCode = 133))
            hub.report(ConnectFailed(LinkRef(2), ConnectFailed.Reason.Transient, 1))
            hub.report(DiagnosticEvent.GattOperationRetried(platformCode = null))

            // Assert
            val expected =
                listOf(
                    "Warning|ML3003 ConnectFailed count=1 attempt=1 platformCode=133|link=link-1 reason=Transient",
                    "Warning|ML3003 ConnectFailed count=1 attempt=1|link=link-2 reason=Transient",
                    "Debug|ML3006 GattOperationRetried count=1|",
                )
            assertEquals(expected, logged)
        }

    @Test
    fun repeatsOfOneCodeAndReferenceWithinTenSecondsAreCoalesced() =
        runTest {
            // Arrange
            val hub = DiagnosticsHub(backgroundScope, debug = false, crypto = SodiumCryptoProvider)
            val events = collect(hub)

            // Act
            hub.report(ConnectFailed(LinkRef(1), ConnectFailed.Reason.Transient, 1))
            hub.report(ConnectFailed(LinkRef(1), ConnectFailed.Reason.Transient, 2))
            hub.report(ConnectFailed(LinkRef(2), ConnectFailed.Reason.Transient, 1))
            hub.report(ConnectFailed(LinkRef(1), ConnectFailed.Reason.RadioError, 3))
            advanceTimeBy(WINDOW_MS - 1)
            val withinWindow = events.map(::label)
            advanceTimeBy(2)

            // Assert
            assertEquals(listOf("ML3003 link-1", "ML3003 link-2"), withinWindow)
            assertEquals(listOf("ML3003 link-1", "ML3003 link-2", "ML3003 link-1 x2"), events.map(::label))
            val latest = events.last() as ConnectFailed
            assertEquals(ConnectFailed.Reason.RadioError, latest.reason)
            assertEquals(3, latest.attempt)
        }

    @Test
    fun anOccurrenceAfterAWindowOpensANewOne() =
        runTest {
            // Arrange
            val hub = DiagnosticsHub(backgroundScope, debug = false, crypto = SodiumCryptoProvider)
            val events = collect(hub)
            hub.report(ConnectFailed(LinkRef(1), ConnectFailed.Reason.Transient, 1))
            advanceTimeBy(WINDOW_MS + 1)

            // Act
            hub.report(ConnectFailed(LinkRef(1), ConnectFailed.Reason.Transient, 2))
            hub.report(ConnectFailed(LinkRef(1), ConnectFailed.Reason.Transient, 3))
            advanceTimeBy(WINDOW_MS + 1)

            // Assert
            assertEquals(listOf("ML3003 link-1", "ML3003 link-1", "ML3003 link-1"), events.map(::label))
            assertEquals(listOf(1, 1, 1), events.map { it.count })
        }

    @Test
    fun aSlowCollectorLosesItsOldestEventsAndLearnsHowMany() =
        runTest {
            // Arrange
            val hub = DiagnosticsHub(backgroundScope, debug = false, crypto = SodiumCryptoProvider)
            val events = mutableListOf<DiagnosticEvent>()
            backgroundScope.launch { hub.events.collect { events += it } }
            runCurrent()

            // Act
            repeat(SENT) { hub.report(ConnectFailed(LinkRef(it + 1), ConnectFailed.Reason.Transient, 1)) }
            runCurrent()

            // Assert: the waiting collector holds event 1, its buffer the newest 256, and the rest are lost.
            val lost = SENT - BUFFER - 1
            assertEquals(BUFFER + 2, events.size)
            assertEquals(lost, (events.first() as DiagnosticsDropped).dropped)
            assertEquals("ML3003 link-1", label(events[1]))
            assertEquals("ML3003 link-${lost + 2}", label(events[2]))
            assertEquals("ML3003 link-$SENT", label(events.last()))
        }

    @Test
    fun closeCompletesCurrentAndLaterCollectors() =
        runTest {
            // Arrange
            val hub = DiagnosticsHub(backgroundScope, debug = false, crypto = SodiumCryptoProvider)
            val current = backgroundScope.launch { hub.events.toList() }
            runCurrent()

            // Act
            hub.close()
            runCurrent()

            // Assert
            assertTrue(current.isCompleted)
            assertEquals(emptyList(), hub.events.toList())
        }

    @Test
    fun aPeerRefIsStableWithinAStartAndChangesAcrossStarts() =
        runTest {
            // Arrange
            val hub = DiagnosticsHub(backgroundScope, debug = false, crypto = SodiumCryptoProvider)
            val node = NodeId(ByteArray(32) { it.toByte() })
            hub.begin(SecurityProfile.Unsecured, Preset.OfflineChat)
            val first = hub.peer(node)

            // Act
            val again = hub.peer(node)
            hub.begin(SecurityProfile.Unsecured, Preset.OfflineChat)
            val nextStart = hub.peer(node)

            // Assert
            assertEquals(first, again)
            assertEquals(first.hashCode(), again.hashCode())
            assertNotEquals(first, nextStart)
            assertNotEquals(hub.peer(NodeId(ByteArray(32))), nextStart)
            assertNotEquals<Any>(first, first.toString())
            assertTrue(Regex("peer-[0-9a-f]{8}").matches(first.toString()), first.toString())
        }

    @Test
    fun linkRefsCountFromOneOnEachStart() =
        runTest {
            // Arrange
            val hub = DiagnosticsHub(backgroundScope, debug = false, crypto = SodiumCryptoProvider)
            hub.begin(SecurityProfile.Unsecured, Preset.OfflineChat)

            // Act
            val firstRun = listOf(hub.link(), hub.link())
            hub.begin(SecurityProfile.Unsecured, Preset.OfflineChat)
            val secondRun = hub.link()

            // Assert
            assertEquals(listOf("link-1", "link-2"), firstRun.map { it.toString() })
            assertEquals(LinkRef(1), secondRun)
            assertNotEquals<Any>(LinkRef(1), "link-1")
        }

    @Test
    fun theHubCountsEveryReportedEventAndEachTallyAndRestartsOnEachStart() =
        runTest {
            // Arrange
            val hub = DiagnosticsHub(backgroundScope, debug = false, crypto = SodiumCryptoProvider)
            hub.begin(SecurityProfile.Classical, Preset.OfflineChat)
            hub.report(LinkCapReached(4))
            hub.tally(DiagnosticsHub.Tally.FramesSent)
            hub.begin(SecurityProfile.Classical, Preset.OfflineChat)

            // Act
            repeat(3) { hub.report(ConnectFailed(LinkRef(1), ConnectFailed.Reason.Transient, it + 1)) }
            hub.report(LinkCapReached(4))
            hub.tally(DiagnosticsHub.Tally.FramesRelayed)
            hub.tally(DiagnosticsHub.Tally.FramesRelayed)

            // Assert
            assertEquals(mapOf("ML1001" to 1L, "ML3003" to 3L, "ML3004" to 1L), hub.counts)
            assertEquals(mapOf(DiagnosticsHub.Tally.FramesRelayed to 2L), hub.tallies)
        }

    @Test
    fun theHistoryKeepsTheLatestDeliveredEventsWithTheirTimeAcrossStarts() =
        runTest {
            // Arrange
            var now = 1_000L
            val hub = DiagnosticsHub(backgroundScope, debug = false, crypto = SodiumCryptoProvider) { now }
            hub.begin(SecurityProfile.Unsecured, Preset.OfflineChat)

            // Act
            repeat(HISTORY) {
                now += 1
                hub.report(ConnectFailed(LinkRef(it), ConnectFailed.Reason.Transient, 1))
            }
            hub.report(LinkCapReached(4))
            advanceTimeBy(WINDOW_MS + 1)
            hub.begin(SecurityProfile.Unsecured, Preset.OfflineChat)

            // Assert
            val history = hub.history
            assertEquals(HISTORY, history.size)
            assertEquals("link-2", (history.first().event as ConnectFailed).link.toString())
            assertEquals(1_003L, history.first().atMillis)
            assertEquals(listOf("ML3003", "ML1001", "ML1003"), history.takeLast(3).map { it.event.code })
        }

    private fun TestScope.collect(hub: DiagnosticsHub): List<DiagnosticEvent> {
        val events = mutableListOf<DiagnosticEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { hub.events.collect { events += it } }
        return events
    }

    private fun label(event: DiagnosticEvent): String {
        val reference = (event as? ConnectFailed)?.let { " ${it.link}" }.orEmpty()
        val count = if (event.count > 1) " x${event.count}" else ""
        return "${event.code}$reference$count"
    }

    private companion object {
        const val WINDOW_MS = 10_000L
        const val BUFFER = 256
        const val SENT = 300
        const val HISTORY = 500
    }
}
