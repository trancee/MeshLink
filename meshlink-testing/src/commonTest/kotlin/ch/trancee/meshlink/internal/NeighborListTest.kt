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
                    // A list sent less than the interval before the rotation already announced the next identifier.
                    val last = sent.last { it.first <= after.first }.second
                    assertContentEquals(before.second, last.ownId, "node $i: no list before rotating")
                    assertContentEquals(after.second, last.nextId, "node $i: next_id not announced before rotating")
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
    fun aPeersFirstListOrNewIdentifierIsResentOnEveryLinkButAnUnchangedListIsNot() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            node(alice).start()
            val rotating = connectFake(air, alice).also { it.write(hello(FAKE_ID)) }
            val other = connectFake(air, alice)
            other.write(record(LinkFrame.PlainHello(1, digest(MESH), OTHER_ID)))
            grantCredits(other, alice.radio.ends[1])
            advanceTimeBy(MIN_INTERVAL_MILLIS + SETTLE_MILLIS)
            val onOther = { lists(alice.radio.ends[1]).map { (_, list) -> list.neighbors.map { it.toList() } } }
            val before = onOther().size

            // Act
            rotating.write(record(LinkFrame.NeighborList(id(1), id(2), emptyList())))
            advanceTimeBy(MIN_INTERVAL_MILLIS)
            rotating.write(record(LinkFrame.NeighborList(id(1), id(2), emptyList())))
            advanceTimeBy(MIN_INTERVAL_MILLIS)
            rotating.write(record(LinkFrame.NeighborList(id(2), id(3), emptyList())))
            advanceTimeBy(MIN_INTERVAL_MILLIS)

            // Assert
            assertEquals(listOf(listOf(id(1).toList()), listOf(id(2).toList())), onOther().drop(before))
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
    fun twoNodesHeardBelowMinus88DbmNeverLinkAndTwoHeardBetweenMinus88AndMinus85LinkAsABridge() =
        runTest {
            // Arrange
            val air = air(SEED)
            val pairs = List(2) { List(2) { air.platform(StandardTestDispatcher(testScheduler)) } }
            val (weak, bridged) = pairs
            air.setRssi(weak[0].radio, weak[1].radio, BELOW_BRIDGE_FLOOR_DBM)
            air.setRssi(bridged[0].radio, bridged[1].radio, BELOW_DIAL_FLOOR_DBM)
            bridged.forEach { air.move(it.radio, 2 * VirtualAir.DEFAULT_RANGE, 0.0) }
            pairs.flatten().forEach { node(it).start() }

            // Act
            advanceTimeBy(BRIDGE_WINDOW_MILLIS)

            // Assert
            assertTrue(weak.all { it.radio.ends.isEmpty() }, "a link below the bridge floor")
            assertTrue(bridged.all { p -> p.radio.ends.any { it.open } }, "no bridge above its floor")
        }

    @Test
    fun eightNodesInRangeOfEachOtherKeepTheirLinksOnceSettled() =
        runTest {
            // Arrange
            val air = air()
            val platforms = List(NODES) { air.platform(StandardTestDispatcher(testScheduler)) }
            platforms.forEach { node(it).start() }
            advanceTimeBy(STABLE_AFTER_MILLIS)
            val settled = platforms.map { it.radio.ends.size }

            // Act
            advanceTimeBy(STABLE_FOR_MILLIS)

            // Assert
            assertEquals(settled, platforms.map { it.radio.ends.size })
        }

    @Test
    fun aRadioMovedBetweenTwoSplitCliquesBridgesThemWithOneDialPerNodeWithinTheBridgeWindow() =
        runTest {
            // Arrange
            val air = air(SEED)
            val platforms = List(MESH_NODES) { air.platform(StandardTestDispatcher(testScheduler)) }
            val (left, right) = platforms.chunked(MESH_NODES / 2)
            right.forEach { air.move(it.radio, APART, 0.0) }
            platforms.forEach { node(it).start() }
            advanceTimeBy(MESH_MILLIS + 1)
            val split = components(platforms)
            val crossing = { platform: VirtualPlatform ->
                val other = if (platform in left) right else left
                platform.radio.ends.count { end -> end is VirtualCentralEnd && other.any { it.radio === end.remote } }
            }

            // Act
            air.move(right.first().radio, APART / 2, 0.0)
            advanceTimeBy(REPAIR_MILLIS)

            // Assert
            assertEquals(listOf(2, 1), listOf(split, components(platforms)))
            assertTrue(platforms.all { crossing(it) <= 1 }, "more than one bridge dial")
        }

    @Test
    fun everyThirtySecondsANodeAtItsLastFreeSlotReplacesItsMostRedundantLinkForALessNamedCandidate() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            node(alice).start()
            val fakes = List(BUDGET - 1) { n -> connectFake(air, alice).also { it.write(hello(fakeId(n))) } }
            fakes.forEachIndexed { n, fake ->
                val others = fakes.indices.filter { it != n }.map { id(FAKE_TAG + it) }
                fake.write(record(LinkFrame.NeighborList(id(FAKE_TAG + n), id(FAKE_NEXT + n), others)))
            }
            advanceTimeBy(SETTLE_MILLIS)
            // A decoy that refuses the link takes the bridge dial, so the candidate is never one.
            val decoy = advertiser(air, id(DECOY), versions = listOf(UNSUPPORTED_VERSION))
            repeat(BRIDGE_WAIT_STEPS) { if (decoy.ends.isEmpty()) advanceTimeBy(SETTLE_MILLIS) }
            decoy.stopAdvertising()
            val candidate = advertiser(air, id(CANDIDATE))

            // Act
            val dialedAt =
                (1..2 * REPLACE_INTERVAL_MILLIS / SETTLE_MILLIS).firstNotNullOfOrNull {
                    advanceTimeBy(SETTLE_MILLIS)
                    testScheduler.currentTime.takeIf { candidate.ends.isNotEmpty() }
                }
            candidate.ends.singleOrNull()?.write(record(LinkFrame.PlainHello(1, digest(MESH), CANDIDATE_ID)))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertTrue(dialedAt != null && dialedAt % REPLACE_INTERVAL_MILLIS <= SETTLE_MILLIS, "dialed at $dialedAt")
            assertEquals(1, decoy.ends.size)
            assertTrue(candidate.ends.single().open)
            assertEquals(1, alice.radio.ends.count { closes(it) == listOf(CloseReason.NORMAL) })
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
    fun aFailedDialIsNotRepeatedWithinThreeSecondsAndARepeatedFailureNotWithinSixty() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val refusing = advertiser(air, id(1), versions = listOf(UNSUPPORTED_VERSION))
            node(alice).start()
            val fakes = List(2) { n -> connectFake(air, alice).also { it.write(hello(fakeId(n))) } }
            advanceTimeBy(SETTLE_MILLIS)
            val failed = refusing.ends.size

            // Act
            fakes[0].disconnect()
            advanceTimeBy(SETTLE_MILLIS)
            val backedOff = refusing.ends.size
            // Past the backoff, while the sighting is still within the last four seconds.
            advanceTimeBy(FIRST_BACKOFF_MILLIS - SETTLE_MILLIS - SETTLE_MILLIS / 2)
            fakes[1].disconnect()
            advanceTimeBy(SETTLE_MILLIS)
            val again = refusing.ends.size
            advanceTimeBy(REPEATED_BACKOFF_MILLIS - 2 * SETTLE_MILLIS)
            val repeated = refusing.ends.size
            advanceTimeBy(SCAN_PERIOD_MILLIS)

            // Assert
            assertEquals(listOf(1, 1, 2, 2), listOf(failed, backedOff, again, repeated))
            assertEquals(3, refusing.ends.size)
        }

    @Test
    fun aPeerThatClosedItsKeptLinkIsNotDialedAgainFor120Seconds() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val peer = advertiser(air, id(1))
            node(alice).start()
            advanceTimeBy(SETTLE_MILLIS)
            val toAlice = peer.ends.single()
            toAlice.write(record(LinkFrame.PlainHello(1, digest(MESH), OTHER_ID)))
            advanceTimeBy(SETTLE_MILLIS)

            // Act
            toAlice.write(record(LinkFrame.Close(CloseReason.NORMAL)))
            advanceTimeBy(COOL_DOWN_MILLIS - SETTLE_MILLIS)
            val cooled = peer.ends.size
            advanceTimeBy(SCAN_PERIOD_MILLIS + SETTLE_MILLIS)

            // Assert
            assertEquals(1, cooled)
            assertEquals(2, peer.ends.size)
        }

    @Test
    fun aNodeHoldingItsLinkBudgetForThirtySecondsFreesOneSlotWhichANewcomerTakes() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            node(alice).start()
            List(BUDGET) { n -> connectFake(air, alice).also { it.write(hello(fakeId(n))) } }
            advanceTimeBy(FULL_MILLIS - SETTLE_MILLIS)
            val held = alice.radio.ends.map { closes(it) }

            // Act
            advanceTimeBy(2 * SETTLE_MILLIS)
            val freed = alice.radio.ends.map { closes(it) }
            connectFake(air, alice).write(hello(fakeId(BUDGET)))
            advanceTimeBy(SETTLE_MILLIS)

            // Assert
            assertTrue(held.all { it.isEmpty() })
            assertEquals(1, freed.count { it == listOf(CloseReason.NORMAL) })
            assertEquals(BUDGET - 1, freed.count { it.isEmpty() })
            val newcomer = alice.radio.ends.last()
            assertEquals(emptyList(), closes(newcomer))
            assertTrue(newcomer.open)
        }

    @Test
    fun aNodeThatDropsBelowItsLinkBudgetBeforeThirtySecondsFreesNothing() =
        runTest {
            // Arrange
            val air = air()
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            node(alice).start()
            val fakes = List(BUDGET) { n -> connectFake(air, alice).also { it.write(hello(fakeId(n))) } }
            advanceTimeBy(FULL_MILLIS - SETTLE_MILLIS)

            // Act
            fakes.first().disconnect()
            advanceTimeBy(2 * SETTLE_MILLIS)

            // Assert
            assertTrue(alice.radio.ends.all { closes(it).isEmpty() })
        }

    @Test
    fun aPeerWhoseLinkTheNodeFreedIsNotDialedAgainFor120Seconds() =
        runTest {
            // Arrange
            val air = air(ROTATION_SEED)
            val alice = air.platform(StandardTestDispatcher(testScheduler))
            val peer = advertiser(air, id(1))
            node(alice).start()
            advanceTimeBy(SETTLE_MILLIS)
            peer.ends.single().write(record(LinkFrame.PlainHello(1, digest(MESH), OTHER_ID)))
            val fakes = List(BUDGET - 1) { n -> connectFake(air, alice).also { it.write(hello(fakeId(n + 2))) } }
            advanceTimeBy(FULL_MILLIS + SETTLE_MILLIS)
            val freed = closes(alice.radio.ends.first())

            // Act
            fakes.first().disconnect()
            advanceTimeBy(COOL_DOWN_MILLIS - 2 * SETTLE_MILLIS)
            val cooled = peer.ends.size
            advanceTimeBy(SCAN_PERIOD_MILLIS + SETTLE_MILLIS)

            // Assert
            assertEquals(listOf(CloseReason.NORMAL), freed)
            assertEquals(1, cooled)
            assertEquals(2, peer.ends.size)
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

    /** A raw radio advertising [MESH] with [identifier], whose capability record offers [versions]. */
    private fun TestScope.advertiser(
        air: VirtualAir,
        identifier: ByteArray,
        versions: List<Int> = listOf(1),
    ): VirtualRadio {
        val radio = air.radio()
        val discovery = Discovery(SodiumCryptoProvider, MESH.encodeToByteArray(), WireProfile.UNSECURED)
        val epoch = Derivations.epoch(TestClock(testScheduler).wallClockMillis() / MILLIS)
        radio.serveCapability(CapabilityRecord.of(versions, ByteArray(RadioPort.ADVERTISEMENT_LENGTH)).encode())
        radio.startAdvertising(discovery.advertisement(epoch, identifier = identifier))
        return radio
    }

    /** How many connected parts the open links split [platforms] into. */
    private fun components(platforms: List<VirtualPlatform>): Int {
        val part = platforms.indices.toMutableList()

        fun root(i: Int): Int = if (part[i] == i) i else root(part[i])
        platforms.forEachIndexed { i, platform ->
            platform.radio.ends.filter { it.open }.forEach { end ->
                val j = platforms.indexOfFirst { it.radio === end.remote }
                part[root(i)] = root(j)
            }
        }
        return platforms.indices.count { root(it) == it }
    }

    private fun decoded(body: ByteArray): LinkFrame = assertIs<Decoded.Valid<LinkFrame>>(LinkFrame.decode(body)).value

    private fun tagOf(payload: ByteArray): ByteArray =
        assertIs<Decoded.Valid<Advertisement>>(Advertisement.decode(payload)).value.resolvableTag

    private fun fakeId(n: Int): ByteArray = ByteArray(NODE_ID) { (FAKE_ID[0] - n).toByte() }

    private fun id(n: Int): ByteArray = ByteArray(Advertisement.RESOLVABLE_TAG_LENGTH) { n.toByte() }

    private companion object {
        const val NODES = 8
        const val STABLE_AFTER_MILLIS = 10 * 60_000L
        const val STABLE_FOR_MILLIS = 20 * 60_000L
        const val APART = 40.0

        // The bridge window's end, a settle and a scan period after the move, plus a second.
        const val REPAIR_MILLIS = 14_000L
        const val REPLACE_INTERVAL_MILLIS = 30_000L
        const val FAKE_TAG = 20
        const val FAKE_NEXT = 40
        const val DECOY = 60
        const val BRIDGE_WAIT_STEPS = 20
        const val CANDIDATE = 61
        const val SHUFFLE_INTERVAL_MILLIS = 10_000L
        const val ENTRIES_MAX = 5
        const val BUDGET = 6
        const val SEED = 35L
        const val BELOW_BRIDGE_FLOOR_DBM = -89
        const val BELOW_DIAL_FLOOR_DBM = -87

        /** Settling, then one newcomer bridge window (`Views.NEW_MILLIS`), with a margin. */
        const val BRIDGE_WINDOW_MILLIS = 30_000L
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
        const val UNSUPPORTED_VERSION = 99
        const val FIRST_BACKOFF_MILLIS = 3_000L
        const val REPEATED_BACKOFF_MILLIS = 60_000L
        const val COOL_DOWN_MILLIS = 120_000L
        const val FULL_MILLIS = 30_000L

        // A seed whose node frees its link to the advertising peer, not one of the fakes' links.
        const val ROTATION_SEED = 6L
        val OTHER_ID = ByteArray(32) { 0x7E }
        val GONE_ID = ByteArray(32) { 0x7D }
        val CANDIDATE_ID = ByteArray(32) { 0x10 }
    }
}
