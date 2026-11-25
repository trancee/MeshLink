package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Router's origin rules (spec "Origin behavior" and "Size classes"), on lanes to three linked peers.
class RouterOriginTest {
    private val a = nodeId(1)
    private val b = nodeId(2)
    private val c = nodeId(3)
    private val lanes = Lanes().apply { listOf(a, b, c).forEach { open(it) } }

    private fun TestScope.router(): Router {
        val floods = Floods(lanes, broadcastCodec(), lowTree(lanes), ::tombstoneCommitment) {}
        val endpoint = FakeEndpoint(OWN).apply { kinds = setOf(Container.UNSECURED) }
        return Router(SchedulerClock(testScheduler), lanes, Routes(), { ByteArray(it) }, endpoint, Handoff(floods))
    }

    private fun TestScope.nowSeconds() = SchedulerClock(testScheduler).wallClockMillis() / 1_000

    // A message from [source] to [destination] that B relays here.
    private fun TestScope.message(
        id: Int,
        source: ByteArray,
        destination: ByteArray,
    ) = unicast(
        MessageFrame.TYPE,
        5,
        messageId(id),
        nowSeconds() + 60,
        destination,
        source,
        messageBody(Container.Unsecured(ByteArray(32), byteArrayOf()), ByteArray(16)),
    )

    // Originates message [id] whose frame is its hop limit.
    private fun Router.send(
        id: Int,
        tag: ByteArray,
        reach: Reach,
        search: Boolean = false,
    ) = originate(tag, OWN, reach, search) { Parcel(messageId(id), relayed = false, { byteArrayOf(it.toByte()) }) {} }

    // A route to [target] through [peer] that a route reply found: sequence 5, one hop.
    private fun TestScope.found(
        router: Router,
        peer: NodeId,
        target: ByteArray,
    ) {
        val header = UnicastHeader(5, messageId(target[0] + 50), (nowSeconds() + 60).toUInt(), OWN, target)
        router.received(peer, RouteReply(header, target, 5u, 0).encode())
    }

    private suspend fun waiting() = listOf(a, b, c).associateWith { peer -> lanes.drain(peer).map { it.render() } }

    @Test
    fun aLowClassOriginFloodsWithTheMaximumHopLimitEvenWithARoute() =
        runTest {
            // Arrange
            val router = router()
            router.received(b, message(id = 1, source = tag(9), destination = tag(7)))
            waiting()

            // Act
            router.send(2, tag(9), Reach.of(DensityClass.LOW))

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
            router.send(2, tag(9), Reach.of(DensityClass.MEDIUM))
            val routed = waiting()
            router.send(3, tag(6), Reach.of(DensityClass.HIGH))

            // Assert: without a route, a route request goes first on every link.
            val hopLimits = routed.values.map { frames -> frames.map { it[0].toInt() and 0xFF } }
            assertEquals(listOf(emptyList(), listOf(255), emptyList()), hopLimits)
            assertEquals(listOf(3, 3, 3), waiting().values.map { it.last()[0].toInt() })
        }

    @Test
    fun aLargeMessageGoesOnlyOnARouteFoundByRouteRequestInEveryClass() =
        runTest {
            // Arrange: C found a route to tag 4.
            val router = router()
            found(router, c, tag(4))
            waiting()

            // Act
            val hops = DensityClass.entries.map { router.send(it.ordinal, tag(4), Reach.of(it, large = true)) }

            // Assert: on that route only, with the maximum hop limit, and no route request.
            val sent = waiting()
            assertEquals(listOf(1, 1, 1), hops)
            val hopLimits = sent.values.map { frames -> frames.map { it[0].toInt() and 0xFF } }
            assertEquals(listOf(emptyList(), emptyList(), listOf(255, 255, 255)), hopLimits)
        }

    @Test
    fun aLargeMessageWithoutAFoundRouteOnlySearchesForOne() =
        runTest {
            // Arrange: B shows a reverse route to tag 9; C found a route to tag 4.
            val router = router()
            router.received(b, message(id = 1, source = tag(9), destination = tag(7)))
            found(router, c, tag(4))
            waiting()

            // Act: a reverse route, no route, and a resend's new search.
            val sends =
                listOf(
                    router.send(2, tag(9), Reach.ROUTE) to waiting(),
                    router.send(3, tag(6), Reach.ROUTE) to waiting(),
                    router.send(4, tag(4), Reach.ROUTE, search = true) to waiting(),
                )

            // Assert: no message goes, only a route request on every link for each.
            assertEquals(listOf(null, null, null), sends.map { it.first })
            sends.forEach { (_, sent) ->
                assertTrue(sent.values.all { frames -> frames.single()[0] == RouteRequest.TYPE.toByte() })
            }
        }

    private companion object {
        val OWN = tag(0x55)
    }
}
