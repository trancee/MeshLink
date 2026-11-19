package ch.trancee.meshlink.internal

import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.MeshLink
import ch.trancee.meshlink.MeshPlatform
import ch.trancee.meshlink.Preset
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import ch.trancee.meshlink.meshLinkConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Advertisement rotation and resolvable tags (spec "Resolvable tag", "Resolving key update"). */
@OptIn(ExperimentalCoroutinesApi::class, InternalMeshLinkApi::class)
class RotationTest {
    @Test
    fun eachNodeRotatesOncePerEpochAtItsOwnPhase() =
        runTest(timeout = DAY_TIMEOUT) {
            // Arrange
            val air = air()
            val platforms = List(3) { air.platform(StandardTestDispatcher(testScheduler)) }
            val nodes = platforms.map { gatedNode(it) }

            // Act
            nodes.forEach { it.start() }
            advanceTimeBy(24.hours)

            // Assert
            val phases =
                platforms.map { platform ->
                    val rotations = platform.radio.advertisements.drop(1)
                    val times = rotations.map { it.first }
                    assertEquals(DAY_EPOCHS, rotations.size)
                    assertEquals(setOf(EPOCH_MILLIS), times.zipWithNext { a, b -> b - a }.toSet())
                    assertEquals(rotations.size, rotations.map { it.second.toList() }.toSet().size)
                    times.first() % EPOCH_MILLIS
                }
            assertEquals(platforms.size, phases.toSet().size)
        }

    @Test
    fun aGatedPeerResolvesEveryRotationAcrossTheDailyKeyChange() =
        runTest(timeout = DAY_TIMEOUT) {
            // Arrange
            val air = air()
            val platforms = List(2) { air.platform(StandardTestDispatcher(testScheduler)) }
            val nodes = platforms.map { gatedNode(it) }
            nodes.forEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)
            val linked = nodes.map { node -> node.peers.value.map { it.nodeId } }
            val ends = platforms.map { it.radio.ends.size }

            // Act
            advanceTimeBy(25.hours)

            // Assert
            assertEquals(listOf(1, 1), linked.map { it.size })
            assertEquals(linked, nodes.map { node -> node.peers.value.map { it.nodeId } })
            assertEquals(ends, platforms.map { it.radio.ends.size })
            assertTrue(platforms.all { it.radio.advertisements.size > DAY_EPOCHS })
        }

    @Test
    fun theNextKeyIsAnnouncedALeadBeforeUseAndSentToALaterLinkAfterItsHandshake() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            gatedNode(alice).start()
            val clock = TestClock(testScheduler)
            val early = FakePeer(WireProfile.CLASSICAL, clock, MESH_KEY)
            val earlyEnd = alice.radio.ends.size
            val earlyLink = connectFake(air, alice)
            val earlyTransport = initiateGranting(early, earlyLink, alice.radio.ends[earlyEnd])
            val earlyFrames = Sealed(earlyTransport, alice.radio.ends[earlyEnd], clock)
            val current = assertNotNull(decodePayload(early.received).resolvingKey)

            // Act
            var announced: List<LinkFrame> = emptyList()
            while (announced.isEmpty()) {
                advanceTimeBy(1.minutes)
                announced = earlyFrames.frames()
            }
            val announcedAt = clock.wallClockMillis()
            val late = FakePeer(WireProfile.CLASSICAL, clock, MESH_KEY)
            val lateEnd = alice.radio.ends.size
            val lateTransport = initiate(late, connectFake(air, alice), alice.radio.ends[lateEnd])
            advanceTimeBy(2.hours)

            // Assert
            val next = assertIs<LinkFrame.ResolvingKeyUpdate>(announced.single()).next
            assertEquals(current.notAfter + DAY_SECONDS, next.notAfter)
            val lateFrames = Sealed(lateTransport, alice.radio.ends[lateEnd], clock).frames()
            val sentLater = assertIs<LinkFrame.ResolvingKeyUpdate>(lateFrames.first())
            assertContentEquals(next.key, sentLater.next.key)
            assertContentEquals(current.key, decodePayload(late.received).resolvingKey?.key)
            val firstUse =
                alice.radio.advertisements.first { (at, payload) -> tagOf(payload).contentEquals(tag(next, at)) }
            assertTrue(firstUse.first - announcedAt >= LEAD_MILLIS, "used ${firstUse.first - announcedAt} ms after")
            val before =
                alice.radio.advertisements
                    .filter { it.first < firstUse.first }
                    .drop(1)
            assertTrue(before.all { (at, payload) -> tagOf(payload).contentEquals(tag(current, at)) })
        }

    @Test
    fun anOpenMeshSendsNoResolvingKeyAndRecognizesARotatedPeerByItsAnnouncedIdentifier() =
        runTest {
            // Arrange
            val air = air()
            val platforms = List(2) { air.platform(StandardTestDispatcher(testScheduler)) }
            val nodes = platforms.map { securedNode(it, meshKey = null) }
            val fake = FakePeer(WireProfile.CLASSICAL, TestClock(testScheduler))
            // An open-mesh node ignores a RESOLVING_KEY TLV and still opens the link.
            fake.payload = HandshakePayload.of(fake.certificate, resolvingKey(1)).encode()
            nodes.forEach { it.start() }
            advanceTimeBy(SETTLE_MILLIS)
            val ends = platforms[0].radio.ends.size
            val transport = initiate(fake, connectFake(air, platforms[0]), platforms[0].radio.ends[ends])
            val linked = nodes.map { node -> node.peers.value.size }
            val bobEnds = platforms[1].radio.ends.size

            // Act
            advanceTimeBy(2.hours)

            // Assert
            assertEquals(listOf(CERTIFICATE_TLV), decodePayload(fake.received).tlvs.map { it.type })
            val frames = Sealed(transport, platforms[0].radio.ends[ends], TestClock(testScheduler)).frames()
            assertTrue(frames.none { it is LinkFrame.ResolvingKeyUpdate })
            val tags = platforms[0].radio.advertisements.map { tagOf(it.second).toList() }
            assertEquals(tags.size, tags.toSet().size)
            assertEquals(listOf(2, 1), linked)
            assertEquals(bobEnds, platforms[1].radio.ends.size, "redials")
            assertEquals(listOf(1), nodes.drop(1).map { node -> node.peers.value.size })
            assertTrue(nodes.all { it.linkCandidates().size <= WINDOW_EPOCHS }, "candidates bounded")
        }

    @Test
    fun aGatedNodeSkipsSightingsOfPeersItHasALinkToByTheKeysTheyGave() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val node = gatedNode(alice).also { it.start() }
            val fake = FakePeer(WireProfile.CLASSICAL, TestClock(testScheduler), MESH_KEY)
            val inHandshake = resolvingKey(1)
            val updated = resolvingKey(2)
            val expired = ResolvingKey(ByteArray(ResolvingKey.KEY_LENGTH) { 3 }, nowSeconds() - 1u)
            fake.payload = HandshakePayload.of(fake.certificate, inHandshake).encode()
            val link = connectFake(air, alice)
            val transport = initiate(fake, link, alice.radio.ends.single())

            // Act
            listOf(updated, expired).forEach { link.write(seal(transport, LinkFrame.ResolvingKeyUpdate(it).encode())) }
            advanceTimeBy(SETTLE_MILLIS)
            val radios = listOf(inHandshake, updated, expired, null).map { advertiser(air, it) }
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(setOf(radios[2].handle, radios[3].handle), node.linkCandidates())
            assertTrue(
                alice.radio.ends
                    .first()
                    .open,
            )
        }

    @Test
    fun aResolvingKeyUpdateIsDroppedOnACleartextLinkAndInAnOpenMeshButAWrongLengthCloses() =
        runTest {
            // Arrange
            val air = air()
            val platforms = List(3) { air.platform(StandardTestDispatcher(testScheduler)) }
            val update = LinkFrame.ResolvingKeyUpdate(resolvingKey(1)).encode()
            node(platforms[0]).start()
            securedNode(platforms[1], meshKey = null).start()
            gatedNode(platforms[2]).start()
            val cleartext = connectFake(air, platforms[0])
            val open = FakePeer(WireProfile.CLASSICAL, TestClock(testScheduler))
            val gated = FakePeer(WireProfile.CLASSICAL, TestClock(testScheduler), MESH_KEY)
            val openLink = connectFake(air, platforms[1])
            val openTransport = initiate(open, openLink, platforms[1].radio.ends.single())
            val gatedLink = connectFake(air, platforms[2])
            val gatedTransport = initiate(gated, gatedLink, platforms[2].radio.ends.single())

            // Act
            cleartext.write(record(update))
            cleartext.write(record(LinkFrame.PlainHello(1, digest(MESH), FAKE_ID)))
            cleartext.write(record(update))
            openLink.write(seal(openTransport, update))
            gatedLink.write(seal(gatedTransport, update.copyOf(update.size - 1)))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(
                listOf(true, true, false),
                platforms.map {
                    it.radio.ends
                        .single()
                        .open
                },
            )
            val closed = Sealed(gatedTransport, platforms[2].radio.ends.single(), TestClock(testScheduler)).frames()
            assertEquals(CloseReason.PROTOCOL_ERROR, assertIs<LinkFrame.Close>(closed.single()).reason)
        }

    /** A stopped `Classical` node of [MESH], gated with [MESH_KEY]. */
    private fun gatedNode(platform: PlatformPort): MeshLink =
        MeshLink.create(
            meshLinkConfig(Preset.OfflineChat, SecurityProfile.Classical) {
                meshId = MESH
                meshKey = MESH_KEY
            }.build(),
            MeshPlatform.of(platform),
        )

    /** A stopped `Classical` node of [MESH], gated with [meshKey] if set. */
    private fun securedNode(
        platform: PlatformPort,
        meshKey: ByteArray?,
    ): MeshLink =
        MeshLink.create(
            meshLinkConfig(Preset.OfflineChat, SecurityProfile.Classical) {
                meshId = MESH
                this.meshKey = meshKey
            }.build(),
            MeshPlatform.of(platform),
        )

    /** A raw radio advertising [MESH] gated with [MESH_KEY], with the resolvable tag of [key] or a random one. */
    private fun TestScope.advertiser(
        air: VirtualAir,
        key: ResolvingKey?,
    ): VirtualRadio {
        val radio = air.radio()
        val discovery = Discovery(SodiumCryptoProvider, MESH.encodeToByteArray(), WireProfile.CLASSICAL, MESH_KEY)
        radio.serveCapability(CapabilityRecord.of(listOf(1), ByteArray(RadioPort.ADVERTISEMENT_LENGTH)).encode())
        radio.startAdvertising(discovery.advertisement(epochAt(TestClock(testScheduler).wallClockMillis()), key?.key))
        return radio
    }

    private fun TestScope.nowSeconds(): UInt = (TestClock(testScheduler).wallClockMillis() / MILLIS).toUInt()

    private fun resolvingKey(n: Int): ResolvingKey =
        ResolvingKey(
            ByteArray(ResolvingKey.KEY_LENGTH) { n.toByte() },
            (TestClock.WALL_CLOCK_START_MILLIS / MILLIS).toUInt() + DAY_SECONDS,
        )

    /**
     * Reads the frames but `REKEY` and `NEIGHBOR_LIST` [end] writes after its cleartext message 2, decrypted with
     * [transport].
     */
    private class Sealed(
        transport: NoiseTransport,
        private val end: VirtualLinkEnd,
        clock: Clock,
    ) {
        private val cipher = LinkCipher(transport, clock)
        private val frames = mutableListOf<LinkFrame>()
        private var opened = 0

        /** Every frame [end] wrote so far. */
        fun frames(): List<LinkFrame> {
            written(end, 1).second.drop(opened).forEach { record ->
                val frame = assertIs<LinkCipher.Opened.Frame>(cipher.open(record)).bytes
                frames += assertIs<Decoded.Valid<LinkFrame>>(LinkFrame.decode(frame)).value
                opened++
            }
            return frames.filter { it != LinkFrame.Rekey && it !is LinkFrame.NeighborList }
        }
    }

    private fun decodePayload(bytes: ByteArray): HandshakePayload =
        assertIs<Decoded.Valid<HandshakePayload>>(HandshakePayload.decode(bytes)).value

    private fun tagOf(payload: ByteArray): ByteArray =
        assertIs<Decoded.Valid<Advertisement>>(Advertisement.decode(payload)).value.resolvableTag

    private fun tag(
        key: ResolvingKey,
        atMillis: Long,
    ): ByteArray = Derivations(SodiumCryptoProvider).resolvableTag(key.key, epochAt(atMillis))

    private fun epochAt(millis: Long): UInt = Derivations.epoch(millis / MILLIS)

    private companion object {
        // A simulated day of linked nodes carries about 50,000 encrypted shuffle frames: tens of
        // seconds of real time on a CI simulator, past runTest's 60 s default.
        val DAY_TIMEOUT = 5.minutes
        const val EPOCH_MILLIS = 300_000L
        const val DAY_EPOCHS = 288
        const val WINDOW_EPOCHS = 3
        const val DAY_SECONDS = 86_400u
        const val LEAD_MILLIS = 3_600_000L
        const val MILLIS = 1_000L
        const val CERTIFICATE_TLV = 0x81
        val MESH_KEY = ByteArray(32) { 0x11 }
    }
}
