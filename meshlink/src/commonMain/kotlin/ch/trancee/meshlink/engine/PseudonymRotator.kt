package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DiagnosticCode
import ch.trancee.meshlink.api.DiagnosticPayload
import ch.trancee.meshlink.api.DiagnosticSinkApi
import ch.trancee.meshlink.crypto.CryptoProvider
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Computes HMAC-SHA-256-based 12-byte pseudonyms on epoch boundaries with hash-based per-peer
 * stagger.
 *
 * Each epoch is [epochDurationMs] milliseconds long. On every epoch boundary (plus a deterministic
 * stagger offset unique to this node), the rotator computes a new pseudonym and invokes
 * [onRotation].
 *
 * The pseudonym for epoch `e` is `HMAC-SHA-256(keyHash, epochToBytes(e))[0:12]`.
 *
 * The stagger offset for epoch `e` is `abs(toLittleEndianInt(HMAC-SHA-256(keyHash,
 * epochToBytes(e))[0:4])) % epochDurationMs`, spreading rotation events across the epoch window so
 * multiple nodes don't rotate simultaneously.
 *
 * @param keyHash 12-byte identity hash (from [ch.trancee.meshlink.crypto.Identity.keyHash]).
 * @param cryptoProvider Platform crypto for HMAC-SHA-256.
 * @param scope Coroutine scope for the rotation timer — cancellation stops the timer.
 * @param clock Monotonic clock returning milliseconds.
 * @param diagnosticSink Sink for rotation diagnostic events.
 * @param onRotation Callback invoked with the new 12-byte pseudonym on each rotation.
 * @param epochDurationMs Epoch length in milliseconds (default 15 minutes, configurable for tests).
 */
internal class PseudonymRotator(
    private val keyHash: ByteArray,
    private val cryptoProvider: CryptoProvider,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val diagnosticSink: DiagnosticSinkApi,
    private val onRotation: (ByteArray) -> Unit,
    private val epochDurationMs: Long = EPOCH_DURATION_MS,
) {
    private var _currentPseudonym: ByteArray = ByteArray(0)

    /** Returns the last-computed 12-byte pseudonym. Empty before [start] is called. */
    fun currentPseudonym(): ByteArray = _currentPseudonym

    /** Current epoch number derived from [clock] divided by [epochDurationMs]. */
    fun currentEpoch(): Long = clock() / epochDurationMs

    /**
     * Deterministic stagger offset for the current epoch. Derived from the first 4 bytes of
     * HMAC-SHA-256(keyHash, epochToBytes(currentEpoch)), interpreted as a little-endian int, then
     * mapped into `[0, epochDurationMs)`.
     */
    fun computeStaggerMs(): Long {
        val hmac = cryptoProvider.hmacSha256(keyHash, epochToBytes(currentEpoch()))
        return abs(toLittleEndianInt(hmac).toLong()) % epochDurationMs
    }

    /**
     * Computes the 12-byte pseudonym for the given [epoch]: first 12 bytes of HMAC-SHA-256(keyHash,
     * epochToBytes(epoch)).
     */
    fun computePseudonym(epoch: Long): ByteArray =
        cryptoProvider.hmacSha256(keyHash, epochToBytes(epoch)).copyOf(PSEUDONYM_LENGTH)

    /**
     * Starts the rotation timer. Computes the initial pseudonym for the current epoch, invokes
     * [onRotation], then launches a coroutine that re-computes and notifies on each epoch boundary
     * (plus stagger). The coroutine cancels when [scope] is cancelled.
     */
    fun start() {
        val epoch = currentEpoch()
        _currentPseudonym = computePseudonym(epoch)
        onRotation(_currentPseudonym)

        scope.launch {
            var lastEpoch = epoch
            // MEM094: use while(true), not while(isActive), for 100% Kover branch coverage.
            while (true) {
                val nextEpoch = lastEpoch + 1
                val nextBoundary = nextEpoch * epochDurationMs + computeStaggerForEpoch(nextEpoch)
                val now = clock()
                delay(nextBoundary - now)

                lastEpoch = currentEpoch()
                _currentPseudonym = computePseudonym(lastEpoch)
                onRotation(_currentPseudonym)
                val message = "pseudonym_rotated epoch=$lastEpoch"
                diagnosticSink.emit(DiagnosticCode.HANDSHAKE_EVENT) {
                    DiagnosticPayload.TextMessage(message)
                }
            }
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Stagger offset for a specific epoch (as opposed to [computeStaggerMs] which uses
     * [currentEpoch]).
     */
    private fun computeStaggerForEpoch(epoch: Long): Long {
        val hmac = cryptoProvider.hmacSha256(keyHash, epochToBytes(epoch))
        return abs(toLittleEndianInt(hmac).toLong()) % epochDurationMs
    }

    companion object {
        /** Default epoch duration: 15 minutes. */
        const val EPOCH_DURATION_MS: Long = 900_000L

        /** Pseudonym length in bytes (truncated HMAC-SHA-256). */
        internal const val PSEUDONYM_LENGTH = 12

        /** Encodes [epoch] as an 8-byte little-endian byte array. */
        internal fun epochToBytes(epoch: Long): ByteArray {
            val bytes = ByteArray(8)
            var v = epoch
            for (i in 0 until 8) {
                bytes[i] = (v and 0xFF).toByte()
                v = v shr 8
            }
            return bytes
        }

        /**
         * Interprets the first 4 bytes of [bytes] as a little-endian 32-bit signed integer.
         * Requires [bytes].size >= 4.
         */
        internal fun toLittleEndianInt(bytes: ByteArray): Int =
            (bytes[0].toInt() and 0xFF) or
                ((bytes[1].toInt() and 0xFF) shl 8) or
                ((bytes[2].toInt() and 0xFF) shl 16) or
                ((bytes[3].toInt() and 0xFF) shl 24)

        /**
         * Verifies a received pseudonym against a known peer's [peerKeyHash] with ±1 epoch
         * tolerance.
         *
         * Computes HMAC-SHA-256(peerKeyHash, epochToBytes(e))[0:12] for each epoch in `[epoch - 1,
         * epoch, epoch + 1]` (skipping negative epochs). Returns `true` if any match the
         * [receivedPseudonym].
         *
         * @param peerKeyHash The 12-byte identity hash of the peer (known from Noise XX handshake).
         * @param receivedPseudonym The 12-byte pseudonym extracted from the peer's advertisement.
         * @param epoch The current epoch number at the verifying node.
         * @param cryptoProvider Platform crypto for HMAC-SHA-256.
         * @return `true` if the pseudonym matches any of the three tolerance epochs.
         */
        internal fun verifyPseudonym(
            peerKeyHash: ByteArray,
            receivedPseudonym: ByteArray,
            epoch: Long,
            cryptoProvider: CryptoProvider,
        ): Boolean {
            if (receivedPseudonym.size != PSEUDONYM_LENGTH) return false

            // Explicit per-epoch checks instead of a LongRange loop to avoid a
            // compiler-generated empty-range branch that can never be hit.
            fun matchesEpoch(e: Long): Boolean =
                cryptoProvider
                    .hmacSha256(peerKeyHash, epochToBytes(e))
                    .copyOf(PSEUDONYM_LENGTH)
                    .contentEquals(receivedPseudonym)

            return matchesEpoch(epoch) ||
                matchesEpoch(epoch + 1) ||
                (epoch > 0L && matchesEpoch(epoch - 1))
        }
    }
}
