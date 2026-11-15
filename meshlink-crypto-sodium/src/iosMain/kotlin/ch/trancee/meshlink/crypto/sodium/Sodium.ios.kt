package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.crypto.sodium.cinterop.randombytes_buf
import ch.trancee.meshlink.crypto.sodium.cinterop.sodium_init
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned

// cinterop bindings to the static libsodium linked into the framework. A thin adapter (ADR 0005
// "Coverage"): it only pins arrays and calls libsodium; the common tests prove it on the simulator.

/** Runs `sodium_init` once, before the first libsodium call. */
@OptIn(ExperimentalForeignApi::class)
internal object Sodium {
    init {
        check(sodium_init() >= 0) { "sodium_init failed" }
    }

    fun initialized() = Unit
}

/**
 * Runs [block] with [bytes] pinned. `addressOf(0)` fails on an empty array, so an empty one pins a
 * one-byte stand-in; callers pass the original size, so libsodium never reads it.
 */
@OptIn(ExperimentalForeignApi::class)
internal inline fun <R> ByteArray.pinned(block: (CPointer<UByteVar>) -> R): R =
    (if (isEmpty()) byteArrayOf(0) else this).usePinned { block(it.addressOf(0).reinterpret()) }

/** Calls libsodium with an output of [length] bytes; the output, or `null` if [call] returned nonzero. */
@OptIn(ExperimentalForeignApi::class)
internal inline fun output(
    length: Int,
    call: (CPointer<UByteVar>) -> Int,
): ByteArray? {
    Sodium.initialized()
    val out = ByteArray(length)
    return if (out.pinned(call) == 0) out else null
}

internal fun ByteArray?.checked(function: String): ByteArray = checkNotNull(this) { "$function failed" }

@OptIn(ExperimentalForeignApi::class)
internal actual fun sodiumRandomBytes(count: Int): ByteArray {
    Sodium.initialized()
    val out = ByteArray(count)
    if (count > 0) {
        out.usePinned { randombytes_buf(it.addressOf(0), count.convert()) }
    }
    return out
}
