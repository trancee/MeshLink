package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.crypto.sodium.cinterop.crypto_generichash_blake2b
import ch.trancee.meshlink.crypto.sodium.cinterop.crypto_sign_ed25519_seed_keypair
import ch.trancee.meshlink.crypto.sodium.cinterop.randombytes_buf
import ch.trancee.meshlink.crypto.sodium.cinterop.sodium_init
import ch.trancee.meshlink.crypto.sodium.cinterop.sodium_memzero
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned

// cinterop bindings to the static libsodium linked into the framework. A thin adapter (ADR 0005
// "Coverage"): it only pins arrays and calls libsodium; the common tests prove it on the simulator.

private const val PUBLIC_KEY_LENGTH = 32
private const val SECRET_KEY_LENGTH = 64

/** Runs `sodium_init` once, before the first libsodium call. */
@OptIn(ExperimentalForeignApi::class)
private object Sodium {
    init {
        check(sodium_init() >= 0) { "sodium_init failed" }
    }

    fun initialized() = Unit
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun sodiumBlake2b(
    outLength: Int,
    input: ByteArray,
    key: ByteArray?,
): ByteArray {
    Sodium.initialized()
    val out = ByteArray(outLength)
    // addressOf(0) fails on an empty array, so an empty input or absent key pins a one-byte stand-in
    // and passes a zero length (a NULL key, as libsodium requires for an unkeyed hash).
    val inputCopy = if (input.isEmpty()) byteArrayOf(0) else input
    val keyCopy = key ?: byteArrayOf(0)
    val status =
        out.usePinned { o ->
            inputCopy.usePinned { i ->
                keyCopy.usePinned { k ->
                    crypto_generichash_blake2b(
                        o.addressOf(0).reinterpret(),
                        outLength.convert(),
                        i.addressOf(0).reinterpret(),
                        input.size.convert(),
                        if (key == null) null else k.addressOf(0).reinterpret(),
                        (key?.size ?: 0).convert(),
                    )
                }
            }
        }
    check(status == 0) { "crypto_generichash_blake2b failed" }
    return out
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun sodiumEd25519PublicKey(seed: ByteArray): ByteArray {
    Sodium.initialized()
    return memScoped {
        val publicKey = ByteArray(PUBLIC_KEY_LENGTH)
        val secretKey = allocArray<UByteVar>(SECRET_KEY_LENGTH)
        val status =
            publicKey.usePinned { p ->
                seed.usePinned { s ->
                    crypto_sign_ed25519_seed_keypair(
                        p.addressOf(0).reinterpret(),
                        secretKey,
                        s.addressOf(0).reinterpret(),
                    )
                }
            }
        sodium_memzero(secretKey, SECRET_KEY_LENGTH.convert())
        check(status == 0) { "crypto_sign_ed25519_seed_keypair failed" }
        publicKey
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun sodiumRandomBytes(count: Int): ByteArray {
    Sodium.initialized()
    val out = ByteArray(count)
    if (count > 0) {
        out.usePinned { randombytes_buf(it.addressOf(0), count.convert()) }
    }
    return out
}
