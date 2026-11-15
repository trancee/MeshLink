package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.internal.CryptoFailure
import ch.trancee.meshlink.internal.CryptoResult
import kotlin.test.assertEquals
import kotlin.test.assertIs

internal fun <T> CryptoResult<T>.ok(): T = assertIs<CryptoResult.Ok<T>>(this).value

internal fun CryptoResult<*>.assertFailed(reason: CryptoFailure) {
    assertEquals(reason, assertIs<CryptoResult.Failed>(this).reason)
}

internal fun ByteArray.flipped(index: Int): ByteArray = copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
