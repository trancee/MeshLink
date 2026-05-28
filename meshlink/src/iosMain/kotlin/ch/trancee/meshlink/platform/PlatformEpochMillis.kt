@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ch.trancee.meshlink.platform

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.gettimeofday
import platform.posix.timeval

internal actual fun currentEpochMillis(): Long {
    return memScoped {
        val now = alloc<timeval>()
        gettimeofday(now.ptr, null)
        (now.tv_sec * MILLISECONDS_PER_SECOND) + (now.tv_usec / MICROSECONDS_PER_MILLISECOND)
    }
}

private const val MILLISECONDS_PER_SECOND: Long = 1_000L
private const val MICROSECONDS_PER_MILLISECOND: Long = 1_000L
