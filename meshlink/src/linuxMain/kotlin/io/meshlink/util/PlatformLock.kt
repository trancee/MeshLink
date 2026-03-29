@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.meshlink.util

import platform.posix.pthread_self
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicLong

/**
 * Reentrant platform lock for Linux using AtomicInt with pthread-based thread ownership.
 * Matches the reentrant behavior of Android (ReentrantLock) and Apple (NSRecursiveLock).
 */
private class LinuxPlatformLock : PlatformLock {
    private val locked = AtomicInt(0)
    private val ownerThreadId = AtomicLong(0L)
    private var recursionCount: Int = 0

    override fun lock() {
        val currentThread = pthread_self().toLong()
        if (ownerThreadId.value == currentThread && recursionCount > 0) {
            recursionCount++
            return
        }
        while (!locked.compareAndSet(0, 1)) {
            // spin
        }
        ownerThreadId.value = currentThread
        recursionCount = 1
    }

    override fun unlock() {
        recursionCount--
        if (recursionCount == 0) {
            ownerThreadId.value = 0L
            locked.value = 0
        }
    }
}

actual fun createPlatformLock(): PlatformLock = LinuxPlatformLock()
