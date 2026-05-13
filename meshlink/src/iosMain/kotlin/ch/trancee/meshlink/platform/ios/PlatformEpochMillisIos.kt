@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ch.trancee.meshlink.platform

import platform.posix.time

internal actual fun currentEpochMillis(): Long {
    return time(null) * 1_000L
}
