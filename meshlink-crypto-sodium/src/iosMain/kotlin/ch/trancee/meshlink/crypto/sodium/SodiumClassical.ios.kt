package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.crypto.sodium.cinterop.crypto_aead_chacha20poly1305_ietf_decrypt
import ch.trancee.meshlink.crypto.sodium.cinterop.crypto_aead_chacha20poly1305_ietf_encrypt
import ch.trancee.meshlink.crypto.sodium.cinterop.crypto_scalarmult_curve25519
import ch.trancee.meshlink.crypto.sodium.cinterop.crypto_scalarmult_curve25519_base
import ch.trancee.meshlink.crypto.sodium.cinterop.crypto_sign_ed25519_detached
import ch.trancee.meshlink.crypto.sodium.cinterop.crypto_sign_ed25519_seed_keypair
import ch.trancee.meshlink.crypto.sodium.cinterop.crypto_sign_ed25519_verify_detached
import ch.trancee.meshlink.crypto.sodium.cinterop.sodium_memzero
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped

// cinterop bindings to the static libsodium linked into the framework. A thin adapter (ADR 0005
// "Coverage"): it only pins arrays and calls libsodium; the common tests prove it on the simulator.

private const val ED25519_PUBLIC_KEY_LENGTH = 32
private const val ED25519_SECRET_KEY_LENGTH = 64
private const val ED25519_SIGNATURE_LENGTH = 64
private const val X25519_LENGTH = 32
private const val AEAD_TAG_LENGTH = 16

@OptIn(ExperimentalForeignApi::class)
internal actual fun sodiumEd25519PublicKey(seed: ByteArray): ByteArray =
    memScoped {
        val secretKey = allocArray<UByteVar>(ED25519_SECRET_KEY_LENGTH)
        val publicKey =
            output(ED25519_PUBLIC_KEY_LENGTH) { p ->
                seed.pinned { s -> crypto_sign_ed25519_seed_keypair(p, secretKey, s) }
            }
        sodium_memzero(secretKey, ED25519_SECRET_KEY_LENGTH.convert())
        publicKey.checked("crypto_sign_ed25519_seed_keypair")
    }

@OptIn(ExperimentalForeignApi::class)
internal actual fun sodiumEd25519Sign(
    seed: ByteArray,
    message: ByteArray,
): ByteArray =
    memScoped {
        val publicKey = allocArray<UByteVar>(ED25519_PUBLIC_KEY_LENGTH)
        val secretKey = allocArray<UByteVar>(ED25519_SECRET_KEY_LENGTH)
        val signature =
            output(ED25519_SIGNATURE_LENGTH) { o ->
                seed.pinned { s ->
                    message.pinned { m ->
                        val derived = crypto_sign_ed25519_seed_keypair(publicKey, secretKey, s)
                        if (derived != 0) {
                            derived
                        } else {
                            crypto_sign_ed25519_detached(o, null, m, message.size.convert(), secretKey)
                        }
                    }
                }
            }
        sodium_memzero(secretKey, ED25519_SECRET_KEY_LENGTH.convert())
        signature.checked("crypto_sign_ed25519_detached")
    }

@OptIn(ExperimentalForeignApi::class)
internal actual fun sodiumEd25519Verify(
    publicKey: ByteArray,
    message: ByteArray,
    signature: ByteArray,
): Boolean {
    Sodium.initialized()
    return signature.pinned { s ->
        message.pinned { m ->
            publicKey.pinned { p -> crypto_sign_ed25519_verify_detached(s, m, message.size.convert(), p) }
        }
    } == 0
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun sodiumX25519PublicKey(key: ByteArray): ByteArray =
    output(X25519_LENGTH) { o ->
        key.pinned { crypto_scalarmult_curve25519_base(o, it) }
    }.checked("crypto_scalarmult_curve25519_base")

@OptIn(ExperimentalForeignApi::class)
internal actual fun sodiumX25519(
    secretKey: ByteArray,
    publicKey: ByteArray,
): ByteArray? =
    output(X25519_LENGTH) { o ->
        secretKey.pinned { s -> publicKey.pinned { p -> crypto_scalarmult_curve25519(o, s, p) } }
    }

@OptIn(ExperimentalForeignApi::class)
internal actual fun sodiumChaCha20Poly1305Seal(
    key: ByteArray,
    nonce: ByteArray,
    plaintext: ByteArray,
    associatedData: ByteArray,
): ByteArray =
    output(plaintext.size + AEAD_TAG_LENGTH) { o ->
        key.pinned { k ->
            nonce.pinned { n ->
                plaintext.pinned { m ->
                    associatedData.pinned { a ->
                        crypto_aead_chacha20poly1305_ietf_encrypt(
                            o,
                            null,
                            m,
                            plaintext.size.convert(),
                            a,
                            associatedData.size.convert(),
                            null,
                            n,
                            k,
                        )
                    }
                }
            }
        }
    }.checked("crypto_aead_chacha20poly1305_ietf_encrypt")

@OptIn(ExperimentalForeignApi::class)
internal actual fun sodiumChaCha20Poly1305Open(
    key: ByteArray,
    nonce: ByteArray,
    ciphertext: ByteArray,
    associatedData: ByteArray,
): ByteArray? =
    output(ciphertext.size - AEAD_TAG_LENGTH) { o ->
        key.pinned { k ->
            nonce.pinned { n ->
                ciphertext.pinned { c ->
                    associatedData.pinned { a ->
                        crypto_aead_chacha20poly1305_ietf_decrypt(
                            o,
                            null,
                            null,
                            c,
                            ciphertext.size.convert(),
                            a,
                            associatedData.size.convert(),
                            n,
                            k,
                        )
                    }
                }
            }
        }
    }
