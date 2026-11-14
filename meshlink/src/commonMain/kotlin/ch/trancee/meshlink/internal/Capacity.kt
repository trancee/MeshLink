package ch.trancee.meshlink.internal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * The send-queue cap on unwritten payload bytes (ADR 0007 amendment). A reservation is refused only
 * while the reserved bytes already reach [LIMIT], so the queue may overshoot it by one message.
 * Thread-safe.
 */
internal class Capacity {
    private val reserved = MutableStateFlow(0L)

    /** Counts [bytes] against the cap; refuses only while the reserved bytes already reach it. */
    fun reserve(bytes: Int): Boolean = reserved.getAndUpdate { if (it < LIMIT) it + bytes else it } < LIMIT

    /** Returns once the reserved bytes are below the cap. */
    suspend fun await() {
        reserved.first { it < LIMIT }
    }

    /** Frees [bytes] reserved earlier. */
    fun release(bytes: Int) {
        reserved.update { it - bytes }
    }

    companion object {
        /** The cap on unwritten payload bytes. */
        const val LIMIT: Long = 1_048_576
    }
}
