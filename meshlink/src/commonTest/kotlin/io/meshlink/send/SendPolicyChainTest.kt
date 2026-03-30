package io.meshlink.send

import io.meshlink.routing.NextHopResult
import io.meshlink.util.ByteArrayKey
import io.meshlink.util.RateLimitResult
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertEquals

class SendPolicyChainTest {

    private val self = byteArrayOf(0x01, 0x02)
    private val peer = byteArrayOf(0x0A, 0x0B)

    private fun key(s: String) = ByteArrayKey(s.encodeToByteArray())

    private fun chain(
        bufferCapacity: Int = 1024,
        isPaused: () -> Boolean = { false },
        checkSendRate: (ByteArrayKey) -> RateLimitResult = { RateLimitResult.Allowed },
        checkCircuitBreaker: () -> RateLimitResult = { RateLimitResult.Allowed },
        resolveNextHop: (ByteArrayKey) -> NextHopResult = { NextHopResult.Direct(it) },
        peerPublicKey: ((ByteArrayKey) -> ByteArray?)? = null,
    ) = SendPolicyChain(
        bufferCapacity = bufferCapacity,
        localPeerId = self,
        isPaused = isPaused,
        checkSendRate = checkSendRate,
        checkCircuitBreaker = checkCircuitBreaker,
        resolveNextHop = resolveNextHop,
        peerPublicKey = peerPublicKey,
    )

    // ── 1. Individual checks ─────────────────────────────────────

    @Test
    fun bufferFullRejectsOversizedPayload() {
        val c = chain(bufferCapacity = 100)
        assertIs<SendDecision.BufferFull>(c.evaluate(peer, payloadSize = 101))
    }

    @Test
    fun selfSendReturnsLoopback() {
        val c = chain()
        assertIs<SendDecision.Loopback>(c.evaluate(self, payloadSize = 10))
    }

    @Test
    fun pausedReturnsPaused() {
        val c = chain(isPaused = { true })
        assertIs<SendDecision.Paused>(c.evaluate(peer, payloadSize = 10))
    }

    @Test
    fun rateLimitedReturnsRateLimited() {
        val c = chain(checkSendRate = { RateLimitResult.Limited("send", it.toString()) })
        val result = c.evaluate(peer, payloadSize = 10)
        assertIs<SendDecision.RateLimited>(result)
    }

    @Test
    fun circuitBreakerOpenReturnsCircuitBreakerOpen() {
        val c = chain(checkCircuitBreaker = { RateLimitResult.Limited("cb", "global") })
        assertIs<SendDecision.CircuitBreakerOpen>(c.evaluate(peer, payloadSize = 10))
    }

    @Test
    fun unreachableReturnsUnreachable() {
        val c = chain(resolveNextHop = { NextHopResult.Unreachable })
        assertIs<SendDecision.Unreachable>(c.evaluate(peer, payloadSize = 10))
    }

    @Test
    fun routedReturnsRoutedWithNextHop() {
        val c = chain(resolveNextHop = { NextHopResult.ViaRoute(key("deadbeef")) })
        val result = c.evaluate(peer, payloadSize = 10)
        assertIs<SendDecision.Routed>(result)
        assertEquals(key("deadbeef"), result.nextHopId)
    }

    @Test
    fun missingPublicKeyWhenCryptoEnabled() {
        val c = chain(peerPublicKey = { null })
        assertIs<SendDecision.MissingPublicKey>(c.evaluate(peer, payloadSize = 10))
    }

    @Test
    fun knownPublicKeyAllowsDirect() {
        val c = chain(peerPublicKey = { byteArrayOf(0xFF.toByte()) })
        assertIs<SendDecision.Direct>(c.evaluate(peer, payloadSize = 10))
    }

    @Test
    fun noCryptoSkipsPublicKeyCheck() {
        val c = chain(peerPublicKey = null)
        assertIs<SendDecision.Direct>(c.evaluate(peer, payloadSize = 10))
    }

    // ── 2. Happy path ────────────────────────────────────────────

    @Test
    fun happyPathReturnsDirect() {
        val c = chain()
        val result = c.evaluate(peer, payloadSize = 10)
        assertIs<SendDecision.Direct>(result)
    }

    // ── 3. Priority ordering ─────────────────────────────────────

    @Test
    fun bufferFullTakesPriorityOverPaused() {
        val c = chain(bufferCapacity = 5, isPaused = { true })
        assertIs<SendDecision.BufferFull>(c.evaluate(peer, payloadSize = 10))
    }

    @Test
    fun pausedTakesPriorityOverRateLimit() {
        val c = chain(
            isPaused = { true },
            checkSendRate = { RateLimitResult.Limited("send", it.toString()) },
        )
        assertIs<SendDecision.Paused>(c.evaluate(peer, payloadSize = 10))
    }

    @Test
    fun rateLimitTakesPriorityOverCircuitBreaker() {
        val c = chain(
            checkSendRate = { RateLimitResult.Limited("send", it.toString()) },
            checkCircuitBreaker = { RateLimitResult.Limited("cb", "global") },
        )
        assertIs<SendDecision.RateLimited>(c.evaluate(peer, payloadSize = 10))
    }

    @Test
    fun circuitBreakerTakesPriorityOverRouting() {
        val c = chain(
            checkCircuitBreaker = { RateLimitResult.Limited("cb", "global") },
            resolveNextHop = { NextHopResult.Unreachable },
        )
        assertIs<SendDecision.CircuitBreakerOpen>(c.evaluate(peer, payloadSize = 10))
    }

    @Test
    fun routingTakesPriorityOverPublicKeyCheck() {
        val c = chain(
            resolveNextHop = { NextHopResult.Unreachable },
            peerPublicKey = { null },
        )
        assertIs<SendDecision.Unreachable>(c.evaluate(peer, payloadSize = 10))
    }
}
