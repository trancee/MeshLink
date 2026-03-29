package io.meshlink.util

import io.meshlink.config.MeshLinkConfig
import kotlin.test.Test
import kotlin.test.assertIs

class RateLimitPolicyTest {

    // ── 1. Send rate limiting ─────────────────────────────────────

    @Test
    fun sendAllowedWhenUnderLimit() {
        val policy = RateLimitPolicy(MeshLinkConfig(rateLimitMaxSends = 2, rateLimitWindowMillis = 1000L))
        assertIs<RateLimitResult.Allowed>(policy.checkSend("peer1"))
        assertIs<RateLimitResult.Allowed>(policy.checkSend("peer1"))
    }

    @Test
    fun sendLimitedWhenExceeded() {
        val policy = RateLimitPolicy(MeshLinkConfig(rateLimitMaxSends = 1, rateLimitWindowMillis = 1000L))
        policy.checkSend("peer1")
        val result = policy.checkSend("peer1")
        assertIs<RateLimitResult.Limited>(result)
        kotlin.test.assertEquals("send", result.scope)
        kotlin.test.assertEquals("peer1", result.key)
    }

    @Test
    fun sendDisabledWhenZero() {
        val policy = RateLimitPolicy(MeshLinkConfig(rateLimitMaxSends = 0))
        assertIs<RateLimitResult.Allowed>(policy.checkSend("peer1"))
    }

    // ── 2. Circuit breaker ────────────────────────────────────────

    @Test
    fun circuitBreakerAllowsWhenClosed() {
        val policy = RateLimitPolicy(MeshLinkConfig(circuitBreakerMaxFailures = 3, circuitBreakerWindowMillis = 1000L))
        assertIs<RateLimitResult.Allowed>(policy.checkCircuitBreaker())
    }

    @Test
    fun circuitBreakerTripsAfterFailures() {
        val policy = RateLimitPolicy(MeshLinkConfig(
            circuitBreakerMaxFailures = 2,
            circuitBreakerWindowMillis = 60_000L,
            circuitBreakerCooldownMillis = 5000L,
        ))
        policy.recordTransportFailure()
        policy.recordTransportFailure()
        val result = policy.checkCircuitBreaker()
        assertIs<RateLimitResult.Limited>(result)
        kotlin.test.assertEquals("circuit_breaker", result.scope)
    }

    @Test
    fun circuitBreakerResetsAfterCooldown() {
        var now = 0L
        val policy = RateLimitPolicy(
            MeshLinkConfig(circuitBreakerMaxFailures = 1, circuitBreakerWindowMillis = 60_000L, circuitBreakerCooldownMillis = 1000L),
            clock = { now },
        )
        policy.recordTransportFailure()
        assertIs<RateLimitResult.Limited>(policy.checkCircuitBreaker())
        now = 1000L
        assertIs<RateLimitResult.Allowed>(policy.checkCircuitBreaker())
    }

    @Test
    fun circuitBreakerDisabledWhenZero() {
        val policy = RateLimitPolicy(MeshLinkConfig(circuitBreakerMaxFailures = 0))
        assertIs<RateLimitResult.Allowed>(policy.checkCircuitBreaker())
    }

    // ── 3. Broadcast rate limiting ────────────────────────────────

    @Test
    fun broadcastLimitedWhenExceeded() {
        val policy = RateLimitPolicy(MeshLinkConfig(broadcastRateLimitPerMinute = 1))
        policy.checkBroadcast()
        assertIs<RateLimitResult.Limited>(policy.checkBroadcast())
    }

    @Test
    fun broadcastDisabledWhenZero() {
        val policy = RateLimitPolicy(MeshLinkConfig(broadcastRateLimitPerMinute = 0))
        assertIs<RateLimitResult.Allowed>(policy.checkBroadcast())
    }

    // ── 4. Handshake rate limiting ────────────────────────────────

    @Test
    fun handshakeLimitedPerPeer() {
        val policy = RateLimitPolicy(MeshLinkConfig(handshakeRateLimitPerSec = 1))
        policy.checkHandshake("peer1")
        assertIs<RateLimitResult.Limited>(policy.checkHandshake("peer1"))
        // Different peer is still allowed
        assertIs<RateLimitResult.Allowed>(policy.checkHandshake("peer2"))
    }

    // ── 5. NACK rate limiting ─────────────────────────────────────

    @Test
    fun nackLimitedPerPeer() {
        val policy = RateLimitPolicy(MeshLinkConfig(nackRateLimitPerSec = 1))
        policy.checkNack("peer1")
        assertIs<RateLimitResult.Limited>(policy.checkNack("peer1"))
    }

    // ── 6. Neighbor aggregate ─────────────────────────────────────

    @Test
    fun neighborAggregateLimitedPerPeer() {
        val policy = RateLimitPolicy(MeshLinkConfig(neighborAggregateLimitPerMin = 2))
        policy.checkNeighborAggregate("peer1")
        policy.checkNeighborAggregate("peer1")
        assertIs<RateLimitResult.Limited>(policy.checkNeighborAggregate("peer1"))
        // Different peer still allowed
        assertIs<RateLimitResult.Allowed>(policy.checkNeighborAggregate("peer2"))
    }

    // ── 7. Sender-neighbor relay ──────────────────────────────────

    @Test
    fun senderNeighborRelayLimited() {
        val policy = RateLimitPolicy(MeshLinkConfig(senderNeighborLimitPerMin = 1))
        policy.checkSenderNeighborRelay("origin1", "neighbor1")
        val result = policy.checkSenderNeighborRelay("origin1", "neighbor1")
        assertIs<RateLimitResult.Limited>(result)
        kotlin.test.assertEquals("sender_neighbor", result.scope)
        // Different pair still allowed
        assertIs<RateLimitResult.Allowed>(policy.checkSenderNeighborRelay("origin2", "neighbor1"))
    }

    // ── 8. Independent scopes ─────────────────────────────────────

    @Test
    fun differentScopesAreIndependent() {
        val policy = RateLimitPolicy(MeshLinkConfig(
            rateLimitMaxSends = 1, rateLimitWindowMillis = 1000L,
            broadcastRateLimitPerMinute = 1,
            handshakeRateLimitPerSec = 1,
        ))
        // Exhaust all three
        policy.checkSend("peer1")
        policy.checkBroadcast()
        policy.checkHandshake("peer1")
        // All three are now limited
        assertIs<RateLimitResult.Limited>(policy.checkSend("peer1"))
        assertIs<RateLimitResult.Limited>(policy.checkBroadcast())
        assertIs<RateLimitResult.Limited>(policy.checkHandshake("peer1"))
        // But different keys in per-peer scopes are still allowed
        assertIs<RateLimitResult.Allowed>(policy.checkSend("peer2"))
        assertIs<RateLimitResult.Allowed>(policy.checkHandshake("peer2"))
    }
}
