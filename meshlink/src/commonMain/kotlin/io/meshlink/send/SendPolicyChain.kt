package io.meshlink.send

import io.meshlink.routing.NextHopResult
import io.meshlink.util.ByteArrayKey
import io.meshlink.util.RateLimitResult
import io.meshlink.util.toKey

sealed interface SendDecision {
    data object BufferFull : SendDecision
    data object Loopback : SendDecision
    data object Paused : SendDecision
    data class RateLimited(val key: ByteArrayKey) : SendDecision
    data object CircuitBreakerOpen : SendDecision
    data class Direct(val recipientId: ByteArrayKey) : SendDecision
    data class Routed(val nextHopId: ByteArrayKey) : SendDecision
    data object Unreachable : SendDecision
    data object MissingPublicKey : SendDecision
}

/**
 * Pure pre-flight evaluator for outbound sends. Checks are applied in
 * priority order; the first failing check short-circuits.
 *
 * Dependencies are injected as function types so the chain is testable
 * without constructing full engine graphs.
 */
class SendPolicyChain(
    private val bufferCapacity: Int,
    private val localPeerId: ByteArray,
    private val isPaused: () -> Boolean,
    private val checkSendRate: (ByteArrayKey) -> RateLimitResult,
    private val checkCircuitBreaker: () -> RateLimitResult,
    private val resolveNextHop: (ByteArrayKey) -> NextHopResult,
    private val peerPublicKey: ((ByteArrayKey) -> ByteArray?)?,
) {
    fun evaluate(recipient: ByteArray, payloadSize: Int): SendDecision {
        if (payloadSize > bufferCapacity) return SendDecision.BufferFull

        if (recipient.contentEquals(localPeerId)) return SendDecision.Loopback

        if (isPaused()) return SendDecision.Paused

        val recipientId = recipient.toKey()

        when (checkSendRate(recipientId)) {
            is RateLimitResult.Allowed -> {}
            is RateLimitResult.Limited -> return SendDecision.RateLimited(recipientId)
        }

        if (checkCircuitBreaker() is RateLimitResult.Limited) {
            return SendDecision.CircuitBreakerOpen
        }

        when (val hop = resolveNextHop(recipientId)) {
            is NextHopResult.Direct -> {}
            is NextHopResult.ViaRoute -> return SendDecision.Routed(hop.nextHop)
            is NextHopResult.Unreachable -> return SendDecision.Unreachable
        }

        if (peerPublicKey != null && peerPublicKey.invoke(recipientId) == null) {
            return SendDecision.MissingPublicKey
        }

        return SendDecision.Direct(recipientId)
    }
}
