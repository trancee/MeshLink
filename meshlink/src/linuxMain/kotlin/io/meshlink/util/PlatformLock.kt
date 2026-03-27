package io.meshlink.util

import kotlin.concurrent.AtomicInt

private class LinuxPlatformLock : PlatformLock {
    private val locked = AtomicInt(0)

    override fun lock() {
        while (!locked.compareAndSet(0, 1)) {
            // spin
        }
    }

    override fun unlock() {
        locked.value = 0
    }
}

actual fun createPlatformLock(): PlatformLock = LinuxPlatformLock()
