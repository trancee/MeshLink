package io.meshlink.util

import io.meshlink.config.meshLinkConfig
import kotlin.test.Test
import kotlin.test.assertIs

class RateLimitPolicyTest {

    private fun key(s: String) = ByteArrayKey(s.encodeToByteArray())

    // ── 1. Send rate limiting ─────────────────────────────────────

    @Test
    fun sendAllowedWhenUnderLimit() {
        val policy = RateLimitPolicy(meshLinkConfig { rateLimitMaxSends = 2; rateLimitWindowMillis = 1000L })
        assertIs<RateLimitResult.Allowed>(policy.checkSend(key("peer1")))
        assertIs<RateLimitResult.Allowed>(policy.checkSend(key("peer1")))
    }

    @Test
    fun sendLimitedWhenExceeded() {
        val policy = RateLimitPolicy(meshLinkConfig { rateLimitMaxSends = 1; rateLimitWindowMillis = 1000L })
        policy.checkSend(key("peer1"))
        val result = policy.checkSend(key("peer1"))
        assertIs<RateLimitResult.Limited>(result)
        kotlin.test.assertEquals("send", result.scope)
        kotlin.test.assertEquals(key("peer1").toString(), result.key)
    }

    @Test
    fun sendDisabledWhenZero() {
        val policy = RateLimitPolicy(meshLinkConfig { rateLimitMaxSends = 0 })
        assertIs<RateLimitResult.Allowed>(policy.checkSend(key("peer1")))
    }

    // ── 2. Circuit breaker ────────────────────────────────────────

    @Test
    fun circuitBreakerAllowsWhenClosed() {
        val policy = RateLimitPolicy(
            meshLinkConfig { circuitBreakerMaxFailures = 3; circuitBreakerWindowMillis = 1000L }
        )
        assertIs<RateLimitResult.Allowed>(policy.checkCircuitBreaker())
    }

    @Test
    fun circuitBreakerTripsAfterFailures() {
        val policy = RateLimitPolicy(meshLinkConfig {
            circuitBreakerMaxFailures = 2
            circuitBreakerWindowMillis = 60_000L
            circuitBreakerCooldownMillis = 5000L
        })
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
            meshLinkConfig {
                circuitBreakerMaxFailures = 1
                circuitBreakerWindowMillis = 60_000L
                circuitBreakerCooldownMillis = 1000L
            },
            clock = { now },
        )
        policy.recordTransportFailure()
        assertIs<RateLimitResult.Limited>(policy.checkCircuitBreaker())
        now = 1000L
        assertIs<RateLimitResult.Allowed>(policy.checkCircuitBreaker())
    }

    @Test
    fun circuitBreakerDisabledWhenZero() {
        val policy = RateLimitPolicy(meshLinkConfig { circuitBreakerMaxFailures = 0 })
        assertIs<RateLimitResult.Allowed>(policy.checkCircuitBreaker())
    }

    // ── 3. Broadcast rate limiting ────────────────────────────────

    @Test
    fun broadcastLimitedWhenExceeded() {
        val policy = RateLimitPolicy(meshLinkConfig { broadcastRateLimitPerMin = 1 })
        policy.checkBroadcast()
        assertIs<RateLimitResult.Limited>(policy.checkBroadcast())
    }

    @Test
    fun broadcastDisabledWhenZero() {
        val policy = RateLimitPolicy(meshLinkConfig { broadcastRateLimitPerMin = 0 })
        assertIs<RateLimitResult.Allowed>(policy.checkBroadcast())
    }

    // ── 4. Handshake rate limiting ────────────────────────────────

    @Test
    fun handshakeLimitedPerPeer() {
        val policy = RateLimitPolicy(meshLinkConfig { handshakeRateLimitPerSec = 1 })
        policy.checkHandshake(key("peer1"))
        assertIs<RateLimitResult.Limited>(policy.checkHandshake(key("peer1")))
        // Different peer is still allowed
        assertIs<RateLimitResult.Allowed>(policy.checkHandshake(key("peer2")))
    }

    // ── 5. NACK rate limiting ─────────────────────────────────────

    @Test
    fun nackLimitedPerPeer() {
        val policy = RateLimitPolicy(meshLinkConfig { nackRateLimitPerSec = 1 })
        policy.checkNack(key("peer1"))
        assertIs<RateLimitResult.Limited>(policy.checkNack(key("peer1")))
    }

    // ── 6. Neighbor aggregate ─────────────────────────────────────

    @Test
    fun neighborAggregateLimitedPerPeer() {
        val policy = RateLimitPolicy(meshLinkConfig { neighborAggregateLimitPerMin = 2 })
        policy.checkNeighborAggregate(key("peer1"))
        policy.checkNeighborAggregate(key("peer1"))
        assertIs<RateLimitResult.Limited>(policy.checkNeighborAggregate(key("peer1")))
        // Different peer still allowed
        assertIs<RateLimitResult.Allowed>(policy.checkNeighborAggregate(key("peer2")))
    }

    // ── 7. Sender-neighbor relay ──────────────────────────────────

    @Test
    fun senderNeighborRelayLimited() {
        val policy = RateLimitPolicy(meshLinkConfig { senderNeighborLimitPerMin = 1 })
        policy.checkSenderNeighborRelay(key("origin1"), key("neighbor1"))
        val result = policy.checkSenderNeighborRelay(key("origin1"), key("neighbor1"))
        assertIs<RateLimitResult.Limited>(result)
        kotlin.test.assertEquals("sender_neighbor", result.scope)
        // Different pair still allowed
        assertIs<RateLimitResult.Allowed>(policy.checkSenderNeighborRelay(key("origin2"), key("neighbor1")))
    }

    // ── 8. Independent scopes ─────────────────────────────────────

    @Test
    fun differentScopesAreIndependent() {
        val policy = RateLimitPolicy(meshLinkConfig {
            rateLimitMaxSends = 1; rateLimitWindowMillis = 1000L
            broadcastRateLimitPerMin = 1
            handshakeRateLimitPerSec = 1
        })
        // Exhaust all three
        policy.checkSend(key("peer1"))
        policy.checkBroadcast()
        policy.checkHandshake(key("peer1"))
        // All three are now limited
        assertIs<RateLimitResult.Limited>(policy.checkSend(key("peer1")))
        assertIs<RateLimitResult.Limited>(policy.checkBroadcast())
        assertIs<RateLimitResult.Limited>(policy.checkHandshake(key("peer1")))
        // But different keys in per-peer scopes are still allowed
        assertIs<RateLimitResult.Allowed>(policy.checkSend(key("peer2")))
        assertIs<RateLimitResult.Allowed>(policy.checkHandshake(key("peer2")))
    }
}
