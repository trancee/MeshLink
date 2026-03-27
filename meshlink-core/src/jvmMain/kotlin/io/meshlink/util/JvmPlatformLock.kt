package io.meshlink.util

import java.util.concurrent.locks.ReentrantLock

actual class PlatformLock actual constructor() {
    private val lock = ReentrantLock()
    actual fun lock() = lock.lock()
    actual fun unlock() = lock.unlock()
}
