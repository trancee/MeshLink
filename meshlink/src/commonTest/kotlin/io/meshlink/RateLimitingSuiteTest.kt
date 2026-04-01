package io.meshlink

import io.meshlink.config.MeshLinkConfig
import io.meshlink.config.meshLinkConfig
import io.meshlink.diagnostics.DiagnosticCode
import io.meshlink.diagnostics.DiagnosticEvent
import io.meshlink.diagnostics.Severity
import io.meshlink.transport.VirtualMeshTransport
import io.meshlink.util.ByteArrayKey
import io.meshlink.util.RateLimiter
import io.meshlink.wire.WireCodec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalUuidApi::class)
class RateLimitingSuiteTest {

    private fun key(s: String) = ByteArrayKey(s.encodeToByteArray())

    private val peerIdAlice = ByteArray(16) { (0xA0 + it).toByte() }
    private val peerIdBob = ByteArray(16) { (0xB0 + it).toByte() }
    private val peerIdCarol = ByteArray(16) { (0xC0 + it).toByte() }

    // ── Config defaults ──

    @Test
    fun configDefaultsAreCorrect() {
        val config = MeshLinkConfig()
        assertEquals(1, config.handshakeRateLimitPerSec)
        assertEquals(10, config.nackRateLimitPerSec)
        assertEquals(100, config.neighborAggregateLimitPerMin)
        assertEquals(20, config.senderNeighborLimitPerMin)
        assertEquals(60, config.rateLimitMaxSends)
        assertEquals(30, config.inboundRateLimitPerSenderPerMinute)
        assertEquals(10, config.broadcastRateLimitPerMinute)
    }

    @Test
    fun configFieldsAreOverridable() {
        val config = MeshLinkConfig(
            handshakeRateLimitPerSec = 5,
            nackRateLimitPerSec = 20,
            neighborAggregateLimitPerMin = 200,
            senderNeighborLimitPerMin = 50,
        )
        assertEquals(5, config.handshakeRateLimitPerSec)
        assertEquals(20, config.nackRateLimitPerSec)
        assertEquals(200, config.neighborAggregateLimitPerMin)
        assertEquals(50, config.senderNeighborLimitPerMin)
    }

    @Test
    fun builderDslOverridesRateLimitFields() {
        val config = meshLinkConfig {
            requireEncryption = false
            handshakeRateLimitPerSec = 3
            nackRateLimitPerSec = 15
            neighborAggregateLimitPerMin = 500
            senderNeighborLimitPerMin = 40
        }
        assertEquals(3, config.handshakeRateLimitPerSec)
        assertEquals(15, config.nackRateLimitPerSec)
        assertEquals(500, config.neighborAggregateLimitPerMin)
        assertEquals(40, config.senderNeighborLimitPerMin)
    }

    @Test
    fun presetIncludesNewRateLimitFields() {
        val chat = MeshLinkConfig.chatOptimized {
            handshakeRateLimitPerSec = 2
        }
        assertEquals(2, chat.handshakeRateLimitPerSec)
        // Non-overridden should keep defaults
        assertEquals(10, chat.nackRateLimitPerSec)
        assertEquals(100, chat.neighborAggregateLimitPerMin)
        assertEquals(20, chat.senderNeighborLimitPerMin)
    }

    // ── Handshake rate limiter (unit) ──

    @Test
    fun handshakeRateLimiterAllows1PerSecRejectsExcess() {
        var now = 0L
        val limiter = RateLimiter(maxEvents = 1, windowMillis = 1_000L, clock = { now })
        assertTrue(limiter.tryAcquire(key("peer-a")), "First handshake allowed")
        assertFalse(limiter.tryAcquire(key("peer-a")), "Second handshake within 1s rejected")
        // Different peer is independent
        assertTrue(limiter.tryAcquire(key("peer-b")), "Different peer allowed")
        // After window expires
        now = 1_001L
        assertTrue(limiter.tryAcquire(key("peer-a")), "After window, allowed again")
    }

    // ── NACK rate limiter (unit) ──

    @Test
    fun nackRateLimiterAllows10PerSecRejectsExcess() {
        var now = 0L
        val limiter = RateLimiter(maxEvents = 10, windowMillis = 1_000L, clock = { now })
        val limiterKey = key("neighbor-x")
        repeat(10) { i ->
            assertTrue(limiter.tryAcquire(limiterKey), "NACK ${i + 1} allowed")
        }
        assertFalse(limiter.tryAcquire(limiterKey), "11th NACK rejected")
        now = 1_001L
        assertTrue(limiter.tryAcquire(limiterKey), "After window, allowed again")
    }

    // ── Neighbor aggregate rate limiter (unit) ──

    @Test
    fun neighborAggregateLimiterAllows100PerMinRejectsExcess() {
        var now = 0L
        val limiter = RateLimiter(maxEvents = 100, windowMillis = 60_000L, clock = { now })
        val limiterKey = key("neighbor-y")
        repeat(100) { i ->
            assertTrue(limiter.tryAcquire(limiterKey), "Message ${i + 1} allowed")
        }
        assertFalse(limiter.tryAcquire(limiterKey), "101st message rejected")
        now = 60_001L
        assertTrue(limiter.tryAcquire(limiterKey), "After window, allowed again")
    }

    // ── Sender-neighbor rate limiter (unit) ──

    @Test
    fun senderNeighborLimiterAllows20PerMinRejectsExcess() {
        var now = 0L
        val limiter = RateLimiter(maxEvents = 20, windowMillis = 60_000L, clock = { now })
        val limiterKey = key("sender-a->neighbor-b")
        repeat(20) { i ->
            assertTrue(limiter.tryAcquire(limiterKey), "Relay ${i + 1} allowed")
        }
        assertFalse(limiter.tryAcquire(limiterKey), "21st relay rejected")
        // Different sender-neighbor pair is independent
        assertTrue(limiter.tryAcquire(key("sender-c->neighbor-b")), "Different pair allowed")
        now = 60_001L
        assertTrue(limiter.tryAcquire(limiterKey), "After window, allowed again")
    }

    // ── NACK integration: sendNack emits diagnostic on rate limit ──

    @Test
    fun sendNackEmitsRateLimitDiagnostic() = runTest {
        val transport = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transport.linkTo(transportBob)

        val config = meshLinkConfig {
            requireEncryption = false
            nackRateLimitPerSec = 2
            neighborAggregateLimitPerMin = 0 // disable to isolate NACK limiter
        }

        val alice = MeshLink(transport, config, coroutineContext)
        alice.start()
        advanceUntilIdle()

        // Discover Bob so transport links work
        transport.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        val messageId = ByteArray(16) { it.toByte() }
        // First 2 NACKs should succeed
        alice.sendNack(peerIdBob, messageId)
        alice.sendNack(peerIdBob, messageId)
        // 3rd NACK should be rate-limited
        alice.sendNack(peerIdBob, messageId)
        advanceUntilIdle()

        @Suppress("DEPRECATION")
        val diagnostics = mutableListOf<DiagnosticEvent>()
        alice.drainDiagnostics().forEach { diagnostics.add(it) }

        val rateLimitEvents = diagnostics.filter {
            it.code == DiagnosticCode.RATE_LIMIT_HIT && it.payload?.contains("NACK") == true
        }
        assertTrue(rateLimitEvents.isNotEmpty(), "Should emit RATE_LIMIT_HIT for NACK: $diagnostics")
        alice.stop()
    }

    // ── Neighbor aggregate integration: safeSend drops when over limit ──

    @Test
    fun neighborAggregateLimitDropsExcessMessages() = runTest {
        val transport = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transport.linkTo(transportBob)

        val config = meshLinkConfig {
            requireEncryption = false
            neighborAggregateLimitPerMin = 3
        }

        val alice = MeshLink(transport, config, coroutineContext)
        alice.start()
        advanceUntilIdle()
        transport.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        // Send messages that go through safeSend (broadcasts go to all known peers)
        // Each broadcast generates 1 safeSend call per known peer
        alice.broadcast(byteArrayOf(1), 1u)
        alice.broadcast(byteArrayOf(2), 1u)
        alice.broadcast(byteArrayOf(3), 1u)
        // The 4th should be dropped by aggregate rate limit
        alice.broadcast(byteArrayOf(4), 1u)
        advanceUntilIdle()

        @Suppress("DEPRECATION")
        val diagnostics = mutableListOf<DiagnosticEvent>()
        alice.drainDiagnostics().forEach { diagnostics.add(it) }

        val aggregateHits = diagnostics.filter {
            it.code == DiagnosticCode.RATE_LIMIT_HIT && it.payload?.contains("neighbor aggregate") == true
        }
        assertTrue(aggregateHits.isNotEmpty(), "Should emit aggregate RATE_LIMIT_HIT: $diagnostics")
        alice.stop()
    }

    // ── Disabling rate limiters with 0 ──

    @Test
    fun zeroDisablesRateLimiters() = runTest {
        val transport = VirtualMeshTransport(peerIdAlice)
        val transportBob = VirtualMeshTransport(peerIdBob)
        transport.linkTo(transportBob)

        val config = meshLinkConfig {
            requireEncryption = false
            handshakeRateLimitPerSec = 0
            nackRateLimitPerSec = 0
            neighborAggregateLimitPerMin = 0
            senderNeighborLimitPerMin = 0
        }

        val alice = MeshLink(transport, config, coroutineContext)
        alice.start()
        advanceUntilIdle()
        transport.simulateDiscovery(peerIdBob)
        advanceUntilIdle()

        // Should be able to send many NACKs without rate limiting
        val messageId = ByteArray(16) { it.toByte() }
        repeat(50) { alice.sendNack(peerIdBob, messageId) }
        advanceUntilIdle()

        // Collect diagnostics
        @Suppress("DEPRECATION")
        val diagnostics = mutableListOf<DiagnosticEvent>()
        alice.drainDiagnostics().forEach { diagnostics.add(it) }

        val rateLimitHits = diagnostics.filter { it.code == DiagnosticCode.RATE_LIMIT_HIT }
        assertTrue(rateLimitHits.isEmpty(), "No rate limit hits when disabled: $rateLimitHits")
        alice.stop()
    }

    // ── WireCodec NACK encode/decode ──

    @Test
    fun nackEncodeDecodeRoundTrip() {
        val messageId = ByteArray(16) { (0x42 + it).toByte() }
        val encoded = WireCodec.encodeNack(messageId)
        assertEquals(WireCodec.TYPE_NACK, encoded[0])
        val decoded = WireCodec.decodeNack(encoded)
        assertTrue(decoded.messageId.contentEquals(messageId), "Message ID should round-trip")
    }
}
