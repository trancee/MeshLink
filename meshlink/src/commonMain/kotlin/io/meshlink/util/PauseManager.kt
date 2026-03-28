package io.meshlink.util

sealed interface QueueResult {
    data object Queued : QueueResult
    data object Evicted : QueueResult
}

data class PauseSnapshot(
    val pendingSends: List<Pair<ByteArray, ByteArray>>,
    val pendingRelays: List<Pair<ByteArray, ByteArray>>,
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
    private val relayQueue = mutableListOf<Pair<ByteArray, ByteArray>>()

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

    fun queueRelay(nextHop: ByteArray, frame: ByteArray): QueueResult {
        relayQueue.add(nextHop to frame)
        return if (relayQueue.size > relayQueueCapacity) {
            relayQueue.removeAt(0)
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
