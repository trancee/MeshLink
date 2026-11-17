package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.Peer
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.Reachability
import ch.trancee.meshlink.ReceivedMessage
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.SendResult
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import ch.trancee.meshlink.meshLinkConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class, InternalMeshLinkApi::class)
class UnsecuredLinkTest {
    @Test
    fun nodesOfOneMeshLinkAndListEachOtherAsDirectUnsecuredPeers() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val bob = air.platform(StandardTestDispatcher(testScheduler))
            val nodes = listOf(node(alice), node(bob))

            // Act
            nodes.forEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            val seenByAlice = nodes[0].peers.value.single()
            assertContentEquals(nodeId(bob), seenByAlice.nodeId.toByteArray())
            assertSame(Reachability.Direct, seenByAlice.reachability)
            assertFalse(seenByAlice.secured)
            assertTrue(seenByAlice.lastSeen in Instant.fromEpochMilliseconds(TestClock.WALL_CLOCK_START_MILLIS)..now())
            assertContentEquals(
                nodeId(alice),
                nodes[1]
                    .peers.value
                    .single()
                    .nodeId
                    .toByteArray(),
            )
        }

    @Test
    fun nodeServesACapabilityRecordWithVersionOneAndItsAdvertisement() =
        runTest {
            // Arrange
            val platform = air().platform(StandardTestDispatcher(testScheduler))

            // Act
            node(platform).start()

            // Assert
            val record = assertIs<Decoded.Valid<CapabilityRecord>>(CapabilityRecord.decode(platform.radio.capability!!))
            assertEquals(listOf(1), record.value.versions)
            assertContentEquals(platform.radio.advertisement, record.value.advertisement)
            assertNull(record.value.l2capPsm)
        }

    @Test
    fun peripheralClosesAHelloFromAnotherMeshWithWrongMeshAndNeverListsTheSender() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val node = node(alice).also { it.start() }
            val seen = mutableListOf<List<Peer>>()
            backgroundScope.launch { node.peers.collect { seen += it } }
            val fake = connectFake(air, alice)

            // Act
            fake.write(record(LinkFrame.PlainHello(1, digest("Elsewhere"), FAKE_ID)))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(listOf(CloseReason.WRONG_MESH), closes(alice.radio.ends.single()))
            assertFalse(
                alice.radio.ends
                    .single()
                    .open,
            )
            assertTrue(seen.all { it.isEmpty() })
        }

    @Test
    fun peripheralClosesAHelloOfAVersionItDoesNotSpeakWithUnsupportedVersion() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            node(alice).start()
            val fake = connectFake(air, alice)

            // Act
            fake.write(record(LinkFrame.PlainHello(2, digest(MESH), FAKE_ID)))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(listOf(CloseReason.UNSUPPORTED_VERSION), closes(alice.radio.ends.single()))
            assertFalse(
                alice.radio.ends
                    .single()
                    .open,
            )
        }

    @Test
    fun centralClosesTheBearerWithoutAHelloWhenNoVersionIsCommon() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            advertiseFake(air, versions = listOf(2))

            // Act
            node(alice).start()
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            val end = alice.radio.ends.single()
            assertEquals(0, end.written.size)
            assertFalse(end.open)
        }

    @Test
    fun centralClosesAReplyThatDoesNotEchoTheSelectedVersionWithProtocolError() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val fake = advertiseFake(air, versions = listOf(1))
            node(alice).start()
            advanceTimeBy(SETTLE_MILLIS)

            // Act
            fake.ends.single().write(record(LinkFrame.PlainHello(2, digest(MESH), FAKE_ID)))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            val end = alice.radio.ends.single()
            assertEquals(listOf(CloseReason.PROTOCOL_ERROR), closes(end))
            assertFalse(end.open)
        }

    @Test
    fun recordLengthOfZeroClosesTheBearer() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            node(alice).start()
            val fake = connectFake(air, alice)

            // Act
            fake.write(byteArrayOf(0, 0))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(
                0,
                alice.radio.ends
                    .single()
                    .written.size,
            )
            assertFalse(
                alice.radio.ends
                    .single()
                    .open,
            )
        }

    @Test
    fun malformedOrUnexpectedFramesCloseWithProtocolError() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            node(alice).start()
            val malformedClose = connectFake(air, alice)
            val malformed = connectFake(air, alice)
            val reserved = connectFake(air, alice)
            val secondHello = connectFake(air, alice)

            // Act

            malformedClose.write(LinkRecord.encode(byteArrayOf(0x04, 0x00, 0x00)))
            malformed.write(LinkRecord.encode(LinkFrame.PlainHello(1, digest(MESH), FAKE_ID).encode().copyOf(66)))
            reserved.write(record(LinkFrame.PlainHello(1, digest(MESH), ByteArray(32) { 1 })))
            reserved.write(LinkRecord.encode(byteArrayOf(0xC0.toByte())))
            secondHello.write(record(LinkFrame.PlainHello(1, digest(MESH), ByteArray(32) { 2 })))
            secondHello.write(record(LinkFrame.PlainHello(1, digest(MESH), ByteArray(32) { 2 })))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            val ends = alice.radio.ends
            assertEquals(listOf(CloseReason.PROTOCOL_ERROR), closes(ends[0]))
            assertEquals(listOf(CloseReason.PROTOCOL_ERROR), closes(ends[1]))
            assertEquals(listOf(CloseReason.PROTOCOL_ERROR), closes(ends[2]))
            assertEquals(listOf(CloseReason.PROTOCOL_ERROR), closes(ends[3]))
            assertTrue(ends.none { it.open })
        }

    @Test
    fun closeBeforeTheHelloEndsTheLinkSilently() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            node(alice).start()
            val fake = connectFake(air, alice)

            // Act
            fake.write(record(LinkFrame.Close(CloseReason.NORMAL)))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(
                0,
                alice.radio.ends
                    .single()
                    .written.size,
            )
            assertFalse(
                alice.radio.ends
                    .single()
                    .open,
            )
        }

    @Test
    fun unknownLinkLocalFrameIsDroppedAndEveryFrameRefreshesLastSeen() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val node = node(alice).also { it.start() }
            val fake = connectFake(air, alice)
            fake.write(record(LinkFrame.PlainHello(1, digest(MESH), FAKE_ID)))
            advanceTimeBy(SETTLE_MILLIS)
            val linkedAt =
                node.peers.value
                    .single()
                    .lastSeen

            // Act
            advanceTimeBy(SETTLE_MILLIS)
            fake.write(LinkRecord.encode(byteArrayOf(0x3F, 0x01)))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            val peer = node.peers.value.single()
            assertContentEquals(FAKE_ID, peer.nodeId.toByteArray())
            assertTrue(peer.lastSeen > linkedAt)
            assertTrue(
                alice.radio.ends
                    .single()
                    .open,
            )
            assertEquals(listOf<LinkFrame>(), frames(alice.radio.ends.single()).drop(1))
        }

    @Test
    fun peerWhoseBearerDropsLeavesPeers() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val node = node(alice).also { it.start() }
            val fake = connectFake(air, alice)
            fake.write(record(LinkFrame.PlainHello(1, digest(MESH), FAKE_ID)))
            advanceTimeBy(SETTLE_MILLIS)
            val linked = node.peers.value.size

            // Act
            fake.disconnect()
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(1, linked)
            assertEquals(emptyList(), node.peers.value)
        }

    @Test
    fun silentPeerIsClosedAfterTheTenSecondHandshakeTimeout() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            node(alice).start()
            connectFake(air, alice)
            val connectedAt = testScheduler.currentTime

            // Act
            advanceTimeBy(HANDSHAKE_TIMEOUT_MILLIS - 1 - (testScheduler.currentTime - connectedAt))
            val openBefore =
                alice.radio.ends
                    .single()
                    .open
            advanceTimeBy(2)

            // Assert
            assertTrue(openBefore)
            assertFalse(
                alice.radio.ends
                    .single()
                    .open,
            )
        }

    @Test
    fun simultaneousLinksCollapseToTheOneWhoseCentralHasTheLowerNodeId() {
        var duplicates = 0
        for (seed in 1L..RACE_SEEDS) {
            runTest {
                // Arrange
                val air = air(seed)
                val alice = air.platform(StandardTestDispatcher(testScheduler))
                val bob = air.platform(StandardTestDispatcher(testScheduler))
                val nodes = listOf(node(alice), node(bob))

                // Act
                nodes.forEach { it.start() }
                advanceTimeBy(SETTLE_MILLIS)

                // Assert
                val aliceCentral = alice.radio.ends.single { it.open } is VirtualCentralEnd
                val bobCentral = bob.radio.ends.single { it.open } is VirtualCentralEnd
                assertTrue(aliceCentral != bobCentral, "seed $seed: both ends of one link")
                assertEquals(1, nodes[0].peers.value.size, "seed $seed")
                assertEquals(1, nodes[1].peers.value.size, "seed $seed")
                val closed = (alice.radio.ends + bob.radio.ends).flatMap(::closes)
                assertTrue(closed.all { it == CloseReason.DUPLICATE_LINK }, "seed $seed")
                if (alice.radio.ends.size > 1) {
                    duplicates++
                    val aliceLower = LinkPolicy.compareNodeIds(nodeId(alice), nodeId(bob)) < 0
                    assertEquals(aliceLower, aliceCentral, "seed $seed: kept link's central is the lower NodeId")
                }
            }
        }
        assertTrue(duplicates > 0, "no seed raced")
    }

    @Test
    fun stopClosesEveryLinkWithShuttingDownAndThePeerDropsIt() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val bob = air.platform(StandardTestDispatcher(testScheduler))
            val nodes = listOf(node(alice), node(bob))
            nodes.forEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)
            val link = alice.radio.ends.single { it.open }

            // Act
            nodes[0].stop()
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(listOf(CloseReason.SHUTTING_DOWN), closes(link))
            assertTrue((alice.radio.ends + bob.radio.ends).none { it.open })
            assertEquals(emptyList(), nodes[0].peers.value)
            assertEquals(emptyList(), nodes[1].peers.value)
        }

    @Test
    fun failedConnectOpensNoLink() =
        runTest {
            // Arrange
            val air = air()
            val virtual = air.platform(StandardTestDispatcher(testScheduler))
            val radio =
                object : RadioPort by virtual.radio {
                    override suspend fun connect(peer: PeerHandle) = RadioResult.Failed(RadioFailure.PEER_UNREACHABLE)
                }
            val fake = advertiseFake(air, capability = CapabilityRecord.of(listOf(1), ByteArray(16)).encode())

            // Act
            node(platformWith(virtual, radio)).start()
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(emptyList(), fake.ends)
        }

    @Test
    fun centralClosesTheBearerWhenThePeerServesNoUsableCapabilityRecord() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            advertiseFake(air, capability = null)
            advertiseFake(air, capability = byteArrayOf(0))

            // Act
            node(alice).start()
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(2, alice.radio.ends.size)
            assertTrue(alice.radio.ends.none { it.open || it.written.isNotEmpty() })
        }

    @Test
    fun recordsAfterAFatalOneInTheSameChunkAreIgnored() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val node = node(alice).also { it.start() }
            val fake = connectFake(air, alice)

            // Act
            fake.write(record(byteArrayOf(0x04, 0x00, 0x00)) + record(LinkFrame.PlainHello(1, digest(MESH), FAKE_ID)))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            val sent = frames(alice.radio.ends.single())
            assertEquals(listOf<LinkFrame>(), sent.filterIsInstance<LinkFrame.PlainHello>())
            assertEquals(emptyList(), node.peers.value)
        }

    @Test
    fun secondLinkFromTheSameCentralNodeIdIsClosedAsDuplicate() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val node = node(alice).also { it.start() }
            val first = connectFake(air, alice)
            first.write(record(LinkFrame.PlainHello(1, digest(MESH), FAKE_ID)))
            advanceTimeBy(SETTLE_MILLIS)
            val second = connectFake(air, alice)

            // Act
            second.write(record(LinkFrame.PlainHello(1, digest(MESH), FAKE_ID)))
            advanceTimeBy(SETTLE_MILLIS + DRAIN_MILLIS)

            // Assert
            val ends = alice.radio.ends
            assertEquals(listOf(true, false), ends.map { it.open })
            assertEquals(listOf(CloseReason.DUPLICATE_LINK), closes(ends[1]))
            assertContentEquals(
                FAKE_ID,
                node.peers.value
                    .single()
                    .nodeId
                    .toByteArray(),
            )
        }

    @Test
    fun peerThatClosesALinkAsDuplicateStaysListedWhileItsOtherLinkOpens() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val node = node(alice).also { it.start() }
            val first = connectFake(air, alice)
            first.write(record(LinkFrame.PlainHello(1, digest(MESH), FAKE_ID)))
            advanceTimeBy(SETTLE_MILLIS)
            val second = connectFake(air, alice)
            val seen = mutableListOf<List<Peer>>()
            backgroundScope.launch { node.peers.collect { seen += it } }

            // Act
            first.write(record(LinkFrame.Close(CloseReason.DUPLICATE_LINK)))
            advanceTimeBy(SETTLE_MILLIS)
            second.write(record(LinkFrame.PlainHello(1, digest(MESH), FAKE_ID)))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertTrue(seen.all { it.size == 1 })
            assertEquals(listOf(false, true), alice.radio.ends.map { it.open })
        }

    @Test
    fun peerKeptAfterADuplicateCloseLeavesWhenItsOtherLinkFails() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val node = node(alice).also { it.start() }
            val first = connectFake(air, alice)
            first.write(record(LinkFrame.PlainHello(1, digest(MESH), FAKE_ID)))
            advanceTimeBy(SETTLE_MILLIS)
            val second = connectFake(air, alice)
            first.write(record(LinkFrame.Close(CloseReason.DUPLICATE_LINK)))
            advanceTimeBy(SETTLE_MILLIS)
            val keptWhileOpening = node.peers.value.size

            // Act
            second.disconnect()
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(1, keptWhileOpening)
            assertEquals(emptyList(), node.peers.value)
        }

    @Test
    fun messageThePeerWroteBeforeItsCloseOnALinkClosedAsDuplicateIsDelivered() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val node = node(alice).also { it.start() }
            val received = mutableListOf<ReceivedMessage>()
            backgroundScope.launch { node.messages.collect { received += it } }
            val inbound = connectFake(air, alice)
            inbound.write(record(LinkFrame.PlainHello(1, digest(MESH), HIGH_ID)))
            advanceTimeBy(SETTLE_MILLIS)
            val outbound = advertiseFake(air, versions = listOf(1))
            advanceTimeBy(SETTLE_MILLIS)
            outbound.ends.single().write(record(LinkFrame.PlainHello(1, digest(MESH), HIGH_ID)))
            advanceTimeBy(SETTLE_MILLIS)
            val inboundEnd = alice.radio.ends.first()
            val drainingAfterClose = inboundEnd.open
            val sender = UnsecuredMessaging(SodiumCryptoProvider, HIGH_ID)
            val draft = sender.draft(nodeId(alice), PAYLOADS[0], (now().epochSeconds + LIFETIME_SECONDS).toUInt(), 1)

            // Act
            inbound.write(record(sender.frame(draft, Derivations.epoch(now().epochSeconds))))
            (0..Reassembly.SLOTS).forEach { stream ->
                inbound.write(record(LinkFrame.Fragment(stream, 0, false, byteArrayOf(MESSAGE_TYPE))))
            }
            inbound.write(record(LinkFrame.Close(CloseReason.DUPLICATE_LINK)))
            advanceTimeBy(SETTLE_MILLIS)

            val afterHello = frames(inboundEnd).drop(1)

            // Assert
            assertEquals(listOf(CloseReason.DUPLICATE_LINK), afterHello.map { assertIs<LinkFrame.Close>(it).reason })
            assertTrue(drainingAfterClose)
            assertEquals(listOf(PAYLOADS[0].toList()), received.map { it.payload.toList() })
            assertEquals(listOf(false, true), alice.radio.ends.map { it.open })
        }

    @Test
    fun linkClosedAsDuplicateDisconnectsWhenThePeersCloseDoesNotArriveInTenSeconds() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            node(alice).start()
            connectFake(air, alice).write(record(LinkFrame.PlainHello(1, digest(MESH), HIGH_ID)))
            advanceTimeBy(SETTLE_MILLIS)
            val outbound = advertiseFake(air, versions = listOf(1))
            advanceTimeBy(SETTLE_MILLIS)
            outbound.ends.single().write(record(LinkFrame.PlainHello(1, digest(MESH), HIGH_ID)))
            // The CLOSE goes out within SETTLE_MILLIS of the hello, so the drain ends within that window too.
            advanceTimeBy(SETTLE_MILLIS)
            val inboundEnd = alice.radio.ends.first()

            // Act
            advanceTimeBy(DRAIN_MILLIS - SETTLE_MILLIS - 1)
            val openBeforeDeadline = inboundEnd.open
            advanceTimeBy(SETTLE_MILLIS + 2)

            // Assert
            assertEquals(listOf(CloseReason.DUPLICATE_LINK), closes(inboundEnd))
            assertTrue(openBeforeDeadline)
            assertFalse(inboundEnd.open)
        }

    @Test
    fun peerHandleWithAnotherLinkLeftIsNotDialedAgainWhenOneLinkCloses() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val fake = advertiseFake(air, listOf(1))
            node(alice).start()
            advanceTimeBy(SETTLE_MILLIS)
            val inbound = (fake.connect(alice.radio.handle) as RadioResult.Ok).value
            advanceTimeBy(SETTLE_MILLIS)

            // Act
            inbound.disconnect()
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(listOf(true, false), alice.radio.ends.map { it.open })
        }

    @Test
    fun stopWhileALinkIsSendingItsCloseStillDisconnectsTheBearer() =
        runTest {
            // Arrange
            val air = air()
            val virtual = air.platform(StandardTestDispatcher(testScheduler))
            val radio =
                object : RadioPort by virtual.radio {
                    override val events =
                        virtual.radio.events.map { event ->
                            if (event is RadioEvent.Connected) RadioEvent.Connected(SlowLink(event.link)) else event
                        }
                }
            val node = node(platformWith(virtual, radio)).also { it.start() }
            val fake = connectFake(air, virtual)
            fake.write(record(byteArrayOf(0xC0.toByte())))
            advanceTimeBy(SETTLE_MILLIS)

            // Act
            node.stop()
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertFalse(
                virtual.radio.ends
                    .single()
                    .open,
            )
        }

    @Test
    fun messagesQueuedOnALinkClosedAsDuplicateGoOutOnTheKeptLink() =
        runTest {
            // Arrange
            val air = air()
            val virtual = air.platform(StandardTestDispatcher(testScheduler))
            val radio =
                object : RadioPort by virtual.radio {
                    override val events =
                        virtual.radio.events.map { event ->
                            if (event is RadioEvent.Connected) {
                                RadioEvent.Connected(SlowLink(event.link) { isMessage(it) })
                            } else {
                                event
                            }
                        }

                    override suspend fun connect(peer: PeerHandle): RadioResult<CentralLink> =
                        when (val result = virtual.radio.connect(peer)) {
                            is RadioResult.Ok -> RadioResult.Ok(SlowCentralLink(result.value))
                            is RadioResult.Failed -> result
                        }
                }
            val node = node(platformWith(virtual, radio)).also { it.start() }
            val inbound = connectFake(air, virtual)
            inbound.write(record(LinkFrame.PlainHello(1, digest(MESH), HIGH_ID)))
            advanceTimeBy(SETTLE_MILLIS)
            grantCredits(inbound, virtual.radio.ends.single())
            val peer =
                node.peers.value
                    .single()
                    .nodeId
            val statuses = PAYLOADS.map { assertIs<SendResult.Accepted>(node.send(peer, it)).message.status }
            runCurrent()

            // Act
            val outbound = advertiseFake(air, versions = listOf(1))
            advanceTimeBy(SETTLE_MILLIS)
            outbound.ends.single().write(record(LinkFrame.PlainHello(1, digest(MESH), HIGH_ID)))
            advanceTimeBy(PAYLOADS.size * SLOW_WRITE_MILLIS + SETTLE_MILLIS)

            // Assert
            val (inboundEnd, outboundEnd) = virtual.radio.ends
            assertEquals(List(PAYLOADS.size) { DeliveryStatus.Sent }, statuses.map { it.value })
            assertEquals(listOf(CloseReason.DUPLICATE_LINK), closes(inboundEnd))
            assertEquals(PAYLOADS.take(1).map { it.toList() }, messages(inboundEnd).map { it.toList() })
            assertEquals(PAYLOADS.drop(1).map { it.toList() }, messages(outboundEnd).map { it.toList() })
        }

    @Test
    fun messageWhoseWriteFailsIsSentOnThePeersNextLink() =
        runTest {
            // Arrange
            val air = air()
            val virtual = air.platform(StandardTestDispatcher(testScheduler))
            var failing = true
            val radio =
                object : RadioPort by virtual.radio {
                    override val events =
                        virtual.radio.events.map { event ->
                            if (event is RadioEvent.Connected && failing) {
                                failing = false
                                RadioEvent.Connected(FailingLink(event.link))
                            } else {
                                event
                            }
                        }
                }
            val node = node(platformWith(virtual, radio)).also { it.start() }
            val first = connectFake(air, virtual)
            first.write(record(LinkFrame.PlainHello(1, digest(MESH), FAKE_ID)))
            advanceTimeBy(SETTLE_MILLIS)
            grantCredits(first, virtual.radio.ends.single())
            advanceTimeBy(SETTLE_MILLIS)
            val status =
                assertIs<SendResult.Accepted>(
                    node.send(
                        node.peers.value
                            .single()
                            .nodeId,
                        PAYLOADS[0],
                    ),
                ).message.status
            advanceTimeBy(SETTLE_MILLIS)
            val afterFailedWrite = status.value

            // Act
            first.disconnect()
            advanceTimeBy(SETTLE_MILLIS)
            val second = connectFake(air, virtual)
            second.write(record(LinkFrame.PlainHello(1, digest(MESH), FAKE_ID)))
            advanceTimeBy(SETTLE_MILLIS)
            grantCredits(second, virtual.radio.ends[1])
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(DeliveryStatus.Queued, afterFailedWrite)
            assertEquals(DeliveryStatus.Sent, status.value)
            assertEquals(listOf(PAYLOADS[0].toList()), messages(virtual.radio.ends[1], FAKE_ID).map { it.toList() })
        }

    @Test
    fun undecodableMessageAfterTheHelloIsDroppedAndTheLinkStaysOpen() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val node = node(alice).also { it.start() }
            val received = mutableListOf<ReceivedMessage>()
            backgroundScope.launch { node.messages.collect { received += it } }
            val fake = connectFake(air, alice)
            fake.write(record(LinkFrame.PlainHello(1, digest(MESH), FAKE_ID)))
            advanceTimeBy(SETTLE_MILLIS)

            // Act
            fake.write(record(byteArrayOf(0x40, 0x01)))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(emptyList(), received)
            assertTrue(
                alice.radio.ends
                    .single()
                    .open,
            )
        }

    @Test
    fun messageBeforeTheHelloIsDroppedAndTheLinkStaysOpen() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val node = node(alice).also { it.start() }
            val received = mutableListOf<ReceivedMessage>()
            backgroundScope.launch { node.messages.collect { received += it } }
            val fake = connectFake(air, alice)
            val sender = UnsecuredMessaging(SodiumCryptoProvider, FAKE_ID)
            val expiry = (now().epochSeconds + LIFETIME_SECONDS).toUInt()
            val draft = sender.draft(nodeId(alice), PAYLOADS[0], expiry, hopLimit = 1)

            // Act
            fake.write(record(sender.frame(draft, Derivations.epoch(now().epochSeconds))))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(emptyList(), received)
            assertTrue(
                alice.radio.ends
                    .single()
                    .open,
            )
        }

    /** A link whose writes, or those [slow] picks, take longer than a test waits. */
    private class SlowLink(
        private val link: RadioLink,
        private val slow: (ByteArray) -> Boolean = { true },
    ) : RadioLink by link {
        override suspend fun write(bytes: ByteArray): RadioResult<Unit> {
            if (slow(bytes)) delay(SLOW_WRITE_MILLIS)
            return link.write(bytes)
        }
    }

    /** A central link whose `MESSAGE` writes take longer than a test waits. */
    private class SlowCentralLink(
        private val link: CentralLink,
    ) : CentralLink by link {
        override suspend fun write(bytes: ByteArray): RadioResult<Unit> {
            if (isMessage(bytes)) delay(SLOW_WRITE_MILLIS)
            return link.write(bytes)
        }
    }

    /** A link whose writes all fail as if the bearer had dropped. */
    private class FailingLink(
        private val link: RadioLink,
    ) : RadioLink by link {
        override suspend fun write(bytes: ByteArray): RadioResult<Unit> = RadioResult.Failed(RadioFailure.LINK_CLOSED)
    }

    private fun TestScope.now(): Instant {
        val millis = TestClock.WALL_CLOCK_START_MILLIS + testScheduler.currentTime
        return Instant.fromEpochMilliseconds(millis)
    }

    /** The payloads of the `MESSAGE` frames [end] wrote that [receiver] accepts. */
    private fun TestScope.messages(
        end: VirtualLinkEnd,
        receiver: ByteArray = HIGH_ID,
    ): List<ByteArray> {
        val bodies = assertIs<Decoded.Valid<List<ByteArray>>>(RecordReader().read(end.written)).value
        val messaging = UnsecuredMessaging(SodiumCryptoProvider, receiver)
        return bodies
            .filter { it[0] == MESSAGE_TYPE }
            .map { assertNotNull(messaging.accept(it, now().epochSeconds)).payload }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val HANDSHAKE_TIMEOUT_MILLIS = 10_000L
        const val RACE_SEEDS = 20L
        const val SLOW_WRITE_MILLIS = 60_000L
        const val DRAIN_MILLIS = 10_000L
        const val LIFETIME_SECONDS = 1_800
        const val MESSAGE_TYPE: Byte = 0x40
        const val RECORD_LENGTH_BYTES = 2

        // Above any real NodeId, so this node keeps the link it dials to the fake.
        val HIGH_ID = ByteArray(32) { 0xFF.toByte() }

        // The first goes out on the closing link, the second on the kept one, and the third is
        // refused by the closing link and moves to the kept one.
        val PAYLOADS = listOf("first", "second", "third").map { it.encodeToByteArray() }

        /** Whether [record] is a link record holding a `MESSAGE` frame. */
        fun isMessage(record: ByteArray): Boolean = record[RECORD_LENGTH_BYTES] == MESSAGE_TYPE
    }
}
