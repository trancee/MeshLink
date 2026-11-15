package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.internal.Aead
import ch.trancee.meshlink.internal.CryptoFailure
import ch.trancee.meshlink.internal.CryptoProvider
import ch.trancee.meshlink.internal.CryptoResult
import ch.trancee.meshlink.internal.Hashing
import ch.trancee.meshlink.internal.KeyAgreement
import ch.trancee.meshlink.internal.KeyEncapsulation
import ch.trancee.meshlink.internal.RandomBytes
import ch.trancee.meshlink.internal.Signatures

/** The [CryptoProvider] backed by libsodium (ADR 0004), one delegate per primitive family. */
public object SodiumCryptoProvider :
    CryptoProvider,
    Hashing by SodiumHashing,
    Signatures by SodiumClassical,
    KeyAgreement by SodiumClassical,
    Aead by SodiumClassical,
    KeyEncapsulation by SodiumKems,
    RandomBytes by SodiumRandom

/** libsodium's `randombytes_buf`, the secure random source the unseeded variants draw their seeds from. */
internal object SodiumRandom : RandomBytes {
    override fun randomBytes(count: Int): ByteArray {
        require(count >= 0) { "random byte count must not be negative, not $count" }
        return sodiumRandomBytes(count)
    }
}

/** Throws [IllegalArgumentException] unless [bytes], named [name] in the message, is [length] bytes. */
internal fun requireLength(
    name: String,
    bytes: ByteArray,
    length: Int,
) = require(bytes.size == length) { "$name must be $length bytes, not ${bytes.size}" }

/** `this` as [CryptoResult.Ok], or [CryptoResult.Failed] for [reason] if the native refused its input. */
internal fun ByteArray?.orFailed(reason: CryptoFailure): CryptoResult<ByteArray> =
    if (this == null) CryptoResult.Failed(reason) else CryptoResult.Ok(this)

/** `randombytes_buf` into a new array of [count] bytes. */
internal expect fun sodiumRandomBytes(count: Int): ByteArray
