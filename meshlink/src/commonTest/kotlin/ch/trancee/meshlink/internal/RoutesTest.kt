package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutesTest {
    private val routes = Routes()
    private val a = nodeId(1)
    private val b = nodeId(2)

    @Test
    fun aReverseRouteHoldsSixSecondsUnlessUsed() {
        // Act
        routes.learn(tag(1), a, nowMillis = 0)

        // Assert
        assertEquals(a, routes.peer(tag(1), Routes.REVERSE_MILLIS - 1))
        assertNull(Routes().apply { learn(tag(1), a, 0) }.use(tag(1), Routes.REVERSE_MILLIS))
        assertNull(routes.use(tag(2), 0))
    }

    @Test
    fun usingARouteKeepsItActiveThirtySecondsAfterTheUse() {
        // Arrange
        routes.learn(tag(1), a, nowMillis = 0)

        // Act
        routes.use(tag(1), nowMillis = 1_000)

        // Assert
        assertEquals(a, routes.peer(tag(1), 1_000 + Routes.ACTIVE_MILLIS - 1))
        assertNull(routes.use(tag(1), 1_000 + 2 * Routes.ACTIVE_MILLIS))
    }

    @Test
    fun relearningOnTheSameLinkKeepsTheLaterExpiryAndAnotherLinkReplacesTheRoute() {
        // Arrange
        routes.learn(tag(1), a, nowMillis = 0)
        routes.use(tag(1), nowMillis = 0)

        // Act
        routes.learn(tag(1), a, nowMillis = 1_000)

        // Assert
        assertEquals(a, routes.peer(tag(1), Routes.ACTIVE_MILLIS - 1))
        routes.learn(tag(1), b, nowMillis = Routes.ACTIVE_MILLIS)
        assertEquals(b, routes.peer(tag(1), Routes.ACTIVE_MILLIS))
    }

    @Test
    fun aClosedLinksRoutesGo() {
        // Arrange
        routes.learn(tag(1), a, nowMillis = 0)
        routes.learn(tag(2), b, nowMillis = 0)

        // Act
        routes.unlinked(a)

        // Assert
        assertNull(routes.use(tag(1), 0))
        assertEquals(b, routes.peer(tag(2), 0))
    }

    @Test
    fun theOldestRouteIsForgottenBeyondCapacity() {
        // Arrange
        repeat(Routes.CAPACITY) { routes.learn(tag(it), a, nowMillis = 0) }

        // Act
        routes.learn(tag(Routes.CAPACITY), a, nowMillis = 0)

        // Assert
        assertNull(routes.use(tag(0), 0))
        assertEquals(a, routes.peer(tag(1), 0))
    }

    @Test
    fun relearningARouteMakesItTheNewestBeforeCapacity() {
        // Arrange
        repeat(Routes.CAPACITY) { routes.learn(tag(it), a, nowMillis = 0) }
        routes.learn(tag(0), a, nowMillis = 0)

        // Act
        routes.learn(tag(Routes.CAPACITY), a, nowMillis = 0)

        // Assert
        assertEquals(a, routes.peer(tag(0), 0))
        assertNull(routes.use(tag(1), 0))
    }

    @Test
    fun aReplyRouteHoldsSixtySecondsThenThirtyAfterItsLastUse() {
        // Arrange
        routes.offer(tag(1), sequenced(a, nowMillis = 0, sequence = 1u, hops = 2), nowMillis = 0)

        // Act
        val used = routes.use(tag(1), nowMillis = 1_000)

        // Assert
        assertEquals(2, used?.hops)
        assertEquals(a, routes.peer(tag(1), Routes.FORWARD_MILLIS - 1))
        assertNull(routes.use(tag(1), Routes.FORWARD_MILLIS - 1 + Routes.ACTIVE_MILLIS))
    }

    @Test
    fun aNewerSequenceWinsThenFewerHops() {
        // Arrange
        routes.offer(tag(1), sequenced(a, nowMillis = 0, sequence = 5u, hops = 2), nowMillis = 0)

        // Act
        val older = routes.offer(tag(1), sequenced(b, nowMillis = 0, sequence = 4u, hops = 1), nowMillis = 0)
        val longer = routes.offer(tag(1), sequenced(b, nowMillis = 0, sequence = 5u, hops = 3), nowMillis = 0)
        val tie = routes.offer(tag(1), sequenced(b, nowMillis = 0, sequence = 5u, hops = 2), nowMillis = 0)
        val shorter = routes.offer(tag(1), sequenced(b, nowMillis = 0, sequence = 5u, hops = 1), nowMillis = 0)
        val newer = routes.offer(tag(1), sequenced(a, nowMillis = 0, sequence = 6u, hops = 9), nowMillis = 0)

        // Assert
        assertEquals(listOf(false, false, false, true, true), listOf(older, longer, tie, shorter, newer))
        assertEquals(9, routes.use(tag(1), 0)?.hops)
        assertEquals(6u, routes.sequence(tag(1)))
    }

    @Test
    fun theSameRouteOfferedAgainKeepsTheLaterExpiry() {
        // Arrange
        routes.offer(tag(1), sequenced(a, nowMillis = 0, sequence = 1u, hops = 1), nowMillis = 0)

        // Act
        val again = routes.offer(tag(1), sequenced(a, nowMillis = 10_000, sequence = 1u, hops = 1), nowMillis = 10_000)
        val short = routes.offer(tag(1), Routes.Route(a, expiry = 10_001, sequence = 1u, hops = 1), nowMillis = 10_000)

        // Assert
        assertTrue(again && short)
        assertEquals(a, routes.peer(tag(1), 10_000 + Routes.FORWARD_MILLIS - 1))
    }

    @Test
    fun anUnsequencedRouteNeverReplacesALiveSequencedOneOnAnotherLink() {
        // Arrange
        routes.offer(tag(1), sequenced(a, nowMillis = 0, sequence = 1u, hops = 4), nowMillis = 0)

        // Act
        routes.learn(tag(1), b, nowMillis = 0)
        routes.learn(tag(1), a, nowMillis = Routes.FORWARD_MILLIS - 1)

        // Assert
        val kept = routes.use(tag(1), Routes.FORWARD_MILLIS - 1)
        assertEquals(a, kept?.peer)
        assertEquals(4, kept?.hops)
        routes.learn(tag(1), b, nowMillis = Routes.FORWARD_MILLIS + 2 * Routes.ACTIVE_MILLIS)
        assertEquals(b, routes.peer(tag(1), Routes.FORWARD_MILLIS + 2 * Routes.ACTIVE_MILLIS))
    }

    @Test
    fun aSequencedRouteReplacesAnUnsequencedOneOrAnExpiredOne() {
        // Arrange
        routes.learn(tag(1), a, nowMillis = 0)
        routes.offer(tag(2), sequenced(a, nowMillis = 0, sequence = 9u, hops = 1), nowMillis = 0)

        // Act
        val overUnsequenced = routes.offer(tag(1), sequenced(b, nowMillis = 0, sequence = 1u, hops = 3), nowMillis = 0)
        val overExpired =
            routes.offer(
                tag(2),
                sequenced(b, Routes.FORWARD_MILLIS, sequence = 1u, hops = 3),
                Routes.FORWARD_MILLIS,
            )

        // Assert
        assertTrue(overUnsequenced && overExpired)
        assertEquals(b, routes.peer(tag(1), 0))
        assertEquals(b, routes.peer(tag(2), Routes.FORWARD_MILLIS))
    }

    @Test
    fun theLastKnownSequenceOutlivesItsRouteAndAnUnknownTagHasNone() {
        // Arrange
        routes.offer(tag(1), sequenced(a, nowMillis = 0, sequence = 7u, hops = 1), nowMillis = 0)

        // Act
        val expired = routes.use(tag(1), Routes.FORWARD_MILLIS)

        // Assert
        assertNull(expired)
        assertEquals(7u, routes.sequence(tag(1)))
        assertEquals(0u, routes.sequence(tag(2)))
        assertNull(routes.active(tag(1), Routes.FORWARD_MILLIS))
    }

    @Test
    fun anActiveRouteIsReadWithoutRefreshingIt() {
        // Arrange
        routes.learn(tag(1), a, nowMillis = 0)

        // Act
        val read = routes.active(tag(1), Routes.REVERSE_MILLIS - 1)

        // Assert
        assertEquals(a, read?.peer)
        assertEquals(0, read?.hops)
        assertNull(routes.active(tag(1), Routes.REVERSE_MILLIS))
    }

    private fun sequenced(
        peer: NodeId,
        nowMillis: Long,
        sequence: UInt,
        hops: Int,
    ) = Routes.Route(peer, nowMillis + Routes.FORWARD_MILLIS, sequence, hops)

    private fun Routes.peer(
        tag: ByteArray,
        nowMillis: Long,
    ) = use(tag, nowMillis)?.peer

    private fun tag(n: Int) = ByteArray(8) { (n shr (8 * (it % 4))).toByte() }
}
