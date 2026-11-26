package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DensityClass
import ch.trancee.meshlink.DiagnosticsSnapshot
import ch.trancee.meshlink.LinkSnapshot
import ch.trancee.meshlink.SecurityProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** The snapshot of a node's state (ADR 0020 decision 5), from nodes on virtual air. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsSnapshotTest {
    @Test
    fun aNodeNeverStartedShowsItsRedactedConfigurationAndNothingElse() =
        runTest {
            // Arrange
            val node = node(air().platform(StandardTestDispatcher(testScheduler)))

            // Act
            val snapshot = node.diagnostics.snapshot()

            // Assert
            assertEquals(
                node.config
                    .describe()
                    .lines()
                    .size,
                snapshot.configuration.size,
            )
            assertTrue("meshId = <redacted> (override)" in snapshot.configuration, "${snapshot.configuration}")
            assertFalse(snapshot.configuration.any { MESH in it })
            assertEquals(SecurityProfile.Unsecured, snapshot.profile)
            assertNull(snapshot.densityClass)
            assertEquals(emptyList(), snapshot.links)
            assertEquals(listOf(0, 0, 0, 0), snapshot.queueCounts())
            assertEquals(emptyMap(), snapshot.counters.events)
            assertEquals(listOf(0L, 0L, 0L), snapshot.tallies())
        }

    @Test
    fun aClosedNodeExportsItsPlatformItsRedactedSnapshotAndTheEventsOfItsRun() =
        runTest {
            // Arrange
            val node = node(air().platform(StandardTestDispatcher(testScheduler)))
            node.start()
            advanceTimeBy(SETTLE_MILLIS)
            node.stop()
            node.close()

            // Act
            val json = node.diagnostics.export()

            // Assert
            val shown =
                listOf(
                    "\"format\": 1",
                    "\"platform\": \"Virtual\"",
                    "\"os\": \"1\"",
                    "\"protocol\": 1",
                    "\"meshId = <redacted> (override)\"",
                )
            assertEquals(shown, shown.filter { it in json }, json)
            assertFalse(MESH in json)
            val codes = CODE.findAll(json).map { it.groupValues[1] }.toList()
            assertEquals(listOf("ML1001", "ML1003", "ML1002"), codes.take(2) + codes.last(), "$codes")
        }

    @Test
    fun theRelayOfASecuredLineCountsTheFramesItRelayedAndEachLinksHandshake() =
        runTest(timeout = 5.minutes) {
            val line = SecuredLine(SEED, length = 3)
            try {
                // Arrange
                line.start(SecurityProfile.Classical)

                // Act
                line.send(line.id(CHARLIE), "Hi")
                val alice = line.nodes[ALICE].diagnostics.snapshot()
                val bob = line.nodes[BOB].diagnostics.snapshot()

                // Assert
                assertEquals(DensityClass.Low, bob.densityClass)
                assertEquals(2, bob.links.size)
                assertEquals(
                    2,
                    bob.links
                        .map { it.peer }
                        .toSet()
                        .size,
                )
                assertTrue(bob.links.all { it.mtu > 0 && it.phy == null }, "${bob.links.map { it.mtu }}")
                assertTrue(alice.counters.framesSent > 0)
                assertEquals(0L, alice.counters.framesRelayed)
                assertTrue(bob.counters.framesRelayed > 0)
                assertTrue(bob.counters.framesSent >= bob.counters.framesRelayed)
                listOf(alice, bob).forEach {
                    assertEquals(it.counters.events["ML3001"], it.counters.handshakesSucceeded)
                }
                assertTrue(alice.queues.certificates > 0)
            } finally {
                line.close()
            }
        }

    @Test
    fun eachLinkShowsThePhyItsRadioReports() =
        runTest {
            // Arrange
            val platforms = air().let { air -> List(2) { air.platform(StandardTestDispatcher(testScheduler)) } }
            val nodes = platforms.map(::node).onEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)

            // Act
            val phys =
                RadioPhy.entries.map { phy ->
                    platforms[0].radio.ends.forEach { it.phy = phy }
                    nodes[0]
                        .diagnostics
                        .snapshot()
                        .links
                        .single()
                        .phy
                }

            // Assert
            assertEquals(LinkSnapshot.Phy.entries, phys)
            nodes.forEach { it.close() }
        }

    @Test
    fun aLinkShowsTheChannelItSwitchedToOrGattWithoutTheUpgrade() =
        runTest {
            // Arrange
            val upgrades = listOf(true, false)

            // Act
            val bearers =
                upgrades.map { upgrade ->
                    val air = air()
                    val nodes =
                        List(2) {
                            val platform = air.platform(StandardTestDispatcher(testScheduler), coc = true)
                            node(platform) { l2capUpgrade = upgrade }
                        }
                    nodes.forEach { it.start() }
                    advanceTimeBy(SETTLE_MILLIS)
                    nodes[0]
                        .diagnostics
                        .snapshot()
                        .links
                        .single()
                        .bearer
                        .also { nodes.forEach { it.close() } }
                }

            // Assert
            assertEquals(listOf(LinkSnapshot.Bearer.L2capCoc, LinkSnapshot.Bearer.Gatt), bearers)
        }

    @Test
    fun aMessageWaitsInTheInboxAndItsSendersQueueUntilTheAppTakesIt() =
        runTest {
            // Arrange
            val air = air()
            val nodes = List(2) { node(air.platform(StandardTestDispatcher(testScheduler))) }
            nodes.forEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)
            val bob =
                nodes[0]
                    .peers.value
                    .single()
                    .nodeId

            // Act
            nodes[0].send(bob, PAYLOAD)
            advanceTimeBy(SETTLE_MILLIS)
            val waiting = nodes.map { it.diagnostics.snapshot().queues }
            nodes[1].messages.first()
            advanceTimeBy(DELIVERED_MILLIS)
            val taken = nodes.map { it.diagnostics.snapshot().queues }

            // Assert
            assertEquals(listOf(1, 1), listOf(waiting[0].outgoing, waiting[1].inbox))
            assertEquals(listOf(0, 0), listOf(taken[0].outgoing, taken[1].inbox))
            nodes.forEach { it.close() }
        }

    private fun DiagnosticsSnapshot.queueCounts() = with(queues) { listOf(outgoing, held, inbox, certificates) }

    private fun DiagnosticsSnapshot.tallies(): List<Long> {
        val counted = counters
        return listOf(counted.framesSent, counted.framesRelayed, counted.handshakesSucceeded)
    }

    private companion object {
        val CODE = Regex("\"code\": \"(ML\\d{4})\"")
        const val SEED = 54L
        const val ALICE = 0
        const val BOB = 1
        const val CHARLIE = 2
        const val DELIVERED_MILLIS = 10_000L
        val PAYLOAD = "Hi".encodeToByteArray()
    }
}
