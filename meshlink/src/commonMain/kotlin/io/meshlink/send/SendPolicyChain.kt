package io.meshlink.send

import io.meshlink.routing.NextHopResult
import io.meshlink.util.RateLimitResult
import io.meshlink.util.toHex

sealed interface SendDecision {
    data object BufferFull : SendDecision
    data object Loopback : SendDecision
    data object Paused : SendDecision
    data class RateLimited(val key: String) : SendDecision
    data object CircuitBreakerOpen : SendDecision
    data class Direct(val recipientHex: String) : SendDecision
    data class Routed(val nextHopHex: String) : SendDecision
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
    private val checkSendRate: (String) -> RateLimitResult,
    private val checkCircuitBreaker: () -> RateLimitResult,
    private val resolveNextHop: (String) -> NextHopResult,
    private val peerPublicKey: ((String) -> ByteArray?)?,
) {
    fun evaluate(recipient: ByteArray, payloadSize: Int): SendDecision {
        if (payloadSize > bufferCapacity) return SendDecision.BufferFull

        if (recipient.contentEquals(localPeerId)) return SendDecision.Loopback

        if (isPaused()) return SendDecision.Paused

        val recipientHex = recipient.toHex()

        when (checkSendRate(recipientHex)) {
            is RateLimitResult.Allowed -> {}
            is RateLimitResult.Limited -> return SendDecision.RateLimited(recipientHex)
        }

        if (checkCircuitBreaker() is RateLimitResult.Limited) {
            return SendDecision.CircuitBreakerOpen
        }

        when (val hop = resolveNextHop(recipientHex)) {
            is NextHopResult.Direct -> {}
            is NextHopResult.ViaRoute -> return SendDecision.Routed(hop.nextHop)
            is NextHopResult.Unreachable -> return SendDecision.Unreachable
        }

        if (peerPublicKey != null && peerPublicKey.invoke(recipientHex) == null) {
            return SendDecision.MissingPublicKey
        }

        return SendDecision.Direct(recipientHex)
    }
}
