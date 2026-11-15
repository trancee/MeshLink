package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.crypto.sodium.cinterop.crypto_generichash_blake2b
import ch.trancee.meshlink.crypto.sodium.cinterop.crypto_hash_sha256
import ch.trancee.meshlink.crypto.sodium.cinterop.crypto_kdf_hkdf_sha256_expand
import ch.trancee.meshlink.crypto.sodium.cinterop.crypto_kdf_hkdf_sha256_extract
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret

// cinterop bindings to the static libsodium linked into the framework. A thin adapter (ADR 0005
// "Coverage"): it only pins arrays and calls libsodium; the common tests prove it on the simulator.

private const val SHA256_LENGTH = 32

@OptIn(ExperimentalForeignApi::class)
internal actual fun sodiumBlake2b(
    outLength: Int,
    input: ByteArray,
    key: ByteArray?,
): ByteArray =
    output(outLength) { o ->
        input.pinned { i ->
            // An absent key must be NULL, as libsodium requires for an unkeyed hash.
            (key ?: byteArrayOf(0)).pinned { k ->
                crypto_generichash_blake2b(
                    o,
                    outLength.convert(),
                    i,
                    input.size.convert(),
                    if (key == null) null else k,
                    (key?.size ?: 0).convert(),
                )
            }
        }
    }.checked("crypto_generichash_blake2b")

@OptIn(ExperimentalForeignApi::class)
internal actual fun sodiumSha256(input: ByteArray): ByteArray =
    output(SHA256_LENGTH) { o ->
        input.pinned { crypto_hash_sha256(o, it, input.size.convert()) }
    }.checked("crypto_hash_sha256")

@OptIn(ExperimentalForeignApi::class)
internal actual fun sodiumHkdfSha256Extract(
    salt: ByteArray,
    ikm: ByteArray,
): ByteArray =
    output(SHA256_LENGTH) { o ->
        salt.pinned { s ->
            ikm.pinned { i -> crypto_kdf_hkdf_sha256_extract(o, s, salt.size.convert(), i, ikm.size.convert()) }
        }
    }.checked("crypto_kdf_hkdf_sha256_extract")

@OptIn(ExperimentalForeignApi::class)
internal actual fun sodiumHkdfSha256Expand(
    prk: ByteArray,
    info: ByteArray,
    length: Int,
): ByteArray =
    output(length) { o ->
        prk.pinned { p ->
            info.pinned { i ->
                crypto_kdf_hkdf_sha256_expand(o, length.convert(), i.reinterpret(), info.size.convert(), p)
            }
        }
    }.checked("crypto_kdf_hkdf_sha256_expand")
