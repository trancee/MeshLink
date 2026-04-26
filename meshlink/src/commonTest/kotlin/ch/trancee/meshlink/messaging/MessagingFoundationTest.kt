package ch.trancee.meshlink.messaging

import ch.trancee.meshlink.transfer.Priority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MessagingFoundationTest {

    // ── MessageIdKey ─────────────────────────────────────────────────────────

    @Test
    fun `MessageIdKey same reference equals`() {
        val key = MessageIdKey(byteArrayOf(1, 2, 3))
        assertEquals(key, key)
    }

    @Test
    fun `MessageIdKey equal content equals`() {
        val a = MessageIdKey(byteArrayOf(1, 2, 3))
        val b = MessageIdKey(byteArrayOf(1, 2, 3))
        assertFalse(a === b) // different object references
        assertEquals(a, b)
    }

    @Test
    fun `MessageIdKey different content not equals`() {
        val a = MessageIdKey(byteArrayOf(1, 2, 3))
        val b = MessageIdKey(byteArrayOf(1, 2, 4))
        assertNotEquals(a, b)
    }

    @Test
    fun `MessageIdKey other type not equals`() {
        val key = MessageIdKey(byteArrayOf(1))
        assertFalse(key.equals("not a key")) // covers !is MessageIdKey → false branch
    }

    @Test
    fun `MessageIdKey hashCode consistency`() {
        val a = MessageIdKey(byteArrayOf(10, 20, 30))
        val b = MessageIdKey(byteArrayOf(10, 20, 30))
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `MessageIdKey use as HashMap key`() {
        val map = HashMap<MessageIdKey, String>()
        val key = MessageIdKey(byteArrayOf(0xAA.toByte(), 0xBB.toByte()))
        map[key] = "value"
        val lookup = MessageIdKey(byteArrayOf(0xAA.toByte(), 0xBB.toByte()))
        assertEquals("value", map[lookup])
    }

    @Test
    fun `MessageIdKey different length arrays not equal`() {
        val a = MessageIdKey(byteArrayOf(1, 2, 3))
        val b = MessageIdKey(byteArrayOf(1, 2))
        assertNotEquals(a, b)
    }

    @Test
    fun `MessageIdKey empty arrays equal`() {
        val a = MessageIdKey(ByteArray(0))
        val b = MessageIdKey(ByteArray(0))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    // ── PeerPair ─────────────────────────────────────────────────────────────

    @Test
    fun `PeerPair equal`() {
        val neighbor = MessageIdKey(byteArrayOf(1))
        val sender = MessageIdKey(byteArrayOf(2))
        val a = PeerPair(neighbor, sender)
        val b = PeerPair(MessageIdKey(byteArrayOf(1)), MessageIdKey(byteArrayOf(2)))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `PeerPair different neighbor not equal`() {
        val a = PeerPair(MessageIdKey(byteArrayOf(1)), MessageIdKey(byteArrayOf(2)))
        val b = PeerPair(MessageIdKey(byteArrayOf(9)), MessageIdKey(byteArrayOf(2)))
        assertNotEquals(a, b)
    }

    @Test
    fun `PeerPair different sender not equal`() {
        val a = PeerPair(MessageIdKey(byteArrayOf(1)), MessageIdKey(byteArrayOf(2)))
        val b = PeerPair(MessageIdKey(byteArrayOf(1)), MessageIdKey(byteArrayOf(9)))
        assertNotEquals(a, b)
    }

    @Test
    fun `PeerPair use as HashMap key`() {
        val map = HashMap<PeerPair, Int>()
        val pair = PeerPair(MessageIdKey(byteArrayOf(1)), MessageIdKey(byteArrayOf(2)))
        map[pair] = 42
        val lookup = PeerPair(MessageIdKey(byteArrayOf(1)), MessageIdKey(byteArrayOf(2)))
        assertEquals(42, map[lookup])
    }

    // ── SendResult ───────────────────────────────────────────────────────────

    @Test
    fun `SendResult Sent is a singleton`() {
        val a: SendResult = SendResult.Sent
        val b: SendResult = SendResult.Sent
        assertSame(a, b) // object — same reference every time
    }

    @Test
    fun `SendResult Queued carries reason PAUSED`() {
        val r = SendResult.Queued(QueuedReason.PAUSED)
        assertEquals(QueuedReason.PAUSED, r.reason)
    }

    @Test
    fun `SendResult Queued carries reason ROUTE_PENDING`() {
        val r = SendResult.Queued(QueuedReason.ROUTE_PENDING)
        assertEquals(QueuedReason.ROUTE_PENDING, r.reason)
    }

    @Test
    fun `SendResult Queued carries reason TRANSFER_LIMIT`() {
        val r = SendResult.Queued(QueuedReason.TRANSFER_LIMIT)
        assertEquals(QueuedReason.TRANSFER_LIMIT, r.reason)
    }

    @Test
    fun `SendResult Queued equality`() {
        val a = SendResult.Queued(QueuedReason.PAUSED)
        val b = SendResult.Queued(QueuedReason.PAUSED)
        assertEquals(a, b)
    }

    @Test
    fun `SendResult Queued inequality different reason`() {
        assertNotEquals(
            SendResult.Queued(QueuedReason.PAUSED),
            SendResult.Queued(QueuedReason.ROUTE_PENDING),
        )
    }

    // ── InboundMessage ───────────────────────────────────────────────────────

    private fun makeMsg(
        messageId: ByteArray = byteArrayOf(1, 2, 3),
        senderId: ByteArray = byteArrayOf(4, 5, 6),
        payload: ByteArray = byteArrayOf(7, 8),
        priority: Priority = Priority.NORMAL,
        receivedAt: Long = 1_000L,
        kind: MessageKind = MessageKind.UNICAST,
    ) = InboundMessage(messageId, senderId, payload, priority, receivedAt, kind)

    @Test
    fun `InboundMessage same reference equals`() {
        val m = makeMsg()
        assertEquals(m, m)
    }

    @Test
    fun `InboundMessage equal content equals`() {
        assertEquals(makeMsg(), makeMsg())
    }

    @Test
    fun `InboundMessage hashCode consistent`() {
        assertEquals(makeMsg().hashCode(), makeMsg().hashCode())
    }

    @Test
    fun `InboundMessage other type not equals`() {
        assertFalse(makeMsg().equals("not a message")) // !is InboundMessage branch
    }

    // Individual field-change tests cover each false branch in the && chain:

    @Test
    fun `InboundMessage differs priority`() {
        assertFalse(makeMsg(priority = Priority.LOW).equals(makeMsg(priority = Priority.HIGH)))
    }

    @Test
    fun `InboundMessage differs receivedAt`() {
        assertFalse(makeMsg(receivedAt = 1L).equals(makeMsg(receivedAt = 2L)))
    }

    @Test
    fun `InboundMessage differs kind`() {
        assertFalse(
            makeMsg(kind = MessageKind.UNICAST).equals(makeMsg(kind = MessageKind.BROADCAST))
        )
    }

    @Test
    fun `InboundMessage differs messageId`() {
        assertFalse(makeMsg(messageId = byteArrayOf(1)).equals(makeMsg(messageId = byteArrayOf(2))))
    }

    @Test
    fun `InboundMessage differs senderId`() {
        assertFalse(makeMsg(senderId = byteArrayOf(1)).equals(makeMsg(senderId = byteArrayOf(2))))
    }

    @Test
    fun `InboundMessage differs payload`() {
        assertFalse(makeMsg(payload = byteArrayOf(0)).equals(makeMsg(payload = byteArrayOf(1))))
    }

    // ── SlidingWindowRateLimiter ─────────────────────────────────────────────

    @Test
    fun `SlidingWindowRateLimiter empty window allows on first call`() {
        var now = 0L
        val rl = SlidingWindowRateLimiter(limit = 3, windowMillis = 1_000, clock = { now })
        assertTrue(rl.tryAcquire())
    }

    @Test
    fun `SlidingWindowRateLimiter allows up to limit`() {
        var now = 0L
        val rl = SlidingWindowRateLimiter(limit = 3, windowMillis = 1_000, clock = { now })
        assertTrue(rl.tryAcquire())
        assertTrue(rl.tryAcquire())
        assertTrue(rl.tryAcquire())
    }

    @Test
    fun `SlidingWindowRateLimiter rejects at limit plus one`() {
        var now = 0L
        val rl = SlidingWindowRateLimiter(limit = 3, windowMillis = 1_000, clock = { now })
        repeat(3) { assertTrue(rl.tryAcquire()) }
        assertFalse(rl.tryAcquire()) // count >= limit → reject
    }

    @Test
    fun `SlidingWindowRateLimiter window expiry re-allows`() {
        var now = 0L
        val rl = SlidingWindowRateLimiter(limit = 2, windowMillis = 1_000, clock = { now })
        assertTrue(rl.tryAcquire())
        assertTrue(rl.tryAcquire())
        assertFalse(rl.tryAcquire()) // full
        now = 1_001L // advance past the window
        assertTrue(rl.tryAcquire()) // eviction re-allows
    }

    @Test
    fun `SlidingWindowRateLimiter clock-driven partial eviction`() {
        // Add 2 events at t=0, then advance so only the first is expired.
        var now = 0L
        val rl = SlidingWindowRateLimiter(limit = 3, windowMillis = 1_000, clock = { now })
        assertTrue(rl.tryAcquire()) // t=0
        now = 500L
        assertTrue(rl.tryAcquire()) // t=500
        // At t=1001 the first entry (t=0) is expired but the second (t=500) is not.
        now = 1_001L
        assertTrue(rl.tryAcquire()) // evicts t=0, adds t=1001 → count = 2
        assertTrue(rl.tryAcquire()) // count = 3 = limit, this call fills it
        assertFalse(rl.tryAcquire()) // full again
    }

    @Test
    fun `SlidingWindowRateLimiter entry within window blocks eviction`() {
        // Fill to limit, advance to just before window expiry — should still be full.
        var now = 0L
        val rl = SlidingWindowRateLimiter(limit = 2, windowMillis = 1_000, clock = { now })
        assertTrue(rl.tryAcquire())
        assertTrue(rl.tryAcquire())
        now = 999L // still within window
        assertFalse(rl.tryAcquire()) // entries NOT evicted, count still >= limit
    }

    @Test
    fun `SlidingWindowRateLimiter rapid-fire at boundary limit 1`() {
        var now = 0L
        val rl = SlidingWindowRateLimiter(limit = 1, windowMillis = 500, clock = { now })
        assertTrue(rl.tryAcquire())
        assertFalse(rl.tryAcquire()) // same instant, still within window
        now = 500L // exactly at boundary (>= windowMillis)
        assertTrue(rl.tryAcquire()) // evicted, allowed
    }

    @Test
    fun `SlidingWindowRateLimiter limit zero rejects everything`() {
        var now = 0L
        val rl = SlidingWindowRateLimiter(limit = 0, windowMillis = 1_000, clock = { now })
        assertFalse(rl.tryAcquire())
        now = 5_000L
        assertFalse(rl.tryAcquire())
    }

    @Test
    fun `SlidingWindowRateLimiter window zero expires entries immediately`() {
        var now = 0L
        val rl = SlidingWindowRateLimiter(limit = 2, windowMillis = 0, clock = { now })
        assertTrue(rl.tryAcquire()) // records at t=0
        assertTrue(rl.tryAcquire()) // t=0 - t=0 = 0 >= 0 → evicted; count < 2, allowed
        assertTrue(rl.tryAcquire()) // same logic, entries always evicted
    }

    @Test
    fun `SlidingWindowRateLimiter all entries evicted restores full capacity`() {
        var now = 0L
        val rl = SlidingWindowRateLimiter(limit = 3, windowMillis = 100, clock = { now })
        repeat(3) { assertTrue(rl.tryAcquire()) }
        assertFalse(rl.tryAcquire())
        now = 200L // all 3 entries expired
        repeat(3) { assertTrue(rl.tryAcquire()) } // full capacity restored
        assertFalse(rl.tryAcquire())
    }

    // ── MessagingConfig ───────────────────────────────────────────────────────

    private val defaultAppId = byteArrayOf(0x01, 0x02)

    private fun makeConfig(appIdHash: ByteArray = defaultAppId) =
        MessagingConfig(appIdHash = appIdHash)

    @Test
    fun `MessagingConfig default values`() {
        val config = makeConfig()
        assertEquals(2_700_000L, config.highPriorityTtlMillis)
        assertEquals(900_000L, config.normalPriorityTtlMillis)
        assertEquals(300_000L, config.lowPriorityTtlMillis)
        assertEquals(100, config.maxBufferedMessages)
        assertEquals(2u, config.broadcastTtl)
        assertEquals(10_000, config.maxBroadcastSize)
        assertEquals(102_400, config.maxMessageSize)
        assertTrue(config.requireBroadcastSignatures)
        assertFalse(config.allowUnsignedBroadcasts)
        assertEquals(60, config.outboundUnicastLimit)
        assertEquals(60_000L, config.outboundUnicastWindowMillis)
        assertEquals(10, config.broadcastLimit)
        assertEquals(60_000L, config.broadcastWindowMillis)
        assertEquals(20, config.relayPerSenderPerNeighborLimit)
        assertEquals(60_000L, config.relayPerSenderPerNeighborWindowMillis)
        assertEquals(100, config.perNeighborAggregateLimit)
        assertEquals(60_000L, config.perNeighborAggregateWindowMillis)
        assertEquals(30, config.perSenderInboundLimit)
        assertEquals(60_000L, config.perSenderInboundWindowMillis)
        assertEquals(1, config.handshakeLimit)
        assertEquals(1_000L, config.handshakeWindowMillis)
        assertEquals(10, config.nackLimit)
        assertEquals(1_000L, config.nackWindowMillis)
        assertEquals(MessagingConfig.CircuitBreakerConfig(), config.circuitBreaker)
    }

    @Test
    fun `MessagingConfig CircuitBreakerConfig defaults`() {
        val cb = MessagingConfig.CircuitBreakerConfig()
        assertEquals(60_000L, cb.windowMillis)
        assertEquals(3, cb.maxFailures)
        assertEquals(30_000L, cb.cooldownMillis)
    }

    @Test
    fun `MessagingConfig custom values`() {
        val cb =
            MessagingConfig.CircuitBreakerConfig(
                windowMillis = 10_000,
                maxFailures = 5,
                cooldownMillis = 5_000,
            )
        val config =
            MessagingConfig(
                highPriorityTtlMillis = 1_000,
                normalPriorityTtlMillis = 2_000,
                lowPriorityTtlMillis = 3_000,
                maxBufferedMessages = 50,
                broadcastTtl = 3u,
                maxBroadcastSize = 5_000,
                maxMessageSize = 50_000,
                appIdHash = byteArrayOf(0xAB.toByte(), 0xCD.toByte()),
                requireBroadcastSignatures = false,
                allowUnsignedBroadcasts = true,
                outboundUnicastLimit = 30,
                outboundUnicastWindowMillis = 30_000,
                broadcastLimit = 5,
                broadcastWindowMillis = 30_000,
                relayPerSenderPerNeighborLimit = 10,
                relayPerSenderPerNeighborWindowMillis = 30_000,
                perNeighborAggregateLimit = 50,
                perNeighborAggregateWindowMillis = 30_000,
                perSenderInboundLimit = 15,
                perSenderInboundWindowMillis = 30_000,
                handshakeLimit = 2,
                handshakeWindowMillis = 2_000,
                nackLimit = 5,
                nackWindowMillis = 500,
                circuitBreaker = cb,
            )
        assertEquals(1_000L, config.highPriorityTtlMillis)
        assertEquals(2_000L, config.normalPriorityTtlMillis)
        assertEquals(3_000L, config.lowPriorityTtlMillis)
        assertEquals(50, config.maxBufferedMessages)
        assertEquals(3u, config.broadcastTtl)
        assertEquals(5_000, config.maxBroadcastSize)
        assertEquals(50_000, config.maxMessageSize)
        assertFalse(config.requireBroadcastSignatures)
        assertTrue(config.allowUnsignedBroadcasts)
        assertEquals(30, config.outboundUnicastLimit)
        assertEquals(30_000L, config.outboundUnicastWindowMillis)
        assertEquals(5, config.broadcastLimit)
        assertEquals(30_000L, config.broadcastWindowMillis)
        assertEquals(10, config.relayPerSenderPerNeighborLimit)
        assertEquals(30_000L, config.relayPerSenderPerNeighborWindowMillis)
        assertEquals(50, config.perNeighborAggregateLimit)
        assertEquals(30_000L, config.perNeighborAggregateWindowMillis)
        assertEquals(15, config.perSenderInboundLimit)
        assertEquals(30_000L, config.perSenderInboundWindowMillis)
        assertEquals(2, config.handshakeLimit)
        assertEquals(2_000L, config.handshakeWindowMillis)
        assertEquals(5, config.nackLimit)
        assertEquals(500L, config.nackWindowMillis)
        assertEquals(cb, config.circuitBreaker)
    }

    @Test
    fun `MessagingConfig same reference equals`() {
        val config = makeConfig()
        assertEquals(config, config)
    }

    @Test
    fun `MessagingConfig equal appIdHash equals`() {
        val a = makeConfig(byteArrayOf(1, 2, 3))
        val b = makeConfig(byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `MessagingConfig different appIdHash not equals`() {
        val a = makeConfig(byteArrayOf(1, 2, 3))
        val b = makeConfig(byteArrayOf(1, 2, 4))
        assertNotEquals(a, b)
    }

    @Test
    fun `MessagingConfig other type not equals`() {
        assertFalse(makeConfig().equals(42)) // !is MessagingConfig branch
    }

    // Individual field-change tests — cover each && false branch in equals():
    // equals order: highPriorityTtlMillis, normalPriorityTtlMillis, lowPriorityTtlMillis,
    //   maxBufferedMessages, broadcastTtl, maxBroadcastSize, maxMessageSize,
    //   appIdHash, requireBroadcastSignatures, allowUnsignedBroadcasts,
    //   outboundUnicastLimit, outboundUnicastWindowMillis, broadcastLimit, broadcastWindowMillis,
    //   relayPerSenderPerNeighborLimit, relayPerSenderPerNeighborWindowMillis,
    //   perNeighborAggregateLimit, perNeighborAggregateWindowMillis,
    //   perSenderInboundLimit, perSenderInboundWindowMillis,
    //   handshakeLimit, handshakeWindowMillis, nackLimit, nackWindowMillis, circuitBreaker

    @Test
    fun `MessagingConfig differs highPriorityTtlMillis`() {
        val base = makeConfig()
        assertFalse(base.equals(base.copy(highPriorityTtlMillis = 1L)))
    }

    @Test
    fun `MessagingConfig differs normalPriorityTtlMillis`() {
        val base = makeConfig()
        assertFalse(base.equals(base.copy(normalPriorityTtlMillis = 1L)))
    }

    @Test
    fun `MessagingConfig differs lowPriorityTtlMillis`() {
        val base = makeConfig()
        assertFalse(base.equals(base.copy(lowPriorityTtlMillis = 1L)))
    }

    @Test
    fun `MessagingConfig differs maxBufferedMessages`() {
        val base = makeConfig()
        assertFalse(base.equals(base.copy(maxBufferedMessages = 1)))
    }

    @Test
    fun `MessagingConfig differs broadcastTtl`() {
        val base = makeConfig()
        assertFalse(base.equals(base.copy(broadcastTtl = 9u)))
    }

    @Test
    fun `MessagingConfig differs maxBroadcastSize`() {
        val base = makeConfig()
        assertFalse(base.equals(base.copy(maxBroadcastSize = 1)))
    }

    @Test
    fun `MessagingConfig differs maxMessageSize`() {
        val base = makeConfig()
        assertFalse(base.equals(base.copy(maxMessageSize = 1)))
    }

    @Test
    fun `MessagingConfig differs requireBroadcastSignatures`() {
        val base = makeConfig()
        assertFalse(base.equals(base.copy(requireBroadcastSignatures = false)))
    }

    @Test
    fun `MessagingConfig differs allowUnsignedBroadcasts`() {
        val base = makeConfig()
        assertFalse(base.equals(base.copy(allowUnsignedBroadcasts = true)))
    }

    @Test
    fun `MessagingConfig differs outboundUnicastLimit`() {
        val base = makeConfig()
        assertFalse(base.equals(base.copy(outboundUnicastLimit = 1)))
    }

    @Test
    fun `MessagingConfig differs outboundUnicastWindowMillis`() {
        val base = makeConfig()
        assertFalse(base.equals(base.copy(outboundUnicastWindowMillis = 1L)))
    }

    @Test
    fun `MessagingConfig differs broadcastLimit`() {
        val base = makeConfig()
        assertFalse(base.equals(base.copy(broadcastLimit = 1)))
    }

    @Test
    fun `MessagingConfig differs broadcastWindowMillis`() {
        val base = makeConfig()
        assertFalse(base.equals(base.copy(broadcastWindowMillis = 1L)))
    }

    @Test
    fun `MessagingConfig differs relayPerSenderPerNeighborLimit`() {
        val base = makeConfig()
        assertFalse(base.equals(base.copy(relayPerSenderPerNeighborLimit = 1)))
    }

    @Test
    fun `MessagingConfig differs relayPerSenderPerNeighborWindowMillis`() {
        val base = makeConfig()
        assertFalse(base.equals(base.copy(relayPerSenderPerNeighborWindowMillis = 1L)))
    }

    @Test
    fun `MessagingConfig differs perNeighborAggregateLimit`() {
        val base = makeConfig()
        assertFalse(base.equals(base.copy(perNeighborAggregateLimit = 1)))
    }

    @Test
    fun `MessagingConfig differs perNeighborAggregateWindowMillis`() {
        val base = makeConfig()
        assertFalse(base.equals(base.copy(perNeighborAggregateWindowMillis = 1L)))
    }

    @Test
    fun `MessagingConfig differs perSenderInboundLimit`() {
        val base = makeConfig()
        assertFalse(base.equals(base.copy(perSenderInboundLimit = 1)))
    }

    @Test
    fun `MessagingConfig differs perSenderInboundWindowMillis`() {
        val base = makeConfig()
        assertFalse(base.equals(base.copy(perSenderInboundWindowMillis = 1L)))
    }

    @Test
    fun `MessagingConfig differs handshakeLimit`() {
        val base = makeConfig()
        assertFalse(base.equals(base.copy(handshakeLimit = 99)))
    }

    @Test
    fun `MessagingConfig differs handshakeWindowMillis`() {
        val base = makeConfig()
        assertFalse(base.equals(base.copy(handshakeWindowMillis = 1L)))
    }

    @Test
    fun `MessagingConfig differs nackLimit`() {
        val base = makeConfig()
        assertFalse(base.equals(base.copy(nackLimit = 1)))
    }

    @Test
    fun `MessagingConfig differs nackWindowMillis`() {
        val base = makeConfig()
        assertFalse(base.equals(base.copy(nackWindowMillis = 1L)))
    }

    @Test
    fun `MessagingConfig differs circuitBreaker`() {
        val base = makeConfig()
        assertFalse(
            base.equals(
                base.copy(circuitBreaker = MessagingConfig.CircuitBreakerConfig(maxFailures = 99))
            )
        )
    }
}
