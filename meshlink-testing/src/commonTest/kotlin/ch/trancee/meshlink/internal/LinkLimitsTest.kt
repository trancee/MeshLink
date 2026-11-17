package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.SendResult
import ch.trancee.meshlink.internal.CloseReason.RESOURCE_LIMIT
import ch.trancee.meshlink.internal.RadioFailure.PEER_UNREACHABLE
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Connect retries, the peripheral link cap and GATT credits (spec "Connect retries and link caps", "Flow control"). */
@OptIn(ExperimentalCoroutinesApi::class, InternalMeshLinkApi::class)
class LinkLimitsTest {
    @Test
    fun transientConnectFailuresAreRetriedAfterAGrowingBackoffAndEachIsReported() =
        runTest {
            // Arrange
            val air = air()
            val virtual = air.platform(StandardTestDispatcher(testScheduler))
            val attempts = mutableListOf<Long>()
            val radio =
                object : RadioPort by virtual.radio {
                    override suspend fun connect(peer: PeerHandle): RadioResult<CentralLink> {
                        attempts += testScheduler.currentTime
                        return RadioResult.Failed(RadioFailure.TRANSIENT)
                    }
                }
            advertiseFake(air, versions = listOf(1))
            val node = node(platformWith(virtual, radio))
            val seen = diagnostics(node)

            // Act
            node.start()
            advanceTimeBy(RETRIES_MILLIS)

            // Assert
            assertEquals(listOf(0L, 500L, 1_500L, 3_000L), attempts.map { it - attempts.first() })
            val failed = seen.filterIsInstance<LinkDiagnostic.ConnectFailed>()
            assertEquals(listOf(1, 2, 3, 4), failed.map { it.attempt })
            assertTrue(failed.all { it.reason == RadioFailure.TRANSIENT && it.code == "ML3003" })
        }

    @Test
    fun otherConnectFailuresAreReportedOnceAndNotRetried() =
        runTest {
            // Arrange
            val air = air()
            val virtual = air.platform(StandardTestDispatcher(testScheduler))
            var attempts = 0
            val radio =
                object : RadioPort by virtual.radio {
                    override suspend fun connect(peer: PeerHandle): RadioResult<CentralLink> {
                        attempts++
                        return RadioResult.Failed(RadioFailure.RADIO_ERROR)
                    }
                }
            advertiseFake(air, versions = listOf(1))
            val node = node(platformWith(virtual, radio))
            val seen = diagnostics(node)

            // Act
            node.start()
            advanceTimeBy(RETRIES_MILLIS)

            // Assert
            assertEquals(1, attempts)
            val failed = assertIs<LinkDiagnostic.ConnectFailed>(seen.single())
            assertEquals(RadioFailure.RADIO_ERROR to 1, failed.reason to failed.attempt)
        }

    @Test
    fun aConnectThatSucceedsOnRetryOpensTheLink() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val bob = air.platform(StandardTestDispatcher(testScheduler))
            var failures = 2
            val flaky =
                object : RadioPort by alice.radio {
                    override suspend fun connect(peer: PeerHandle): RadioResult<CentralLink> =
                        if (failures-- > 0) RadioResult.Failed(RadioFailure.TRANSIENT) else alice.radio.connect(peer)
                }
            val nodes = listOf(node(platformWith(alice, flaky)), node(platformWith(bob, NeverDials(bob.radio))))

            // Act
            nodes.forEach { it.start() }
            advanceTimeBy(RETRIES_MILLIS)

            // Assert
            assertEquals(1, nodes[0].peers.value.size)
            assertEquals(listOf(LinkRole.CENTRAL), nodes[0].linkRoles.values.toList())
        }

    @Test
    fun aCentralReconnectsAfterATransientLossAndNotAfterAnOrdinaryClose() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val bob = air.platform(StandardTestDispatcher(testScheduler))
            val nodes = listOf(node(alice), node(platformWith(bob, NeverDials(bob.radio))))
            nodes.forEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)

            // Act
            alice.radio.ends
                .single()
                .drop()
            advanceTimeBy(RETRIES_MILLIS)
            val afterLoss = alice.radio.ends.map { it.open }
            alice.radio.ends
                .last()
                .disconnect()
            advanceTimeBy(RETRIES_MILLIS)

            // Assert
            assertEquals(listOf(false, true), afterLoss)
            assertEquals(2, bob.radio.ends.size)
            assertEquals(emptyList(), nodes[0].peers.value)
        }

    @Test
    fun aCappedPeripheralRefusesBearersBeyondItsCapAndLinksToTheRestAsCentral() {
        for (seed in 1L..CAP_SEEDS) {
            runTest {
                // Arrange
                val air = air(seed)
                val capped = air.platform(StandardTestDispatcher(testScheduler), peripheralLinkCap = CAP)
                val others = List(NODES - 1) { air.platform(StandardTestDispatcher(testScheduler)) }
                val nodes = (listOf(capped) + others).map(::node)
                val seen = diagnostics(nodes[0])

                // Act
                nodes.forEach { it.start() }
                advanceTimeBy(MESH_SETTLE_MILLIS)

                // Assert
                assertTrue(nodes.all { it.peers.value.size == NODES - 1 }, "seed $seed: full mesh")
                assertTrue(held(capped.radio) <= CAP, "seed $seed: ${held(capped.radio)}")
                val refused = capped.radio.ends.filter { closes(it) == listOf(CloseReason.RESOURCE_LIMIT) }
                assertTrue(refused.isNotEmpty(), "seed $seed: no bearer refused")
                val reached = seen.filterIsInstance<LinkDiagnostic.LinkCapReached>()
                assertEquals(refused.size, reached.size, "seed $seed")
                assertTrue(reached.all { it.cap == CAP && it.code == "ML3004" }, "seed $seed")
            }
        }
    }

    @Test
    fun aFloodOfBearersNeverHoldsMoreThanTheCapAndASlotFreesWhenALinkCloses() =
        runTest {
            // Arrange
            val air = air()
            val capped = air.platform(StandardTestDispatcher(testScheduler), peripheralLinkCap = CAP)
            val node = node(capped).also { it.start() }
            val seen = diagnostics(node)

            // Act
            repeat(FLOOD) { connectFake(air, capped) }
            advanceTimeBy(SETTLE_MILLIS)
            val refused = capped.radio.ends.count { closes(it) == listOf(CloseReason.RESOURCE_LIMIT) }
            advanceTimeBy(HANDSHAKE_TIMEOUT_MILLIS)
            connectFake(air, capped)
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(FLOOD - CAP, refused)
            assertEquals(CAP, held(capped.radio))
            assertEquals(FLOOD - CAP, seen.count { it is LinkDiagnostic.LinkCapReached })
            assertTrue(
                capped.radio.ends
                    .last()
                    .open,
            )
        }

    @Test
    fun aCentralRefusedForTheCapDoesNotDialThatAddressAgain() =
        runTest {
            // Arrange
            val air = air()
            val virtual = air.platform(StandardTestDispatcher(testScheduler))
            val full = advertiseFake(air, versions = listOf(1))
            backgroundScope.launch {
                full.events.collect { event ->
                    if (event is RadioEvent.Connected) event.link.write(record(LinkFrame.Close(RESOURCE_LIMIT)))
                }
            }
            node(virtual).start()
            advanceTimeBy(SETTLE_MILLIS)

            // Act
            advertiseFake(air, versions = listOf(1))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(1, full.ends.size)
            assertEquals(2, virtual.radio.ends.size)
        }

    @Test
    fun theCentralGrantsTheWindowWhenTheLinkOpensAndHalfAgainPerHalfReceived() =
        runTest {
            // Arrange
            val air = air()
            val virtual = air.platform(StandardTestDispatcher(testScheduler))
            val fake = advertiseFake(air, versions = listOf(1))
            node(virtual).start()
            advanceTimeBy(SETTLE_MILLIS)
            val peripheral = fake.ends.single()
            peripheral.write(record(LinkFrame.PlainHello(1, digest(MESH), FAKE_ID)))
            advanceTimeBy(SETTLE_MILLIS)
            val onOpen = credits(virtual.radio.ends.single())

            // Act
            repeat(CREDIT_HALF - 1) { peripheral.write(UNKNOWN_FRAME) }
            advanceTimeBy(SETTLE_MILLIS)
            val beforeHalf = credits(virtual.radio.ends.single())
            repeat(CREDIT_HALF + 1) { peripheral.write(UNKNOWN_FRAME) }
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(listOf(CREDIT_WINDOW), onOpen)
            assertEquals(listOf(CREDIT_WINDOW), beforeHalf)
            assertEquals(listOf(CREDIT_WINDOW, CREDIT_HALF, CREDIT_HALF), credits(virtual.radio.ends.single()))
        }

    @Test
    fun thePeripheralNotifiesNoMoreChunksThanItWasGranted() =
        runTest {
            // Arrange
            val air = air()
            val virtual = air.platform(StandardTestDispatcher(testScheduler))
            val node = node(virtual).also { it.start() }
            val fake = connectFake(air, virtual, SMALL_MTU)
            fake.write(record(LinkFrame.PlainHello(1, digest(MESH), FAKE_ID)))
            advanceTimeBy(SETTLE_MILLIS)
            val end = virtual.radio.ends.single()
            val hello = end.chunks
            val status = sendTo(node, LARGE_PAYLOAD)

            // Act
            fake.write(record(LinkFrame.Credit(2)))
            advanceTimeBy(SETTLE_MILLIS)
            val granted = end.chunks - hello
            fake.write(record(LinkFrame.Credit(CREDIT_WINDOW - 2)))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(2, granted)
            assertEquals(CREDIT_WINDOW, end.chunks - hello)
            assertEquals(DeliveryStatus.Queued, status.value)
        }

    @Test
    fun aGrantAboveTheWindowAndACreditFromAPeripheralCloseWithProtocolError() =
        runTest {
            // Arrange
            val air = air()
            val virtual = air.platform(StandardTestDispatcher(testScheduler))
            node(virtual).start()
            val central = connectFake(air, virtual)
            central.write(record(LinkFrame.PlainHello(1, digest(MESH), FAKE_ID)))
            val peripheral = advertiseFake(air, versions = listOf(1))
            advanceTimeBy(SETTLE_MILLIS)
            peripheral.ends.single().write(record(LinkFrame.PlainHello(1, digest(MESH), HIGH_ID)))
            advanceTimeBy(SETTLE_MILLIS)

            // Act
            central.write(record(LinkFrame.Credit(CREDIT_WINDOW)) + record(LinkFrame.Credit(1)))
            peripheral.ends.single().write(record(LinkFrame.Credit(1)))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            val (asPeripheral, asCentral) = virtual.radio.ends
            assertEquals(listOf(CloseReason.PROTOCOL_ERROR), closes(asPeripheral))
            assertEquals(listOf(CloseReason.PROTOCOL_ERROR), closes(asCentral))
        }

    @Test
    fun closeNeedsNoCreditButARecordCutShortForCreditsEndsWithoutOne() =
        runTest {
            // Arrange
            val air = air()
            val idle = air.platform(StandardTestDispatcher(testScheduler))
            val starved = air.platform(StandardTestDispatcher(testScheduler))
            val nodes = listOf(idle, starved).map { node(platformWith(it, NeverDials(it.radio))) }.onEach { it.start() }
            val idleFake = connectFake(air, idle, SMALL_MTU)
            val starvedFake = connectFake(air, starved, SMALL_MTU)
            listOf(idleFake, starvedFake).forEach { it.write(record(LinkFrame.PlainHello(1, digest(MESH), FAKE_ID))) }
            advanceTimeBy(SETTLE_MILLIS)
            sendTo(nodes[1], LARGE_PAYLOAD)
            starvedFake.write(record(LinkFrame.Credit(1)))
            advanceTimeBy(SETTLE_MILLIS)
            val starvedEnd = starved.radio.ends.single()
            val written = starvedEnd.written.size

            // Act
            nodes.forEach { it.stop() }
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(listOf(CloseReason.SHUTTING_DOWN), closes(idle.radio.ends.single()))
            assertEquals(written, starvedEnd.written.size)
            assertFalse(starvedEnd.open)
        }

    @Test
    fun aRecordStillWaitingForItsFirstCreditIsNotCutShortSoTheLinkClosesWithClose() =
        runTest {
            // Arrange
            val air = air()
            val waiting = air.platform(StandardTestDispatcher(testScheduler))
            val node = node(platformWith(waiting, NeverDials(waiting.radio))).also { it.start() }
            val fake = connectFake(air, waiting, SMALL_MTU)
            fake.write(record(LinkFrame.PlainHello(1, digest(MESH), FAKE_ID)))
            advanceTimeBy(SETTLE_MILLIS)
            sendTo(node, LARGE_PAYLOAD)
            advanceTimeBy(SETTLE_MILLIS)
            val end = waiting.radio.ends.single()

            // Act
            node.stop()
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(listOf(CloseReason.SHUTTING_DOWN), closes(end))
            assertFalse(end.open)
        }

    @Test
    fun aSlowCentralStallsOnlyItsOwnLinkAndNeverHasMoreThanTheWindowOutstanding() =
        runTest {
            // Arrange
            val air = air()
            val hub = air.platform(StandardTestDispatcher(testScheduler))
            val slow = air.platform(StandardTestDispatcher(testScheduler))
            val fast = air.platform(StandardTestDispatcher(testScheduler))
            var consumed = 0
            val slowRadio =
                object : RadioPort by slow.radio {
                    override suspend fun connect(peer: PeerHandle): RadioResult<CentralLink> {
                        val result = slow.radio.connect(peer)
                        val toHub = result is RadioResult.Ok && peer === hub.radio.handle
                        return if (toHub) RadioResult.Ok(SlowReader(result.value) { consumed++ }) else result
                    }
                }
            val hubNode = node(platformWith(hub, NeverDials(hub.radio)))
            val nodes = listOf(hubNode, node(platformWith(slow, slowRadio)), node(fast))
            nodes.forEach { it.start() }
            advanceTimeBy(MESH_SETTLE_MILLIS)
            val toSlow = List(MESSAGES) { sendTo(hubNode, slow, LARGE_PAYLOAD) }
            val toFast = List(MESSAGES) { sendTo(hubNode, fast, LARGE_PAYLOAD) }
            val slowEnd = hub.radio.ends.single { it.open && it.peer === slow.radio.handle }
            var outstanding = 0

            // Act
            repeat(SAMPLES) {
                advanceTimeBy(SAMPLE_MILLIS)
                outstanding = maxOf(outstanding, slowEnd.chunks - consumed)
            }

            // Assert
            assertTrue(toFast.all { it.value == DeliveryStatus.Sent })
            assertTrue(toSlow.any { it.value == DeliveryStatus.Queued })
            assertTrue(outstanding in 1..CREDIT_WINDOW, "outstanding $outstanding")
        }

    /** The radio of [port] that never connects as central, so its node links only as peripheral. */
    private class NeverDials(
        port: RadioPort,
    ) : RadioPort by port {
        override suspend fun connect(peer: PeerHandle): RadioResult<CentralLink> = RadioResult.Failed(PEER_UNREACHABLE)
    }

    /** [link], whose node takes [SLOW_CHUNK_MILLIS] to read each chunk and counts it in [consumed]. */
    private class SlowReader(
        link: CentralLink,
        consumed: () -> Unit,
    ) : CentralLink by link {
        override val incoming: Flow<ByteArray> =
            link.incoming.onEach {
                delay(SLOW_CHUNK_MILLIS)
                consumed()
            }
    }

    private fun sendTo(
        node: MeshLink,
        payload: ByteArray,
    ) = assertIs<SendResult.Accepted>(
        node.send(
            node.peers.value
                .single()
                .nodeId,
            payload,
        ),
    ).message.status

    private fun sendTo(
        node: MeshLink,
        target: VirtualPlatform,
        payload: ByteArray,
    ): StateFlow<DeliveryStatus> {
        val peer = node.peers.value.single { it.nodeId.toByteArray().contentEquals(nodeId(target)) }
        return assertIs<SendResult.Accepted>(node.send(peer.nodeId, payload)).message.status
    }

    /** The most links [radio] held as peripheral at once, leaving out those its node refused for the cap. */
    private fun held(radio: VirtualRadio): Int {
        val refused = radio.ends.filter { closes(it) == listOf(CloseReason.RESOURCE_LIMIT) }.toSet()
        var held = 0
        var peak = 0
        for ((end, opened) in radio.peripheralLog.filter { it.first !in refused }) {
            held += if (opened) 1 else -1
            peak = maxOf(peak, held)
        }
        return peak
    }

    private fun credits(end: VirtualLinkEnd): List<Int> {
        val grants = frames(end).filterIsInstance<LinkFrame.Credit>()
        return grants.map { it.credits }
    }

    private companion object {
        const val CAP = 3
        const val NODES = 6
        const val FLOOD = 10
        const val CAP_SEEDS = 4L
        const val MESSAGES = 3
        const val SMALL_MTU = 23
        const val RETRIES_MILLIS = 5_000L
        const val MESH_SETTLE_MILLIS = 3_000L
        const val HANDSHAKE_TIMEOUT_MILLIS = 10_000L
        const val SLOW_CHUNK_MILLIS = 500L
        const val SAMPLES = 100
        const val SAMPLE_MILLIS = 20L
        val LARGE_PAYLOAD = ByteArray(1_000) { it.toByte() }
        val HIGH_ID = ByteArray(32) { 0xFF.toByte() }

        // One single-chunk record of an unknown link-local frame, which a node drops.
        val UNKNOWN_FRAME = record(byteArrayOf(0x3F, 0x01))
    }
}
