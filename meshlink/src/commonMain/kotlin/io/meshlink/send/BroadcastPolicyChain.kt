package io.meshlink.send

import io.meshlink.crypto.SignedPayload
import io.meshlink.model.MessageId
import io.meshlink.util.ByteArrayKey
import io.meshlink.util.RateLimitResult
import io.meshlink.util.toKey
import io.meshlink.wire.WireCodec

sealed interface BroadcastDecision {
    data object BufferFull : BroadcastDecision
    data object RateLimited : BroadcastDecision
    data class Proceed(
        val encodedFrame: ByteArray,
        val messageId: ByteArray,
    ) : BroadcastDecision
}

/**
 * Pure pre-flight evaluator for broadcast sends. Mirrors [SendPolicyChain]
 * for the broadcast path: validates capacity and rate limits, then constructs
 * a signed, encoded frame ready to flood to all peers.
 *
 * Dependencies are injected as function types so the chain is testable
 * without constructing full engine graphs.
 */
class BroadcastPolicyChain(
    private val bufferCapacity: Int,
    private val checkBroadcastRate: () -> RateLimitResult,
    private val signData: ((ByteArray) -> SignedPayload?)?,
    private val appIdHash: ByteArray,
    private val localPeerId: ByteArray,
    private val markAsSeen: (ByteArrayKey) -> Unit = {},
    private val generateMessageId: () -> ByteArray = { MessageId.random().bytes },
) {
    fun evaluate(payload: ByteArray, maxHops: UByte): BroadcastDecision {
        if (payload.size > bufferCapacity) return BroadcastDecision.BufferFull

        if (checkBroadcastRate() is RateLimitResult.Limited) return BroadcastDecision.RateLimited

        val msgIdBytes = generateMessageId()

        // Sign broadcast content (remainingHops excluded — relays decrement it)
        val signedData = msgIdBytes + localPeerId + appIdHash + payload
        val signed = signData?.invoke(signedData)
        val signature = signed?.signature ?: ByteArray(0)

        val encodedFrame = WireCodec.encodeBroadcast(
            messageId = msgIdBytes,
            origin = localPeerId,
            remainingHops = maxHops,
            appIdHash = appIdHash,
            payload = payload,
            signature = signature,
            signerPublicKey = signed?.signerPublicKey ?: ByteArray(0),
        )

        // Mark as seen so we don't deliver our own broadcast back
        markAsSeen(msgIdBytes.toKey())

        return BroadcastDecision.Proceed(encodedFrame, msgIdBytes)
    }
}
