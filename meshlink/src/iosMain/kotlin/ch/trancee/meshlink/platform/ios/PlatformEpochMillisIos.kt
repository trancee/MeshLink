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
        (now.tv_sec * 1_000L) + (now.tv_usec / 1_000L)
    }
}
