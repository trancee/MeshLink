package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.ReceivedMessage
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.SendResult
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import ch.trancee.meshlink.internal.CloseReason.SHUTTING_DOWN
import ch.trancee.meshlink.meshLinkConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * Link behavior both secured profiles share (spec "Link handshake"), run once per profile by
 * [ClassicalLinkTest] and [PostQuantumLinkTest]. [wire] is [profile]'s byte on the wire.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalMeshLinkApi::class)
abstract class SecuredLinkTest(
    private val profile: SecurityProfile,
    internal val wire: WireProfile,
) {
    private val postQuantum = wire == WireProfile.POST_QUANTUM

    // The other secured profile, whose nodes must never link with this one's.
    private val otherProfile = if (postQuantum) SecurityProfile.Classical else SecurityProfile.PostQuantum
    private val otherWire = if (postQuantum) WireProfile.CLASSICAL else WireProfile.POST_QUANTUM

    // Message 1 is the ephemeral key, followed in `XXhfs` by the ML-KEM-768 key `e1`.
    private val message1Bytes = if (postQuantum) KEY_BYTES + KEM_PUBLIC_KEY_BYTES else KEY_BYTES

    @Test
    fun nodesOfAnOpenOrAGatedMeshLinkAsSecuredPeers() =
        listOf(null, MESH_KEY).forEach { meshKey ->
            runTest {
                // Arrange
                val air = air()
                val alice = air.platform(StandardTestDispatcher(testScheduler))
                val bob = air.platform(StandardTestDispatcher(testScheduler))
                val nodes = listOf(securedNode(alice, meshKey), securedNode(bob, meshKey))

                // Act
                nodes.forEach { it.start() }
                advanceTimeBy(SETTLE_MILLIS)

                // Assert
                val seenByAlice = nodes[0].peers.value.single()
                val seenByBob = nodes[1].peers.value.single()
                assertContentEquals(nodeId(bob), seenByAlice.nodeId.toByteArray())
                assertContentEquals(nodeId(alice), seenByBob.nodeId.toByteArray())
                assertTrue(seenByAlice.secured && seenByBob.secured)
            }
        }

    @Test
    fun nodesOfAnotherProfileOrMeshKeyNeverLink() =
        runTest {
            // Arrange
            val air = air()
            val platforms = List(5) { air.platform(StandardTestDispatcher(testScheduler)) }
            val nodes =
                listOf(
                    node(platforms[0]),
                    securedNode(platforms[1], null, otherProfile),
                    securedNode(platforms[2], null),
                    securedNode(platforms[3], MESH_KEY),
                    securedNode(platforms[4], ByteArray(MESH_KEY_BYTES) { 0x22 }),
                )

            // Act
            nodes.forEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            nodes.forEach { assertEquals(emptyList(), it.peers.value) }
            platforms.forEach { assertEquals(emptyList(), it.radio.ends) }
        }

    @Test
    fun recordsAfterMessageThreeAreEncrypted() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val node = securedNode(alice, null).also { it.start() }
            val fake = FakePeer(wire, TestClock(testScheduler))
            val link = connectFake(air, alice)
            val end = alice.radio.ends.single()
            val transport = initiate(fake, link, end)

            // Act
            node.stop()
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            // sealedFrames authenticates each record, so a cleartext CLOSE fails here.
            assertContentEquals(LinkFrame.Close(SHUTTING_DOWN).encode(), sealedFrames(end, transport).single())
        }

    @Test
    fun fragmentedHandshakeMessagesAndEncryptedFragmentsReassemble() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val node = securedNode(alice, null).also { it.start() }
            val fake = FakePeer(wire, TestClock(testScheduler))
            val noise = fake.noise(NoiseRole.INITIATOR)
            val link = connectFake(air, alice)
            val end = alice.radio.ends.single()

            // Act
            fragments(0, LinkFrame.Handshake(1, 1, ok(noise.writeMessage(EMPTY))).encode()).forEach { link.write(it) }
            advanceTimeBy(SETTLE_MILLIS)
            ok(noise.readMessage(handshakeIn(clearFrames(end).single()).noiseMessage))
            val message3 = LinkFrame.Handshake(1, THIRD, ok(noise.writeMessage(fake.payload))).encode()
            fragments(1, message3).forEach { link.write(it) }
            advanceTimeBy(SETTLE_MILLIS)
            val secured = node.peers.value.map { it.secured }
            val transport = ok(noise.split())
            LinkFrame.Fragment
                .split(2, LinkFrame.Close(CloseReason.SHUTTING_DOWN).encode(), 1)
                .forEach { link.write(seal(transport, it.encode())) }
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(listOf(true), secured)
            assertEquals(emptyList(), written(end, 1).second)
            assertFalse(end.open)
            assertEquals(emptyList(), node.peers.value)
        }

    @Test
    fun aPeerIsListedByTheNodeIdItsCertificateProves() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val node = securedNode(alice, MESH_KEY).also { it.start() }
            val fake = FakePeer(wire, TestClock(testScheduler), MESH_KEY)
            val link = connectFake(air, alice)

            // Act
            initiate(fake, link, alice.radio.ends.single())

            // Assert
            val peer = node.peers.value.single()
            assertContentEquals(fake.nodeId, peer.nodeId.toByteArray())
            assertTrue(peer.secured)
        }

    @Test
    fun aSendOverASecuredLinkStaysQueuedAndReceivedMessagesAreDropped() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val node = securedNode(alice, null).also { it.start() }
            val received = mutableListOf<ReceivedMessage>()
            backgroundScope.launch { node.messages.collect { received += it } }
            val fake = FakePeer(wire, TestClock(testScheduler))
            val link = connectFake(air, alice)
            val end = alice.radio.ends.single()
            val transport = initiate(fake, link, end)

            // Act
            val result =
                node.send(
                    node.peers.value
                        .single()
                        .nodeId,
                    "hello".encodeToByteArray(),
                )
            link.write(seal(transport, byteArrayOf(MESSAGE_TYPE, 0x01, 0x02)))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(DeliveryStatus.Queued, assertIs<SendResult.Accepted>(result).message.status.value)
            assertEquals(emptyList(), received)
            assertEquals(emptyList(), sealedFrames(end, transport))
            assertTrue(end.open)
        }

    @Test
    fun aMeshKeyOrPrologueMismatchFailsToDecrypt() =
        listOf<(TestClock) -> FakePeer>(
            { FakePeer(wire, it, ByteArray(MESH_KEY_BYTES) { 0x22 }) },
            { FakePeer(wire, it, MESH_KEY, digest = digestOf("Elsewhere", wire)) },
            { FakePeer(wire, it, MESH_KEY, digest = digest(MESH)) },
            { FakePeer(wire, it, MESH_KEY, versions = listOf(1, 2)) },
            { FakePeer(otherWire, it, MESH_KEY, digest = digestOf(MESH, otherWire)) },
        ).forEach { peer ->
            runTest {
                // Arrange
                val air = air()
                val alice = air.platform(StandardTestDispatcher(testScheduler))
                val node = securedNode(alice, MESH_KEY).also { it.start() }
                val diagnostics = diagnostics(node)
                val fake = peer(TestClock(testScheduler))
                val link = connectFake(air, alice)
                val end = alice.radio.ends.single()
                val noise = fake.noise(NoiseRole.INITIATOR)

                // Act
                link.write(handshake(1, ok(noise.writeMessage(EMPTY))))
                advanceTimeBy(SETTLE_MILLIS)
                // In XXpsk3 message 1 already authenticates the prologue, so the node may fail there.
                if (end.open) {
                    val read = noise.readMessage(handshakeIn(clearFrames(end).single()).noiseMessage)
                    val message3 = if (read is NoiseResult.Ok) ok(noise.writeMessage(fake.payload)) else GARBAGE
                    link.write(handshake(THIRD, message3))
                    advanceTimeBy(SETTLE_MILLIS)
                }

                // Assert
                assertEquals(listOf(CloseReason.HANDSHAKE_FAILED), clearCloses(end))
                assertEquals(listOf("ML4001 DECRYPT"), diagnostics.map { it.label() })
                assertEquals(emptyList(), node.peers.value)
            }
        }

    @Test
    fun aCentralClosesAResponderWhoseMessageTwoFailsToDecrypt() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val radio = advertise(air)
            val node = securedNode(alice, null)
            val diagnostics = diagnostics(node)
            val fake = FakePeer(wire, TestClock(testScheduler), digest = digestOf("Elsewhere", wire))
            node.start()
            advanceTimeBy(SETTLE_MILLIS)
            val end = alice.radio.ends.single()
            val noise = fake.noise(NoiseRole.RESPONDER)

            // Act
            noise.readMessage(handshakeIn(clearFrames(end).single()).noiseMessage)
            radio.ends.single().write(handshake(SECOND, ok(noise.writeMessage(fake.payload))))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(listOf(CloseReason.HANDSHAKE_FAILED), clearCloses(end))
            assertEquals(listOf("ML4001 DECRYPT"), diagnostics.map { it.label() })
        }

    @Test
    fun aCentralSendsMessageThreeThenClosesAResponderWithoutAValidCertificate() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val radio = advertise(air)
            val node = securedNode(alice, null)
            val diagnostics = diagnostics(node)
            val fake = FakePeer(wire, TestClock(testScheduler))
            node.start()
            advanceTimeBy(SETTLE_MILLIS)
            val end = alice.radio.ends.single()
            val noise = fake.noise(NoiseRole.RESPONDER)

            // Act
            noise.readMessage(handshakeIn(clearFrames(end).single()).noiseMessage)
            radio.ends.single().write(handshake(SECOND, ok(noise.writeMessage(EMPTY))))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            val (frames, rest) = written(end, 2)
            val (message3, close) = frames[1] to rest.single()
            assertEquals(THIRD, handshakeIn(message3).step)
            ok(noise.readMessage(handshakeIn(message3).noiseMessage))
            val transport = ok(noise.split())
            assertContentEquals(LinkFrame.Close(CloseReason.HANDSHAKE_FAILED).encode(), open(transport, close))
            assertEquals(listOf("ML4001 CERTIFICATE"), diagnostics.map { it.label() })
            assertFalse(end.open)
            assertEquals(emptyList(), node.peers.value)
        }

    @Test
    fun aResponderClosesAnInitiatorWithoutAValidCertificateInAnEncryptedRecord() =
        runTest {
            val payloads =
                mapOf<String, (FakePeer) -> ByteArray>(
                    "unknown critical TLV" to { peer ->
                        val tlvs = listOf(Tlv(UNKNOWN_CRITICAL, byteArrayOf(1)), Tlv(CERTIFICATE, peer.certificate))
                        HandshakePayload(tlvs).encode()
                    },
                    "missing certificate" to { EMPTY },
                    "only an unknown non-critical TLV" to { HandshakePayload(listOf(Tlv(UNKNOWN, GARBAGE))).encode() },
                    "undecodable certificate" to { HandshakePayload(listOf(Tlv(CERTIFICATE, GARBAGE))).encode() },
                    "expired certificate" to { peer ->
                        val dayAgo = TestClock(testScheduler, TestClock.WALL_CLOCK_START_MILLIS - DAY_MILLIS)
                        peer.payloadFor(dayAgo, 1.hours)
                    },
                    "another static key" to { peer ->
                        peer.payloadFor(TestClock(testScheduler), VALIDITY, SodiumCryptoProvider.x25519KeyPair())
                    },
                    "the other profile's layout" to { peer ->
                        val xWing = if (postQuantum) null else SodiumCryptoProvider.xWingKeyPair().publicKey
                        peer.payloadFor(TestClock(testScheduler), VALIDITY, xWing = xWing)
                    },
                ) + xWingPayloads()
            payloads.forEach { (case, payload) ->
                // Arrange
                val air = air()
                val alice = air.platform(StandardTestDispatcher(testScheduler))
                val node = securedNode(alice, null).also { it.start() }
                val diagnostics = diagnostics(node)
                val fake = FakePeer(wire, TestClock(testScheduler))
                fake.payload = payload(fake)
                val link = connectFake(air, alice)
                val end = alice.radio.ends.single()

                // Act
                val transport = initiate(fake, link, end)

                // Assert
                val close = open(transport, written(end, 1).second.single())
                assertContentEquals(LinkFrame.Close(CloseReason.HANDSHAKE_FAILED).encode(), close, case)
                assertEquals(listOf("ML4001 CERTIFICATE"), diagnostics.map { it.label() }, case)
                assertEquals(emptyList(), node.peers.value, case)
                assertFalse(end.open, case)
                node.close()
            }
        }

    @Test
    fun anythingButTheNextHandshakeMessageFailsTheHandshake() =
        runTest {
            val cases =
                mapOf<String, Pair<(NoiseHandshake) -> List<ByteArray>, String>>(
                    "hello" to Pair({ listOf(record(LinkFrame.PlainHello(1, digest(MESH), FAKE_ID))) }, "PROTOCOL"),
                    "unknown type" to Pair({ listOf(record(byteArrayOf(0x3F, 0x01))) }, "PROTOCOL"),
                    "step 2 first" to Pair({ listOf(handshake(SECOND, GARBAGE)) }, "PROTOCOL"),
                    "unspoken version" to Pair({ listOf(handshake(1, GARBAGE, version = 2)) }, "PROTOCOL"),
                    "zero ephemeral" to Pair({ listOf(handshake(1, ByteArray(message1Bytes))) }, "DECRYPT"),
                    "repeated step 2" to Pair({ listOf(message1(it), handshake(SECOND, GARBAGE)) }, "PROTOCOL"),
                    "changed version" to
                        Pair({ listOf(message1(it), handshake(THIRD, GARBAGE, version = 2)) }, "PROTOCOL"),
                    "garbage message 3" to Pair({ listOf(message1(it), handshake(THIRD, GARBAGE)) }, "DECRYPT"),
                    "busy" to Pair({ listOf(record(LinkFrame.Busy(0, BusyReason.REASSEMBLY_SLOTS))) }, "PROTOCOL"),
                    "rekey" to Pair({ listOf(record(LinkFrame.Rekey)) }, "PROTOCOL"),
                    "bearer switch" to Pair({ listOf(record(LinkFrame.BearerSwitch)) }, "PROTOCOL"),
                    "rekey after message 1" to Pair({ listOf(message1(it), record(LinkFrame.Rekey)) }, "PROTOCOL"),
                    "fragmented hello" to
                        Pair(
                            { fragments(0, LinkFrame.PlainHello(1, digest(MESH), FAKE_ID).encode()) },
                            "PROTOCOL",
                        ),
                    "nested fragment" to
                        Pair({ listOf(record(LinkFrame.Fragment(0, 0, false, byteArrayOf(0x01, 0x00)))) }, "PROTOCOL"),
                    "fragments above a handshake frame" to
                        Pair(
                            { fragments(0, ByteArray(LinkFrame.Handshake.FRAME_MAX + 1) { 0x02 }, DATA_MAX) },
                            "PROTOCOL",
                        ),
                )
            cases.forEach { (case, script) ->
                // Arrange
                val air = air()
                val alice = air.platform(StandardTestDispatcher(testScheduler))
                val node = securedNode(alice, null).also { it.start() }
                val diagnostics = diagnostics(node)
                val noise = FakePeer(wire, TestClock(testScheduler)).noise(NoiseRole.INITIATOR)
                val link = connectFake(air, alice)
                val end = alice.radio.ends.single()

                // Act
                script.first(noise).forEach {
                    link.write(it)
                    advanceTimeBy(SETTLE_MILLIS)
                }

                // Assert
                assertEquals(listOf(CloseReason.HANDSHAKE_FAILED), clearCloses(end), case)
                assertEquals(listOf("ML4001 ${script.second}"), diagnostics.map { it.label() }, case)
                assertFalse(end.open, case)
                node.close()
            }
        }

    @Test
    fun aSecondHandshakeOnOneBearerIsDroppedWithoutAReply() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val node = securedNode(alice, null).also { it.start() }
            val diagnostics = diagnostics(node)
            val noise = FakePeer(wire, TestClock(testScheduler)).noise(NoiseRole.INITIATOR)
            val link = connectFake(air, alice)
            val end = alice.radio.ends.single()
            val message1 = handshake(1, ok(noise.writeMessage(EMPTY)))
            link.write(message1)
            advanceTimeBy(SETTLE_MILLIS)

            // Act
            link.write(message1)
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(SECOND, handshakeIn(clearFrames(end).single()).step)
            assertEquals(listOf("ML4002 PER_BEARER"), diagnostics.map { it.label() })
            assertFalse(end.open)
        }

    @Test
    fun aHandshakeNotCompleteTenSecondsAfterTheBearerOpenedTimesOut() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val node = securedNode(alice, null).also { it.start() }
            val diagnostics = diagnostics(node)
            val noise = FakePeer(wire, TestClock(testScheduler)).noise(NoiseRole.INITIATOR)
            val link = connectFake(air, alice)
            val end = alice.radio.ends.single()

            // Act
            link.write(handshake(1, ok(noise.writeMessage(EMPTY))))
            advanceTimeBy(HANDSHAKE_TIMEOUT_MILLIS - 1)
            val openBefore = end.open
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertTrue(openBefore)
            assertFalse(end.open)
            assertEquals(SECOND, handshakeIn(clearFrames(end).single()).step)
            assertEquals(listOf("ML4001 TIMEOUT"), diagnostics.map { it.label() })
        }

    @Test
    fun aNeighborStartingAgainWithinTwoSecondsIsDropped() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val node = securedNode(alice, null).also { it.start() }
            val diagnostics = diagnostics(node)
            val neighbor = air.radio()
            val first = connect(neighbor, alice)
            first.write(message1(freshInitiator()))
            advanceTimeBy(SETTLE_MILLIS)
            first.disconnect()

            // Act
            val again = connect(neighbor, alice)
            again.write(message1(freshInitiator()))
            advanceTimeBy(NEIGHBOR_INTERVAL_MILLIS)
            val later = connect(neighbor, alice)
            later.write(message1(freshInitiator()))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            val written = alice.radio.ends.map { it.written.isNotEmpty() }
            assertEquals(listOf(true, false, true), written)
            assertEquals(listOf("ML4002 NEIGHBOR_INTERVAL"), diagnostics.map { it.label() })
        }

    @Test
    fun anOpenLinkClosesOnAHandshakeFrameOrARecordThatFailsToDecrypt() =
        runTest {
            listOf(true, false).forEach { handshakeFrame ->
                // Arrange
                val air = air()
                val alice = air.platform(StandardTestDispatcher(testScheduler))
                val node = securedNode(alice, null).also { it.start() }
                val link = connectFake(air, alice)
                val end = alice.radio.ends.single()
                val transport = initiate(FakePeer(wire, TestClock(testScheduler)), link, end)

                // Act
                val stray = LinkFrame.Handshake(1, 1, GARBAGE).encode()
                val bytes = if (handshakeFrame) seal(transport, stray) else record(GARBAGE)
                link.write(bytes)
                advanceTimeBy(SETTLE_MILLIS)

                // Assert
                val replies = sealedFrames(end, transport).map { it.toList() }
                val protocolError = LinkFrame.Close(CloseReason.PROTOCOL_ERROR).encode().toList()
                val expected = if (handshakeFrame) listOf(protocolError) else emptyList()
                assertEquals(expected, replies)
                assertFalse(end.open)
                assertEquals(emptyList(), node.peers.value)
                node.close()
            }
        }

    @Test
    fun twoNodesStayLinkedAcrossThreeHoursOfRekeys() =
        runTest {
            // Arrange
            val air = air()
            val platforms = List(2) { air.platform(StandardTestDispatcher(testScheduler)) }
            val nodes = platforms.map { securedNode(it, null) }
            nodes.forEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)

            // Act
            advanceTimeBy(HOURS * LinkCipher.REKEY_INTERVAL_MILLIS + SETTLE_MILLIS)

            // Assert
            assertEquals(listOf(1, 1), nodes.map { it.peers.value.size })
            assertEquals(listOf(1, 1), platforms.map { platform -> platform.radio.ends.count { it.open } })
        }

    @Test
    fun twoCocNodesSwitchToCocAndRekeyThereEveryHour() =
        runTest {
            // Arrange
            val air = air()
            val platforms = List(2) { air.platform(StandardTestDispatcher(testScheduler), coc = true) }
            val nodes = platforms.map { securedNode(it, null) }
            nodes.forEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)
            val channels =
                platforms.map { platform ->
                    platform.radio.ends
                        .single { it.open }
                        .channels
                        .single()
                }
            val switched = channels.map { it.written.size }

            // Act
            advanceTimeBy(HOURS * LinkCipher.REKEY_INTERVAL_MILLIS + SETTLE_MILLIS)

            // Assert
            assertEquals(listOf(1, 1), nodes.map { it.peers.value.size })
            assertEquals(listOf(1, 1), platforms.map { platform -> platform.radio.ends.count { it.open } })
            channels.zip(switched).forEach { (channel, before) -> assertTrue(channel.written.size > before) }
        }

    @Test
    fun eachDirectionRekeysEveryHourAndTheLinkStaysOpen() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val node = securedNode(alice, null).also { it.start() }
            val diagnostics = diagnostics(node)
            val link = connectFake(air, alice)
            val end = alice.radio.ends.single()
            val transport = initiateGranting(FakePeer(wire, TestClock(testScheduler)), link, end)
            val fake = LinkCipher(transport, TestClock(testScheduler))
            val linkedAt = testScheduler.currentTime
            val writtenByNode = mutableListOf<Int>()

            // Act
            (1..HOURS).forEach { hour ->
                val due = linkedAt + hour * LinkCipher.REKEY_INTERVAL_MILLIS
                advanceTimeBy(due - SETTLE_MILLIS - 1 - testScheduler.currentTime)
                writtenByNode += written(end, 1).second.size
                advanceTimeBy(SETTLE_MILLIS + 2)
                writtenByNode += written(end, 1).second.size
                link.write(fake.seal(LinkFrame.Rekey.encode()) + fake.seal(UNKNOWN_FRAME))
            }
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            val opened = written(end, 1).second.map { assertIs<LinkCipher.Opened.Frame>(fake.open(it)).bytes.copyOf() }
            val frames = opened.withoutNeighborLists().map { it.toList() }
            assertEquals(List(HOURS) { LinkFrame.Rekey.encode().toList() }, frames)
            assertEquals(listOf(0, 1, 1, 2, 2, 3), writtenByNode.map { opened.take(it).withoutNeighborLists().size })
            assertEquals(List(HOURS) { "ML3007 $UNKNOWN_LINK_LOCAL" }, diagnostics.map { it.label() })
            assertEquals(1, node.peers.value.size)
            assertTrue(end.open)
        }

    @Test
    fun aRecordUnderTheOldKeyAfterARekeyClosesTheBearer() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val node = securedNode(alice, null).also { it.start() }
            val link = connectFake(air, alice)
            val end = alice.radio.ends.single()
            val transport = initiate(FakePeer(wire, TestClock(testScheduler)), link, end)

            // Act
            link.write(seal(transport, LinkFrame.Rekey.encode()) + seal(transport, UNKNOWN_FRAME))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(emptyList(), sealedFrames(end, transport))
            assertFalse(end.open)
            assertEquals(emptyList(), node.peers.value)
        }

    @Test
    fun aPeerThatNeverRekeysIsClosedAfterTwiceRekeyRecordsAndOneThatRekeysIsNot() =
        runTest {
            listOf(true, false).forEach { rekeying ->
                // Arrange
                val air = air()
                val alice = air.platform(StandardTestDispatcher(testScheduler))
                val node = securedNode(alice, null).also { it.start() }
                val link = connectFake(air, alice)
                val end = alice.radio.ends.single()
                val transport = initiate(FakePeer(wire, TestClock(testScheduler)), link, end)
                val cipher = LinkCipher(transport, TestClock(testScheduler))
                val sealer: (ByteArray) -> ByteArray =
                    if (rekeying) cipher::seal else { frame -> seal(transport, frame) }
                // The CREDIT that initiate sent is the key's first record.
                (2..2 * LinkCipher.REKEY_RECORDS).chunked(BATCH).forEach { batch ->
                    link.write(concat(batch.map { sealer(UNKNOWN_FRAME) }))
                }
                advanceTimeBy(SETTLE_MILLIS)
                val openAtLimit = end.open

                // Act
                link.write(sealer(UNKNOWN_FRAME))
                advanceTimeBy(SETTLE_MILLIS)

                // Assert
                val replies = sealedFrames(end, transport).map { it.toList() }
                val protocolError = LinkFrame.Close(CloseReason.PROTOCOL_ERROR).encode().toList()
                assertTrue(openAtLimit, "rekeying $rekeying")
                assertEquals(if (rekeying) emptyList() else listOf(protocolError), replies, "rekeying $rekeying")
                assertEquals(rekeying, end.open, "rekeying $rekeying")
                node.close()
            }
        }

    @Test
    fun anUnsecuredNodeClosesAHandshakeWithProtocolError() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            node(alice).start()
            val noise = FakePeer(wire, TestClock(testScheduler)).noise(NoiseRole.INITIATOR)
            val link = connectFake(air, alice)

            // Act
            link.write(handshake(1, ok(noise.writeMessage(EMPTY))))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(listOf(CloseReason.PROTOCOL_ERROR), clearCloses(alice.radio.ends.single()))
        }

    /** A raw radio that advertises [MESH] in this profile and serves a capability record for version 1. */
    private fun advertise(air: VirtualAir): VirtualRadio {
        val radio = air.radio()
        val discovery = Discovery(SodiumCryptoProvider, MESH.encodeToByteArray(), wire)
        radio.serveCapability(CapabilityRecord.of(listOf(1), ByteArray(RadioPort.ADVERTISEMENT_LENGTH)).encode())
        val epoch = Derivations.epoch(TestClock.WALL_CLOCK_START_MILLIS / MILLIS_PER_SECOND)
        radio.startAdvertising(discovery.advertisement(epoch))
        return radio
    }

    private suspend fun TestScope.connect(
        radio: VirtualRadio,
        target: VirtualPlatform,
    ): CentralLink {
        val link = (radio.connect(target.radio.handle) as RadioResult.Ok).value
        advanceTimeBy(1)
        return link
    }

    /** A new fake initiator's Noise handshake. */
    private fun TestScope.freshInitiator(): NoiseHandshake {
        val fake = FakePeer(wire, TestClock(testScheduler))
        return fake.noise(NoiseRole.INITIATOR)
    }

    /** [noise]'s message 1 as a `HANDSHAKE` record. */
    private fun message1(noise: NoiseHandshake): ByteArray = handshake(1, ok(noise.writeMessage(EMPTY)))

    private fun LinkDiagnostic.label(): String =
        when (this) {
            is LinkDiagnostic.HandshakeFailed -> "$code $reason"
            is LinkDiagnostic.HandshakeLimitDropped -> "$code $limit"
            is LinkDiagnostic.ConnectFailed -> "$code $reason $attempt"
            is LinkDiagnostic.LinkCapReached -> "$code $cap"
            is LinkDiagnostic.BearerFallback -> "$code $reason"
            is LinkDiagnostic.UnknownLinkFrameDropped -> "$code $type"
        }

    /** The cleartext `CLOSE` reasons [end] wrote. */
    private fun clearCloses(end: VirtualLinkEnd): List<CloseReason> =
        bodies(end)
            .mapNotNull { (LinkFrame.decode(it) as? Decoded.Valid)?.value as? LinkFrame.Close }
            .map { it.reason }

    /** [frame] as records of `FRAGMENT`s of stream [id], each carrying at most [dataMax] bytes. */
    private fun fragments(
        id: Int,
        frame: ByteArray,
        dataMax: Int = FRAGMENT_DATA,
    ): List<ByteArray> = LinkFrame.Fragment.split(id, frame, dataMax).map { record(it) }

    /** [records] as one byte array. */
    private fun concat(records: List<ByteArray>): ByteArray {
        val bytes = ByteArray(records.sumOf { it.size })
        var at = 0
        records.forEach {
            it.copyInto(bytes, at)
            at += it.size
        }
        return bytes
    }

    /** `PostQuantum` only: a certificate with a byte of its X-Wing key flipped after signing. */
    private fun xWingPayloads(): Map<String, (FakePeer) -> ByteArray> =
        if (postQuantum) {
            mapOf(
                "flipped X-Wing byte" to { peer ->
                    val certificate = peer.certificate.copyOf()
                    certificate[X_WING_OFFSET] = (certificate[X_WING_OFFSET].toInt() xor 1).toByte()
                    HandshakePayload.of(certificate).encode()
                },
            )
        } else {
            emptyMap()
        }

    /** A stopped node of [MESH] in [profile], gated with [meshKey] if set. */
    internal fun securedNode(
        platform: PlatformPort,
        meshKey: ByteArray?,
        profile: SecurityProfile = this.profile,
    ): MeshLink =
        MeshLink.create(
            meshLinkConfig(Preset.OfflineChat, profile) {
                meshId = MESH
                this.meshKey = meshKey
            }.build(),
            MeshPlatform.of(platform),
        )

    private companion object {
        const val MESH_KEY_BYTES = 32
        const val KEY_BYTES = 32
        const val KEM_PUBLIC_KEY_BYTES = 1_184
        const val X_WING_OFFSET = 66
        const val SECOND = 2
        const val THIRD = 3
        const val HANDSHAKE_TIMEOUT_MILLIS = 10_000L
        const val NEIGHBOR_INTERVAL_MILLIS = 2_000L
        const val MILLIS_PER_SECOND = 1_000L
        const val DAY_MILLIS = 86_400_000L
        const val MESSAGE_TYPE: Byte = 0x40
        const val CERTIFICATE = 0x81
        const val UNKNOWN_CRITICAL = 0x8F
        const val UNKNOWN = 0x0F
        const val FRAGMENT_DATA = 20
        const val HOURS = 3
        const val BATCH = 4_096
        const val UNKNOWN_LINK_LOCAL = 0x3F
        val UNKNOWN_FRAME = byteArrayOf(UNKNOWN_LINK_LOCAL.toByte())
        const val DATA_MAX = LinkFrame.Fragment.DATA_MAX
        val MESH_KEY = ByteArray(MESH_KEY_BYTES) { 0x11 }
        val GARBAGE = ByteArray(48) { 0x5A }
    }
}

class ClassicalLinkTest : SecuredLinkTest(SecurityProfile.Classical, WireProfile.CLASSICAL)
