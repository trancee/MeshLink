package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** When a scanner first heard each candidate, and whether it knows the candidate's peer (bridge rule). */
@OptIn(ExperimentalCoroutinesApi::class)
class ScannerCandidateTest {
    private val bluetooth = FakeCoreBluetooth()
    private val radio = AppleRadio(bluetooth).also { bluetooth.events = FakeEvents(it.delegates) }
    private val peripheral = FakePeripheral("A")
    private val discovery = Discovery(SodiumCryptoProvider, MESH_ID, WireProfile.UNSECURED)

    // The peers' own discovery: the scanner's treats the latest payload it made as its own.
    private val peers = Discovery(SodiumCryptoProvider, MESH_ID, WireProfile.UNSECURED)

    @Test
    fun aCandidateKeepsWhenItWasFirstHeardUntilItsIdentifierChangesOrItGoesUnheardForAMinute() =
        runTest {
            // Arrange
            val scanner = scanner(null)
            val first = peers.advertisement(EPOCH)
            val second = peers.advertisement(EPOCH)
            val since = mutableListOf<Long>()
            val sightAt = { at: Long, payload: ByteArray? ->
                advanceTimeBy(at - testScheduler.currentTime)
                runCurrent()
                bluetooth.events.discovered(peripheral, listOfNotNull(payload), rssi = RSSI)
                runCurrent()
                since +=
                    scanner.candidates.value
                        .getValue(ApplePeer(peripheral))
                        .since
            }

            // Act: sightings arrive only in the scan windows, 4 s of every 8 s.
            sightAt(FIRST, first)
            sightAt(KEPT, first)
            sightAt(KEPT + GAP_MILLIS - 1, first)
            sightAt(RESET, first)
            sightAt(RESET + 1, second)
            sightAt(RESET + 2, null)
            sightAt(RESET + 3, second)

            // Assert
            assertEquals(listOf(FIRST, FIRST, FIRST, RESET, RESET + 1, RESET + 2, RESET + 3), since)
        }

    @Test
    fun aCandidatesSignalIsSmoothedAcrossSightingsAndRestartsWithItsFirstHeardTime() =
        runTest {
            // Arrange
            val scanner = scanner(null)
            val first = peers.advertisement(EPOCH)
            val second = peers.advertisement(EPOCH)
            val heard = mutableListOf<Pair<Long, Double>>()
            val sightAt = { at: Long, payload: ByteArray?, rssi: Int ->
                advanceTimeBy(at - testScheduler.currentTime)
                runCurrent()
                bluetooth.events.discovered(peripheral, listOfNotNull(payload), rssi = rssi)
                runCurrent()
                val candidate = scanner.candidates.value.getValue(ApplePeer(peripheral))
                heard += candidate.since to candidate.rssi
            }

            // Act
            sightAt(FIRST, first, -60)
            sightAt(FIRST + 1, first, -80)
            sightAt(FIRST + 2, first, -40)
            sightAt(FIRST + 3, second, -70)
            sightAt(AFTER_GAP, second, -50)
            sightAt(RESET, null, -90)
            sightAt(RESET + 1, null, -50)

            // Assert
            val expected =
                listOf(
                    FIRST to -60.0,
                    FIRST to -65.0,
                    FIRST to -58.75,
                    FIRST + 3 to -70.0,
                    AFTER_GAP to -50.0,
                    RESET to -90.0,
                    RESET to -80.0,
                )
            assertEquals(expected, heard)
        }

    @Test
    fun aCandidateWhoseResolvableTagResolvesIsKnownAndAStrangerIsNot() =
        runTest {
            // Arrange
            val key = ByteArray(ResolvingKey.KEY_LENGTH) { 7 }
            val keys = ResolvingKeys(SodiumCryptoProvider, WALL_START_MILLIS)
            val notAfter = (WALL_START_MILLIS / MILLIS + DAY_SECONDS).toUInt()
            keys.learn(NodeId(ByteArray(NODE_ID_LENGTH) { 1 }), ResolvingKey(key, notAfter), WALL_START_MILLIS)
            val scanner = scanner(keys)
            val stranger = FakePeripheral("B")

            // Act
            advanceTimeBy(1_000)
            bluetooth.events.discovered(peripheral, listOf(peers.advertisement(EPOCH, key)), rssi = RSSI)
            bluetooth.events.discovered(stranger, listOf(peers.advertisement(EPOCH)), rssi = RSSI)
            runCurrent()

            // Assert
            val candidates = scanner.candidates.value
            val known = listOf(peripheral, stranger).map { candidates.getValue(ApplePeer(it)).known }
            assertEquals(listOf(true, false), known)
        }

    /** A central scanner of this test's mesh that resolves tags with [keys], running from now on. */
    private fun TestScope.scanner(keys: ResolvingKeys?): Scanner {
        val clock = SchedulerClock(this)
        val scanner = Scanner(radio, clock, SeededRandom(1L), discovery)
        backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
            val recognition = Recognition(keys, NeighborLists(clock, backgroundScope) {}) { false }
            scanner.run(LinkRole.CENTRAL, recognition, coc = false, onLinkEvent = {}, onSighting = { _, _, _ -> })
        }
        runCurrent()
        return scanner
    }

    /** The test scheduler's time, on a wall clock that starts at [WALL_START_MILLIS]. */
    private class SchedulerClock(
        private val scope: TestScope,
    ) : Clock {
        override fun monotonicMillis(): Long = scope.testScheduler.currentTime

        override fun wallClockMillis(): Long = WALL_START_MILLIS + scope.testScheduler.currentTime
    }

    private companion object {
        val MESH_ID = "Festival".encodeToByteArray()

        /** 2026-01-01T00:00:00Z, the start of an epoch. */
        const val WALL_START_MILLIS = 1_767_225_600_000L
        const val MILLIS = 1_000L
        val EPOCH = Derivations.epoch(WALL_START_MILLIS / MILLIS)

        /** How long a candidate may go unheard and keep when it was first heard. */
        const val GAP_MILLIS = 60_000L
        const val FIRST = 1_000L
        const val KEPT = 56_000L

        /** A gap of one more than [GAP_MILLIS] after `KEPT + GAP_MILLIS - 1`, in a scan window again. */
        const val RESET = KEPT + 2 * GAP_MILLIS
        const val DAY_SECONDS = 86_400L
        const val NODE_ID_LENGTH = 32
        const val RSSI = -60

        /** The first scan window over [GAP_MILLIS] after `FIRST + 3`. */
        const val AFTER_GAP = 64_000L
    }
}
