package io.meshlink.util

sealed interface QueueResult {
    data object Queued : QueueResult
    data object Evicted : QueueResult
}

data class PauseSnapshot(
    val pendingSends: List<Pair<ByteArray, ByteArray>>,
    val pendingRelays: List<PauseManager.RelayEntry>,
)

/**
 * Manages pause/resume state and the two queues that accumulate
 * while the mesh is paused: outbound sends and relay forwards.
 *
 * MeshLink checks [isPaused] before sending, queues via
 * [queueSend]/[queueRelay], and drains via [resume] which returns
 * a [PauseSnapshot] of everything that was queued.
 */
class PauseManager(
    private val sendQueueCapacity: Int = Int.MAX_VALUE,
    private val relayQueueCapacity: Int = 100,
) {
    private var _paused = false
    private val sendQueue = mutableListOf<Pair<ByteArray, ByteArray>>()
    data class RelayEntry(
        val nextHop: ByteArray,
        val frame: ByteArray,
        val priority: Byte = 0,
    )

    private val relayQueue = mutableListOf<RelayEntry>()

    val isPaused: Boolean get() = _paused
    val sendQueueSize: Int get() = sendQueue.size
    val relayQueueSize: Int get() = relayQueue.size

    fun pause() {
        _paused = true
    }

    fun resume(): PauseSnapshot {
        _paused = false
        val sends = sendQueue.toList()
        val relays = relayQueue.toList()
        sendQueue.clear()
        relayQueue.clear()
        return PauseSnapshot(sends, relays)
    }

    fun queueSend(recipient: ByteArray, payload: ByteArray): QueueResult {
        sendQueue.add(recipient to payload)
        return if (sendQueue.size > sendQueueCapacity) {
            sendQueue.removeAt(0)
            QueueResult.Evicted
        } else {
            QueueResult.Queued
        }
    }

    fun queueRelay(nextHop: ByteArray, frame: ByteArray, priority: Byte = 0): QueueResult {
        relayQueue.add(RelayEntry(nextHop, frame, priority))
        return if (relayQueue.size > relayQueueCapacity) {
            // Evict the lowest-priority entry (lowest priority value first, then FIFO)
            val lowestIdx = relayQueue.indices.minByOrNull { relayQueue[it].priority } ?: 0
            relayQueue.removeAt(lowestIdx)
            QueueResult.Evicted
        } else {
            QueueResult.Queued
        }
    }

    fun drainSendQueue(): List<Pair<ByteArray, ByteArray>> {
        val items = sendQueue.toList()
        sendQueue.clear()
        return items
    }

    fun clear() {
        sendQueue.clear()
        relayQueue.clear()
    }
}
