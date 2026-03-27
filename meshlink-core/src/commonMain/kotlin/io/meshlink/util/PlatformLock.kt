package io.meshlink.util

/**
 * Platform-specific reentrant mutual exclusion lock.
 * Used for thread safety of synchronous public API methods.
 */
interface PlatformLock {
    fun lock()
    fun unlock()
}

expect fun createPlatformLock(): PlatformLock

inline fun <T> PlatformLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
