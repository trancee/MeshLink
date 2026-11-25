package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Route discovery through Router, on lanes to three linked peers; this node's tag is OWN.
@OptIn(ExperimentalCoroutinesApi::class)
class RouteDiscoveryTest {
    private val a = nodeId(1)
    private val b = nodeId(2)
    private val c = nodeId(3)
    private val lanes = Lanes().apply { listOf(a, b, c).forEach { open(it) } }

    private fun TestScope.router(endpoint: Endpoint = FakeEndpoint(OWN)) =
        Router(
            SchedulerClock(testScheduler),
            lanes,
            Routes(),
            ::sevens,
            endpoint,
            Handoff(Floods(lanes, broadcastCodec(), lowTree(lanes), ::tombstoneCommitment) {}),
        )

    private fun sevens(size: Int) = ByteArray(size) { 7 }

    private fun TestScope.nowSeconds() = SchedulerClock(testScheduler).wallClockMillis() / 1_000

    private fun TestScope.request(
        target: ByteArray = TARGET,
        id: Int = 1,
        hopLimit: Int = 5,
        originSequence: UInt = 4u,
        targetSequence: UInt = 0u,
        hopCount: Int = 2,
        expiry: Long = nowSeconds() + 60,
    ) = RouteRequest(
        FloodHeader(hopLimit, messageId(id), expiry.toUInt()),
        target,
        ORIGIN,
        originSequence,
        targetSequence,
        hopCount,
    ).encode()

    private fun TestScope.reply(
        destination: ByteArray = OWN,
        id: Int = 9,
        targetSequence: UInt = 5u,
        hopCount: Int = 0,
    ) = RouteReply(
        UnicastHeader(5, messageId(id), (nowSeconds() + 60).toUInt(), destination, TARGET),
        TARGET,
        targetSequence,
        hopCount,
    ).encode()

    // A message of [id] whose frame is its hop limit; returns the route's hop count.
    private fun Router.send(
        id: Int,
        tag: ByteArray,
        search: Boolean = false,
        density: DensityClass = DensityClass.MEDIUM,
        origin: ByteArray? = OWN,
    ) = originate(tag, origin, Reach.of(density), search) { hopLimit ->
        Parcel(messageId(id), relayed = false, { byteArrayOf(hopLimit.toByte()) }) {}
    }

    private suspend fun waiting() = listOf(a, b, c).associateWith { peer -> lanes.drain(peer).map { it.render() } }

    private fun <T> valid(decoded: Decoded<T>) = assertIs<Decoded.Valid<T>>(decoded).value

    private fun decodedRequest(bytes: ByteArray) = valid(RouteRequest.decode(bytes))

    private fun decodedReply(bytes: ByteArray) = valid(RouteReply.decode(bytes))

    // The peers whose lanes got exactly one frame, a one-byte message of hop limit 255.
    private suspend fun routedTo(): List<NodeId> =
        waiting().filterValues { it.singleOrNull()?.contentEquals(byteArrayOf(-1)) == true }.keys.toList()

    @Test
    fun aRequestFloodsOnToEveryOtherLinkWithOneHopLessAndOneMoreAndTeachesTheWayBackToItsOrigin() =
        runTest {
            // Arrange
            val endpoint = FakeEndpoint(OWN)
            val router = router(endpoint)

            // Act
            router.received(a, request(hopLimit = 2, hopCount = 2))

            // Assert
            val waiting = waiting()
            assertEquals(emptyList(), waiting.getValue(a))
            listOf(b, c).forEach { peer ->
                val relayed = decodedRequest(waiting.getValue(peer).single())
                assertEquals(listOf(1, 3), listOf(relayed.header.hopLimit, relayed.hopCount))
                assertContentEquals(ORIGIN, relayed.originTag)
            }
            assertContentEquals(ORIGIN, endpoint.found.single())
            assertEquals(3, router.send(2, ORIGIN))
            assertEquals(listOf(a), routedTo())
        }

    @Test
    fun theTargetAnswersOnTheRequestsLinkWithANewSequenceAndDoesNotFloodIt() =
        runTest {
            // Arrange
            val router = router()

            // Act
            router.received(a, request(target = OWN, id = 1))
            router.received(b, request(target = OWN, id = 2))

            // Assert
            val waiting = waiting()
            assertEquals(emptyList(), waiting.getValue(c))
            val (first, second) = listOf(a, b).map { decodedReply(waiting.getValue(it).single()) }
            assertEquals(listOf(2u, 3u), listOf(first.targetSequence, second.targetSequence))
            assertEquals(0, first.hopCount)
            assertContentEquals(OWN, first.targetTag)
            assertContentEquals(OWN, first.header.sourceTag)
            assertContentEquals(ORIGIN, first.header.destinationTag)
            assertEquals(255, first.header.hopLimit)
            assertEquals((nowSeconds() + 120).toUInt(), first.header.expiry)
            assertContentEquals(ByteArray(12) { 7 }, first.header.messageId)
        }

    @Test
    fun aNodeWithAFreshEnoughRouteAnswersForTheTargetWithThatRoute() =
        runTest {
            // Arrange: a reply from b found the target 1 hop away with sequence 5.
            val router = router()
            router.received(b, reply(targetSequence = 5u))
            waiting()

            // Act
            router.received(a, request(id = 1, targetSequence = 5u))
            router.received(c, request(id = 2, targetSequence = 0u))

            // Assert
            val waiting = waiting()
            assertEquals(emptyList(), waiting.getValue(b))
            listOf(a, c).forEach { peer ->
                val answer = decodedReply(waiting.getValue(peer).single())
                assertEquals(listOf(5u, 1u), listOf(answer.targetSequence, answer.hopCount.toUInt()))
                assertContentEquals(OWN, answer.header.sourceTag)
                assertContentEquals(TARGET, answer.targetTag)
            }
        }

    @Test
    fun anUnknownTargetSequenceTakesARouteOfAnySequence() =
        runTest {
            // Arrange: past half the sequence space, 0 is not older than the route's sequence.
            val router = router()
            router.received(b, reply(targetSequence = 0x8000_0001u))
            waiting()

            // Act
            router.received(a, request(targetSequence = 0u))

            // Assert
            assertEquals(0x8000_0001u, decodedReply(waiting().getValue(a).single()).targetSequence)
        }

    @Test
    fun aNodeAskedForANewerSequenceThroughItsRoutesLinkOrWithoutATagFloodsTheRequestOn() =
        runTest {
            // Arrange: each router knows the target through b with sequence 5.
            val newer = router()
            val secured = router(FakeEndpoint(null))
            listOf(newer, secured).forEach { it.received(b, reply(destination = OWN, targetSequence = 5u)) }
            val known = router()
            known.received(b, reply(targetSequence = 5u))
            waiting()

            // Act
            newer.received(a, request(id = 1, targetSequence = 6u))
            known.received(b, request(id = 2, targetSequence = 5u))
            secured.received(a, request(id = 3, targetSequence = 5u))

            // Assert: no replies, only flooded requests.
            val frames = waiting().values.flatten()
            assertEquals(6, frames.size)
            assertTrue(frames.all { RouteRequest.decode(it) is Decoded.Valid })
        }

    @Test
    fun aSeenUntimelyOrUncountableRequestChangesNothingAndASpentOneIsNotFloodedOn() =
        runTest {
            // Arrange
            val endpoint = FakeEndpoint(OWN)
            val router = router(endpoint)
            router.received(a, request(id = 1))
            endpoint.found.clear()
            waiting()
            val drops =
                listOf(
                    request(id = 1),
                    request(id = 2, expiry = nowSeconds() - 31),
                    request(id = 3, expiry = nowSeconds() + 3_631),
                    request(id = 4, hopCount = 255),
                    request(id = 5).also { it.fill(0, 34, 38) },
                    request(id = 6).copyOf(RouteRequest.LENGTH - 1),
                    request(id = 7).copyOf(RouteRequest.LENGTH + 1),
                    byteArrayOf(),
                )

            // Act
            drops.forEach { router.received(c, it) }
            router.received(b, request(id = 8, hopLimit = 1, originSequence = 5u))

            // Assert: only the spent request teaches the way back, now through b.
            assertTrue(waiting().values.all { it.isEmpty() })
            assertEquals(1, endpoint.found.size)
            router.send(9, ORIGIN)
            assertEquals(listOf(b), routedTo())
        }

    @Test
    fun aRequestIsRememberedThroughTheLastSecondItIsTimely() =
        runTest {
            // Arrange
            val router = router()
            val frame = request(id = 1, expiry = nowSeconds() + 1)
            router.received(a, frame)
            waiting()

            // Act: the last millisecond of expiry plus clock skew.
            advanceTimeBy(31_999)
            router.received(b, frame)

            // Assert
            assertTrue(waiting().values.all { it.isEmpty() })
        }

    @Test
    fun aRouteWithoutASequenceAnswersNoRequest() =
        runTest {
            // Arrange: a message from the target through b taught a reverse route without a sequence.
            val router = router()
            val body = messageBody(Container.Unsecured(ByteArray(32), byteArrayOf()))
            router.received(b, unicast(MessageFrame.TYPE, 5, messageId(20), nowSeconds() + 60, tag(0x33), TARGET, body))
            waiting()

            // Act
            router.received(a, request(id = 1))

            // Assert: the request floods on.
            val waiting = waiting()
            assertEquals(emptyList(), waiting.getValue(a))
            listOf(b, c).forEach { decodedRequest(waiting.getValue(it).single()) }
        }

    @Test
    fun aRequestWithAnOlderOriginSequenceDoesNotReplaceTheWayBack() =
        runTest {
            // Arrange
            val endpoint = FakeEndpoint(OWN)
            val router = router(endpoint)
            router.received(a, request(id = 1, originSequence = 4u))
            waiting()

            // Act
            router.received(b, request(id = 2, originSequence = 3u))

            // Assert
            assertEquals(1, endpoint.found.size)
            waiting()
            router.send(3, ORIGIN)
            assertEquals(listOf(a), routedTo())
        }

    @Test
    fun aReplyForThisNodeTeachesTheRouteToItsTargetOneHopMore() =
        runTest {
            // Arrange
            val endpoint = FakeEndpoint(OWN)
            val router = router(endpoint)

            // Act
            router.received(b, reply(hopCount = 2))
            router.received(c, reply(id = 10).also { it.fill(0, 42, 46) })
            router.received(c, reply(id = 11, targetSequence = 4u))

            // Assert
            assertContentEquals(TARGET, endpoint.found.single())
            assertEquals(3, router.send(1, TARGET))
            assertEquals(listOf(b), routedTo())
        }

    @Test
    fun aRelayedReplyCountsTheHopAndTeachesTheRouteToItsTarget() =
        runTest {
            // Arrange
            val endpoint = FakeEndpoint(OWN)
            val router = router(endpoint)

            // Act
            router.received(b, reply(destination = ORIGIN, hopCount = 2))
            router.received(b, reply(destination = ORIGIN, id = 10, hopCount = 255))

            // Assert
            val waiting = waiting()
            assertEquals(emptyList(), waiting.getValue(b))
            listOf(a, c).forEach { peer ->
                val relayed = decodedReply(waiting.getValue(peer).single())
                assertEquals(listOf(4, 3), listOf(relayed.header.hopLimit, relayed.hopCount))
            }
            assertContentEquals(TARGET, endpoint.found.single())
            assertEquals(3, router.send(1, TARGET))
            assertEquals(listOf(b), routedTo())
        }

    @Test
    fun anOriginWithoutARouteOrSearchingAgainSendsARouteRequestFromTheTagItIsGiven() =
        runTest {
            // Arrange: a reply from b found the target with sequence 5.
            val router = router(FakeEndpoint(null))
            router.received(b, reply(destination = ORIGIN, targetSequence = 5u))
            waiting()

            // Act
            val hops = router.send(1, TARGET, search = true)
            val lows = router.send(2, ORIGIN, density = DensityClass.LOW)

            // Assert
            assertNull(hops)
            assertNull(lows)
            val waiting = waiting()
            listOf(a, b, c).forEach { peer ->
                val frames = waiting.getValue(peer)
                assertEquals(listOf(3, 255), frames.drop(1).map { it[0].toInt() and 0xFF })
                val sent = decodedRequest(frames.first())
                assertEquals(listOf(64, 0), listOf(sent.header.hopLimit, sent.hopCount))
                assertEquals(listOf(2u, 5u), listOf(sent.originSequence, sent.targetSequence))
                assertEquals((nowSeconds() + 120).toUInt(), sent.header.expiry)
                assertContentEquals(OWN, sent.originTag)
                assertContentEquals(TARGET, sent.targetTag)
            }
        }

    @Test
    fun anOriginWithoutATagSendsNoRouteRequest() =
        runTest {
            // Arrange
            val router = router()

            // Act
            router.send(1, TARGET, origin = null)

            // Assert
            assertEquals(listOf(3, 3, 3), waiting().values.map { it.single()[0].toInt() })
        }

    @Test
    fun anOriginsOwnRequestComingBackIsDropped() =
        runTest {
            // Arrange
            val router = router()
            router.send(1, TARGET)
            val sent = waiting().getValue(a).first()

            // Act
            router.received(a, sent.copyOf().also { it[1] = 5 })

            // Assert
            assertTrue(waiting().values.all { it.isEmpty() })
        }

    @Test
    fun aMalformedReplyForThisNodeOrToRelayIsDroppedAndTeachesNothing() =
        runTest {
            // Arrange
            val endpoint = FakeEndpoint(OWN)
            val router = router(endpoint)
            val forThisNode = reply().let { it.copyOf(it.size - 1) }
            val toRelay = reply(destination = ORIGIN, id = 10).let { it.copyOf(it.size + 1) }

            // Act
            router.received(b, forThisNode)
            router.received(b, toRelay)

            // Assert
            assertTrue(waiting().values.all { it.isEmpty() })
            assertEquals(emptyList(), endpoint.found)
            assertNull(router.send(1, TARGET))
        }

    @Test
    fun anUnsecuredNodeAnswersARequestForItsCurrentTagAndSearchesFromIt() =
        runTest {
            // Arrange
            val nodeId = ByteArray(32) { 3 }
            val end = UnsecuredEnd(SodiumCryptoProvider, SchedulerClock(testScheduler), Inbox(), receipts(), {}) {}
            end.begin(nodeId)
            val router = router(end)
            val own = Derivations(SodiumCryptoProvider).unsecuredTag(nodeId, Derivations.epoch(nowSeconds()))

            // Act
            router.received(a, request(target = own))
            val answered = waiting().getValue(a).single()
            router.send(2, TARGET, origin = end.tag())

            // Assert
            assertContentEquals(own, decodedReply(answered).targetTag)
            assertContentEquals(own, decodedRequest(waiting().getValue(b).first()).originTag)
        }

    @Test
    fun aSecuredNodeAnswersARequestForItsPublicTagButFloodsOnItsUnsecuredTagOrARouteItKnows() =
        runTest {
            // Arrange: a secured node whose route to TARGET a relayed reply from b taught.
            val clock = SchedulerClock(testScheduler)
            val lookups =
                Lookups(SodiumCryptoProvider, clock, backgroundScope, WireProfile.CLASSICAL, lanes) {
                    DensityClass.MEDIUM
                }
            val receipts = Receipts<Reply>(clock, backgroundScope, { _, _, _ -> }, { _, _ -> }, reads())
            val end = SecuredEnd(SodiumCryptoProvider, clock, Inbox(), lookups, receipts, SecuredHooks({}, {}, {}) {})
            val keys = securedKeys(clock, seed = 3)
            end.keys.certify(keys)
            val router = router(end)
            router.received(b, reply(destination = ORIGIN))
            waiting()
            val epoch = Derivations.epoch(nowSeconds())
            val public = EndToEndTags(Derivations(SodiumCryptoProvider)).publicTag(keys.static.publicKey, epoch)
            val unsecured = Derivations(SodiumCryptoProvider).unsecuredTag(keys.nodeId, epoch)

            // Act
            router.received(a, request(target = public, id = 1))
            val answered = waiting().getValue(a).single()
            router.received(a, request(target = unsecured, id = 2))
            router.received(a, request(id = 3, targetSequence = 5u))

            // Assert
            assertContentEquals(public, decodedReply(answered).targetTag)
            val waiting = waiting()
            assertEquals(emptyList(), waiting.getValue(a))
            listOf(b, c).forEach { peer ->
                val relayed = waiting.getValue(peer).map(::decodedRequest)
                assertEquals(listOf(unsecured.toList(), TARGET.toList()), relayed.map { it.targetTag.toList() })
            }
        }

    private companion object {
        val OWN = tag(0x55)
        val ORIGIN = tag(0x0a)
        val TARGET = tag(0x0b)
    }
}
