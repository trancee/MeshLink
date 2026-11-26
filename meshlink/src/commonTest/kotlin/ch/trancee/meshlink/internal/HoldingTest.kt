package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// The bounds and eviction order of holding for others (spec "Holding"), at wall-clock second NOW.
class HoldingTest {
    private val holding = Holding(enabled = true)
    private val a = nodeId(1)
    private val b = nodeId(2)
    private val c = nodeId(3)
    private val d = nodeId(4)
    private val e = nodeId(5)

    @Test
    fun aSmallTimelyFrameThatCanGoOnIsHeldAndListed() {
        // Act
        val held = holding.hold(unicast(a, 1), HOP_LIMIT, NOW_MILLIS)

        // Assert
        assertTrue(held)
        assertEquals(listOf(id(1)), holding.unicasts(NOW_MILLIS).map { it.messageId.toList() })
        assertEquals(emptyList(), holding.broadcasts(NOW_MILLIS))
        assertContentEquals(tag(9), holding.find(mid(1), NOW_MILLIS)?.destinationTag)
    }

    @Test
    fun aBroadcastIsHeldUnderTheSameQuotaAndListedApart() {
        // Arrange
        repeat(SHARE - 1) { holding.hold(unicast(a, it), HOP_LIMIT, NOW_MILLIS) }

        // Act
        val first = holding.hold(broadcast(a, SHARE), HOP_LIMIT, NOW_MILLIS)
        val over = holding.hold(broadcast(a, SHARE + 1, expiry = NOW + LIFETIME), HOP_LIMIT, NOW_MILLIS)

        // Assert
        assertTrue(first)
        assertFalse(over)
        assertEquals(listOf(id(SHARE)), holding.broadcasts(NOW_MILLIS).map { it.messageId.toList() })
        assertEquals(SHARE - 1, holding.unicasts(NOW_MILLIS).size)
    }

    @Test
    fun aFrameThatArrivedWithHopLimitOneIsNotHeldAndTwoIs() {
        // Act
        val one = holding.hold(unicast(a, 1), 1, NOW_MILLIS)
        val two = holding.hold(unicast(a, 2), 2, NOW_MILLIS)

        // Assert
        assertFalse(one)
        assertTrue(two)
    }

    @Test
    fun aLargeFrameIsNotHeldAndOneOfTheSmallMaximumIs() {
        // Act
        val large = holding.hold(unicast(a, 1, size = Reassembly.FRAME_SMALL_MAX + 1), HOP_LIMIT, NOW_MILLIS)
        val small = holding.hold(unicast(a, 2, size = Reassembly.FRAME_SMALL_MAX), HOP_LIMIT, NOW_MILLIS)

        // Assert
        assertFalse(large)
        assertTrue(small)
    }

    @Test
    fun aFrameWithNineSecondsLeftIsNotHeldAndOneWithTenIs() {
        // Act
        val nine = holding.hold(unicast(a, 1, expiry = NOW + 9), HOP_LIMIT, NOW_MILLIS)
        val ten = holding.hold(unicast(a, 2, expiry = NOW + 10), HOP_LIMIT, NOW_MILLIS)

        // Assert
        assertFalse(nine)
        assertTrue(ten)
    }

    @Test
    fun aFrameExpiringFurtherAheadThanALifetimePlusSkewIsNotHeld() {
        // Arrange
        val bound = NOW + UnsecuredMessaging.LIFETIME_MAX_SECONDS + UnsecuredMessaging.CLOCK_SKEW_SECONDS

        // Act
        val over = holding.hold(unicast(a, 1, expiry = bound + 1), HOP_LIMIT, NOW_MILLIS)
        val edge = holding.hold(unicast(a, 2, expiry = bound), HOP_LIMIT, NOW_MILLIS)

        // Assert
        assertFalse(over)
        assertTrue(edge)
    }

    @Test
    fun aHeldFrameIsGoneAtItsExpiry() {
        // Arrange
        holding.hold(unicast(a, 1, expiry = NOW + 10), HOP_LIMIT, NOW_MILLIS)
        val expiryMillis = (NOW + 10) * MILLIS

        // Act
        val before = holding.find(mid(1), expiryMillis - 1)
        val at = holding.find(mid(1), expiryMillis)

        // Assert
        assertTrue(before != null)
        assertNull(at)
        assertEquals(emptyList(), holding.unicasts(expiryMillis))
    }

    @Test
    fun aHeldFrameIsCopiedForAGraftUntilItsExpiry() {
        // Arrange
        val frame = unicast(a, 1, expiry = NOW + 10)
        holding.hold(frame, HOP_LIMIT, NOW_MILLIS)
        val expiryMillis = (NOW + 10) * MILLIS

        // Act
        val before = holding.copy(mid(1), expiryMillis - 1)?.takenBy(b)
        val at = holding.copy(mid(1), expiryMillis)

        // Assert
        assertContentEquals(mid(1), before?.messageId)
        assertEquals(setOf(b), frame.sentTo)
        assertNull(at)
    }

    @Test
    fun aFrameHeldAlreadyIsNotHeldAgain() {
        // Arrange
        val first = unicast(a, 1)
        holding.hold(first, HOP_LIMIT, NOW_MILLIS)

        // Act
        val again = holding.hold(unicast(b, 1), HOP_LIMIT, NOW_MILLIS)

        // Assert
        assertFalse(again)
        assertSame(first, holding.find(mid(1), NOW_MILLIS))
    }

    @Test
    fun aLinkThatFloodsFillsAtMostAQuarterOfTheMessages() {
        // Act
        val taken = (0 until SHARE + 10).count { holding.hold(unicast(a, it), HOP_LIMIT, NOW_MILLIS) }

        // Assert
        assertEquals(SHARE, taken)
        assertEquals(SHARE, holding.unicasts(NOW_MILLIS).size)
    }

    @Test
    fun aLinkThatFloodsFillsAtMostAQuarterOfTheBytes() {
        // Arrange
        val size = Reassembly.FRAME_SMALL_MAX
        val fit = Holding.BYTES_MAX / Holding.LINK_SHARE_DIVISOR / size

        // Act
        val taken = (0 until fit + 3).count { holding.hold(unicast(a, it, size = size), HOP_LIMIT, NOW_MILLIS) }

        // Assert
        assertEquals(fit, taken)
    }

    @Test
    fun aLinkAtItsShareReplacesItsOwnFrameClosestToExpiryOnlyWithALongerLivedOne() {
        // Arrange: a's frame 0 expires first, the rest later.
        holding.hold(unicast(a, 0, expiry = NOW + 100), HOP_LIMIT, NOW_MILLIS)
        (1 until SHARE).forEach { holding.hold(unicast(a, it, expiry = NOW + 200), HOP_LIMIT, NOW_MILLIS) }
        holding.hold(unicast(b, 50, expiry = NOW + 50), HOP_LIMIT, NOW_MILLIS)

        // Act
        val shorter = holding.hold(unicast(a, 60, expiry = NOW + 100), HOP_LIMIT, NOW_MILLIS)
        val longer = holding.hold(unicast(a, 61, expiry = NOW + 101), HOP_LIMIT, NOW_MILLIS)

        // Assert
        assertFalse(shorter)
        assertTrue(longer)
        assertNull(holding.find(mid(0), NOW_MILLIS))
        assertTrue(holding.find(mid(50), NOW_MILLIS) != null, "b's frame stays: a only replaces its own")
    }

    @Test
    fun theHundredAndFirstFrameEvictsFromTheLinkFurthestOverItsShare() {
        // Arrange: a and b hold a quarter each, c and d less, so the mesh holds 100 frames.
        fill(a, from = 0, count = SHARE)
        fill(b, from = 100, count = SHARE)
        fill(c, from = 200, count = SHARE)
        fill(d, from = 300, count = SHARE - 1)
        holding.hold(unicast(d, 399, expiry = NOW + 50), HOP_LIMIT, NOW_MILLIS)
        assertEquals(Holding.MESSAGES_MAX, holding.unicasts(NOW_MILLIS).size)

        // Act: e holds nothing, so all four are over its fair share of 100 / 5; the first, a, goes first.
        val held = holding.hold(unicast(e, 500, expiry = NOW + 20), HOP_LIMIT, NOW_MILLIS)

        // Assert: a's frame closest to expiry went, though d holds the frame closest to expiry of all.
        assertTrue(held)
        assertEquals(Holding.MESSAGES_MAX, holding.unicasts(NOW_MILLIS).size)
        assertNull(holding.find(mid(0), NOW_MILLIS))
        assertTrue(holding.find(mid(399), NOW_MILLIS) != null)
    }

    @Test
    fun byte524289EvictsFromTheLinkFurthestOverItsShare() {
        // Arrange: four links fill the bytes with frames of the small maximum, a's first frame expiring first.
        val size = Reassembly.FRAME_SMALL_MAX
        val perLink = Holding.BYTES_MAX / Holding.LINK_SHARE_DIVISOR / size
        listOf(a, b, c, d).forEachIndexed { link, from ->
            repeat(perLink) {
                val expiry = if (link == 0 && it == 0) NOW + 100 else NOW + 200
                holding.hold(unicast(from, link * 100 + it, size = size, expiry = expiry), HOP_LIMIT, NOW_MILLIS)
            }
        }
        assertEquals(Holding.BYTES_MAX, holding.unicasts(NOW_MILLIS).sumOf { it.bytes.size })

        // Act
        val held = holding.hold(unicast(e, 500, size = 1), HOP_LIMIT, NOW_MILLIS)

        // Assert
        assertTrue(held)
        assertNull(holding.find(mid(0), NOW_MILLIS))
        assertTrue(holding.unicasts(NOW_MILLIS).sumOf { it.bytes.size } <= Holding.BYTES_MAX)
    }

    @Test
    fun whenFullAndNoOtherLinkIsOverItsFairShareTheFrameClosestToExpiryGivesWayOnlyToALongerLivedOne() {
        // Arrange: five links hold 20 frames each, their fair share of 100; a's first frame expires first.
        holding.hold(unicast(a, 0, expiry = NOW + 100), HOP_LIMIT, NOW_MILLIS)
        fill(a, from = 1, count = 19)
        listOf(b, c, d, e).forEachIndexed { index, link -> fill(link, from = (index + 1) * 100, count = 20) }
        assertEquals(Holding.MESSAGES_MAX, holding.unicasts(NOW_MILLIS).size)

        // Act
        val shorter = holding.hold(unicast(b, 900, expiry = NOW + 100), HOP_LIMIT, NOW_MILLIS)
        val longer = holding.hold(unicast(b, 901, expiry = NOW + 101), HOP_LIMIT, NOW_MILLIS)

        // Assert
        assertFalse(shorter)
        assertTrue(longer)
        assertEquals(Holding.MESSAGES_MAX, holding.unicasts(NOW_MILLIS).size)
        assertNull(holding.find(mid(0), NOW_MILLIS))
    }

    @Test
    fun expiredFramesGoFirst() {
        // Arrange: the mesh is full, and a's frames expire soon.
        fill(a, from = 0, count = SHARE, expiry = NOW + 10)
        fill(b, from = 100, count = SHARE, expiry = NOW + 200)
        fill(c, from = 200, count = SHARE, expiry = NOW + 200)
        fill(d, from = 300, count = SHARE, expiry = NOW + 200)
        val later = (NOW + 10) * MILLIS

        // Act
        val held = holding.hold(unicast(d, 400, expiry = NOW + 100), HOP_LIMIT, later)

        // Assert: d's share counts only live frames; nothing live was evicted.
        assertFalse(held, "d is at its share and its frames outlive the new one")
        assertTrue(holding.hold(unicast(e, 500, expiry = NOW + 100), HOP_LIMIT, later))
        assertEquals(SHARE * 3 + 1, holding.unicasts(later).size)
    }

    @Test
    fun theFramesHeldTowardATagAreItsLiveUnicasts() {
        // Arrange
        holding.hold(unicast(a, 1, destination = tag(7)), HOP_LIMIT, NOW_MILLIS)
        holding.hold(unicast(a, 2, destination = tag(8)), HOP_LIMIT, NOW_MILLIS)
        holding.hold(unicast(a, 3, destination = tag(7), expiry = NOW + 10), HOP_LIMIT, NOW_MILLIS)
        holding.hold(broadcast(a, 4), HOP_LIMIT, NOW_MILLIS)

        // Act
        val toward = holding.toward(tag(7), NOW_MILLIS + 10 * MILLIS)

        // Assert
        assertEquals(listOf(id(1)), toward.map { it.messageId.toList() })
    }

    @Test
    fun clearDropsEveryHeldFrame() {
        // Arrange
        holding.hold(unicast(a, 1), HOP_LIMIT, NOW_MILLIS)
        holding.hold(broadcast(a, 2), HOP_LIMIT, NOW_MILLIS)

        // Act
        holding.clear()

        // Assert
        assertEquals(emptyList(), holding.unicasts(NOW_MILLIS))
        assertEquals(emptyList(), holding.broadcasts(NOW_MILLIS))
        assertTrue(holding.hold(unicast(a, 1), HOP_LIMIT, NOW_MILLIS))
    }

    private fun fill(
        link: NodeId,
        from: Int,
        count: Int,
        expiry: Long = NOW + 200,
    ) {
        repeat(count) { assertTrue(holding.hold(unicast(link, from + it, expiry = expiry), HOP_LIMIT, NOW_MILLIS)) }
    }

    private fun unicast(
        from: NodeId,
        id: Int,
        expiry: Long = NOW + LIFETIME,
        size: Int = SIZE,
        destination: ByteArray = tag(9),
    ) = HeldFrame(from, mid(id), destination, expiry, ByteArray(size))

    private fun broadcast(
        from: NodeId,
        id: Int,
        expiry: Long = NOW + LIFETIME,
    ) = HeldFrame(from, mid(id), null, expiry, ByteArray(SIZE))

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
        const val SHARE = Holding.MESSAGES_MAX / Holding.LINK_SHARE_DIVISOR
    }
}

// The live frame with message_id [id], unicast or broadcast, if held.
private fun Holding.find(
    id: ByteArray,
    nowMillis: Long,
): HeldFrame? = (unicasts(nowMillis) + broadcasts(nowMillis)).firstOrNull { it.messageId.contentEquals(id) }
