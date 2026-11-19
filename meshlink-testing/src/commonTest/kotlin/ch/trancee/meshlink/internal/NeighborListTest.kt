package ch.trancee.meshlink.internal

import ch.trancee.meshlink.InternalMeshLinkApi
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** Neighbor lists and shuffles (spec "Neighbor list", "Shuffle"). */
@OptIn(ExperimentalCoroutinesApi::class, InternalMeshLinkApi::class)
class NeighborListTest {
    @Test
    fun eachNodeSendsItsIdentifiersAndItsPeersOnEveryLinkAtMostOncePerInterval() =
        runTest {
            // Arrange
            val air = air()
            val platforms = List(NODES) { air.platform(StandardTestDispatcher(testScheduler)) }
            val nodes = platforms.map { node(it) }

            // Act
            nodes.forEach { it.start() }
            advanceTimeBy(1.hours)

            // Assert
            val advertised = platforms.map { it.radio.advertisements.map { (at, payload) -> at to tagOf(payload) } }
            platforms.forEachIndexed { i, platform ->
                val perEnd = platform.radio.ends.map { lists(it) }
                val kept = perEnd.filterIndexed { e, _ -> platform.radio.ends[e].open }
                assertTrue(kept.isNotEmpty() && kept.all { it.isNotEmpty() }, "node $i: a kept link without a list")
                val sent = perEnd.flatten().distinctBy { it.first }.sortedBy { it.first }
                // A peripheral link's first list waits for the central's first credit, so it is written late.
                val paced = perEnd.flatMap { end -> end.drop(1).map { it.first } }.distinct().sorted()
                assertTrue(paced.zipWithNext { a, b -> b - a }.all { it >= MIN_INTERVAL_MILLIS }, "node $i: interval")
                val own = advertised[i]
                sent.forEach { (at, list) ->
                    val k = own.indexOfFirst { it.second.contentEquals(list.ownId) }
                    assertTrue(k >= 0 && own[k].first <= at, "node $i: own_id isn't advertised")
                    own.getOrNull(k + 1)?.let { (rotatedAt, nextTag) ->
                        assertTrue(rotatedAt >= at, "node $i: own_id outdated")
                        assertContentEquals(nextTag, list.nextId, "node $i: next_id isn't what it advertised next")
                    }
                    val others = advertised.filterIndexed { j, _ -> j != i }.flatten().filter { it.first <= at }
                    assertTrue(list.neighbors.all { entry -> others.any { it.second.contentEquals(entry) } })
                }
                val firstSent = sent.first().first
                own.zipWithNext().filter { (_, rotation) -> rotation.first > firstSent }.forEach { (before, after) ->
                    val around = sent.filter { it.first == after.first }.map { it.second.ownId.toList() }
                    assertTrue(before.second.toList() in around, "node $i: no list before rotating")
                    assertTrue(sent.any { it.second.ownId.contentEquals(after.second) }, "node $i: no list after")
                }
                assertEquals(
                    nodes[i].peers.value.size,
                    sent
                        .last()
                        .second.neighbors.size,
                    "node $i",
                )
            }
        }

    @Test
    fun aRotatedPeerIsRecognizedOnlyByTheNextIdItAnnouncedOnItsKeptLink() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val node = node(alice).also { it.start() }
            val announced = connectFake(air, alice)
            val other = connectFake(air, alice)
            val gone = connectFake(air, alice)
            announced.write(hello(FAKE_ID))
            other.write(record(LinkFrame.PlainHello(1, digest(MESH), OTHER_ID)))
            gone.write(record(LinkFrame.PlainHello(1, digest(MESH), GONE_ID)))
            gone.write(record(LinkFrame.NeighborList(id(8), id(9), emptyList())))
            advanceTimeBy(SETTLE_MILLIS)
            gone.disconnect()
            val duplicate = connectFake(air, alice)
            duplicate.write(record(LinkFrame.PlainHello(1, digest(MESH), OTHER_ID)))
            advanceTimeBy(SETTLE_MILLIS)

            // Act
            announced.write(record(LinkFrame.NeighborList(id(1), id(2), emptyList())))
            other.write(record(LinkFrame.NeighborList(id(3), id(4), emptyList())))
            duplicate.write(record(LinkFrame.NeighborList(id(5), id(6), emptyList())))
            advanceTimeBy(SETTLE_MILLIS)
            val radios = listOf(id(2), id(4), id(7), id(6), id(9)).map { advertiser(air, it) }
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(radios.drop(2).map { it.handle }.toSet(), node.linkCandidates())
            advanceTimeBy(EPOCH_MILLIS)
            val latest = lists(alice.radio.ends.first()).last().second
            assertEquals(setOf(id(1), id(3)).map { it.toList() }.toSet(), latest.neighbors.map { it.toList() }.toSet())
            assertEquals(listOf(CloseReason.DUPLICATE_LINK), closes(alice.radio.ends[DUPLICATE]))
            assertTrue(
                alice.radio.ends
                    .take(2)
                    .all { it.open },
            )
        }

    @Test
    fun aCentralOnlyNodeAdvertisesNoIdentifierAndSendsNoList() =
        runTest {
            // Arrange
            val air = air()
            val platforms = List(2) { air.platform(StandardTestDispatcher(testScheduler)) }
            val nodes = listOf(node(platforms[0]).apply { restrictedLinkRole = LinkRole.CENTRAL }, node(platforms[1]))

            // Act
            nodes.forEach { it.start() }
            advanceTimeBy(EPOCH_MILLIS)

            // Assert
            assertEquals(listOf(1, 1), nodes.map { it.peers.value.size })
            assertEquals(emptyList(), platforms[0].radio.ends.flatMap { lists(it) })
            assertTrue(platforms[1].radio.ends.all { lists(it).isNotEmpty() })
        }

    @Test
    fun aShuffleIsAnsweredWithASampleTakenBeforeMergingAndItsEntriesAreShuffledOnward() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            node(alice).start()
            val fake = connectFake(air, alice)
            fake.write(hello(FAKE_ID))
            advanceTimeBy(SETTLE_MILLIS)
            val own =
                tagOf(
                    alice.radio.advertisements
                        .last()
                        .second,
                )

            // Act
            fake.write(record(LinkFrame.Shuffle(listOf(id(1), own))))
            advanceTimeBy(SETTLE_MILLIS)
            fake.write(record(LinkFrame.Shuffle(listOf(id(2)))))
            advanceTimeBy(SETTLE_MILLIS)
            fake.write(record(LinkFrame.ShuffleReply(listOf(id(3)))))
            advanceTimeBy(SHUFFLE_INTERVAL_MILLIS)

            // Assert
            val end = alice.radio.ends.single()
            val replies = frames(end).filterIsInstance<LinkFrame.ShuffleReply>()
            val answered = replies.map { r -> r.entries.map { it.toList() } }
            assertEquals(listOf(emptyList(), listOf(id(1).toList())), answered)
            val shuffles = frames(end).filterIsInstance<LinkFrame.Shuffle>()
            assertEquals(1, shuffles.size)
            assertEquals(
                (1..3).map { id(it).toList() }.toSet(),
                shuffles
                    .single()
                    .entries
                    .map { it.toList() }
                    .toSet(),
            )
            assertTrue(end.open)
        }

    @Test
    fun twelveNodesHoldAtMostTheirLinkBudgetShuffleEveryTenSecondsAndLeaveANewcomerASlot() =
        runTest {
            // Arrange
            val air = air(SEED)
            val platforms = List(MESH_NODES) { air.platform(StandardTestDispatcher(testScheduler)) }
            val nodes = platforms.map { node(it) }
            val newcomer = air.platform(StandardTestDispatcher(testScheduler))

            // Act
            nodes.forEach { it.start() }
            val held =
                List((MESH_MILLIS / SETTLE_MILLIS).toInt()) {
                    advanceTimeBy(SETTLE_MILLIS)
                    nodes.maxOf { it.peers.value.size }
                }
            node(newcomer).start()
            advanceTimeBy(NEWCOMER_MILLIS)

            // Assert
            assertTrue(held.all { it <= BUDGET }, "more than d links: $held")
            assertTrue(platforms.all { p -> p.radio.ends.count { it.open } <= BUDGET })
            val shuffles =
                platforms.map { platform ->
                    platform.radio.ends.flatMap { end -> written(end).filter { it.second is LinkFrame.Shuffle } }
                }
            assertTrue(shuffles.flatten().isNotEmpty(), "no shuffle")
            shuffles.forEachIndexed { i, sent ->
                val times = sent.map { it.first }.sorted()
                val gaps = times.zipWithNext { a, b -> b - a }
                assertTrue(gaps.all { it >= SHUFFLE_INTERVAL_MILLIS }, "node $i: interval")
                assertTrue(sent.all { (it.second as LinkFrame.Shuffle).entries.size in 1..ENTRIES_MAX }, "node $i")
            }
            platforms.flatMap { it.radio.ends }.forEach { end ->
                val asked = frames(end).count { it is LinkFrame.Shuffle }
                val replies = frames(end.opposite).filterIsInstance<LinkFrame.ShuffleReply>()
                assertEquals(asked, replies.size)
                assertTrue(replies.all { it.entries.size <= ENTRIES_MAX })
            }
            assertTrue(newcomer.radio.ends.any { it.open }, "no slot for a newcomer")
        }

    @Test
    fun aClosingLinkFreesItsSlotForACandidateHeardInTheLastFourSeconds() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            node(alice).start()
            val fakes = List(BUDGET - 1) { n -> connectFake(air, alice).also { it.write(hello(fakeId(n))) } }
            advanceTimeBy(SETTLE_MILLIS)
            val candidate = advertiser(air, id(1))
            advanceTimeBy(SETTLE_MILLIS)
            val full = candidate.ends.size

            // Act
            fakes.first().disconnect()
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(0, full)
            assertEquals(1, candidate.ends.size)
        }

    @Test
    fun aClosingCentralLinkFreesItsSlotForTheNextDial() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val peripheral = advertiser(air, id(2))
            node(alice).start()
            advanceTimeBy(SETTLE_MILLIS)
            val toAlice = peripheral.ends.single()
            toAlice.write(record(LinkFrame.PlainHello(1, digest(MESH), OTHER_ID)))
            List(BUDGET - 2) { n -> connectFake(air, alice).also { it.write(hello(fakeId(n))) } }
            advanceTimeBy(SETTLE_MILLIS)
            val candidate = advertiser(air, id(1))
            advanceTimeBy(SETTLE_MILLIS)
            val full = candidate.ends.size

            // Act
            toAlice.disconnect()
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertEquals(0, full)
            assertEquals(2, candidate.ends.size + peripheral.ends.size)
        }

    @Test
    fun aCandidateLastHeardMoreThanFourSecondsAgoIsDialedOnlyAtItsNextSighting() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            node(alice).start()
            val fakes = List(BUDGET - 1) { n -> connectFake(air, alice).also { it.write(hello(fakeId(n))) } }
            advanceTimeBy(SETTLE_MILLIS)
            val candidate = advertiser(air, id(1))
            advanceTimeBy(STALE_MILLIS)

            // Act
            fakes.first().disconnect()
            advanceTimeBy(SETTLE_MILLIS)
            val stale = candidate.ends.size
            advanceTimeBy(SCAN_PERIOD_MILLIS)

            // Assert
            assertEquals(0, stale)
            assertEquals(1, candidate.ends.size)
        }

    @Test
    fun aMalformedListOrShuffleAndAListBeforeTheHelloCloseTheLinkWithProtocolError() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            node(alice).start()
            val list = LinkFrame.NeighborList(id(1), id(2), listOf(id(3))).encode()
            val over = LinkFrame.NeighborList.NEIGHBORS_MAX + 1
            val malformed =
                listOf(
                    list.copyOf().also { it[COUNT] = 2 },
                    list + 0,
                    list.copyOf(COUNT) + over.toByte() + ByteArray(ID * over),
                    byteArrayOf(SHUFFLE, SHUFFLE_OVER) + ByteArray(ID * SHUFFLE_OVER),
                )
            val fakes = malformed.map { connectFake(air, alice) }
            val early = connectFake(air, alice)

            // Act
            fakes.zip(malformed).forEachIndexed { i, (fake, frame) -> fake.write(hello(fakeId(i)) + record(frame)) }
            early.write(record(LinkFrame.NeighborList(id(1), id(2), emptyList())))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            val expected = List(malformed.size + 1) { listOf(CloseReason.PROTOCOL_ERROR) }
            assertEquals(expected, alice.radio.ends.map { closes(it) })
        }

    /** Each `NEIGHBOR_LIST` [end] wrote, with the time of the write that completed it. */
    private fun lists(end: VirtualLinkEnd): List<Pair<Long, LinkFrame.NeighborList>> {
        val reader = RecordReader()
        return end.writes.flatMap { (at, bytes) ->
            assertIs<Decoded.Valid<List<ByteArray>>>(reader.read(bytes))
                .value
                .filter { it[0] == NEIGHBOR_LIST }
                .map { at to assertIs<LinkFrame.NeighborList>(decoded(it)) }
        }
    }

    /** Each frame [end] wrote, with the time of the write that completed it. */
    private fun written(end: VirtualLinkEnd): List<Pair<Long, LinkFrame>> {
        val reader = RecordReader()
        return end.writes.flatMap { (at, bytes) ->
            assertIs<Decoded.Valid<List<ByteArray>>>(reader.read(bytes)).value.map { at to decoded(it) }
        }
    }

    /** A `PlainHello` claiming [id], with the credit window a MeshLink central grants. */
    private fun hello(id: ByteArray): ByteArray =
        record(LinkFrame.PlainHello(1, digest(MESH), id)) + record(LinkFrame.Credit(CREDIT_WINDOW))

    /** A raw radio advertising [MESH] with [identifier]. */
    private fun TestScope.advertiser(
        air: VirtualAir,
        identifier: ByteArray,
    ): VirtualRadio {
        val radio = air.radio()
        val discovery = Discovery(SodiumCryptoProvider, MESH.encodeToByteArray(), WireProfile.UNSECURED)
        val epoch = Derivations.epoch(TestClock(testScheduler).wallClockMillis() / MILLIS)
        radio.serveCapability(CapabilityRecord.of(listOf(1), ByteArray(RadioPort.ADVERTISEMENT_LENGTH)).encode())
        radio.startAdvertising(discovery.advertisement(epoch, identifier = identifier))
        return radio
    }

    private fun decoded(body: ByteArray): LinkFrame = assertIs<Decoded.Valid<LinkFrame>>(LinkFrame.decode(body)).value

    private fun tagOf(payload: ByteArray): ByteArray =
        assertIs<Decoded.Valid<Advertisement>>(Advertisement.decode(payload)).value.resolvableTag

    private fun fakeId(n: Int): ByteArray = ByteArray(NODE_ID) { (FAKE_ID[0] - n).toByte() }

    private fun id(n: Int): ByteArray = ByteArray(Advertisement.RESOLVABLE_TAG_LENGTH) { n.toByte() }

    private companion object {
        const val NODES = 8
        const val SHUFFLE_INTERVAL_MILLIS = 10_000L
        const val ENTRIES_MAX = 5
        const val BUDGET = 6
        const val SEED = 35L
        const val MESH_NODES = 12
        const val MESH_MILLIS = 60_000L
        const val NEWCOMER_MILLIS = 20_000L
        const val STALE_MILLIS = 5_000L
        const val SCAN_PERIOD_MILLIS = 8_000L
        const val MIN_INTERVAL_MILLIS = 2_000L
        const val MILLIS = 1_000L
        const val EPOCH_MILLIS = Derivations.EPOCH_SECONDS * MILLIS
        const val NEIGHBOR_LIST: Byte = 0x07
        const val COUNT = 17
        const val ID = 8
        const val NODE_ID = 32
        const val SHUFFLE: Byte = 0x08
        const val SHUFFLE_OVER: Byte = 6
        const val DUPLICATE = 3
        val OTHER_ID = ByteArray(32) { 0x7E }
        val GONE_ID = ByteArray(32) { 0x7D }
    }
}
