@file:Suppress("WildcardImport")

package ch.trancee.meshlink.crypto

import kotlinx.cinterop.*
import libsodium.*

@OptIn(ExperimentalForeignApi::class)
internal class IosCryptoProvider : CryptoProvider {

    init {
        check(sodium_init() >= 0) { "sodium_init() failed" }
    }

    override fun generateEd25519KeyPair(): KeyPair {
        val pk = ByteArray(32)
        val sk = ByteArray(64)
        memScoped {
            val rc =
                crypto_sign_ed25519_keypair(
                    pk.refTo(0).getPointer(this).reinterpret<UByteVar>(),
                    sk.refTo(0).getPointer(this).reinterpret<UByteVar>(),
                )
            check(rc == 0) { "Ed25519 key generation failed" }
        }
        return KeyPair(publicKey = pk, privateKey = sk)
    }

    override fun sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        val sig = ByteArray(64)
        memScoped {
            val sigLen = alloc<ULongVar>()
            val msgPtr =
                if (message.isNotEmpty()) message.refTo(0).getPointer(this).reinterpret<UByteVar>()
                else null
            val rc =
                crypto_sign_ed25519_detached(
                    sig.refTo(0).getPointer(this).reinterpret<UByteVar>(),
                    sigLen.ptr,
                    msgPtr,
                    message.size.toULong(),
                    privateKey.refTo(0).getPointer(this).reinterpret<UByteVar>(),
                )
            check(rc == 0) { "Ed25519 signing failed" }
        }
        return sig
    }

    override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        return memScoped {
            val msgPtr =
                if (message.isNotEmpty()) message.refTo(0).getPointer(this).reinterpret<UByteVar>()
                else null
            crypto_sign_ed25519_verify_detached(
                signature.refTo(0).getPointer(this).reinterpret<UByteVar>(),
                msgPtr,
                message.size.toULong(),
                publicKey.refTo(0).getPointer(this).reinterpret<UByteVar>(),
            ) == 0
        }
    }

    override fun generateX25519KeyPair(): KeyPair {
        val pk = ByteArray(32)
        val sk = ByteArray(32)
        memScoped {
            val rc =
                crypto_kx_keypair(
                    pk.refTo(0).getPointer(this).reinterpret<UByteVar>(),
                    sk.refTo(0).getPointer(this).reinterpret<UByteVar>(),
                )
            check(rc == 0) { "X25519 key generation failed" }
        }
        return KeyPair(publicKey = pk, privateKey = sk)
    }

    override fun x25519SharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val shared = ByteArray(32)
        memScoped {
            val rc =
                crypto_scalarmult(
                    shared.refTo(0).getPointer(this).reinterpret<UByteVar>(),
                    privateKey.refTo(0).getPointer(this).reinterpret<UByteVar>(),
                    publicKey.refTo(0).getPointer(this).reinterpret<UByteVar>(),
                )
            check(rc == 0) { "X25519 scalar multiplication failed (possible low-order point)" }
        }
        return shared
    }

    override fun aeadEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val ct = ByteArray(plaintext.size + 16)
        memScoped {
            val ctLen = alloc<ULongVar>()
            val ptPtr =
                if (plaintext.isNotEmpty())
                    plaintext.refTo(0).getPointer(this).reinterpret<UByteVar>()
                else null
            val aadPtr =
                if (aad.isNotEmpty()) aad.refTo(0).getPointer(this).reinterpret<UByteVar>()
                else null
            val rc =
                crypto_aead_chacha20poly1305_ietf_encrypt(
                    ct.refTo(0).getPointer(this).reinterpret<UByteVar>(),
                    ctLen.ptr,
                    ptPtr,
                    plaintext.size.toULong(),
                    aadPtr,
                    aad.size.toULong(),
                    null,
                    nonce.refTo(0).getPointer(this).reinterpret<UByteVar>(),
                    key.refTo(0).getPointer(this).reinterpret<UByteVar>(),
                )
            check(rc == 0) { "AEAD encryption failed" }
        }
        return ct
    }

    override fun aeadDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        val pt = ByteArray(ciphertext.size - 16)
        memScoped {
            val ptLen = alloc<ULongVar>()
            val ptPtr =
                if (pt.isNotEmpty()) pt.refTo(0).getPointer(this).reinterpret<UByteVar>() else null
            val aadPtr =
                if (aad.isNotEmpty()) aad.refTo(0).getPointer(this).reinterpret<UByteVar>()
                else null
            val rc =
                crypto_aead_chacha20poly1305_ietf_decrypt(
                    ptPtr,
                    ptLen.ptr,
                    null,
                    ciphertext.refTo(0).getPointer(this).reinterpret<UByteVar>(),
                    ciphertext.size.toULong(),
                    aadPtr,
                    aad.size.toULong(),
                    nonce.refTo(0).getPointer(this).reinterpret<UByteVar>(),
                    key.refTo(0).getPointer(this).reinterpret<UByteVar>(),
                )
            check(rc == 0) { "AEAD authentication tag verification failed" }
        }
        return pt
    }

    override fun sha256(input: ByteArray): ByteArray {
        val out = ByteArray(32)
        memScoped {
            val inputPtr =
                if (input.isNotEmpty()) input.refTo(0).getPointer(this).reinterpret<UByteVar>()
                else null
            val rc =
                crypto_hash_sha256(
                    out.refTo(0).getPointer(this).reinterpret<UByteVar>(),
                    inputPtr,
                    input.size.toULong(),
                )
            check(rc == 0) { "SHA-256 hash failed" }
        }
        return out
    }

    override fun hkdfSha256(
        salt: ByteArray,
        ikm: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        require(length in 1..8160) { "HKDF output length out of bounds: $length" }
        val effectiveSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val prk = hmacSha256(effectiveSalt, ikm)
        val okm = ByteArray(length)
        val n = (length + 31) / 32
        var t = ByteArray(0)
        var offset = 0
        for (i in 1..n) {
            t = hmacSha256(prk, t, info, byteArrayOf(i.toByte()))
            val toCopy = minOf(32, length - offset)
            t.copyInto(okm, offset, 0, toCopy)
            offset += toCopy
        }
        return okm
    }

    /**
     * Computes HMAC-SHA-256(key, chunk1 || chunk2 || ...) via libsodium's streaming HMAC-SHA-256
     * state machine. Empty chunks are skipped (no-op update). Used by [hkdfSha256] for both the
     * extract and expand phases (RFC 5869).
     */
    private fun hmacSha256(key: ByteArray, vararg data: ByteArray): ByteArray {
        val out = ByteArray(32)
        memScoped {
            val state = alloc<crypto_auth_hmacsha256_state>()
            crypto_auth_hmacsha256_init(
                state.ptr,
                key.refTo(0).getPointer(this).reinterpret<UByteVar>(),
                key.size.toULong(),
            )
            for (chunk in data) {
                if (chunk.isNotEmpty()) {
                    crypto_auth_hmacsha256_update(
                        state.ptr,
                        chunk.refTo(0).getPointer(this).reinterpret<UByteVar>(),
                        chunk.size.toULong(),
                    )
                }
            }
            crypto_auth_hmacsha256_final(
                state.ptr,
                out.refTo(0).getPointer(this).reinterpret<UByteVar>(),
            )
        }
        return out
    }
}

actual fun createCryptoProvider(): CryptoProvider = IosCryptoProvider()
