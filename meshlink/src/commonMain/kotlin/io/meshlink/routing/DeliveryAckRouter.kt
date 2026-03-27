package io.meshlink.routing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Routes delivery ACKs back along the reverse path of routed messages.
 * Maintains a map of messageId → source peer for reverse-path lookup.
 * Supports retry with configurable attempts and delay.
 */
class DeliveryAckRouter(
    private val maxAttempts: Int = 2,
    private val retryDelayMs: Long = 2_000L,
) {
    // messageId hex → source peer ID bytes (who forwarded the message to us)
    private val reversePath = mutableMapOf<String, ByteArray>()

    /**
     * Record that a routed message arrived from [sourcePeerId].
     * This establishes the reverse path for delivery ACK routing.
     */
    fun recordSource(messageIdHex: String, sourcePeerId: ByteArray) {
        reversePath[messageIdHex] = sourcePeerId
    }

    /**
     * Get the reverse-path peer for a given messageId.
     * Returns null if no reverse path is known (we originated the message or it was direct).
     */
    fun getSource(messageIdHex: String): ByteArray? = reversePath[messageIdHex]

    /**
     * Route a delivery ACK back along the reverse path.
     * Tries [maxAttempts] times with [retryDelayMs] between attempts.
     *
     * @param scope Coroutine scope for async retries
     * @param messageIdHex The message ID being acknowledged
     * @param ackData The encoded delivery ACK wire bytes
     * @param sendFn Function to send data to a peer (may throw on failure)
     * @return true if a reverse path was found (send attempted), false if no path
     */
    fun routeAck(
        scope: CoroutineScope,
        messageIdHex: String,
        ackData: ByteArray,
        sendFn: suspend (peerId: ByteArray, data: ByteArray) -> Unit,
    ): Boolean {
        val target = reversePath[messageIdHex] ?: return false
        scope.launch {
            for (attempt in 1..maxAttempts) {
                try {
                    sendFn(target, ackData)
                    break
                } catch (_: Exception) {
                    if (attempt < maxAttempts) {
                        delay(retryDelayMs)
                    }
                }
            }
        }
        return true
    }

    /**
     * Remove reverse path entry (after ACK is successfully routed or timed out).
     */
    fun remove(messageIdHex: String) {
        reversePath.remove(messageIdHex)
    }

    /**
     * Clear all reverse path entries.
     */
    fun clear() {
        reversePath.clear()
    }

    /**
     * Number of tracked reverse paths.
     */
    fun size(): Int = reversePath.size
}
