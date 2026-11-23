package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.ReceivedMessage
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Router's forwarding and origin rules, on lanes to three linked peers.
@OptIn(ExperimentalCoroutinesApi::class)
class RouterTest {
    private val a = nodeId(1)
    private val b = nodeId(2)
    private val c = nodeId(3)
    private val lanes = Lanes().apply { listOf(a, b, c).forEach { open(it) } }
    private val endpoint = FakeEndpoint(OWN)
    private val delivered = endpoint.delivered
    private val broadcasts = mutableListOf<ReceivedMessage>()
    private val floods = Floods(lanes, broadcastCodec(), lowTree(lanes)) { broadcasts += it }

    private var lookups: Lookups? = null

    private fun TestScope.router(kinds: Set<Int> = setOf(Container.UNSECURED)): Router {
        endpoint.kinds = kinds
        return Router(SchedulerClock(testScheduler), lanes, Routes(), { ByteArray(it) }, endpoint, Handoff(floods))
    }

    // A secured node's router, with its lookups.
    private fun TestScope.securedRouter(): Router {
        val clock = SchedulerClock(testScheduler)
        val secured =
            Lookups(SodiumCryptoProvider, clock, backgroundScope, WireProfile.CLASSICAL, lanes) {
                DensityClass.MEDIUM
            }.also { it.begin() }
        lookups = secured
        return Router(clock, lanes, Routes(), { ByteArray(it) }, endpoint, Handoff(floods, secured))
    }

    private fun TestScope.lookupRequest(
        salt: ByteArray = SALT,
        key: ByteArray = SodiumCryptoProvider.x25519KeyPair().publicKey,
    ) = LookupRequest(FloodHeader(5, messageId(20), (nowSeconds() + 60).toUInt()), salt, tag(3), key).encode()

    private fun TestScope.lookupReply(
        id: Int,
        salt: ByteArray = SALT,
        key: ByteArray = ByteArray(32) { 9 },
        sealed: ByteArray = ByteArray(LookupReply.CLASSICAL_LENGTH - 68),
    ) = LookupReply(UnicastHeader(5, messageId(id), (nowSeconds() + 60).toUInt(), salt, tag(id)), key, sealed).encode()

    private fun TestScope.nowSeconds() = SchedulerClock(testScheduler).wallClockMillis() / 1_000

    private fun TestScope.message(
        hopLimit: Int = 5,
        id: Int = 1,
        destination: ByteArray = tag(9),
        source: ByteArray = tag(8),
        container: Container = Container.Unsecured(ByteArray(32), byteArrayOf()),
    ) = unicast(
        MessageFrame.TYPE,
        hopLimit,
        messageId(id),
        nowSeconds() + 60,
        destination,
        source,
        messageBody(container),
    )

    // Originates message [id] whose frame is its hop limit.
    private fun Router.send(
        id: Int,
        tag: ByteArray,
        density: DensityClass,
        search: Boolean = false,
    ) = originate(tag, density, search) { Parcel(messageId(id), relayed = false, { byteArrayOf(it.toByte()) }) {} }

    private suspend fun waiting() = listOf(a, b, c).associateWith { peer -> lanes.drain(peer).map { it.render() } }

    @Test
    fun aSmallFrameWithoutARouteFloodsToEveryOtherLinkWithOneHopLess() =
        runTest {
            // Arrange
            val router = router()
            // Hop limit 2 is the lowest that is still forwarded.
            val frame = message(hopLimit = 2)

            // Act
            router.received(a, frame)

            // Assert
            val relayed = frame.copyOf().also { it[1] = 1 }
            val waiting = waiting()
            assertEquals(emptyList(), waiting.getValue(a))
            listOf(b, c).forEach { assertContentEquals(relayed, waiting.getValue(it).single()) }
        }

    @Test
    fun aFrameOnAnActiveRouteGoesOnThatLinkOnly() =
        runTest {
            // Arrange
            val router = router()
            router.received(b, message(id = 1, source = tag(9), destination = tag(7)))
            waiting()

            // Act
            router.received(a, message(id = 2, destination = tag(9)))

            // Assert
            val waiting = waiting()
            assertEquals(1, waiting.getValue(b).size)
            assertEquals(emptyList(), waiting.getValue(c))
        }

    @Test
    fun aReverseRouteExpiresAfterSixSeconds() =
        runTest {
            // Arrange
            val router = router()
            router.received(b, message(id = 1, source = tag(9), destination = tag(7)))
            waiting()
            advanceTimeBy(Routes.REVERSE_MILLIS)

            // Act
            router.received(a, message(id = 2, destination = tag(9)))

            // Assert
            val waiting = waiting()
            assertEquals(1, waiting.getValue(b).size)
            assertEquals(1, waiting.getValue(c).size)
        }

    @Test
    fun aMessageIdForwardedWithinTheSeenWindowIsDroppedAndPassesAfterIt() =
        runTest {
            // Arrange
            val router = router()
            router.received(a, message())
            waiting()
            advanceTimeBy(Router.SEEN_WINDOW_MILLIS - 1)

            // Act
            router.received(c, message())
            val within = waiting()
            advanceTimeBy(1)
            router.received(c, message())

            // Assert
            assertTrue(within.values.all { it.isEmpty() })
            assertEquals(1, waiting().getValue(a).size)
        }

    @Test
    fun aDuplicateCopyWithinTheSeenWindowTeachesNoReverseRoute() =
        runTest {
            // Arrange: the first copy came from A; a later copy over a longer path comes from B.
            val router = router()
            router.received(a, message(id = 1, source = tag(9), destination = tag(7)))
            router.received(b, message(id = 1, source = tag(9), destination = tag(7)))
            waiting()

            // Act
            router.received(c, message(id = 2, destination = tag(9)))

            // Assert
            assertEquals(listOf(1, 0, 0), waiting().values.map { it.size })
        }

    @Test
    fun aFrameWhoseNextHopLimitWouldBeZeroIsNotForwardedButTeachesItsRoute() =
        runTest {
            // Arrange
            val router = router()

            // Act
            router.received(a, message(hopLimit = 1, source = tag(4)))

            // Assert
            assertTrue(waiting().values.all { it.isEmpty() })
            router.received(c, message(id = 2, destination = tag(4)))
            assertEquals(listOf(1, 0, 0), waiting().values.map { it.size })
        }

    @Test
    fun anUntimelyMalformedOrDisallowedFrameIsDroppedWithoutStateChange() =
        runTest {
            // Arrange
            val router = router()
            val now = nowSeconds()
            val drops =
                listOf(
                    message(hopLimit = 1).also { it[1] = 0 },
                    unicast(MessageFrame.TYPE, 5, messageId(1), now - 31, tag(9), tag(8), messageBody(UNSECURED)),
                    unicast(MessageFrame.TYPE, 5, messageId(1), now + 3_631, tag(9), tag(8), messageBody(UNSECURED)),
                    message().copyOf(UnicastHeader.LENGTH - 1),
                    message().copyOf(UnicastHeader.LENGTH + 1),
                    message(container = TRANSPORT),
                    message().also { it[0] = FLOOD_TYPE },
                )

            // Act
            drops.forEach { router.received(a, it) }

            // Assert
            assertTrue(waiting().values.all { it.isEmpty() })
            router.received(c, message(id = 2, destination = tag(8), source = tag(5)))
            router.received(a, message())
            assertEquals(listOf(1, 2, 1), waiting().values.map { it.size }, "no route to tag 8, no message_id seen")
        }

    @Test
    fun theTimelinessBoundsIncludeTheirEdges() =
        runTest {
            // Arrange
            val router = router()
            val now = nowSeconds()

            // Act
            router.received(a, unicast(0x41, 5, messageId(1), now - 30, tag(9), tag(8)))
            router.received(a, unicast(0x41, 5, messageId(2), now + 3_630, tag(9), tag(8)))

            // Assert
            assertEquals(2, waiting().getValue(b).size)
        }

    @Test
    fun aUnicastTypeThisVersionDoesNotKnowIsRelayedByItsHeader() =
        runTest {
            // Arrange
            val router = router()

            // Act
            router.received(a, unicast(0x42, 5, messageId(1), nowSeconds(), tag(9), tag(8)))

            // Assert
            assertEquals(1, waiting().getValue(b).size)
        }

    @Test
    fun aSecuredMeshRelaysItsContainerKindsOnly() =
        runTest {
            // Arrange
            val router = router(kinds = setOf(Container.IK_INITIATOR, Container.TRANSPORT))

            // Act
            router.received(a, message(id = 1, container = TRANSPORT))
            router.received(a, message(id = 2, container = Container.IkInitiator(ByteArray(48))))
            router.received(a, message(id = 3, container = UNSECURED))

            // Assert
            assertEquals(2, waiting().getValue(b).size)
        }

    @Test
    fun aLargeFrameTravelsOnARouteOnly() =
        runTest {
            // Arrange
            val router = router()
            val large = ByteArray(Reassembly.FRAME_SMALL_MAX)
            val now = nowSeconds()

            // Act
            router.received(a, unicast(0x41, 5, messageId(1), now, tag(9), tag(8), large))
            val unrouted = waiting()
            router.received(b, message(id = 2, source = tag(9), destination = tag(7)))
            router.received(a, unicast(0x41, 5, messageId(3), now, tag(9), tag(8), large))

            // Assert
            assertTrue(unrouted.values.all { it.isEmpty() })
            assertEquals(1, waiting().getValue(b).size)
        }

    @Test
    fun aFrameCutInsideItsHeaderIsDropped() =
        runTest {
            // Arrange
            val router = router()
            val frame = message(destination = OWN)

            // Act
            router.received(a, frame.copyOf(UnicastHeader.LENGTH - 1))

            // Assert
            assertTrue(delivered.isEmpty())
            assertTrue(waiting().values.all { it.isEmpty() })
        }

    @Test
    fun aFrameOfTheSmallMaximumFloodsWithoutARoute() =
        runTest {
            // Arrange
            val router = router()
            val now = nowSeconds()
            val header = unicast(0x41, 5, messageId(1), now, tag(9), tag(8)).size
            val body = ByteArray(Reassembly.FRAME_SMALL_MAX - header)
            val frame = unicast(0x41, 5, messageId(1), now, tag(9), tag(8), body)

            // Act
            router.received(a, frame)

            // Assert
            assertEquals(Reassembly.FRAME_SMALL_MAX, frame.size)
            assertEquals(1, waiting().getValue(b).size)
        }

    @Test
    fun aMessageForThisNodeIsDeliveredOnceAndNeverForwarded() =
        runTest {
            // Arrange
            val router = router()
            val frame = message(destination = OWN)

            // Act
            router.received(a, frame)
            router.received(b, frame)

            // Assert
            assertEquals(1, delivered.size)
            assertContentEquals(frame, delivered.single())
            assertTrue(waiting().values.all { it.isEmpty() })
        }

    @Test
    fun aMessageThisNodeDidNotAcceptIsProcessedAgainAndOtherTypesForItAreNot() =
        runTest {
            // Arrange
            val router = router()
            endpoint.accepting = false
            router.received(a, message(destination = OWN))
            endpoint.accepting = true

            // Act
            router.received(a, message(destination = OWN))
            router.received(a, unicast(0x41, 5, messageId(2), nowSeconds(), OWN, tag(8)))

            // Assert
            assertEquals(2, delivered.size)
            assertTrue(waiting().values.all { it.isEmpty() })
        }

    @Test
    fun aDeliveredMessageIdIsRememberedThroughTheLastSecondItsFrameIsTimely() =
        runTest {
            // Arrange
            val router = router()
            val frame = message(destination = OWN)
            router.received(a, frame)

            // Act: the last millisecond of expiry plus clock skew.
            advanceTimeBy((60 + 30) * 1_000L + 999)
            router.received(a, frame)

            // Assert
            assertEquals(1, delivered.size)
        }

    @Test
    fun aLowClassOriginFloodsWithTheMaximumHopLimitEvenWithARoute() =
        runTest {
            // Arrange
            val router = router()
            router.received(b, message(id = 1, source = tag(9), destination = tag(7)))
            waiting()

            // Act
            router.send(2, tag(9), DensityClass.LOW)

            // Assert
            assertEquals(listOf(255, 255, 255), waiting().values.map { it.single()[0].toInt() and 0xFF })
        }

    @Test
    fun aMediumOrHighClassOriginUsesAnActiveRouteElseFloodsWithTheFloodRadius() =
        runTest {
            // Arrange
            val router = router()
            router.received(b, message(id = 1, source = tag(9), destination = tag(7)))
            waiting()

            // Act
            router.send(2, tag(9), DensityClass.MEDIUM)
            val routed = waiting()
            router.send(3, tag(6), DensityClass.HIGH)

            // Assert: without a route, a route request goes first on every link.
            val hopLimits = routed.values.map { frames -> frames.map { it[0].toInt() and 0xFF } }
            assertEquals(listOf(emptyList(), listOf(255), emptyList()), hopLimits)
            assertEquals(listOf(3, 3, 3), waiting().values.map { it.last()[0].toInt() })
        }

    @Test
    fun anOriginsOwnMessageIdComingBackWithinTheSeenWindowIsNotRelayed() =
        runTest {
            // Arrange
            val router = router()
            router.send(1, tag(9), DensityClass.LOW)
            waiting()

            // Act
            router.received(a, message(id = 1))

            // Assert
            assertTrue(waiting().values.all { it.isEmpty() })
        }

    @Test
    fun aFloodTypeOtherThanARouteRequestGoesToFloodForwarding() =
        runTest {
            // Arrange
            val router = router()
            val header = FloodHeader(5, messageId(1), (nowSeconds() + 60).toUInt())
            val frame = BroadcastFrame(header, Container.Unsecured(ByteArray(32), byteArrayOf(1))).encode()

            // Act
            router.received(a, frame)

            // Assert
            assertEquals(1, broadcasts.size)
            assertEquals(listOf(0, 1, 1), waiting().values.map { it.size })
        }

    @Test
    fun aClosedLinksRoutesGo() =
        runTest {
            // Arrange
            val router = router()
            router.received(b, message(id = 1, source = tag(9), destination = tag(7)))
            waiting()

            // Act
            router.unlinked(b)
            router.received(a, message(id = 2, destination = tag(9)))

            // Assert
            assertEquals(1, waiting().getValue(c).size)
        }

    @Test
    fun aSecuredNodeFloodsALookupRequestOnAndRoutesItsRepliesBackByTheSalt() =
        runTest {
            // Arrange
            val router = securedRouter()
            router.received(a, lookupRequest())
            val flooded = waiting()

            // Act
            router.received(b, lookupReply(id = 1))

            // Assert: the request left a reverse route keyed by its salt, which the reply took alone.
            assertEquals(listOf(0, 1, 1), flooded.values.map { it.size })
            assertEquals(listOf(1, 0, 0), waiting().values.map { it.size })
        }

    @Test
    fun onlyANewWellFormedLookupRequestLeavesAReverseRoute() =
        runTest {
            // Arrange
            val router = securedRouter()
            val request = lookupRequest()
            router.received(a, request)

            // Act
            router.received(b, request)
            router.received(c, request.copyOf(LookupRequest.LENGTH - 1))
            waiting()
            router.received(b, lookupReply(id = 1))

            // Assert: the reply took the route the first request left.
            assertEquals(listOf(1, 0, 0), waiting().values.map { it.size })
        }

    @Test
    fun anUnsecuredNodeRelaysLookupFramesByTheirHeaders() =
        runTest {
            // Arrange
            val router = router()
            router.received(a, lookupRequest())
            val flooded = waiting()

            // Act
            router.received(b, lookupReply(id = 1))

            // Assert: no reverse route by salt, so the small reply floods.
            assertEquals(listOf(0, 1, 1), flooded.values.map { it.size })
            assertEquals(listOf(1, 0, 1), waiting().values.map { it.size })
        }

    @Test
    fun aRelayPassesOnlyTheFirstLookupReplyPerSalt() =
        runTest {
            // Arrange
            val router = securedRouter()
            router.received(a, lookupRequest())
            waiting()
            router.received(b, lookupReply(id = 1))
            val first = waiting()

            // Act
            router.received(c, lookupReply(id = 2))

            // Assert
            assertEquals(listOf(1, 0, 0), first.values.map { it.size })
            assertTrue(waiting().values.all { it.isEmpty() })
        }

    @Test
    fun aLookupReplyWithoutARouteOrMalformedIsDropped() =
        runTest {
            // Arrange
            val router = securedRouter()
            router.received(a, lookupRequest())
            waiting()
            val malformed = lookupReply(id = 2).copyOf(LookupReply.CLASSICAL_LENGTH - 1)

            // Act
            router.received(b, lookupReply(id = 1, salt = tag(4)))
            router.received(b, malformed)

            // Assert
            assertTrue(waiting().values.all { it.isEmpty() })
        }

    @Test
    fun aReplyToThisNodesLookupIsTakenAndNotRelayed() =
        runTest {
            // Arrange: the reply's salt also has a route through a.
            val router = securedRouter()
            val crypto = SodiumCryptoProvider
            val identity = identityKey(9)
            val target = NodeId(Derivations(crypto).nodeId(identity.publicKey))
            assertNotNull(lookups).find(target, SchedulerClock(testScheduler).wallClockMillis() + 60_000)
            runCurrent()
            val decoded = LookupRequest.decode(waiting().getValue(a)[0])
            val request = assertIs<Decoded.Valid<LookupRequest>>(decoded).value
            router.received(a, lookupRequest(salt = request.salt))
            waiting()
            val clock = SchedulerClock(testScheduler)
            val keys = StaticKeys(crypto.x25519KeyPair().publicKey, null)
            val certificate = IdentityCertificate.issue(crypto, clock, identity, keys)
            val key = crypto.x25519KeyPair()
            val (salt, lookupTag) = request.salt to request.lookupTag
            val sealed = LookupSeal(crypto).seal(key, request.ephemeralKey, salt, lookupTag, certificate.encode())

            // Act
            val reply = lookupReply(id = 1, salt = request.salt, key = key.publicKey, sealed = assertNotNull(sealed))
            router.received(b, reply)

            // Assert
            assertTrue(waiting().values.all { it.isEmpty() })
            assertContentEquals(certificate.encode(), lookups?.certificate(target)?.encode())
        }

    private companion object {
        val SALT = tag(0x5a)
        val OWN = tag(0x55)
        val UNSECURED = Container.Unsecured(ByteArray(32), byteArrayOf())
        val TRANSPORT = Container.Transport(0u, ByteArray(17))
        val FLOOD_TYPE: Byte = 0x80.toByte()
    }
}
