package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.NodeId
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The delivered messages that asked for a read receipt, which this node watches until it arrives (spec
 * "Read receipts"; ADR 0013 decision 10): at most `read-tracking` of them; tracking one more forgets the
 * oldest, which is reported to [evicted].
 *
 * Runs on the node's serial dispatcher.
 */
internal class ReadTracking(
    private val evicted: (messageId: ByteArray) -> Unit,
) {
    // In tracking order, oldest first.
    private val tracked = LinkedHashMap<List<Byte>, Pair<NodeId, MutableStateFlow<DeliveryStatus>>>()

    /** The message [messageId] to [to], whose status is [status], was delivered. */
    fun track(
        to: NodeId,
        messageId: ByteArray,
        status: MutableStateFlow<DeliveryStatus>,
    ) {
        tracked[messageId.toList()] = to to status
        if (tracked.size > MAX) {
            val oldest = tracked.keys.first()
            tracked -= oldest
            evicted(oldest.toByteArray())
        }
    }

    /** [from]'s read receipt for [messageIds] arrived: those of its tracked messages are `Read`. */
    fun read(
        from: NodeId,
        messageIds: List<ByteArray>,
    ) {
        messageIds.map { it.toList() }.forEach { key ->
            tracked[key]?.takeIf { (to, _) -> to == from }?.let { (_, status) ->
                tracked -= key
                status.value = status.value.then(DeliveryStatus.Read)
            }
        }
    }

    companion object {
        /** `read-tracking`: the most delivered messages watched for their read receipts. */
        const val MAX: Int = 1_000
    }
}
