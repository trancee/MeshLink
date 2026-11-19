package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals

/** A node's estimate of its local density (ADR 0008 decision 2). */
class DensityEstimatorTest {
    private val clock = MutableClock()
    private val estimator = DensityEstimator(clock)

    @Test
    fun theFirstEstimateCountsTheDistinctIdentifiersHeardUnsmoothed() {
        // Arrange
        listOf(1, 2, 3, 2).forEach { estimator.heard(identifier(it), EPOCH) }

        // Act
        val estimate = estimator.estimate(emptyMap(), ::resolve)

        // Assert
        assertEquals(3.0, estimate)
    }

    @Test
    fun eachLaterEstimateMovesThreeTenthsOfTheWayToTheCount() {
        // Arrange
        val first = estimator.estimate(emptyMap(), ::resolve)
        (1..10).forEach { estimator.heard(identifier(it), EPOCH) }

        // Act
        val second = estimator.estimate(emptyMap(), ::resolve)
        val estimates = listOf(first, second, estimator.estimate(emptyMap(), ::resolve))

        // Assert
        assertEquals(listOf(0.0, 3.0, 5.1), estimates.map { (it * 1_000).toLong() / 1_000.0 })
    }

    @Test
    fun aSightingCountsFor10Seconds() {
        // Arrange
        val links = mapOf<PeerHandle, NodeId>(Handle() to nodeId(1))
        val later = DensityEstimator(clock)
        listOf(estimator, later).forEach {
            it.heard(identifier(1), EPOCH)
            it.heard(identifier(3), EPOCH)
            it.heardAnonymous(Handle())
        }

        // Act
        clock.now += WINDOW_MILLIS
        val kept = estimator.estimate(links, ::resolve)
        clock.now += 1
        val dropped = later.estimate(links, ::resolve)

        // Assert: once unheard, the linked peer counts as such.
        assertEquals(listOf(3.0, 1.0), listOf(kept, dropped))
    }

    @Test
    fun aLinkedPeerNotHeardCountsOnce() {
        // Arrange
        estimator.heard(identifier(1), EPOCH)
        estimator.heard(identifier(4), EPOCH)
        val links = mapOf<PeerHandle, NodeId>(Handle() to nodeId(1), Handle() to nodeId(2), Handle() to nodeId(3))

        // Act
        val estimate = estimator.estimate(links, ::resolve)

        // Assert
        assertEquals(4.0, estimate)
    }

    @Test
    fun eachIdentifierLessAddressCountsOnceAndALinkedOneAsItsPeer() {
        // Arrange
        val linked = Handle()
        val anonymous = Handle()
        listOf(linked, anonymous, anonymous, Handle()).forEach { estimator.heardAnonymous(it) }

        // Act
        val estimate = estimator.estimate(mapOf(linked to nodeId(1)), ::resolve)

        // Assert
        assertEquals(3.0, estimate)
    }

    @Test
    fun aForgottenAddressNoLongerCounts() {
        // Arrange
        val stale = Handle()
        estimator.heardAnonymous(stale)
        estimator.heardAnonymous(Handle())
        estimator.forget(stale)

        // Act
        val estimate = estimator.estimate(emptyMap(), ::resolve)

        // Assert
        assertEquals(1.0, estimate)
    }

    @Test
    fun anIdentifierResolvedOnlyAfterItsSightingCountsAsItsLinkedPeerOnce() {
        // Arrange
        val links = mapOf<PeerHandle, NodeId>(Handle() to nodeId(2))
        estimator.heard(identifier(2), EPOCH)
        estimator.heard(identifier(3), EPOCH)
        val first = estimator.estimate(links, ::resolve)
        val resolving = DensityEstimator(clock)
        resolving.heard(identifier(2), EPOCH)
        resolving.heard(identifier(3), EPOCH)
        resolving.estimate(links) { _, _ -> null }

        // Act
        val resolved = resolving.estimate(links, ::resolve)
        resolving.heard(identifier(2), EPOCH)
        val kept = resolving.estimate(links) { _, _ -> null }

        // Assert
        assertEquals(listOf(2.0, 2.7, 2.49), listOf(first, resolved, kept).map { (it * 1_000).toLong() / 1_000.0 })
    }

    /** Identifier n resolves, in [EPOCH] only, to the peer n if n is 1 or 2. */
    private fun resolve(
        identifier: ByteArray,
        epoch: UInt,
    ): NodeId? = identifier[0].toInt().takeIf { epoch == EPOCH && it <= 2 }?.let(::nodeId)

    private fun identifier(n: Int): ByteArray = ByteArray(IDENTIFIER_LENGTH) { n.toByte() }

    private fun nodeId(n: Int): NodeId = NodeId(ByteArray(NODE_ID_LENGTH) { n.toByte() })

    private class Handle : PeerHandle

    private class MutableClock : Clock {
        var now = 1_000L

        override fun monotonicMillis(): Long = now

        override fun wallClockMillis(): Long = now
    }

    private companion object {
        const val WINDOW_MILLIS = 10_000L
        const val EPOCH = 7u
        const val IDENTIFIER_LENGTH = 16
        const val NODE_ID_LENGTH = 32
    }
}
