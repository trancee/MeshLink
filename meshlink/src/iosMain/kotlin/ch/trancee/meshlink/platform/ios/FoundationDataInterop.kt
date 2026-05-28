@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package ch.trancee.meshlink.platform.ios

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSMutableData
import platform.Foundation.create
import platform.posix.memcpy

internal fun NSData.toByteArray(): ByteArray {
    val lengthInt = length.toInt()
    if (lengthInt == NO_DATA_BYTES) {
        return ByteArray(0)
    }
    return ByteArray(lengthInt).also { output ->
        output.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
}

internal fun ByteArray.toNSData(): NSData {
    val data = if (isEmpty()) null else NSMutableData.create(length = size.toULong())
    if (data != null) {
        usePinned { pinned -> memcpy(data.mutableBytes, pinned.addressOf(0), size.toULong()) }
    }
    return data ?: NSData()
}

private const val NO_DATA_BYTES: Int = 0
