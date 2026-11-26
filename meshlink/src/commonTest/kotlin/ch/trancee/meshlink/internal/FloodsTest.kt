package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DensityClass
import ch.trancee.meshlink.ReceivedMessage
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest

// Flood forwarding of broadcasts, the broadcast tree's frames, and unknown flood types, on lanes to three linked peers.
@OptIn(ExperimentalCoroutinesApi::class)
class FloodsTest {
    private val a = nodeId(1)
    private val b = nodeId(2)
    private val c = nodeId(3)
    private val lanes = Lanes().apply { listOf(a, b, c).forEach { open(it) } }
    private val delivered = mutableListOf<ReceivedMessage>()

    private fun TestScope.floods(
        secured: Boolean = false,
        density: DensityClass = DensityClass.Low,
        holds: Boolean = true,
    ): Floods {
        val tree = Plumtree(lanes, SchedulerClock(testScheduler), backgroundScope) { density }
        return Floods(lanes, broadcastCodec(secured), tree, ::tombstoneCommitment, holds) { delivered += it }
    }

    private suspend fun waiting() = listOf(a, b, c).associateWith { peer -> lanes.drain(peer).map { it.render() } }

    @Test
    fun aNewBroadcastIsDeliveredAndFloodedToEveryOtherLinkWithOneHopLess() =
        runTest {
            // Arrange
            val floods = floods()
            val frame = broadcast(hopLimit = 5)

            // Act
            floods.received(a, frame, NOW)

            // Assert
            val relayed = frame.copyOf().also { it[1] = 4 }
            val waiting = waiting()
            assertContentEquals(SENDER, delivered.single().sender.toByteArray())
            assertEquals("hello", delivered.single().text())
            assertEquals(emptyList(), waiting.getValue(a))
            listOf(b, c).forEach { assertContentEquals(relayed, waiting.getValue(it).single()) }
        }

    @Test
    fun aMessageIdSeenBeforeIsDroppedThroughItsLastTimelySecond() =
        runTest {
            // Arrange
            val floods = floods()
            floods.received(a, broadcast(), NOW)
            waiting()

            // Act
            floods.received(b, broadcast(), NOW + LIFETIME + SKEW)

            // Assert
            assertEquals(1, delivered.size)
            assertEquals(listOf(a, b, c).associateWith { emptyList<ByteArray>() }, waiting())
        }

    @Test
    fun aBroadcastWhoseNextHopLimitWouldBeZeroIsDeliveredButNotForwarded() =
        runTest {
            // Arrange
            val floods = floods()

            // Act
            floods.received(a, broadcast(hopLimit = 1), NOW)

            // Assert
            assertEquals(1, delivered.size)
            assertEquals(listOf(a, b, c).associateWith { emptyList<ByteArray>() }, waiting())
        }

    @Test
    fun aFrameThisNodeMustNotProcessIsDroppedWithoutStateChange() =
        runTest {
            // Arrange
            val floods = floods()
            val valid = broadcast()
            val dropped =
                listOf(
                    valid.copyOf().also { it[1] = 0 },
                    valid.copyOf(valid.size - 1),
                    valid.copyOf(FloodHeader.LENGTH - 1),
                    broadcast(container = Container.Unsecured(SENDER, ByteArray(PAYLOAD_MAX + 1))),
                    broadcast(container = Container.SignedBroadcast(ByteArray(32), byteArrayOf(1), ByteArray(64))),
                    signed(),
                    header() + byteArrayOf(Container.IK_INITIATOR.toByte()) + ByteArray(48),
                )

            // Act
            dropped.forEach { floods.received(a, it, NOW) }
            floods.received(a, broadcast(expiry = NOW - SKEW - 1), NOW)
            floods.received(a, broadcast(expiry = NOW + LIFETIME_MAX + SKEW + 1), NOW)
            val nothing = waiting()
            floods.received(a, valid, NOW)

            // Assert
            assertEquals(listOf(a, b, c).associateWith { emptyList<ByteArray>() }, nothing)
            assertEquals(1, delivered.size)
            assertEquals(listOf(0, 1, 1), waiting().values.map { it.size })
        }

    @Test
    fun theTimelinessBoundsIncludeTheirEdges() =
        runTest {
            // Arrange
            val floods = floods()

            // Act
            floods.received(a, broadcast(id = 1, expiry = NOW - SKEW), NOW)
            floods.received(a, broadcast(id = 2, expiry = NOW + LIFETIME_MAX + SKEW), NOW)

            // Assert
            assertEquals(2, delivered.size)
        }

    @Test
    fun aSecuredNodeNeitherDeliversNorRelaysAnUnsecuredBroadcast() =
        runTest {
            // Arrange
            val floods = floods(secured = true)

            // Act
            floods.received(a, broadcast(), NOW)

            // Assert
            assertEquals(emptyList(), delivered)
            assertEquals(listOf(a, b, c).associateWith { emptyList<ByteArray>() }, waiting())
        }

    @Test
    fun aSecuredNodeDeliversAndRelaysASignedBroadcastFromItsOriginsNodeId() =
        runTest {
            // Arrange
            val floods = floods(secured = true)
            val frame = signed()

            // Act
            floods.received(a, frame, NOW)

            // Assert
            val origin = Derivations(SodiumCryptoProvider).nodeId(identityKey(2).publicKey)
            assertContentEquals(origin, delivered.single().sender.toByteArray())
            assertEquals("hello", delivered.single().text())
            assertEquals(listOf(0, 1, 1), waiting().values.map { it.size })
        }

    @Test
    fun aForgedSignedBroadcastIsDroppedAndDoesNotShadowTheGenuineOne() =
        runTest {
            // Arrange
            val floods = floods(secured = true)
            val genuine = signed()
            val forged = genuine.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }

            // Act
            floods.received(a, forged, NOW)
            val nothing = waiting()
            floods.received(a, genuine, NOW)

            // Assert
            assertEquals(listOf(a, b, c).associateWith { emptyList<ByteArray>() }, nothing)
            assertEquals(1, delivered.size)
            assertEquals(listOf(0, 1, 1), waiting().values.map { it.size })
        }

    @Test
    fun anUnknownFloodTypeIsRelayedOnceWithOneHopLessAndNotDelivered() =
        runTest {
            // Arrange
            val floods = floods()
            val frame = header(type = UNKNOWN_TYPE, hopLimit = 5) + byteArrayOf(9, 9)

            // Act
            floods.received(a, frame, NOW)
            val waiting = waiting()
            floods.received(b, frame, NOW)

            // Assert
            val relayed = frame.copyOf().also { it[1] = 4 }
            assertEquals(emptyList(), delivered)
            listOf(b, c).forEach { assertContentEquals(relayed, waiting.getValue(it).single()) }
            assertEquals(listOf(a, b, c).associateWith { emptyList<ByteArray>() }, waiting())
        }

    @Test
    fun anUnknownFloodTypeAboveTheSmallFrameMaximumIsDroppedWithoutStateChange() =
        runTest {
            // Arrange
            val floods = floods()
            val small = header(type = UNKNOWN_TYPE)
            val large = small + ByteArray(Reassembly.FRAME_SMALL_MAX - small.size + 1)

            // Act
            floods.received(a, large, NOW)
            val nothing = waiting()
            floods.received(a, small + ByteArray(Reassembly.FRAME_SMALL_MAX - small.size), NOW)

            // Assert
            assertEquals(listOf(a, b, c).associateWith { emptyList<ByteArray>() }, nothing)
            assertEquals(listOf(0, 1, 1), waiting().values.map { it.size })
        }

    @Test
    fun anOriginsOwnBroadcastComingBackIsDropped() =
        runTest {
            // Arrange
            val floods = floods()
            floods.originate(Parcel(messageId(1), relayed = false, { ByteArray(0) }) {}, NOW + LIFETIME)
            waiting()

            // Act
            floods.received(a, broadcast(), NOW + LIFETIME + SKEW)

            // Assert
            assertEquals(emptyList(), delivered)
            assertEquals(listOf(a, b, c).associateWith { emptyList<ByteArray>() }, waiting())
        }

    @Test
    fun anOriginatedBroadcastGoesToTheLinksUpThenAndNotToALaterLink() =
        runTest {
            // Arrange
            val floods = floods()
            val d = nodeId(4)

            // Act
            floods.originate(Parcel(messageId(1), relayed = false, { ByteArray(0) }) {}, NOW + LIFETIME)
            lanes.open(d)
            floods.linked(d, NOW * MILLIS_PER_SECOND)

            // Assert
            assertEquals(listOf(1, 1, 1), waiting().values.map { it.size })
            assertEquals(emptyList(), lanes.drain(d))
        }

    @Test
    fun aDuplicateBroadcastAnswersWithPruneAndADuplicateOfAnotherFloodTypeDoesNot() =
        runTest {
            // Arrange
            val floods = floods(density = DensityClass.Medium)
            val other = header(type = UNKNOWN_TYPE, id = 2)
            floods.received(a, broadcast(), NOW)
            floods.received(a, other, NOW)
            waiting()

            // Act
            floods.received(c, other, NOW)
            val none = waiting()
            floods.received(b, broadcast(), NOW)

            // Assert
            val prune = LinkFrame.Prune.encode().toList()
            assertEquals(listOf(0, 0, 0), none.values.map { it.size })
            val pruned = waiting().values.map { it.map(ByteArray::toList) }
            assertEquals(listOf(emptyList(), listOf(prune), emptyList()), pruned)
        }

    @Test
    fun anIhaveGraftsOnlyTheIdsNotSeenAndAnArrivalEndsAPendingGraft() =
        runTest {
            // Arrange
            val floods = floods(density = DensityClass.Medium)
            floods.received(a, broadcast(id = 1), NOW)
            waiting()
            val ihave = LinkFrame.Ihave(listOf(messageId(1), messageId(2), messageId(3)))

            // Act
            floods.plumtree(b, ihave, NOW * MILLIS_PER_SECOND)
            floods.received(c, broadcast(id = 3), NOW)
            waiting()
            advanceTimeBy(Plumtree.GRAFT_BASE_MILLIS + 1)

            // Assert
            val graft = LinkFrame.Graft(messageId(2)).encode().toList()
            assertEquals(listOf(graft), lanes.drain(b).map { it.render().toList() })
        }

    @Test
    fun aGraftIsAnsweredAndAPruneOrUnlinkSetsTheLinkLazyOrEager() =
        runTest {
            // Arrange
            val floods = floods(density = DensityClass.Medium)
            floods.received(a, broadcast(id = 1), NOW)
            waiting()

            // Act
            floods.plumtree(b, LinkFrame.Prune, NOW * MILLIS_PER_SECOND)
            floods.plumtree(c, LinkFrame.Prune, NOW * MILLIS_PER_SECOND)
            floods.unlinked(c)
            floods.plumtree(a, LinkFrame.Graft(messageId(1)), NOW * MILLIS_PER_SECOND)
            val answer = waiting()
            floods.received(a, broadcast(id = 2), NOW)

            // Assert
            assertEquals(listOf(1, 0, 0), answer.values.map { it.size })
            val sizes = waiting().filterKeys { it != a }.mapValues { (_, frames) -> frames.single().size }
            assertEquals(LinkFrame.Ihave(listOf(messageId(2))).encode().size, sizes.getValue(b))
            assertEquals(broadcast().size, sizes.getValue(c))
        }

    @Test
    fun anOriginatedBroadcastAnnouncesItselfOnLazyLinksAndAnswersTheirGraft() =
        runTest {
            // Arrange
            val floods = floods(density = DensityClass.Medium)
            floods.plumtree(b, LinkFrame.Prune, NOW * MILLIS_PER_SECOND)
            val own = Parcel(messageId(1), relayed = false, { broadcast() }) {}

            // Act
            floods.originate(own, NOW + LIFETIME)
            val first = waiting()
            floods.plumtree(b, LinkFrame.Graft(messageId(1)), NOW * MILLIS_PER_SECOND)

            // Assert
            val ihave = LinkFrame.Ihave(listOf(messageId(1))).encode().toList()
            assertEquals(listOf(ihave), first.getValue(b).map { it.toList() })
            assertEquals(listOf(broadcast().toList()), lanes.drain(b).map { it.render().toList() })
        }

    @Test
    fun theCatchUpOffersTheHeldBroadcastsThenThoseOfTheLastMinuteOldestFirstAndListsTheHeldUnicasts() =
        runTest {
            // Arrange: broadcast 1 is held but out of the tree's last minute; 3 is this node's own.
            val floods = floods()
            floods.received(a, broadcast(id = 1), NOW)
            advanceTimeBy(Plumtree.HOLD_MILLIS / 2)
            floods.received(a, broadcast(id = 2), NOW + HALF_MINUTE)
            floods.originate(Parcel(messageId(3), relayed = false, { broadcast(id = 3) }) {}, NOW + LIFETIME)
            floods.holding.hold(HeldFrame(a, messageId(9), tag(9), NOW + LIFETIME, ByteArray(10)), 5, wallMillis())
            advanceTimeBy(Plumtree.HOLD_MILLIS / 2)

            // Act
            val catchUp = floods.linked(b, wallMillis())

            // Assert
            assertEquals(listOf(1, 2, 3).map { messageId(it).toList() }, catchUp.offered.map { it.toList() })
            val held = catchUp.held.map { it.messageId.toList() to it.destinationTag.toList() }
            assertEquals(listOf(messageId(9).toList() to tag(9).toList()), held)
            assertEquals(0, catchUp.tombstones.size)
        }

    @Test
    fun aCatchUpsTombstoneDeletesTheHeldCopyItOpensAndLaterCatchUpsOfferIt() =
        runTest {
            // Arrange
            val floods = floods()
            val k = ByteArray(16) { 7 }
            val copy = HeldFrame(a, messageId(9), tag(9), NOW + LIFETIME, ByteArray(10), tombstoneCommitment(k))
            floods.holding.hold(copy, 5, wallMillis())

            // Act
            val unsolicited = LinkFrame.CatchUp(emptyList(), emptyList(), listOf(Tombstone(messageId(9), k)))
            floods.plumtree(b, unsolicited, wallMillis())
            val catchUp = floods.linked(c, wallMillis())

            // Assert
            assertEquals(emptyList(), catchUp.held)
            assertEquals(listOf(messageId(9).toList()), catchUp.tombstones.map { it.messageId.toList() })
        }

    @Test
    fun aRelayedBroadcastIsHeldAsRelayedAndAnotherFloodTypeIsNot() =
        runTest {
            // Arrange
            val floods = floods()
            val frame = broadcast(hopLimit = 5)

            // Act
            floods.received(a, frame, NOW)
            floods.received(a, header(type = UNKNOWN_TYPE, id = 2), NOW)

            // Assert
            val held = floods.holding.broadcasts(NOW * MILLIS_PER_SECOND).single()
            assertContentEquals(frame.copyOf().also { it[1] = 4 }, held.bytes)
            assertEquals(a, held.from)
        }

    @Test
    fun aRelayedBroadcastAndItsHeldCopyAreBroadcastsForBroadcastPriorityAndAnotherFloodTypeIsNot() =
        runTest {
            // Arrange
            val floods = floods()

            // Act
            floods.received(a, broadcast(id = 1), NOW)
            floods.received(a, header(type = UNKNOWN_TYPE, id = 2, hopLimit = 5) + byteArrayOf(9, 9), NOW)

            // Assert
            assertEquals(listOf(Parcel.Kind.BROADCAST, Parcel.Kind.OTHER), lanes.drain(b).map { it.kind })
            assertEquals(Parcel.Kind.BROADCAST, floods.holding.copy(messageId(1), wallMillis())?.parcel()?.kind)
        }

    @Test
    fun aGraftForAFrameHeldButNotInTheTreeIsAnsweredFromTheHold() =
        runTest {
            // Arrange: broadcast 1 left the tree's last minute; unicast 9 was never in it.
            val floods = floods()
            floods.received(a, broadcast(id = 1), NOW)
            val unicast = HeldFrame(a, messageId(9), tag(9), NOW + LIFETIME, ByteArray(10) { 9 })
            floods.holding.hold(unicast, 5, wallMillis())
            advanceTimeBy(Plumtree.HOLD_MILLIS)
            waiting()

            // Act
            listOf(1, 9, 8).forEach { floods.plumtree(b, LinkFrame.Graft(messageId(it)), wallMillis()) }

            // Assert
            val relayed = broadcast(id = 1).also { it[1] = 4 }.toList()
            assertEquals(listOf(relayed, unicast.bytes.toList()), lanes.drain(b).map { it.render().toList() })
        }

    @Test
    fun withHoldingOffARelayStillFloodsABroadcastButHoldsNothing() =
        runTest {
            // Arrange
            val floods = floods(holds = false)
            val unicast = HeldFrame(a, messageId(9), tag(9), NOW + LIFETIME, ByteArray(10) { 9 })

            // Act
            floods.received(a, broadcast(id = 1, hopLimit = 5), NOW)
            val heldUnicast = floods.holding.hold(unicast, 5, wallMillis())

            // Assert
            val relayed = broadcast(id = 1, hopLimit = 4).toList()
            assertEquals(listOf(relayed), lanes.drain(b).map { it.render().toList() })
            assertEquals(listOf(relayed), lanes.drain(c).map { it.render().toList() })
            assertEquals(false, heldUnicast)
            assertEquals(emptyList(), floods.holding.broadcasts(wallMillis()) + floods.holding.unicasts(wallMillis()))
        }

    private fun TestScope.wallMillis() = SchedulerClock(testScheduler).wallClockMillis()

    @Test
    fun aCatchUpGraftsTheOfferedIdsNotSeenFromItsSender() =
        runTest {
            // Arrange
            val floods = floods()
            floods.received(a, broadcast(id = 1), NOW)
            waiting()
            val catchUp = LinkFrame.CatchUp(listOf(messageId(1), messageId(2), messageId(3)), emptyList(), emptyList())

            // Act
            floods.plumtree(b, catchUp, NOW * MILLIS_PER_SECOND)
            advanceTimeBy(Plumtree.GRAFT_BASE_MILLIS + 1)

            // Assert
            val grafts = listOf(2, 3).map { LinkFrame.Graft(messageId(it)).encode().toList() }
            assertEquals(grafts, lanes.drain(b).map { it.render().toList() })
            assertEquals(listOf(0, 0), listOf(a, c).map { lanes.drain(it).size })
        }

    @Test
    fun aCatchUpOfTheMostUnknownIdsCostsAGraftEach() =
        runTest {
            // Arrange
            val floods = floods()
            val offered = List(LinkFrame.CatchUp.OFFERED_MAX) { messageId(it + 1) }

            // Act
            floods.plumtree(b, LinkFrame.CatchUp(offered, emptyList(), emptyList()), NOW * MILLIS_PER_SECOND)
            advanceTimeBy(Plumtree.GRAFT_BASE_MILLIS + 1)

            // Assert
            val grafts = offered.map { LinkFrame.Graft(it).encode().toList() }
            assertEquals(grafts, lanes.drain(b).map { it.render().toList() })
        }

    private fun header(
        type: Int = BroadcastFrame.TYPE,
        hopLimit: Int = 5,
        id: Int = 1,
        expiry: Long = NOW + LIFETIME,
    ): ByteArray {
        val bytes = FloodHeader(maxOf(hopLimit, 1), messageId(id), expiry.toUInt()).encode(type)
        return bytes.also { it[1] = hopLimit.toByte() }
    }

    private fun broadcast(
        hopLimit: Int = 5,
        id: Int = 1,
        expiry: Long = NOW + LIFETIME,
        container: Container = Container.Unsecured(SENDER, "hello".encodeToByteArray()),
    ): ByteArray = header(BroadcastFrame.TYPE, hopLimit, id, expiry) + container.encode()

    private fun signed(): ByteArray {
        val header = FloodHeader(5, messageId(1), (NOW + LIFETIME).toUInt())
        return BroadcastFrame.sign(SodiumCryptoProvider, header, identityKey(2), "hello".encodeToByteArray()).encode()
    }

    private fun ReceivedMessage.text() = payload.decodeToString()

    private companion object {
        const val NOW = 1_767_225_600L
        const val MILLIS_PER_SECOND = 1_000L
        const val LIFETIME = 300L
        const val HALF_MINUTE = 30L
        const val SKEW = 30L
        const val LIFETIME_MAX = 3_600L
        const val PAYLOAD_MAX = 1_024
        const val UNKNOWN_TYPE = 0xbf
        val SENDER = ByteArray(32) { 7 }
    }
}
