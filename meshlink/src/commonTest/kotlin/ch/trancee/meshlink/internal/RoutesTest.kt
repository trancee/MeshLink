package ch.trancee.meshlink.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoutesTest {
    private val routes = Routes()
    private val a = nodeId(1)
    private val b = nodeId(2)

    @Test
    fun aReverseRouteHoldsSixSecondsUnlessUsed() {
        // Act
        routes.learn(tag(1), a, nowMillis = 0)

        // Assert
        assertEquals(a, routes.use(tag(1), Routes.REVERSE_MILLIS - 1))
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
        assertEquals(a, routes.use(tag(1), 1_000 + Routes.ACTIVE_MILLIS - 1))
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
        assertEquals(a, routes.use(tag(1), Routes.ACTIVE_MILLIS - 1))
        routes.learn(tag(1), b, nowMillis = Routes.ACTIVE_MILLIS)
        assertEquals(b, routes.use(tag(1), Routes.ACTIVE_MILLIS))
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
        assertEquals(b, routes.use(tag(2), 0))
    }

    @Test
    fun theOldestRouteIsForgottenBeyondCapacity() {
        // Arrange
        repeat(Routes.CAPACITY) { routes.learn(tag(it), a, nowMillis = 0) }

        // Act
        routes.learn(tag(Routes.CAPACITY), a, nowMillis = 0)

        // Assert
        assertNull(routes.use(tag(0), 0))
        assertEquals(a, routes.use(tag(1), 0))
    }

    @Test
    fun relearningARouteMakesItTheNewestBeforeCapacity() {
        // Arrange
        repeat(Routes.CAPACITY) { routes.learn(tag(it), a, nowMillis = 0) }
        routes.learn(tag(0), a, nowMillis = 0)

        // Act
        routes.learn(tag(Routes.CAPACITY), a, nowMillis = 0)

        // Assert
        assertEquals(a, routes.use(tag(0), 0))
        assertNull(routes.use(tag(1), 0))
    }

    private fun tag(n: Int) = ByteArray(8) { (n shr (8 * (it % 4))).toByte() }
}
