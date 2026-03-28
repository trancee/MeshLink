package io.meshlink.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.open
import platform.posix.read

@OptIn(ExperimentalForeignApi::class)
actual fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    val fd = open("/dev/urandom", O_RDONLY)
    check(fd >= 0) { "Failed to open /dev/urandom" }
    try {
        var offset = 0
        bytes.usePinned { pinned ->
            while (offset < size) {
                val n = read(fd, pinned.addressOf(offset), (size - offset).toULong())
                check(n > 0) { "Failed to read from /dev/urandom" }
                offset += n.toInt()
            }
        }
    } finally {
        close(fd)
    }
    return bytes
}
