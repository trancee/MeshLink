package bakeoff.ios

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.create
import platform.posix.memcpy

fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val out = ByteArray(size)
    if (size > 0) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

fun cbuuid(text: String): CBUUID = CBUUID.UUIDWithString(text)

fun CBUUID.matches(text: String): Boolean = UUIDString.equals(text, ignoreCase = true)

fun NSError.describe(): String = "NSError ${domain}:${code} ${localizedDescription}"
