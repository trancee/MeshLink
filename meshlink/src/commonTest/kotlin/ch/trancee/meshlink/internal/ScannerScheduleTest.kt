package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DensityClass
import ch.trancee.meshlink.Power
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

/** A central's scan windows per density class and scan policy (ADR 0008 decisions 2 and 3). */
@OptIn(ExperimentalCoroutinesApi::class)
class ScannerScheduleTest {
    private val discovery = Discovery(SodiumCryptoProvider, MESH_ID, WireProfile.UNSECURED)
    private val peers = Discovery(SodiumCryptoProvider, MESH_ID, WireProfile.UNSECURED)

    @Test
    fun eachPolicyScansFourSecondsAndRestsPerClass() =
        runTest {
            // Arrange
            val cases = Power.entries.flatMap { policy -> DensityClass.entries.map { policy to it } }

            // Act
            val schedules = cases.map { (policy, density) -> schedule(Rig(this, policy, density)) { _, _ -> } }

            // Assert
            val expected =
                listOf(4_000L, 12_000L, 16_000L, 2_500L, 8_000L, 12_000L).map { off ->
                    listOf(0L, ON, ON + off, 2 * ON + off, 2 * ON + 2 * off).zip(CALLS)
                }
            assertEquals(expected, schedules)
        }

    @Test
    fun theFirstWindowsEstimateSetsTheClassAndAStaleAddressDoesNotCount() =
        runTest {
            // Arrange
            val sightTen = { events: FakeEvents ->
                List(MEDIUM) { FakePeripheral("P$it") }.forEach { events.discovered(it, emptyList(), rssi = RSSI) }
            }
            val staleP0 = { scanner: Scanner -> scanner.vet(ApplePeer(FakePeripheral("P0")), null) }

            // Act
            val ten = schedule(Rig(this)) { _, events -> sightTen(events) }
            val vettedAfter =
                schedule(Rig(this)) { scanner, events ->
                    sightTen(events)
                    runCurrent()
                    staleP0(scanner)
                }
            val staleBefore =
                schedule(Rig(this)) { scanner, events ->
                    staleP0(scanner)
                    sightTen(events)
                }
            val identified =
                schedule(Rig(this)) { _, events ->
                    List(MEDIUM) { FakePeripheral("P$it") }.forEach {
                        events.discovered(it, listOf(peers.advertisement(EPOCH)), rssi = RSSI)
                    }
                }

            // Assert
            val restStarts = listOf(ten, vettedAfter, staleBefore, identified).map { it[2].first }
            assertEquals(listOf(ON + MEDIUM_OFF, ON + LOW_OFF, ON + LOW_OFF, ON + MEDIUM_OFF), restStarts)
        }

    @Test
    fun eachRunStartsInTheLowClass() =
        runTest {
            // Arrange
            val rig = Rig(this)
            schedule(rig) { _, events ->
                List(MEDIUM) { FakePeripheral("P$it") }.forEach { events.discovered(it, emptyList(), rssi = RSSI) }
            }

            // Act
            val again = schedule(rig) { _, _ -> }

            // Assert
            assertEquals(ON + LOW_OFF, again[2].first)
        }

    /** A central scanner with [policy] and [pinned] on a fake CoreBluetooth, on [scope]'s clock. */
    private inner class Rig(
        scope: TestScope,
        policy: Power = Power.Balanced,
        pinned: DensityClass? = null,
    ) {
        val bluetooth = FakeCoreBluetooth()
        val radio = AppleRadio(bluetooth).also { bluetooth.events = FakeEvents(it.delegates) }
        val clock = SchedulerClock(scope)
        val scanner = Scanner(radio, clock, SeededRandom(1L), discovery, ScanSchedule(policy, pinned))
    }

    /**
     * The first five scan starts and stops, with their times since the start, of a run of [rig]'s
     * scanner that hears what [sight] makes it hear in its first window.
     */
    private fun TestScope.schedule(
        rig: Rig,
        sight: (Scanner, FakeEvents) -> Unit,
    ): List<Pair<Long, String>> {
        val bluetooth = rig.bluetooth
        val start = testScheduler.currentTime
        val clock = rig.clock
        val job =
            backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
                val recognition = Recognition(null, NeighborLists(clock, backgroundScope) {}) { emptyMap() }
                rig.scanner.run(
                    LinkRole.CENTRAL,
                    recognition,
                    coc = false,
                    onLinkEvent = {},
                    onSighting = { _, _, _ -> },
                )
            }
        val calls = mutableListOf<Pair<Long, String>>()
        val record = {
            bluetooth.calls.filter { it.startsWith("scan") || it == "stopScan" }.forEach {
                calls += testScheduler.currentTime - start to it.substringBefore(' ')
            }
            bluetooth.calls.clear()
        }
        runCurrent()
        record()
        sight(rig.scanner, bluetooth.events)
        runCurrent()
        while (calls.size < CALLS.size) {
            advanceTimeBy(STEP)
            runCurrent()
            record()
        }
        job.cancel()
        runCurrent()
        bluetooth.calls.clear()
        return calls.take(CALLS.size)
    }

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
        val EPOCH = Derivations.epoch(WALL_START_MILLIS / 1_000L)
        val CALLS = listOf("scan", "stopScan", "scan", "stopScan", "scan")
        const val ON = 4_000L
        const val LOW_OFF = 4_000L
        const val MEDIUM_OFF = 12_000L
        const val STEP = 500L
        const val MEDIUM = 10
        const val RSSI = -60
    }
}
