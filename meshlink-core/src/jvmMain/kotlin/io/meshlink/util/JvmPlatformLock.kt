package io.meshlink.util

import java.util.concurrent.locks.ReentrantLock

private class JvmPlatformLock : PlatformLock {
    private val lock = ReentrantLock()
    override fun lock() = lock.lock()
    override fun unlock() = lock.unlock()
}

actual fun createPlatformLock(): PlatformLock = JvmPlatformLock()
