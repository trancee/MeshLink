package ch.trancee.meshlink.internal

import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.Peer
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.Reachability
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import ch.trancee.meshlink.meshLinkConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
            fake.write(LinkRecord.encode(byteArrayOf(0x02, 0x01)))
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
            advanceTimeBy(SETTLE_MILLIS)

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

    /** A link whose writes take longer than a test waits. */
    private class SlowLink(
        private val link: RadioLink,
    ) : RadioLink by link {
        override suspend fun write(bytes: ByteArray): RadioResult<Unit> {
            delay(SLOW_WRITE_MILLIS)
            return link.write(bytes)
        }
    }

    private fun TestScope.air(seed: Long = 1): VirtualAir {
        val clock = TestClock(testScheduler)
        return VirtualAir(backgroundScope, clock, SeededRandom(seed))
    }

    private fun TestScope.now(): Instant {
        val millis = TestClock.WALL_CLOCK_START_MILLIS + testScheduler.currentTime
        return Instant.fromEpochMilliseconds(millis)
    }

    private fun node(platform: PlatformPort): MeshLink =
        MeshLink.create(
            meshLinkConfig(Preset.OfflineChat, SecurityProfile.Unsecured) { meshId = MESH }.build(),
            MeshPlatform.of(platform),
        )

    private fun platformWith(
        virtual: VirtualPlatform,
        radio: RadioPort,
    ): PlatformPort =
        object : PlatformPort by virtual {
            override val radio: RadioPort = radio
        }

    private fun nodeId(platform: VirtualPlatform): ByteArray =
        Derivations(SodiumCryptoProvider).nodeId(platform.identityStore.loadOrCreate { error("no identity") }.publicKey)

    /** A raw radio that connects to [target] as central and waits for the link to open. */
    private suspend fun TestScope.connectFake(
        air: VirtualAir,
        target: VirtualPlatform,
    ): CentralLink {
        val link = (air.radio().connect(target.radio.handle) as RadioResult.Ok).value
        advanceTimeBy(1)
        return link
    }

    /** A raw radio that advertises [MESH] and serves a capability record listing [versions]. */
    private fun advertiseFake(
        air: VirtualAir,
        versions: List<Int>,
    ): VirtualRadio {
        val record = CapabilityRecord.of(versions, ByteArray(RadioPort.ADVERTISEMENT_LENGTH))
        return advertiseFake(air, record.encode())
    }

    /** A raw radio that advertises [MESH] and serves [capability], or no capability characteristic if `null`. */
    private fun advertiseFake(
        air: VirtualAir,
        capability: ByteArray?,
    ): VirtualRadio {
        val radio = air.radio()
        val discovery = Discovery(SodiumCryptoProvider, MESH.encodeToByteArray(), WireProfile.UNSECURED)
        val payload = discovery.advertisement(Derivations.epoch(TestClock.WALL_CLOCK_START_MILLIS / MILLIS_PER_SECOND))
        capability?.let { radio.serveCapability(it) }
        radio.startAdvertising(payload)
        return radio
    }

    private fun digest(meshId: String): ByteArray =
        Derivations(SodiumCryptoProvider).meshIdDigest(meshId.encodeToByteArray(), WireProfile.UNSECURED)

    private fun record(frame: LinkFrame): ByteArray = LinkRecord.encode(frame.encode())

    private fun record(bytes: ByteArray): ByteArray = LinkRecord.encode(bytes)

    private fun frames(end: VirtualLinkEnd): List<LinkFrame> =
        assertIs<Decoded.Valid<List<ByteArray>>>(RecordReader().read(end.written)).value.map {
            assertIs<Decoded.Valid<LinkFrame>>(LinkFrame.decode(it)).value
        }

    private fun closes(end: VirtualLinkEnd): List<CloseReason> {
        val closes = frames(end).filterIsInstance<LinkFrame.Close>()
        return closes.map { it.reason }
    }

    private companion object {
        const val MESH = "Festival"
        const val MILLIS_PER_SECOND = 1_000L
        const val HANDSHAKE_TIMEOUT_MILLIS = 10_000L
        const val RACE_SEEDS = 20L
        const val SLOW_WRITE_MILLIS = 60_000L

        // Discovery, connect, capability read and both hellos: a handful of air latencies.
        const val SETTLE_MILLIS = 1_000L
        val FAKE_ID = ByteArray(32) { 0x7F }
    }
}
