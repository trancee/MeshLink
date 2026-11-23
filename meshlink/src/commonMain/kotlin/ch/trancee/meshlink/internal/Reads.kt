package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.ReceivedMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * This node's read receipts (spec "Read receipts"; ADR 0013 decision 10; D-39). Gives each message it
 * opens a [ReceivedMessage.markRead] that, if the sender asked and this node is [allowed] to, lists the
 * message in its sender's next read receipt. Batches one receipt per sender and [send]s it once it lists
 * `read-receipt-batch-max` messages or `read-receipt-batch-delay` after its first. Hands the read receipts
 * that arrive for this node's messages to [arrived].
 *
 * Runs on [scope]'s serial dispatcher, except the [ReceivedMessage.markRead] it hands out.
 */
internal class Reads(
    private val scope: CoroutineScope,
    private val allowed: Boolean,
    private val send: (to: NodeId, messageIds: List<ByteArray>) -> Unit,
    private val arrived: (from: NodeId, messageIds: List<ByteArray>) -> Unit,
) {
    private val batches = mutableMapOf<NodeId, MutableList<ByteArray>>()
    private lateinit var run: CoroutineScope

    /** Starts a run of the node: nothing is batched yet. */
    fun begin() {
        batches.clear()
        run = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext.job))
    }

    /** Ends the run: batches not yet sent are dropped, and so are messages marked read until the next run. */
    suspend fun end() {
        run.coroutineContext.job.cancelAndJoin()
    }

    /** [delivery] from [sender] as the app receives it. */
    fun message(
        sender: NodeId,
        delivery: Delivery,
    ): ReceivedMessage {
        val messageId = delivery.tombstone.messageId
        val requested = delivery.readReceipt
        val reader: () -> Unit = { scope.launch { read(sender, messageId) } }
        return ReceivedMessage(sender, delivery.payload, false, requested, reader.takeIf { requested && allowed })
    }

    /** [from]'s read receipt for [messageIds] arrived. */
    fun arrived(
        from: NodeId,
        messageIds: List<ByteArray>,
    ) {
        arrived.invoke(from, messageIds)
    }

    private fun read(
        sender: NodeId,
        messageId: ByteArray,
    ) {
        if (!run.isActive) return
        val batch =
            batches.getOrPut(sender) {
                mutableListOf<ByteArray>().also {
                    run.launch {
                        delay(BATCH_DELAY_MILLIS)
                        flush(sender, it)
                    }
                }
            }
        batch += messageId
        if (batch.size == ReadReceipt.IDS_MAX) flush(sender, batch)
    }

    // A batch sent when full is gone by the time its timer fires.
    private fun flush(
        sender: NodeId,
        batch: List<ByteArray>,
    ) {
        if (batches[sender] !== batch) return
        batches -= sender
        send(sender, batch)
    }

    private companion object {
        // read-receipt-batch-delay.
        const val BATCH_DELAY_MILLIS = 2_000L
    }
}
