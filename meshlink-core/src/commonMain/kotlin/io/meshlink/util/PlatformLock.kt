package io.meshlink.util

/**
 * Platform-specific reentrant mutual exclusion lock.
 * Used for thread safety of synchronous public API methods.
 */
expect class PlatformLock() {
    fun lock()
    fun unlock()
}

inline fun <T> PlatformLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
