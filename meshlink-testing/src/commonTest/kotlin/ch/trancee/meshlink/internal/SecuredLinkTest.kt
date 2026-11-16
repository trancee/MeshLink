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
import ch.trancee.meshlink.meshLinkConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
            val fake = FakePeer(TestClock(testScheduler))
            val link = connectFake(air, alice)
            val end = alice.radio.ends.single()
            val transport = initiate(fake, link, end)

            // Act
            node.stop()
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            val sealed = written(end, 1).second.single()
            assertFalse(sealed.contentEquals(LinkFrame.Close(CloseReason.SHUTTING_DOWN).encode()))
            assertContentEquals(LinkFrame.Close(CloseReason.SHUTTING_DOWN).encode(), open(transport, sealed))
        }

    @Test
    fun fragmentedHandshakeMessagesAndEncryptedFragmentsReassemble() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val node = securedNode(alice, null).also { it.start() }
            val fake = FakePeer(TestClock(testScheduler))
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
            val fake = FakePeer(TestClock(testScheduler), MESH_KEY)
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
            val fake = FakePeer(TestClock(testScheduler))
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
            assertEquals(emptyList(), written(end, 1).second)
            assertTrue(end.open)
        }

    @Test
    fun aMeshKeyOrPrologueMismatchFailsToDecrypt() =
        listOf<(TestClock) -> FakePeer>(
            { FakePeer(it, ByteArray(MESH_KEY_BYTES) { 0x22 }) },
            { FakePeer(it, MESH_KEY, digest = digestOf("Elsewhere")) },
            { FakePeer(it, MESH_KEY, digest = digest(MESH)) },
            { FakePeer(it, MESH_KEY, versions = listOf(1, 2)) },
            { FakePeer(it, MESH_KEY, digest = digestOf(MESH, otherWire), wire = otherWire) },
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
            val fake = FakePeer(TestClock(testScheduler), digest = digestOf("Elsewhere"))
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
            val fake = FakePeer(TestClock(testScheduler))
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
                val fake = FakePeer(TestClock(testScheduler))
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
                val noise = FakePeer(TestClock(testScheduler)).noise(NoiseRole.INITIATOR)
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
            val noise = FakePeer(TestClock(testScheduler)).noise(NoiseRole.INITIATOR)
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
    fun theNinthHalfOpenHandshakeIsDroppedOnEitherSide() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val node = securedNode(alice, null).also { it.start() }
            val diagnostics = diagnostics(node)
            repeat(HALF_OPEN_GLOBAL) {
                val noise = FakePeer(TestClock(testScheduler)).noise(NoiseRole.INITIATOR)
                connectFake(air, alice).write(handshake(1, ok(noise.writeMessage(EMPTY))))
            }
            advanceTimeBy(SETTLE_MILLIS)
            val noise = FakePeer(TestClock(testScheduler)).noise(NoiseRole.INITIATOR)

            // Act
            connectFake(air, alice).write(handshake(1, ok(noise.writeMessage(EMPTY))))
            advanceTimeBy(SETTLE_MILLIS)
            advertise(air)
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            val (answered, dropped) = alice.radio.ends.partition { it.written.isNotEmpty() }
            assertEquals(HALF_OPEN_GLOBAL, answered.size)
            assertEquals(2, dropped.size)
            assertTrue(dropped.none { it.open })
            assertEquals(List(2) { "ML4002 GLOBAL" }, diagnostics.map { it.label() })
        }

    @Test
    fun aHandshakeNotCompleteTenSecondsAfterTheBearerOpenedTimesOut() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val node = securedNode(alice, null).also { it.start() }
            val diagnostics = diagnostics(node)
            val noise = FakePeer(TestClock(testScheduler)).noise(NoiseRole.INITIATOR)
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
                val transport = initiate(FakePeer(TestClock(testScheduler)), link, end)

                // Act
                val stray = LinkFrame.Handshake(1, 1, GARBAGE).encode()
                val bytes = if (handshakeFrame) seal(transport, stray) else record(GARBAGE)
                link.write(bytes)
                advanceTimeBy(SETTLE_MILLIS)

                // Assert
                val replies = written(end, 1).second.map { open(transport, it).toList() }
                val protocolError = LinkFrame.Close(CloseReason.PROTOCOL_ERROR).encode().toList()
                val expected = if (handshakeFrame) listOf(protocolError) else emptyList()
                assertEquals(expected, replies)
                assertFalse(end.open)
                assertEquals(emptyList(), node.peers.value)
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
            val noise = FakePeer(TestClock(testScheduler)).noise(NoiseRole.INITIATOR)
            val link = connectFake(air, alice)

            // Act
            link.write(handshake(1, ok(noise.writeMessage(EMPTY))))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(listOf(CloseReason.PROTOCOL_ERROR), clearCloses(alice.radio.ends.single()))
        }

    /**
     * A scripted peer of [MESH] in profile [wire]: its own identity, static key and, in
     * `PostQuantum`, X-Wing key; the prologue bound to [digest] and [versions]; gated with
     * [meshKey] if set.
     */
    protected inner class FakePeer(
        clock: Clock,
        private val meshKey: ByteArray? = null,
        private val digest: ByteArray = digestOf(MESH),
        private val versions: List<Int> = listOf(1),
        private val wire: WireProfile = this@SecuredLinkTest.wire,
    ) {
        private val identity = SodiumCryptoProvider.ed25519KeyPair()
        private val static = SodiumCryptoProvider.x25519KeyPair()
        private val xWingKey =
            if (wire == WireProfile.POST_QUANTUM) SodiumCryptoProvider.xWingKeyPair().publicKey else null

        val nodeId: ByteArray = Derivations(SodiumCryptoProvider).nodeId(identity.publicKey)
        val certificate: ByteArray = issue(clock, static, VALIDITY, xWingKey).encode()

        /** The payload of this peer's message 2 or 3. */
        var payload: ByteArray = HandshakePayload.of(certificate).encode()

        fun noise(role: NoiseRole): NoiseHandshake {
            val psk = meshKey?.let { Derivations(SodiumCryptoProvider).meshPsk(it, digest) }
            val pattern =
                when {
                    wire == WireProfile.CLASSICAL -> if (psk == null) NoisePattern.XX else NoisePattern.XX_PSK3
                    else -> if (psk == null) NoisePattern.XX_HFS else NoisePattern.XX_HFS_PSK3
                }
            val prologue = Derivations.linkPrologue(versions, 1, wire, digest)
            return NoiseHandshake(SodiumCryptoProvider, pattern, role, prologue, NoiseKeys(static, psk))
        }

        /** A payload whose certificate [clock] issued for [validity], binding [key] and [xWing]. */
        fun payloadFor(
            clock: Clock,
            validity: Duration,
            key: KeyPair = static,
            xWing: ByteArray? = xWingKey,
        ): ByteArray = HandshakePayload.of(issue(clock, key, validity, xWing).encode()).encode()

        private fun issue(
            clock: Clock,
            key: KeyPair,
            validity: Duration,
            xWing: ByteArray?,
        ): IdentityCertificate {
            val keys = StaticKeys(key.publicKey, xWing)
            return IdentityCertificate.issue(SodiumCryptoProvider, clock, identity, keys, validity)
        }
    }

    /** Runs [fake]'s initiator side of a full handshake on [link] with the node at [end]. */
    private suspend fun TestScope.initiate(
        fake: FakePeer,
        link: RadioLink,
        end: VirtualLinkEnd,
    ): NoiseTransport {
        val noise = fake.noise(NoiseRole.INITIATOR)
        link.write(handshake(1, ok(noise.writeMessage(EMPTY))))
        advanceTimeBy(SETTLE_MILLIS)
        ok(noise.readMessage(handshakeIn(clearFrames(end).single()).noiseMessage))
        link.write(handshake(THIRD, ok(noise.writeMessage(fake.payload))))
        advanceTimeBy(SETTLE_MILLIS)
        return ok(noise.split())
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
        val fake = FakePeer(TestClock(testScheduler))
        return fake.noise(NoiseRole.INITIATOR)
    }

    /** [noise]'s message 1 as a `HANDSHAKE` record. */
    private fun message1(noise: NoiseHandshake): ByteArray = handshake(1, ok(noise.writeMessage(EMPTY)))

    private fun TestScope.diagnostics(node: MeshLink): List<LinkDiagnostic> {
        val seen = mutableListOf<LinkDiagnostic>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { node.linkDiagnostics.collect { seen += it } }
        return seen
    }

    private fun LinkDiagnostic.label(): String =
        when (this) {
            is LinkDiagnostic.HandshakeFailed -> "$code $reason"
            is LinkDiagnostic.HandshakeLimitDropped -> "$code $limit"
        }

    internal fun bodies(end: VirtualLinkEnd): List<ByteArray> =
        assertIs<Decoded.Valid<List<ByteArray>>>(RecordReader().read(end.written)).value

    /**
     * The first [clear] frames [end] wrote in cleartext, each reassembled from its `FRAGMENT`s,
     * and the record bodies after them, which may be encrypted.
     */
    internal fun written(
        end: VirtualLinkEnd,
        clear: Int = Int.MAX_VALUE,
    ): Pair<List<ByteArray>, List<ByteArray>> {
        val bodies = bodies(end)
        val frames = mutableListOf<ByteArray>()
        val streams = mutableMapOf<Int, ByteArray>()
        var used = 0
        while (frames.size < clear && used < bodies.size) {
            val body = bodies[used++]
            val fragment = (LinkFrame.decode(body) as? Decoded.Valid)?.value as? LinkFrame.Fragment
            val data = fragment?.let { streams.getOrElse(it.streamId) { EMPTY } + it.data }
            when {
                fragment == null -> frames += body
                fragment.last -> frames += data!!.also { streams -= fragment.streamId }
                else -> streams[fragment.streamId] = data!!
            }
        }
        return frames to bodies.drop(used)
    }

    /** Every frame [end] wrote, all in cleartext. */
    internal fun clearFrames(end: VirtualLinkEnd): List<ByteArray> = written(end).first

    /** The cleartext `CLOSE` reasons [end] wrote. */
    private fun clearCloses(end: VirtualLinkEnd): List<CloseReason> =
        bodies(end)
            .mapNotNull { (LinkFrame.decode(it) as? Decoded.Valid)?.value as? LinkFrame.Close }
            .map { it.reason }

    internal fun handshakeIn(body: ByteArray): LinkFrame.Handshake =
        assertIs<LinkFrame.Handshake>(assertIs<Decoded.Valid<LinkFrame>>(LinkFrame.decode(body)).value)

    /** A `HANDSHAKE` frame as one record, or as records of `FRAGMENT`s if longer than a record body. */
    private fun handshake(
        step: Int,
        message: ByteArray,
        version: Int = 1,
    ): ByteArray {
        val frame = LinkFrame.Handshake(version, step, message).encode()
        val dataMax = LinkRecord.BODY_MAX - LinkFrame.Fragment.HEADER
        val records = if (frame.size <= LinkRecord.BODY_MAX) listOf(record(frame)) else fragments(0, frame, dataMax)
        return records.reduce(ByteArray::plus)
    }

    /** [frame] as records of `FRAGMENT`s of stream [id], each carrying at most [dataMax] bytes. */
    private fun fragments(
        id: Int,
        frame: ByteArray,
        dataMax: Int = FRAGMENT_DATA,
    ): List<ByteArray> = LinkFrame.Fragment.split(id, frame, dataMax).map { record(it) }

    private fun seal(
        transport: NoiseTransport,
        frame: ByteArray,
    ): ByteArray = record(ok(transport.encrypt(frame)))

    private fun open(
        transport: NoiseTransport,
        body: ByteArray,
    ): ByteArray = ok(transport.decrypt(body))

    internal fun <T> ok(result: NoiseResult<T>): T = assertIs<NoiseResult.Ok<T>>(result).value

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

    /** The digest of [meshId] in [wire]. */
    internal fun digestOf(
        meshId: String,
        wire: WireProfile = this.wire,
    ): ByteArray = Derivations(SodiumCryptoProvider).meshIdDigest(meshId.encodeToByteArray(), wire)

    private companion object {
        const val MESH_KEY_BYTES = 32
        const val KEY_BYTES = 32
        const val KEM_PUBLIC_KEY_BYTES = 1_184
        const val X_WING_OFFSET = 66
        const val SECOND = 2
        const val THIRD = 3
        const val HALF_OPEN_GLOBAL = 8
        const val HANDSHAKE_TIMEOUT_MILLIS = 10_000L
        const val NEIGHBOR_INTERVAL_MILLIS = 2_000L
        const val MILLIS_PER_SECOND = 1_000L
        const val DAY_MILLIS = 86_400_000L
        const val MESSAGE_TYPE: Byte = 0x40
        const val CERTIFICATE = 0x81
        const val UNKNOWN_CRITICAL = 0x8F
        const val UNKNOWN = 0x0F
        const val FRAGMENT_DATA = 20
        const val DATA_MAX = LinkFrame.Fragment.DATA_MAX
        val VALIDITY = 24.hours
        val MESH_KEY = ByteArray(MESH_KEY_BYTES) { 0x11 }
        val EMPTY = ByteArray(0)
        val GARBAGE = ByteArray(48) { 0x5A }
    }
}

class ClassicalLinkTest : SecuredLinkTest(SecurityProfile.Classical, WireProfile.CLASSICAL)
