package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DiagnosticEvent
import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
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

/** Moving a link from GATT to an L2CAP CoC channel (spec "Bearer switch", D-49) in the `unsecured` profile. */
@OptIn(ExperimentalCoroutinesApi::class, InternalMeshLinkApi::class)
class BearerSwitchTest {
    @Test
    fun twoCocNodesMoveTheirLinkToCocAndKeepEveryMessageInOrder() =
        runTest {
            // Arrange
            val air = air()
            val platforms = List(2) { air.platform(StandardTestDispatcher(testScheduler), coc = true) }
            // The second node doesn't scan, so the first opens the one link and no duplicate resends a message.
            val listener =
                object : RadioPort by platforms[1].radio {
                    override fun startScanning() = RadioResult.Ok(Unit)
                }
            val nodes = listOf(node(platforms[0]), node(platformWith(platforms[1], listener)))
            val received = nodes.map { inbox(it) }
            nodes.forEach { it.start() }
            while (nodes.any { it.peers.value.isEmpty() }) advanceTimeBy(1)

            // Act
            var sent = 0
            val sendEach = {
                nodes.forEachIndexed { n, node -> node.send(node.peer(), "$n-$sent".encodeToByteArray()) }
            }
            while (platforms.any { lastFrame(it) != LinkFrame.BearerSwitch }) {
                sendEach()
                sent++
                advanceTimeBy(1)
            }
            repeat(COUNT) {
                sendEach()
                sent++
            }
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(List(sent) { "1-$it" }, received[0])
            assertEquals(List(sent) { "0-$it" }, received[1])
            platforms.forEach { platform ->
                val end = platform.radio.ends.single { it.open }
                val onCoc = bodies(end.channels.single().written)
                assertEquals(LinkFrame.BearerSwitch, frames(end).last())
                assertTrue(bodies(end.written).any { it[0] == MESSAGE_TYPE }, "messages before the switch")
                assertTrue(onCoc.any { it[0] == MESSAGE_TYPE }, "messages after the switch")
                assertFalse(onCoc.any { it[0] == CREDIT_TYPE }, "credits on CoC")
            }
        }

    @Test
    fun aCocNodeLinksOverGattWithANodeWithL2capUpgradeOffOrARadioWithoutCoc() =
        runTest {
            // Arrange
            val air = air()
            val platforms =
                listOf(
                    air.platform(StandardTestDispatcher(testScheduler), coc = true),
                    air.platform(StandardTestDispatcher(testScheduler), coc = true),
                    air.platform(StandardTestDispatcher(testScheduler)),
                )
            val nodes = platforms.mapIndexed { index, platform -> node(platform) { l2capUpgrade = index != 1 } }
            val diagnostics = nodes.flatMap { diagnostics(it) }

            // Act
            nodes.forEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(listOf(2, 2, 2), nodes.map { it.peers.value.size })
            assertTrue(platforms.all { platform -> platform.radio.ends.all { it.channels.isEmpty() } })
            assertEquals(emptyList(), diagnostics)
        }

    @Test
    fun aChannelThePeerRefusesLeavesTheLinkOnCreditedGattAndReportsTheFallback() =
        runTest {
            // Arrange
            val air = air()
            val platforms = List(2) { air.platform(StandardTestDispatcher(testScheduler), coc = true) }
            platforms.forEach { it.radio.refuseChannels = true }
            val nodes = platforms.map { node(it) }
            val received = nodes.map { inbox(it) }
            val seen = nodes.map { diagnostics(it) }

            // Act
            nodes.forEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)
            repeat(COUNT) { i ->
                nodes.forEachIndexed { n, node -> node.send(node.peer(), "$n-$i".encodeToByteArray()) }
            }
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            val fallbacks = seen.flatten()
            assertTrue(fallbacks.isNotEmpty())
            fallbacks.forEach {
                assertEquals("ML3005", it.code)
                val fallback = assertIs<DiagnosticEvent.BearerFallback>(it)
                assertEquals(DiagnosticEvent.BearerFallback.Reason.RadioError, fallback.reason)
                assertTrue(Regex("peer-[0-9a-f]{8}").matches(fallback.peer.toString()), fallback.peer.toString())
            }
            assertEquals(List(COUNT) { "1-$it" }, received[0])
            assertEquals(List(COUNT) { "0-$it" }, received[1])
            assertTrue(platforms.all { platform -> platform.radio.ends.all { it.channels.isEmpty() } })
        }

    @Test
    fun aLostChannelEndsTheLinkAndTheCentralLinksAgainWithAFreshHello() =
        runTest {
            // Arrange
            val air = air()
            val platforms = List(2) { air.platform(StandardTestDispatcher(testScheduler), coc = true) }
            val nodes = platforms.map { node(it) }
            nodes.forEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)
            val lost = platforms.map { platform -> platform.radio.ends.single { it.open } }
            val central =
                platforms.single { platform -> platform.radio.ends.any { it.open && it is VirtualCentralEnd } }

            // Act
            lost
                .single { it is VirtualCentralEnd }
                .channels
                .single()
                .drop()
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            val again = central.radio.ends.last { it is VirtualCentralEnd }
            assertTrue(lost.none { it.open })
            assertTrue(again.open)
            assertIs<LinkFrame.PlainHello>(frames(again).first())
            assertEquals(LinkFrame.BearerSwitch, frames(again).last())
            assertEquals(1, again.channels.size)
            assertEquals(listOf(1, 1), nodes.map { it.peers.value.size })
        }

    @Test
    fun gattBytesAfterTheCentralsBearerSwitchAreDiscardedAndEarlierCocBytesWait() =
        runTest {
            // Arrange
            val (alice, link) = linkedToFakeCentral()
            val received = inbox(alice.node)
            val channel = (link.openChannel(VirtualRadio.PSM) as RadioResult.Ok).value
            channel.write(record(draft(alice.platform, "early")))
            advanceTimeBy(SETTLE_MILLIS)
            val beforeSwitch = received.toList()

            // Act
            link.write(record(LinkFrame.BearerSwitch) + record(draft(alice.platform, "same write")))
            link.write(record(draft(alice.platform, "later write")))
            advanceTimeBy(SETTLE_MILLIS)
            channel.write(record(draft(alice.platform, "on CoC")))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            val end =
                alice.platform.radio.ends
                    .single()
            assertEquals(emptyList(), beforeSwitch)
            assertEquals(listOf("early", "on CoC"), received)
            assertEquals(LinkFrame.BearerSwitch, frames(end).last())
            assertTrue(end.open)
        }

    @Test
    fun aCentralsBearerSwitchWithoutAChannelClosesWithProtocolErrorAfterFiveSeconds() =
        runTest {
            // Arrange
            val (alice, link) = linkedToFakeCentral()
            val end =
                alice.platform.radio.ends
                    .single()

            // Act
            link.write(record(LinkFrame.BearerSwitch))
            advanceTimeBy(COC_ACCEPT_TIMEOUT_MILLIS - 1)
            val waiting = end.open
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertTrue(waiting)
            assertEquals(listOf(CloseReason.PROTOCOL_ERROR), closes(end))
            assertFalse(end.open)
        }

    @Test
    fun aBearerSwitchOutOfTurnClosesWithProtocolError() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler), coc = true)
            node(alice).start()
            val beforeHello = connectFake(air, alice)
            val peripheral = advertiseFake(air, capability = OFFER, coc = true).also { it.listenForChannels() }
            advanceTimeBy(SETTLE_MILLIS)
            val asCentral = alice.radio.ends.single { it is VirtualCentralEnd }
            val toAlice = peripheral.ends.single()

            // Act
            beforeHello.write(record(LinkFrame.BearerSwitch))
            toAlice.write(record(LinkFrame.PlainHello(1, digest(MESH), HIGH_ID)) + record(LinkFrame.BearerSwitch))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            val asPeripheral = alice.radio.ends.single { it !is VirtualCentralEnd }
            assertEquals(listOf(CloseReason.PROTOCOL_ERROR), closes(asPeripheral))
            assertEquals(listOf(CloseReason.PROTOCOL_ERROR), closes(asCentral))
        }

    @Test
    fun aSecondBearerSwitchOnCocClosesWithProtocolErrorOnCoc() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler), coc = true)
            node(alice).start()
            val peripheral = advertiseFake(air, capability = OFFER, coc = true).also { it.listenForChannels() }
            advanceTimeBy(SETTLE_MILLIS)
            val toAlice = peripheral.ends.single()
            toAlice.write(record(LinkFrame.PlainHello(1, digest(MESH), HIGH_ID)))
            advanceTimeBy(SETTLE_MILLIS)
            val coc = toAlice.channels.single()
            toAlice.write(record(LinkFrame.BearerSwitch))
            advanceTimeBy(SETTLE_MILLIS)

            // Act
            coc.write(record(LinkFrame.BearerSwitch))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            // The freed slot dials the peripheral again (ticket 35a); the first link is the one closed.
            val end = alice.radio.ends.first()
            assertEquals(LinkFrame.BearerSwitch, frames(end).last())
            assertEquals(listOf(CloseReason.PROTOCOL_ERROR), closes(frames(end.channels.single().written)))
            assertFalse(end.open)
        }

    @Test
    fun aChannelNoLinkWillClaimIsClosed() =
        runTest {
            // Arrange
            val air = air()
            val virtual = air.platform(StandardTestDispatcher(testScheduler), coc = true)
            val stray = Channel<RadioEvent>(Channel.UNLIMITED)
            val radio =
                object : RadioPort by virtual.radio {
                    override val events = merge(virtual.radio.events, stray.receiveAsFlow())
                }
            node(platformWith(virtual, radio)).start()
            advanceTimeBy(1)
            val fake = (air.radio(coc = true).connect(virtual.radio.handle) as RadioResult.Ok).value
            fake.write(record(LinkFrame.PlainHello(1, digest(MESH), FAKE_ID)) + record(LinkFrame.Credit(CREDIT_WINDOW)))
            advanceTimeBy(SETTLE_MILLIS)
            val elsewhere = air.radio(coc = true).also { it.listenForChannels() }
            elsewhere.startAdvertising(ByteArray(RadioPort.ADVERTISEMENT_LENGTH))
            val toElsewhere = (air.radio(coc = true).connect(elsewhere.handle) as RadioResult.Ok).value
            advanceTimeBy(SETTLE_MILLIS)
            val unlinked = (toElsewhere.openChannel(VirtualRadio.PSM) as RadioResult.Ok).value
            advanceTimeBy(SETTLE_MILLIS)

            // Act
            stray.send(
                RadioEvent.ChannelOpened(
                    elsewhere.ends
                        .single()
                        .channels
                        .single(),
                ),
            )
            val unclaimed = (fake.openChannel(VirtualRadio.PSM) as RadioResult.Ok).value
            val second = (fake.openChannel(VirtualRadio.PSM) as RadioResult.Ok).value
            advanceTimeBy(SETTLE_MILLIS)
            val beforeClose = unclaimed.write(byteArrayOf(0))
            fake.write(record(LinkFrame.Close(CloseReason.SHUTTING_DOWN)))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertIs<RadioResult.Failed>(unlinked.write(byteArrayOf(0)))
            assertIs<RadioResult.Failed>(second.write(byteArrayOf(0)))
            assertIs<RadioResult.Ok<Unit>>(beforeClose)
            assertIs<RadioResult.Failed>(unclaimed.write(byteArrayOf(0)))
        }

    @Test
    fun aChannelLostWhileItsLinkDrainsAsDuplicateClosesOnlyThatLink() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler), coc = true)
            val node = node(alice).also { it.start() }
            advanceTimeBy(1)
            val inbound = (air.radio(coc = true).connect(alice.radio.handle) as RadioResult.Ok).value
            val hello = record(LinkFrame.PlainHello(1, digest(MESH), HIGH_ID))
            inbound.write(hello + record(LinkFrame.Credit(CREDIT_WINDOW)))
            advanceTimeBy(SETTLE_MILLIS)
            inbound.openChannel(VirtualRadio.PSM)
            inbound.write(record(LinkFrame.BearerSwitch))
            advanceTimeBy(SETTLE_MILLIS)
            val outbound = advertiseFake(air, versions = listOf(1))
            advanceTimeBy(SETTLE_MILLIS)
            outbound.ends.single().write(record(LinkFrame.PlainHello(1, digest(MESH), HIGH_ID)))
            advanceTimeBy(SETTLE_MILLIS)
            val draining = alice.radio.ends.first()
            val drainingBeforeDrop = draining.open

            // Act
            draining.channels.single().drop()
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertTrue(drainingBeforeDrop)
            assertEquals(LinkFrame.BearerSwitch, frames(draining).last())
            assertEquals(listOf(false, true), alice.radio.ends.map { it.open })
            assertEquals(1, alice.radio.ends.count { it is VirtualCentralEnd })
            assertEquals(1, node.peers.value.size)
        }

    /** A started CoC node linked as peripheral to a fake CoC central that said hello as [FAKE_ID]. */
    private suspend fun TestScope.linkedToFakeCentral(): Pair<Alice, CentralLink> {
        val air = air()
        val platform = air.platform(StandardTestDispatcher(testScheduler), coc = true)
        val node = node(platform).also { it.start() }
        advanceTimeBy(1)
        val link = (air.radio(coc = true).connect(platform.radio.handle) as RadioResult.Ok).value
        link.write(record(LinkFrame.PlainHello(1, digest(MESH), FAKE_ID)) + record(LinkFrame.Credit(CREDIT_WINDOW)))
        advanceTimeBy(SETTLE_MILLIS)
        return Alice(node, platform) to link
    }

    private data class Alice(
        val node: MeshLink,
        val platform: VirtualPlatform,
    )

    /** Every message [node] receives from now on, as text. */
    private fun TestScope.inbox(node: MeshLink): List<String> {
        val received = mutableListOf<String>()
        backgroundScope.launch { node.messages.collect { received += it.payload.decodeToString() } }
        return received
    }

    private fun MeshLink.peer() = peers.value.single().nodeId

    /** A `MESSAGE` frame from [FAKE_ID] to the node on [platform] carrying [text]. */
    private fun TestScope.draft(
        platform: VirtualPlatform,
        text: String,
    ): ByteArray {
        val seconds = (TestClock.WALL_CLOCK_START_MILLIS + testScheduler.currentTime) / MILLIS_PER_SECOND
        val sender = UnsecuredMessaging(SodiumCryptoProvider, FAKE_ID)
        val draft = sender.draft(nodeId(platform), text.encodeToByteArray(), (seconds + LIFETIME_SECONDS).toUInt())
        return sender.frame(draft, Derivations.epoch(seconds), hopLimit = 1)
    }

    private fun lastFrame(platform: VirtualPlatform): LinkFrame = frames(platform.radio.ends.single { it.open }).last()

    private fun bodies(written: ByteArray): List<ByteArray> {
        val read = RecordReader().read(written)
        return assertIs<Decoded.Valid<List<ByteArray>>>(read).value
    }

    private fun frames(written: ByteArray): List<LinkFrame> =
        bodies(written)
            .filter { it[0] != MESSAGE_TYPE }
            .map { assertIs<Decoded.Valid<LinkFrame>>(LinkFrame.decode(it)).value }

    private fun closes(frames: List<LinkFrame>): List<CloseReason> {
        val closes = frames.filterIsInstance<LinkFrame.Close>()
        return closes.map { it.reason }
    }

    private companion object {
        const val COUNT = 24
        const val MESSAGE_TYPE: Byte = 0x40
        const val CREDIT_TYPE: Byte = 0x03
        const val LIFETIME_SECONDS = 1_800
        const val MILLIS_PER_SECOND = 1_000L
        const val COC_ACCEPT_TIMEOUT_MILLIS = 5_000L
        val HIGH_ID = ByteArray(32) { 0xFF.toByte() }
        val OFFER = CapabilityRecord.of(listOf(1), ByteArray(RadioPort.ADVERTISEMENT_LENGTH), VirtualRadio.PSM).encode()
    }
}
