@file:OptIn(BetaInteropApi::class, ExperimentalForeignApi::class, ExperimentalObjCRefinement::class)

package ch.trancee.meshlink

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.ShouldRefineInSwift

/**
 * Copies these bytes into a new [NSData] with one bulk copy.
 *
 * The Swift overlay uses it to hand payloads to Swift as `Data`. Swift sees it only as
 * `__toNSData`, so Swift apps use the overlay's `Data` API instead (ADR 0007).
 */
@ShouldRefineInSwift
public fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
}

/**
 * Copies the bytes of this [NSData] into a new [ByteArray] with one bulk copy.
 *
 * The Swift overlay uses it to take payloads from Swift `Data`. Swift sees it only as
 * `__toByteArray`, so Swift apps use the overlay's `Data` API instead (ADR 0007).
 */
@ShouldRefineInSwift
public fun NSData.toByteArray(): ByteArray {
    val bytes = ByteArray(length.toInt())
    if (bytes.isNotEmpty()) bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
    return bytes
}
