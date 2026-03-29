package io.meshlink.dispatch

import io.meshlink.config.MeshLinkConfig
import io.meshlink.crypto.SecurityEngine
import io.meshlink.delivery.DeliveryPipeline
import io.meshlink.diagnostics.DiagnosticCode
import io.meshlink.diagnostics.DiagnosticSink
import io.meshlink.diagnostics.Severity
import io.meshlink.util.AppIdFilter
import io.meshlink.util.RateLimitPolicy
import io.meshlink.util.RateLimitResult

/**
 * Pre-dispatch validation for inbound messages.
 *
 * Consolidates all accept/reject checks (signatures, replay counters,
 * loop detection, hop limits, rate limiting, app-ID filtering) and
 * their associated diagnostic emissions.  Each method returns `true`
 * when the message **passes** validation.
 *
 * MessageDispatcher calls the relevant checks before proceeding to
 * handler logic, keeping handlers focused on "what to do" rather
 * than "should I do it".
 */
class InboundValidator(
    private val securityEngine: SecurityEngine?,
    private val deliveryPipeline: DeliveryPipeline,
    private val rateLimitPolicy: RateLimitPolicy,
    private val appIdFilter: AppIdFilter,
    private val diagnosticSink: DiagnosticSink,
    private val localPeerId: ByteArray,
    private val config: MeshLinkConfig,
) {
    /** True when crypto is configured and unsigned frames must be rejected. */
    val cryptoRequired: Boolean get() = securityEngine != null

    // ── Signature verification ─────────────────────────────────────

    /**
     * Returns true if the route update carries a valid signature.
     * Rejects unsigned updates when crypto is enabled, and rejects
     * updates whose signature does not verify.
     */
    fun validateRouteUpdateSignature(
        fromPeerHex: String,
        signature: ByteArray?,
        signerPublicKey: ByteArray?,
        signedData: ByteArray,
    ): Boolean {
        val se = securityEngine ?: return true
        if (signature == null || signerPublicKey == null) {
            diagnosticSink.emit(
                DiagnosticCode.MALFORMED_DATA, Severity.WARN,
                "unsigned route_update rejected (crypto enabled) from $fromPeerHex",
            )
            return false
        }
        if (!se.verify(signerPublicKey, signedData, signature)) {
            diagnosticSink.emit(
                DiagnosticCode.MALFORMED_DATA, Severity.WARN,
                "route_update signature verification failed from $fromPeerHex",
            )
            return false
        }
        return true
    }

    /**
     * Returns true if the broadcast signature is valid (or crypto is off).
     * Rejects unsigned broadcasts when crypto is enabled.
     */
    fun validateBroadcastSignature(
        signature: ByteArray,
        signerPublicKey: ByteArray,
        signedData: ByteArray,
    ): Boolean {
        val se = securityEngine ?: return true
        if (signature.isEmpty()) return false
        return se.verify(signerPublicKey, signedData, signature)
    }

    /**
     * Returns true if the delivery-ACK signature is valid.
     * Rejects unsigned ACKs when crypto is enabled.
     */
    fun validateDeliveryAckSignature(
        signature: ByteArray,
        signerPublicKey: ByteArray,
        signedData: ByteArray,
    ): Boolean {
        val se = securityEngine ?: return true
        if (signature.isEmpty()) return false
        return se.verify(signerPublicKey, signedData, signature)
    }

    // ── App-ID filtering ───────────────────────────────────────────

    /** Returns true if the message's app-ID hash passes the filter. */
    fun checkAppId(key: String, appIdHash: ByteArray): Boolean {
        if (appIdFilter.accepts(appIdHash)) return true
        diagnosticSink.emit(DiagnosticCode.APP_ID_REJECTED, Severity.INFO, "messageId=$key")
        return false
    }

    // ── Replay, loop & hop-limit checks ────────────────────────────

    /** Returns true if the replay counter is acceptable. */
    fun checkReplay(key: String, originHex: String, replayCounter: ULong): Boolean {
        if (replayCounter == 0uL) return true
        if (deliveryPipeline.checkReplay(originHex, replayCounter)) return true
        diagnosticSink.emit(
            DiagnosticCode.REPLAY_REJECTED, Severity.WARN,
            "messageId=$key, origin=$originHex, counter=$replayCounter",
        )
        return false
    }

    /** Returns true if the visited list does NOT contain the local peer. */
    fun checkLoop(key: String, visitedList: List<ByteArray>, originHex: String): Boolean {
        if (visitedList.none { it.contentEquals(localPeerId) }) return true
        diagnosticSink.emit(
            DiagnosticCode.LOOP_DETECTED, Severity.WARN,
            "messageId=$key, origin=$originHex",
        )
        return false
    }

    /** Returns true if the hop limit has not been exhausted. */
    fun checkHopLimit(key: String, hopLimit: UByte, originHex: String): Boolean {
        if (hopLimit > 0u) return true
        diagnosticSink.emit(
            DiagnosticCode.HOP_LIMIT_EXCEEDED, Severity.INFO,
            "messageId=$key, origin=$originHex",
        )
        return false
    }

    // ── Rate limiting ──────────────────────────────────────────────

    /** Returns true if the inbound rate for this sender has not been exceeded. */
    fun checkInboundRate(originHex: String): Boolean {
        if (config.inboundRateLimitPerSenderPerMinute <= 0) return true
        if (deliveryPipeline.checkInboundRate(originHex, config.inboundRateLimitPerSenderPerMinute)) return true
        diagnosticSink.emit(DiagnosticCode.RATE_LIMIT_HIT, Severity.WARN, "inbound, origin=$originHex")
        return false
    }

    /** Returns true if the sender→neighbor relay rate has not been exceeded. */
    fun checkRelayRate(originHex: String, neighborHex: String): Boolean {
        if (rateLimitPolicy.checkSenderNeighborRelay(originHex, neighborHex) is RateLimitResult.Allowed) return true
        diagnosticSink.emit(
            DiagnosticCode.RATE_LIMIT_HIT, Severity.WARN,
            "sender-neighbor relay limit exceeded, origin=$originHex, neighbor=$neighborHex",
        )
        return false
    }

    // ── Decryption ─────────────────────────────────────────────────

    /** Decrypt payload, returning null on failure (with diagnostic emission). */
    fun unsealPayload(ciphertext: ByteArray, context: String): ByteArray? {
        return when (val ur = securityEngine?.unseal(ciphertext)) {
            is io.meshlink.crypto.UnsealResult.Decrypted -> ur.plaintext
            is io.meshlink.crypto.UnsealResult.Failed -> {
                diagnosticSink.emit(DiagnosticCode.DECRYPTION_FAILED, Severity.WARN, context)
                null
            }
            is io.meshlink.crypto.UnsealResult.TooShort -> {
                diagnosticSink.emit(DiagnosticCode.DECRYPTION_FAILED, Severity.WARN, "$context (too short)")
                null
            }
            null -> ciphertext
        }
    }
}
