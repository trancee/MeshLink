package io.meshlink.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Controls graceful drain of in-flight operations during shutdown.
 * Polls an "active count" function and waits for it to reach zero,
 * or until a timeout expires.
 */
class DrainController(
    private val drainTimeoutMs: Long = 5_000L,
    private val pollIntervalMs: Long = 100L,
) {
    /**
     * Wait for [activeCount] to return 0, up to [drainTimeoutMs].
     * Returns the number of items still active when drain ended.
     * Returns 0 if all items drained successfully.
     * Returns immediately if drainTimeoutMs <= 0.
     */
    suspend fun drain(activeCount: () -> Int): Int {
        val current = activeCount()
        if (current == 0) return 0
        if (drainTimeoutMs <= 0) return current

        withTimeoutOrNull(drainTimeoutMs) {
            while (true) {
                if (activeCount() == 0) return@withTimeoutOrNull
                delay(pollIntervalMs)
            }
        }

        return activeCount()
    }

    /**
     * Whether this controller is configured for draining (timeout > 0).
     */
    val enabled: Boolean get() = drainTimeoutMs > 0
}
