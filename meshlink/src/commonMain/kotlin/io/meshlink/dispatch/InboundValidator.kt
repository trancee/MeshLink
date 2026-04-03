package io.meshlink.dispatch

import io.meshlink.config.MeshLinkConfig
import io.meshlink.crypto.SecurityEngine
import io.meshlink.delivery.DeliveryPipeline
import io.meshlink.diagnostics.DiagnosticCode
import io.meshlink.diagnostics.DiagnosticSink
import io.meshlink.diagnostics.Severity
import io.meshlink.util.AppIdFilter
import io.meshlink.util.ByteArrayKey
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
internal class InboundValidator(
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
        fromPeerId: ByteArrayKey,
        signature: ByteArray?,
        signerPublicKey: ByteArray?,
        signedData: ByteArray,
    ): Boolean {
        val se = securityEngine ?: return true
        if (signature == null || signerPublicKey == null) {
            diagnosticSink.emit(
                DiagnosticCode.MALFORMED_DATA,
                Severity.WARN,
                "unsigned route_update rejected (crypto enabled) from $fromPeerId",
            )
            return false
        }
        if (!se.verify(signerPublicKey, signedData, signature)) {
            diagnosticSink.emit(
                DiagnosticCode.MALFORMED_DATA,
                Severity.WARN,
                "route_update signature verification failed from $fromPeerId",
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
    fun checkAppId(key: ByteArrayKey, appIdHash: ByteArray): Boolean {
        if (appIdFilter.accepts(appIdHash)) return true
        diagnosticSink.emit(DiagnosticCode.APP_ID_REJECTED, Severity.INFO, "messageId=$key")
        return false
    }

    // ── Replay, loop & hop-limit checks ────────────────────────────

    /** Returns true if the replay counter is acceptable. */
    fun checkReplay(key: ByteArrayKey, originId: ByteArrayKey, replayCounter: ULong): Boolean {
        // When counter is zero: accept only if crypto is not actively enforced.
        // Security: when encryption is required AND a security engine is present,
        // counter=0 would bypass replay protection, enabling message replay attacks.
        if (replayCounter == 0uL) return !(config.requireEncryption && cryptoRequired)
        if (deliveryPipeline.checkReplay(originId, replayCounter)) return true
        diagnosticSink.emit(
            DiagnosticCode.REPLAY_REJECTED,
            Severity.WARN,
            "messageId=$key, origin=$originId, counter=$replayCounter",
        )
        return false
    }

    /** Returns true if the visited list does NOT contain the local peer. */
    fun checkLoop(key: ByteArrayKey, visitedList: List<ByteArray>, originId: ByteArrayKey): Boolean {
        if (visitedList.none { it.contentEquals(localPeerId) }) return true
        diagnosticSink.emit(
            DiagnosticCode.LOOP_DETECTED,
            Severity.WARN,
            "messageId=$key, origin=$originId",
        )
        return false
    }

    /** Returns true if the hop limit has not been exhausted. */
    fun checkHopLimit(key: ByteArrayKey, hopLimit: UByte, originId: ByteArrayKey): Boolean {
        if (hopLimit > 0u) return true
        diagnosticSink.emit(
            DiagnosticCode.HOP_LIMIT_EXCEEDED,
            Severity.INFO,
            "messageId=$key, origin=$originId",
        )
        return false
    }

    // ── Rate limiting ──────────────────────────────────────────────

    /** Returns true if the inbound rate for this sender has not been exceeded. */
    fun checkInboundRate(originId: ByteArrayKey): Boolean {
        if (config.inboundRateLimitPerSenderPerMin <= 0) return true
        if (deliveryPipeline.checkInboundRate(originId, config.inboundRateLimitPerSenderPerMin)) return true
        diagnosticSink.emit(DiagnosticCode.RATE_LIMIT_HIT, Severity.WARN, "inbound, origin=$originId")
        return false
    }

    /** Returns true if the sender→neighbor relay rate has not been exceeded. */
    fun checkRelayRate(originId: ByteArrayKey, neighborId: ByteArrayKey): Boolean {
        if (rateLimitPolicy.checkSenderNeighborRelay(originId, neighborId) is RateLimitResult.Allowed) return true
        diagnosticSink.emit(
            DiagnosticCode.RATE_LIMIT_HIT,
            Severity.WARN,
            "sender-neighbor relay limit exceeded, origin=$originId, neighbor=$neighborId",
        )
        return false
    }

    // ── Decryption ─────────────────────────────────────────────────

    /** Decrypt payload, returning null on failure (with diagnostic emission). */
    fun unsealPayload(
        ciphertext: ByteArray,
        context: String,
        senderPeerId: ByteArrayKey? = null,
    ): ByteArray? {
        return when (val ur = securityEngine?.unseal(ciphertext, senderPeerId)) {
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

    /**
     * Try to unseal, but fall back to returning the raw payload when
     * decryption fails.  Used for routed messages where the sender may
     * not have had the destination's public key (non-adjacent peer).
     *
     * Security note: passthrough on decryption failure is logged as a
     * diagnostic warning. Callers should be aware that the returned
     * payload may be unencrypted attacker-controlled data.
     */
    fun unsealOrPassthrough(
        ciphertext: ByteArray,
        senderPeerId: ByteArrayKey? = null,
    ): ByteArray {
        return when (val ur = securityEngine?.unseal(ciphertext, senderPeerId)) {
            is io.meshlink.crypto.UnsealResult.Decrypted -> ur.plaintext
            is io.meshlink.crypto.UnsealResult.Failed,
            is io.meshlink.crypto.UnsealResult.TooShort -> {
                // Security: log when decryption fails and raw payload is passed through.
                // This may indicate a non-encrypting sender or a potential injection attempt.
                diagnosticSink.emit(
                    DiagnosticCode.DECRYPTION_FAILED,
                    Severity.WARN,
                    "decryption failed, passing through raw payload (sender=$senderPeerId)",
                )
                ciphertext
            }
            null -> ciphertext
        }
    }
}
