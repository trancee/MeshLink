package ch.trancee.meshlink.bench.crypto

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import sodium.crypto_aead_chacha20poly1305_ietf_decrypt
import sodium.crypto_aead_chacha20poly1305_ietf_encrypt
import sodium.crypto_auth_hmacsha256
import sodium.crypto_hash_sha256
import sodium.crypto_kdf_hkdf_sha256_expand
import sodium.crypto_kdf_hkdf_sha256_extract
import sodium.crypto_kem_mlkem768_dec
import sodium.crypto_kem_mlkem768_enc
import sodium.crypto_kem_mlkem768_keypair
import sodium.crypto_kem_xwing_dec
import sodium.crypto_kem_xwing_enc
import sodium.crypto_kem_xwing_enc_deterministic
import sodium.crypto_kem_xwing_keypair
import sodium.crypto_kem_xwing_seed_keypair
import sodium.crypto_scalarmult_curve25519
import sodium.crypto_scalarmult_curve25519_base
import sodium.crypto_xof_shake256
import sodium.crypto_sign_ed25519_detached
import sodium.crypto_sign_ed25519_keypair
import sodium.crypto_sign_ed25519_verify_detached
import sodium.randombytes_buf
import sodium.sodium_init
import sodium.sodium_version_string

/** Thin libsodium binding: only the calls MeshLink needs (ADR 0004). Every failure is a typed [CryptoFailure]. */
@OptIn(ExperimentalForeignApi::class)
internal object Sodium {
    const val KEY_BYTES = 32
    const val TAG_BYTES = 16
    const val MLKEM_PUBLIC_KEY_BYTES = 1184
    const val MLKEM_SECRET_KEY_BYTES = 2400
    const val MLKEM_CIPHERTEXT_BYTES = 1088
    const val XWING_PUBLIC_KEY_BYTES = 1216
    const val XWING_SECRET_KEY_BYTES = 32
    const val XWING_CIPHERTEXT_BYTES = 1120
    const val SIGNATURE_BYTES = 64

    private val empty = ByteArray(1)

    val initResult: Int = sodium_init()
    val version: String get() = sodium_version_string()?.toKString() ?: "unknown"

    fun random(size: Int): ByteArray = ByteArray(size).also { out -> out.pin { randombytes_buf(it, size.toULong()) } }

    fun x25519Keypair(): KeyPair {
        val secret = random(KEY_BYTES)
        return KeyPair(x25519Base(secret), secret)
    }

    fun x25519Base(secret: ByteArray): ByteArray = ByteArray(KEY_BYTES).also { out ->
        out.pin { o -> secret.pin { s -> check(crypto_scalarmult_curve25519_base(o, s)) } }
    }

    fun x25519(secret: ByteArray, public: ByteArray): ByteArray = ByteArray(KEY_BYTES).also { out ->
        out.pin { o -> secret.pin { s -> public.pin { p -> check(crypto_scalarmult_curve25519(o, s, p)) } } }
    }

    fun ed25519Keypair(): KeyPair {
        val public = ByteArray(32)
        val secret = ByteArray(64)
        public.pin { p -> secret.pin { s -> check(crypto_sign_ed25519_keypair(p, s)) } }
        return KeyPair(public, secret)
    }

    fun ed25519Sign(secret: ByteArray, message: ByteArray): ByteArray = ByteArray(SIGNATURE_BYTES).also { out ->
        out.pin { o ->
            message.pin { m ->
                secret.pin { s -> check(crypto_sign_ed25519_detached(o, null, m, message.size.toULong(), s)) }
            }
        }
    }

    fun ed25519Verify(public: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        signature.pin { sig ->
            message.pin { m ->
                public.pin { p -> crypto_sign_ed25519_verify_detached(sig, m, message.size.toULong(), p) == 0 }
            }
        }

    fun sha256(input: ByteArray): ByteArray = ByteArray(32).also { out ->
        out.pin { o -> input.pin { i -> check(crypto_hash_sha256(o, i, input.size.toULong())) } }
    }

    fun shake256(input: ByteArray, length: Int): ByteArray = ByteArray(length).also { out ->
        out.pin { o -> input.pin { i -> check(crypto_xof_shake256(o, length.toULong(), i, input.size.toULong())) } }
    }

    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        require(key.size == 32) { "HMAC key must be 32 bytes" }
        return ByteArray(32).also { out ->
            out.pin { o ->
                message.pin { m -> key.pin { k -> check(crypto_auth_hmacsha256(o, m, message.size.toULong(), k)) } }
            }
        }
    }

    fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray = ByteArray(32).also { out ->
        out.pin { o ->
            salt.pin { s ->
                ikm.pin { i ->
                    check(crypto_kdf_hkdf_sha256_extract(o, s, salt.size.toULong(), i, ikm.size.toULong()))
                }
            }
        }
    }

    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray = ByteArray(length).also { out ->
        out.pin { o ->
            (if (info.isEmpty()) empty else info).usePinned { i ->
                prk.pin { p ->
                    check(crypto_kdf_hkdf_sha256_expand(o, length.toULong(), i.addressOf(0), info.size.toULong(), p))
                }
            }
        }
    }

    fun aeadEncrypt(key: ByteArray, nonce: ByteArray, ad: ByteArray, plaintext: ByteArray): ByteArray =
        ByteArray(plaintext.size + TAG_BYTES).also { out ->
            out.pin { o ->
                plaintext.pin { m ->
                    ad.pin { a ->
                        nonce.pin { n ->
                            key.pin { k ->
                                check(
                                    crypto_aead_chacha20poly1305_ietf_encrypt(
                                        o, null, m, plaintext.size.toULong(), a, ad.size.toULong(), null, n, k,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }

    fun aeadDecrypt(key: ByteArray, nonce: ByteArray, ad: ByteArray, ciphertext: ByteArray): ByteArray {
        if (ciphertext.size < TAG_BYTES) throw CryptoFailure("ciphertext shorter than tag")
        val out = ByteArray(ciphertext.size - TAG_BYTES)
        val result = out.pin { o ->
            ciphertext.pin { c ->
                ad.pin { a ->
                    nonce.pin { n ->
                        key.pin { k ->
                            crypto_aead_chacha20poly1305_ietf_decrypt(
                                o, null, null, c, ciphertext.size.toULong(), a, ad.size.toULong(), n, k,
                            )
                        }
                    }
                }
            }
        }
        if (result != 0) throw CryptoFailure("AEAD authentication failed")
        return out
    }

    fun mlkemKeypair(): KeyPair {
        val public = ByteArray(MLKEM_PUBLIC_KEY_BYTES)
        val secret = ByteArray(MLKEM_SECRET_KEY_BYTES)
        public.pin { p -> secret.pin { s -> check(crypto_kem_mlkem768_keypair(p, s)) } }
        return KeyPair(public, secret)
    }

    fun mlkemEncapsulate(public: ByteArray): Encapsulation {
        val ciphertext = ByteArray(MLKEM_CIPHERTEXT_BYTES)
        val secret = ByteArray(32)
        ciphertext.pin { c -> secret.pin { s -> public.pin { p -> check(crypto_kem_mlkem768_enc(c, s, p)) } } }
        return Encapsulation(ciphertext, secret)
    }

    fun mlkemDecapsulate(secretKey: ByteArray, ciphertext: ByteArray): ByteArray = ByteArray(32).also { out ->
        out.pin { o -> ciphertext.pin { c -> secretKey.pin { s -> check(crypto_kem_mlkem768_dec(o, c, s)) } } }
    }

    fun xwingKeypair(): KeyPair {
        val public = ByteArray(XWING_PUBLIC_KEY_BYTES)
        val secret = ByteArray(XWING_SECRET_KEY_BYTES)
        public.pin { p -> secret.pin { s -> check(crypto_kem_xwing_keypair(p, s)) } }
        return KeyPair(public, secret)
    }

    fun xwingSeedKeypair(seed: ByteArray): KeyPair {
        val public = ByteArray(XWING_PUBLIC_KEY_BYTES)
        val secret = ByteArray(XWING_SECRET_KEY_BYTES)
        public.pin { p -> secret.pin { s -> seed.pin { d -> check(crypto_kem_xwing_seed_keypair(p, s, d)) } } }
        return KeyPair(public, secret)
    }

    fun xwingEncapsulate(public: ByteArray, seed: ByteArray? = null): Encapsulation {
        val ciphertext = ByteArray(XWING_CIPHERTEXT_BYTES)
        val secret = ByteArray(32)
        ciphertext.pin { c ->
            secret.pin { s ->
                public.pin { p ->
                    if (seed == null) {
                        check(crypto_kem_xwing_enc(c, s, p))
                    } else {
                        seed.pin { d -> check(crypto_kem_xwing_enc_deterministic(c, s, p, d)) }
                    }
                }
            }
        }
        return Encapsulation(ciphertext, secret)
    }

    fun xwingDecapsulate(secretKey: ByteArray, ciphertext: ByteArray): ByteArray = ByteArray(32).also { out ->
        out.pin { o -> ciphertext.pin { c -> secretKey.pin { s -> check(crypto_kem_xwing_dec(o, c, s)) } } }
    }

    private fun check(result: Int) {
        if (result != 0) throw CryptoFailure("libsodium returned $result")
    }

    private inline fun <R> ByteArray.pin(block: (CPointer<UByteVar>) -> R): R =
        (if (isEmpty()) empty else this).usePinned { pinned -> block(pinned.addressOf(0).reinterpret()) }
}

internal class KeyPair(val public: ByteArray, val secret: ByteArray)

internal class Encapsulation(val ciphertext: ByteArray, val sharedSecret: ByteArray)

internal class CryptoFailure(reason: String) : Exception(reason)

internal fun concat(vararg parts: ByteArray): ByteArray {
    val out = ByteArray(parts.sumOf { it.size })
    var offset = 0
    for (part in parts) {
        part.copyInto(out, offset)
        offset += part.size
    }
    return out
}
