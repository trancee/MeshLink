package ch.trancee.meshlink.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// A node's tombstones (spec "Tombstones", D-42) over the frames it holds, at wall-clock second NOW.
class TombstonesTest {
    private val holding = Holding(enabled = true)
    private val tombstones = Tombstones(holding, ::commitment)
    private val a = nodeId(1)

    @Test
    fun aRelayedReceiptsTombstoneThatVerifiesAHeldCopyDeletesItAndIsOfferedUntilTheReceiptsExpiry() {
        // Arrange
        holding.hold(message(1, expiry = NOW + LIFETIME), HOP_LIMIT, NOW_MILLIS)

        // Act
        tombstones.relayed(listOf(tombstone(1)), expiry = NOW + 100, NOW_MILLIS)

        // Assert
        assertEquals(emptyList(), holding.unicasts(NOW_MILLIS))
        assertEquals(listOf(id(1)), tombstones.offered((NOW + 100) * MILLIS - 1).map { it.messageId.toList() })
        assertEquals(emptyList(), tombstones.offered((NOW + 100) * MILLIS))
    }

    @Test
    fun aRelayedReceiptsTombstoneWithNoHeldCopyIsStored() {
        // Act
        tombstones.relayed(listOf(tombstone(1)), expiry = NOW + 100, NOW_MILLIS)

        // Assert
        assertEquals(listOf(id(1)), tombstones.offered(NOW_MILLIS).map { it.messageId.toList() })
    }

    @Test
    fun aRelayedReceiptsTombstoneForAHeldFrameWithNoCommitmentKeepsTheFrameAndIsStored() {
        // Arrange: a held receipt, not a MESSAGE, with the tombstone's message_id.
        holding.hold(HeldFrame(a, mid(1), ByteArray(16) { 9 }, NOW + LIFETIME, ByteArray(SIZE)), HOP_LIMIT, NOW_MILLIS)

        // Act
        tombstones.relayed(listOf(tombstone(1)), expiry = NOW + 100, NOW_MILLIS)

        // Assert
        assertEquals(listOf(id(1)), holding.unicasts(NOW_MILLIS).map { it.messageId.toList() })
        assertEquals(listOf(id(1)), tombstones.offered(NOW_MILLIS).map { it.messageId.toList() })
    }

    @Test
    fun aTombstoneWithAWrongKDeletesNothingAndIsNotStored() {
        // Arrange
        holding.hold(message(1), HOP_LIMIT, NOW_MILLIS)

        // Act
        tombstones.relayed(listOf(tombstone(1, k = 7)), expiry = NOW + 100, NOW_MILLIS)
        tombstones.caughtUp(listOf(tombstone(1, k = 8)), NOW_MILLIS)

        // Assert
        assertEquals(listOf(id(1)), holding.unicasts(NOW_MILLIS).map { it.messageId.toList() })
        assertEquals(emptyList(), tombstones.offered(NOW_MILLIS))
    }

    @Test
    fun aCatchUpTombstoneThatVerifiesAHeldCopyDeletesItAndIsKeptUntilTheHeldCopyExpires() {
        // Arrange
        holding.hold(message(1, expiry = NOW + 200), HOP_LIMIT, NOW_MILLIS)

        // Act
        tombstones.caughtUp(listOf(tombstone(1)), NOW_MILLIS)

        // Assert
        assertEquals(emptyList(), holding.unicasts(NOW_MILLIS))
        assertEquals(listOf(id(1)), tombstones.offered((NOW + 200) * MILLIS - 1).map { it.messageId.toList() })
        assertEquals(emptyList(), tombstones.offered((NOW + 200) * MILLIS))
    }

    @Test
    fun anUnsolicitedCatchUpTombstoneIsIgnored() {
        // Arrange: a held broadcast with the same message_id commits to nothing.
        holding.hold(HeldFrame(a, mid(2), null, NOW + LIFETIME, ByteArray(SIZE)), HOP_LIMIT, NOW_MILLIS)

        // Act
        tombstones.caughtUp(listOf(tombstone(1), tombstone(2)), NOW_MILLIS)

        // Assert
        assertEquals(emptyList(), tombstones.offered(NOW_MILLIS))
        assertEquals(1, holding.broadcasts(NOW_MILLIS).size)
    }

    @Test
    fun theThousandAndFirstTombstoneDropsTheOldest() {
        // Arrange
        tombstones.relayed((0 until Tombstones.STORE_MAX).map { tombstone(it) }, NOW + 100, NOW_MILLIS)

        // Act
        tombstones.relayed(listOf(tombstone(Tombstones.STORE_MAX)), NOW + 100, NOW_MILLIS)

        // Assert
        val offered = tombstones.offered(NOW_MILLIS).map { it.messageId.toList() }
        assertEquals(Tombstones.STORE_MAX, offered.size)
        assertEquals(id(1), offered.first())
        assertEquals(id(Tombstones.STORE_MAX), offered.last())
    }

    @Test
    fun aTombstoneStoredAlreadyIsNotStoredAgain() {
        // Arrange
        tombstones.relayed(listOf(tombstone(1)), NOW + 100, NOW_MILLIS)

        // Act
        tombstones.relayed(listOf(tombstone(1)), NOW + 100, NOW_MILLIS)

        // Assert
        assertEquals(1, tombstones.offered(NOW_MILLIS).size)
    }

    @Test
    fun aFloodOfForgedTombstonesDeletesNoHeldCopyAndStoresAtMostAThousand() {
        // Arrange
        holding.hold(message(1), HOP_LIMIT, NOW_MILLIS)

        // Act: forged openings of the held copy, then unmatched tombstones in relayed receipts and on catch-up.
        tombstones.relayed((2..200).map { tombstone(1, k = it) }, NOW + 100, NOW_MILLIS)
        repeat(3) { round ->
            val forged = (0 until Tombstones.STORE_MAX).map { tombstone(10_000 * (round + 1) + it) }
            tombstones.relayed(forged, NOW + 100, NOW_MILLIS)
            tombstones.caughtUp(forged.map { tombstone(it.messageId, k = 3) }, NOW_MILLIS)
        }

        // Assert
        assertEquals(listOf(id(1)), holding.unicasts(NOW_MILLIS).map { it.messageId.toList() })
        assertEquals(Tombstones.STORE_MAX, tombstones.offered(NOW_MILLIS).size)
    }

    @Test
    fun aStoredTombstoneBuriesItsMessageButNotAnotherWithTheSameId() {
        // Arrange
        tombstones.relayed(listOf(tombstone(1)), NOW + 100, NOW_MILLIS)

        // Act
        val genuine = tombstones.buries(message(1), NOW_MILLIS)
        val other = tombstones.buries(message(1, k = 5), NOW_MILLIS)
        val unknown = tombstones.buries(message(2), NOW_MILLIS)
        val broadcast = tombstones.buries(HeldFrame(a, mid(1), null, NOW + LIFETIME, ByteArray(SIZE)), NOW_MILLIS)
        val expired = tombstones.buries(message(1), (NOW + 100) * MILLIS)

        // Assert
        assertTrue(genuine)
        assertFalse(other)
        assertFalse(unknown)
        assertFalse(broadcast)
        assertFalse(expired)
    }

    @Test
    fun clearDropsEveryTombstone() {
        // Arrange
        tombstones.relayed(listOf(tombstone(1)), NOW + 100, NOW_MILLIS)

        // Act
        tombstones.clear()

        // Assert
        assertEquals(emptyList(), tombstones.offered(NOW_MILLIS))
    }

    private fun message(
        id: Int,
        k: Int = K,
        expiry: Long = NOW + LIFETIME,
    ) = HeldFrame(a, mid(id), ByteArray(16) { 9 }, expiry, ByteArray(SIZE), commitment(secret(k)))

    private fun tombstone(
        id: Int,
        k: Int = K,
    ) = tombstone(mid(id), k)

    private fun tombstone(
        id: ByteArray,
        k: Int,
    ) = Tombstone(id, secret(k))

    private fun secret(k: Int) = ByteArray(16) { k.toByte() }

    // A stand-in for trunc_16(H("tombstone", k)): any function a forger can't invert does.
    private fun commitment(k: ByteArray) = k.reversedArray().also { it[0] = (it[0] + 1).toByte() }

    private fun id(n: Int) = mid(n).toList()

    // A message_id unique for each n below 2^32.
    private fun mid(n: Int) = ByteArray(12) { (n shr (8 * (it % 4))).toByte() }

    private companion object {
        const val NOW = 1_767_225_600L
        const val MILLIS = 1_000L
        const val NOW_MILLIS = NOW * MILLIS
        const val LIFETIME = 300L
        const val HOP_LIMIT = 5
        const val SIZE = 100
        const val K = 1
    }
}
