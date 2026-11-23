package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

// The delivered messages a node watches for their read receipts, with a recording eviction.
class ReadTrackingTest {
    private val evicted = mutableListOf<Int>()
    private val tracking = ReadTracking { evicted += index(it) }

    private fun delivered(): MutableStateFlow<DeliveryStatus> = MutableStateFlow(DeliveryStatus.Delivered)

    @Test
    fun aReadReceiptFromTheDestinationMovesItsListedTrackedMessagesToReadOnce() {
        // Arrange
        val (listed, unlisted, elsewhere) = List(3) { delivered() }
        tracking.track(PEER, id(1), listed)
        tracking.track(PEER, id(2), unlisted)
        tracking.track(OTHER, id(3), elsewhere)

        // Act
        tracking.read(PEER, listOf(id(1), id(3), id(4)))

        // Assert
        assertEquals(DeliveryStatus.Read, listed.value)
        assertEquals(listOf(DeliveryStatus.Delivered), listOf(unlisted, elsewhere).map { it.value }.distinct())
        listed.value = DeliveryStatus.Delivered
        tracking.read(PEER, listOf(id(1)))
        assertEquals(DeliveryStatus.Delivered, listed.value)
        tracking.read(OTHER, listOf(id(3)))
        assertEquals(DeliveryStatus.Read, elsewhere.value)
    }

    @Test
    fun theThousandAndFirstTrackedMessageDropsTheOldestWithADiagnostic() {
        // Arrange
        val statuses = List(ReadTracking.MAX + 1) { delivered() }

        // Act
        statuses.forEachIndexed { index, status -> tracking.track(PEER, id(index), status) }

        // Assert
        assertEquals(listOf(0), evicted)
        tracking.read(PEER, List(statuses.size) { id(it) })
        assertEquals(DeliveryStatus.Delivered, statuses.first().value)
        assertEquals(listOf(DeliveryStatus.Read), statuses.drop(1).map { it.value }.distinct())
    }

    private companion object {
        val PEER = nodeId(1)
        val OTHER = nodeId(2)

        fun id(index: Int): ByteArray = ByteArray(12) { (index shr (8 * (it % 4))).toByte() }

        fun index(id: ByteArray): Int = (0..3).sumOf { (id[it].toInt() and 0xFF) shl (8 * it) }
    }
}
