package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * This node's delivery receipts (spec "Delivery receipts"; ADR 0013 decisions 1 and 6). Batches one
 * receipt per sender for each message the app took, and again for each later copy of a taken one,
 * and [send]s a batch once it holds `receipt-batch-max` messages or `receipt-batch-delay` after its
 * first. Hands the receipts that arrive for this node's messages to [delivered].
 *
 * Runs on [scope]'s serial dispatcher, except [taken].
 */
internal class Receipts(
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val send: (to: NodeId, tombstones: List<Tombstone>, expiry: UInt) -> Unit,
    private val delivered: (from: NodeId, messageIds: List<ByteArray>) -> Unit,
) {
    private val batches = mutableMapOf<NodeId, MutableList<Pair<Tombstone, UInt>>>()
    private var takenIds = MessageIds()
    private lateinit var run: CoroutineScope

    /** Starts a run of the node: nothing is taken yet. */
    fun begin() {
        batches.clear()
        takenIds = MessageIds()
        run = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext.job))
    }

    /** Ends the run: batches not yet sent are dropped. */
    suspend fun end() {
        run.coroutineContext.job.cancelAndJoin()
    }

    /** The app took [sender]'s message of [tombstone], expiring at Unix second [expiry]. Thread-safe. */
    fun taken(
        sender: NodeId,
        tombstone: Tombstone,
        expiry: UInt,
    ) {
        scope.launch { take(sender, tombstone, expiry) }
    }

    /** A later copy of [sender]'s message of [tombstone] arrived: acknowledged again if it was taken. */
    fun again(
        sender: NodeId,
        tombstone: Tombstone,
        expiry: UInt,
    ) {
        if (takenIds.has(tombstone.messageId, clock.wallClockMillis())) add(sender, tombstone, expiry)
    }

    /** [from]'s receipt for [messageIds] arrived. */
    fun arrived(
        from: NodeId,
        messageIds: List<ByteArray>,
    ) {
        delivered(from, messageIds)
    }

    private fun take(
        sender: NodeId,
        tombstone: Tombstone,
        expiry: UInt,
    ) {
        if (!run.isActive) return
        takenIds.put(tombstone.messageId, remembered(expiry.toLong()))
        add(sender, tombstone, expiry)
    }

    private fun add(
        sender: NodeId,
        tombstone: Tombstone,
        expiry: UInt,
    ) {
        val batch =
            batches.getOrPut(sender) {
                mutableListOf<Pair<Tombstone, UInt>>().also {
                    run.launch {
                        delay(BATCH_DELAY_MILLIS)
                        flush(sender, it)
                    }
                }
            }
        batch += tombstone to expiry
        if (batch.size == DeliveryReceipt.TOMBSTONES_MAX) flush(sender, batch)
    }

    // A batch sent when full is gone by the time its timer fires.
    private fun flush(
        sender: NodeId,
        batch: List<Pair<Tombstone, UInt>>,
    ) {
        if (batches[sender] !== batch) return
        batches -= sender
        val latest = batch.fold(0u) { expiry, (_, next) -> maxOf(expiry, next) }
        send(sender, batch.map { it.first }, latest)
    }

    private companion object {
        // receipt-batch-delay.
        const val BATCH_DELAY_MILLIS = 500L
    }
}
