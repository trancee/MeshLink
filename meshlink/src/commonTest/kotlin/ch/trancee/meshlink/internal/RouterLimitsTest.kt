package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DensityClass
import ch.trancee.meshlink.DiagnosticEvent.RelayRateLimited.Frame
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Router's use of the relay rate limits (spec "Relay rate limits"), on lanes to three linked peers.
class RouterLimitsTest {
    private val a = nodeId(1)
    private val b = nodeId(2)
    private val c = nodeId(3)
    private val lanes = Lanes().apply { listOf(a, b, c).forEach { open(it) } }
    private val endpoint = FakeEndpoint(OWN).apply { kinds = setOf(Container.UNSECURED) }
    private val floods = Floods(lanes, broadcastCodec(), lowTree(lanes), keptTombstones()) {}
    private val limits = Limits()

    // Limits that refuse every frame and record what they were asked and told.
    private class Limits : Admission {
        val asked = mutableListOf<Pair<NodeId, Frame>>()
        val unlinked = mutableListOf<NodeId>()

        override fun admits(
            peer: NodeId,
            frame: Frame,
            origin: NodeId?,
        ): Boolean = false.also { asked += peer to frame }

        override fun unlinked(peer: NodeId) {
            unlinked += peer
        }
    }

    private fun TestScope.router(): Router {
        val clock = SchedulerClock(testScheduler)
        return Router(clock, lanes, Routes(), { ByteArray(it) }, endpoint, Handoff(floods, limits = limits))
    }

    // A secured node's router, with its lookups.
    private fun TestScope.securedRouter(): Router {
        val clock = SchedulerClock(testScheduler)
        val secured =
            Lookups(
                SodiumCryptoProvider,
                Pace(clock) { DensityClass.Medium },
                backgroundScope,
                WireProfile.CLASSICAL,
                lanes,
                DiagnosticsHub(backgroundScope, debug = true, crypto = SodiumCryptoProvider),
            ).also { it.begin {} }
        return Router(clock, lanes, Routes(), { ByteArray(it) }, endpoint, Handoff(floods, secured, limits = limits))
    }

    private fun TestScope.nowSeconds() = SchedulerClock(testScheduler).wallClockMillis() / 1_000

    private fun TestScope.message(
        id: Int,
        destination: ByteArray,
        source: ByteArray = tag(8),
    ) = unicast(
        MessageFrame.TYPE,
        5,
        messageId(id),
        nowSeconds() + 60,
        destination,
        source,
        messageBody(Container.Unsecured(ByteArray(32), byteArrayOf()), ByteArray(16)),
        0,
    )

    private suspend fun waiting() = listOf(a, b, c).map { lanes.drain(it).size }

    @Test
    fun aFloodedUnicastOverBudgetIsDroppedAndARoutedOneGoesOnWithoutAsking() =
        runTest {
            // Arrange
            val router = router()

            // Act
            router.received(b, message(id = 1, source = tag(9), destination = tag(7)))
            router.received(a, message(id = 2, destination = tag(9)))

            // Assert
            assertEquals(listOf(0, 1, 0), waiting())
            assertEquals(listOf(b to Frame.Unicast), limits.asked)
        }

    @Test
    fun theRouterAsksItsLimitsForBroadcastsAndLookupRequestsAndTellsThemOfAClosedLink() =
        runTest {
            // Arrange
            val router = securedRouter()
            val header = FloodHeader(5, messageId(1), (nowSeconds() + 60).toUInt())
            val broadcast = BroadcastFrame(header, Container.Unsecured(ByteArray(32), byteArrayOf(1))).encode()
            val key = SodiumCryptoProvider.x25519KeyPair().publicKey
            val request = LookupRequest(FloodHeader(5, messageId(20), (nowSeconds() + 60).toUInt()), SALT, tag(3), key)

            // Act
            router.received(a, request.encode())
            router.received(b, broadcast)
            router.unlinked(c)

            // Assert
            assertEquals(listOf(a to Frame.LookupRequest, b to Frame.Broadcast), limits.asked)
            assertEquals(listOf(c), limits.unlinked)
            assertEquals(listOf(0, 0, 0), waiting())
        }

    private companion object {
        val SALT = tag(0x5a)
        val OWN = tag(0x55)
    }
}
