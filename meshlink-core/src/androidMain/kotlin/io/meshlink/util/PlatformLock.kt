@file:JvmName("PlatformLockAndroid")

package io.meshlink.util

import java.util.concurrent.locks.ReentrantLock

private class AndroidPlatformLock : PlatformLock {
    private val lock = ReentrantLock()
    override fun lock() = lock.lock()
    override fun unlock() = lock.unlock()
}

actual fun createPlatformLock(): PlatformLock = AndroidPlatformLock()
