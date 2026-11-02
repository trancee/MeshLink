package ch.trancee.meshlink

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/** Bulk `NSData` ↔ `ByteArray` copies with `memcpy`, for the bulk Swift overlay. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
public object DataBridge {
    public fun toByteArray(data: NSData): ByteArray {
        val size = data.length.toInt()
        val bytes = ByteArray(size)
        if (size > 0) bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
        return bytes
    }

    public fun toNSData(bytes: ByteArray): NSData {
        if (bytes.isEmpty()) return NSData()
        return bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    }
}
