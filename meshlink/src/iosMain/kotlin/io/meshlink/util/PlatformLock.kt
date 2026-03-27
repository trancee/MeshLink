package io.meshlink.util

import platform.Foundation.NSRecursiveLock

private class IosPlatformLock : PlatformLock {
    private val lock = NSRecursiveLock()
    override fun lock() = lock.lock()
    override fun unlock() = lock.unlock()
}

actual fun createPlatformLock(): PlatformLock = IosPlatformLock()
