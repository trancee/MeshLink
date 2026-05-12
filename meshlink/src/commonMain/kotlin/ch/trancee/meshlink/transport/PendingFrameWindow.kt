package ch.trancee.meshlink.transport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class PendingFrameWindow(
    private val maxPendingFrames: Int,
    private val maxPendingBytes: Int,
) {
    init {
        require(maxPendingFrames > 0) { "maxPendingFrames must be greater than zero" }
        require(maxPendingBytes > 0) { "maxPendingBytes must be greater than zero" }
    }

    private val mutationMutex = Mutex()
    private val usageFlow = MutableStateFlow(WindowUsage())

    suspend fun acquire(frameBytes: Int): Boolean {
        require(frameBytes > 0) { "frameBytes must be greater than zero" }

        while (true) {
            val snapshot = usageFlow.value
            if (snapshot.closed) {
                return false
            }
            if (canReserve(snapshot, frameBytes)) {
                val reserved = mutationMutex.withLock {
                    val current = usageFlow.value
                    if (!canReserve(current, frameBytes)) {
                        return@withLock false
                    }
                    usageFlow.value =
                        current.copy(
                            pendingFrames = current.pendingFrames + 1,
                            pendingBytes = current.pendingBytes + frameBytes,
                        )
                    true
                }
                if (reserved) {
                    return true
                }
            }

            val resumedState = usageFlow.first { state ->
                state.closed || canReserve(state, frameBytes)
            }
            if (resumedState.closed) {
                return false
            }
        }
    }

    suspend fun release(frameBytes: Int): Unit {
        require(frameBytes > 0) { "frameBytes must be greater than zero" }

        mutationMutex.withLock {
            val current = usageFlow.value
            if (current.pendingFrames == 0 && current.pendingBytes == 0) {
                return
            }
            usageFlow.value =
                current.copy(
                    pendingFrames = (current.pendingFrames - 1).coerceAtLeast(0),
                    pendingBytes = (current.pendingBytes - frameBytes).coerceAtLeast(0),
                )
        }
    }

    fun close(): Unit {
        usageFlow.value = usageFlow.value.copy(closed = true)
    }

    fun snapshot(): WindowUsage {
        return usageFlow.value
    }

    private fun canReserve(state: WindowUsage, frameBytes: Int): Boolean {
        if (state.closed || state.pendingFrames >= maxPendingFrames) {
            return false
        }
        return state.pendingFrames == 0 || state.pendingBytes + frameBytes <= maxPendingBytes
    }

    internal data class WindowUsage(
        val pendingFrames: Int = 0,
        val pendingBytes: Int = 0,
        val closed: Boolean = false,
    )
}
