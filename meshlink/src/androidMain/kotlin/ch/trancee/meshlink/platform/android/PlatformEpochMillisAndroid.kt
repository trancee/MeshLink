package ch.trancee.meshlink.platform

internal actual fun currentEpochMillis(): Long {
    return System.currentTimeMillis()
}
