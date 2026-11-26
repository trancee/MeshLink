package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DiagnosticEvent
import ch.trancee.meshlink.MeshLink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The link diagnostics (ADR 0020 decision 7, `ML3xxx` and `ML7004`). */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkDiagnosticsTest {
    @Test
    fun anOpenedLinkReportsItsRoleAndMtuAndThePeersCloseItsReason() =
        runTest {
            // Arrange
            val (_, fake, seen) = linkedToFake(maxAttMtu = MTU)

            // Act
            fake.write(record(LinkFrame.Close(CloseReason.NORMAL)))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(2, seen.size, "$seen")
            val opened = assertIs<DiagnosticEvent.LinkOpened>(seen[0])
            assertEquals(DiagnosticEvent.LinkOpened.Role.Peripheral to MTU, opened.role to opened.mtu)
            val closed = assertIs<DiagnosticEvent.LinkClosed>(seen[1])
            assertEquals(Triple(opened.peer, DiagnosticEvent.LinkClosed.Reason.Normal, true), closed.ended())
        }

    @Test
    fun aLinkThisNodeClosesOrLosesReportsWhy() =
        runTest {
            // Arrange
            val stop: suspend (MeshLink, RadioLink) -> Unit = { node, _ -> node.stop() }
            val corrupt: suspend (MeshLink, RadioLink) -> Unit = { _, fake -> fake.write(byteArrayOf(0x00, 0x00)) }
            val lose: suspend (MeshLink, RadioLink) -> Unit = { _, fake -> fake.disconnect() }
            val cases =
                listOf(
                    stop to DiagnosticEvent.LinkClosed.Reason.ShuttingDown,
                    corrupt to DiagnosticEvent.LinkClosed.Reason.ProtocolError,
                    lose to DiagnosticEvent.LinkClosed.Reason.ConnectionLost,
                )

            // Act
            val ended =
                cases.map { (close, _) ->
                    val (node, fake, seen) = linkedToFake()
                    close(node, fake)
                    advanceTimeBy(SETTLE_MILLIS)
                    seen.filterIsInstance<DiagnosticEvent.LinkClosed>().map { it.reason to it.byPeer }
                }

            // Assert
            assertEquals(cases.map { listOf(it.second to false) }, ended)
        }

    @Test
    fun onlyALostConnectionReportsThePlatformStatus() =
        runTest {
            // Arrange
            val lose: suspend (RadioLink) -> Unit = { fake -> (fake as VirtualLinkEnd).drop(SUPERVISION_TIMEOUT) }
            val closeThenLose: suspend (RadioLink) -> Unit = { fake ->
                fake.write(record(LinkFrame.Close(CloseReason.NORMAL)))
                advanceTimeBy(SETTLE_MILLIS)
                (fake as VirtualLinkEnd).drop(REMOTE_USER_TERMINATED)
            }

            // Act
            val ended =
                listOf(lose, closeThenLose).map { end ->
                    val (_, fake, seen) = linkedToFake()
                    end(fake)
                    advanceTimeBy(SETTLE_MILLIS)
                    seen.filterIsInstance<DiagnosticEvent.LinkClosed>().map { it.reason to it.platformCode }
                }

            // Assert
            val expected =
                listOf(
                    listOf(DiagnosticEvent.LinkClosed.Reason.ConnectionLost to SUPERVISION_TIMEOUT),
                    listOf(DiagnosticEvent.LinkClosed.Reason.Normal to null),
                )
            assertEquals(expected, ended)
        }

    @Test
    fun noVersionInCommonIsReportedWithThePeersHighestAsPeripheralAndAsCentral() =
        runTest {
            // Arrange
            val toPeripheral = air()
            val toCentral = air()
            val peripheral = toPeripheral.platform(StandardTestDispatcher(testScheduler))
            val central = toCentral.platform(StandardTestDispatcher(testScheduler))
            val nodes = listOf(node(peripheral), node(central))
            val seen = nodes.map { diagnostics(it, "ML3008") }
            nodes[0].start()
            advertiseFake(toCentral, versions = listOf(THEIR_HIGHEST, 2))
            val fake = connectFake(toPeripheral, peripheral)

            // Act
            fake.write(record(LinkFrame.PlainHello(2, digest(MESH), FAKE_ID)))
            nodes[1].start()
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(
                listOf(
                    listOf("ML3008 ProtocolVersionRejected(theirVersion=2) x1"),
                    listOf("ML3008 ProtocolVersionRejected(theirVersion=$THEIR_HIGHEST) x1"),
                ),
                seen.map { events -> events.map { it.toString() } },
            )
        }

    @Test
    fun everyBusyIsReportedWithItsDirectionAndReason() =
        runTest {
            // Arrange
            val (node, fake, _) = linkedToFake()
            val seen = diagnostics(node, "ML3009", "ML7004")

            // Act
            (0..Reassembly.SLOTS).forEach { fake.write(record(fragment(it))) }
            advanceTimeBy(SETTLE_MILLIS)
            fake.write(record(LinkFrame.Busy(0, BusyReason.LARGE_BUDGET)))
            advanceTimeBy(COALESCING_MILLIS)

            // Assert
            assertEquals(
                listOf(
                    "ML3009 BusyReply(direction=Sent, reason=Slots) x1",
                    "ML3009 BusyReply(direction=Received, reason=Budget) x1",
                ),
                seen.map { it.toString() },
            )
        }

    @Test
    fun aBusyForTheSpentLargeFrameBudgetIsReportedAsExhausted() =
        runTest {
            // Arrange
            val air = air()
            val platform = air.platform(StandardTestDispatcher(testScheduler))
            val node = node(platform).also { it.start() }
            val seen = diagnostics(node, "ML3009", "ML7004")
            val fakes =
                List(LARGE_FRAMES_OVER_BUDGET) { n ->
                    connectFake(air, platform).also {
                        it.write(record(LinkFrame.PlainHello(1, digest(MESH), ByteArray(NODE_ID_BYTES) { n.toByte() })))
                    }
                }
            advanceTimeBy(SETTLE_MILLIS)
            platform.radio.ends
                .zip(fakes)
                .forEach { (end, fake) -> grantCredits(fake, end) }

            // Act
            fakes.forEach { fake -> largeFrame().dropLast(1).forEach { fake.write(record(it.encode())) } }
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(
                listOf(
                    "ML3009 BusyReply(direction=Sent, reason=Budget) x1",
                    "ML7004 ReassemblyBudgetExhausted() x1",
                ),
                seen.map { it.toString() },
            )
        }

    @Test
    fun eachEndOfALinkThatMovesToCocReportsTheSwitch() =
        runTest {
            // Arrange
            val air = air()
            val platforms = List(2) { air.platform(StandardTestDispatcher(testScheduler), coc = true) }
            val nodes = platforms.map { node(it) }
            val seen = nodes.map { diagnostics(it, "ML3010") }

            // Act
            nodes.forEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            val switches = seen.map { events -> events.filterIsInstance<DiagnosticEvent.BearerSwitched>().size }
            assertEquals(listOf(1, 1), switches)
        }

    /**
     * A started node linked as peripheral to a fake central with [maxAttMtu] that said hello as
     * [FAKE_ID] and grants credits, and the link diagnostics the node reported from its start.
     */
    private suspend fun TestScope.linkedToFake(maxAttMtu: Int = VirtualAir.DEFAULT_ATT_MTU): Linked {
        val air = air()
        val alice = air.platform(StandardTestDispatcher(testScheduler))
        val node = node(alice)
        val seen = diagnostics(node, "ML3001", "ML3002")
        node.start()
        val fake = connectFake(air, alice, maxAttMtu)
        fake.write(record(LinkFrame.PlainHello(1, digest(MESH), FAKE_ID)))
        advanceTimeBy(SETTLE_MILLIS)
        grantCredits(fake, alice.radio.ends.single())
        advanceTimeBy(SETTLE_MILLIS)
        return Linked(node, fake, seen)
    }

    private data class Linked(
        val node: MeshLink,
        val fake: RadioLink,
        val seen: List<DiagnosticEvent>,
    )

    private fun DiagnosticEvent.LinkClosed.ended() = Triple(peer, reason, byPeer)

    /** The first fragment of stream [id], carrying a routed type byte. */
    private fun fragment(id: Int): ByteArray {
        val routed = byteArrayOf(MESSAGE_TYPE, 0x00)
        return LinkFrame.Fragment(id, 0, false, routed).encode()
    }

    /** The fragments of a routed frame of the largest size a link reassembles, as stream 0. */
    private fun largeFrame(): List<LinkFrame.Fragment> =
        LinkFrame.Fragment.split(
            0,
            ByteArray(Reassembly.FRAME_MAX).also { it[0] = MESSAGE_TYPE },
            LinkFrame.Fragment.DATA_MAX,
        )

    private companion object {
        /** Android's GATT status for a supervision timeout. */
        const val SUPERVISION_TIMEOUT = 8

        /** Android's GATT status for a disconnect the peer asked for. */
        const val REMOTE_USER_TERMINATED = 19

        const val SETTLE_MILLIS = 1_000L
        const val COALESCING_MILLIS = 10_000L
        const val MTU = 185
        const val THEIR_HIGHEST = 5
        const val NODE_ID_BYTES = 32
        const val MESSAGE_TYPE: Byte = 0x40

        /** Large frames of the largest size: four exceed the 512 KiB budget. */
        const val LARGE_FRAMES_OVER_BUDGET = 4
    }
}
