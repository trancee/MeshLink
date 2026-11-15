package ch.trancee.meshlink.crypto.sodium

import ch.trancee.meshlink.crypto.sodium.cinterop.crypto_kem_mlkem768_dec
import ch.trancee.meshlink.crypto.sodium.cinterop.crypto_kem_mlkem768_enc_deterministic
import ch.trancee.meshlink.crypto.sodium.cinterop.crypto_kem_mlkem768_seed_keypair
import ch.trancee.meshlink.crypto.sodium.cinterop.crypto_kem_xwing_dec
import ch.trancee.meshlink.crypto.sodium.cinterop.crypto_kem_xwing_enc_deterministic
import ch.trancee.meshlink.crypto.sodium.cinterop.crypto_kem_xwing_seed_keypair
import ch.trancee.meshlink.crypto.sodium.cinterop.sodium_memzero
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus

// cinterop bindings to the static libsodium linked into the framework. A thin adapter (ADR 0005
// "Coverage"): it only pins arrays and calls libsodium; the common tests prove it on the simulator.

private const val ML_KEM_768_PUBLIC_KEY_LENGTH = 1184
private const val ML_KEM_768_KEY_PAIR_LENGTH = ML_KEM_768_PUBLIC_KEY_LENGTH + 2400
private const val ML_KEM_768_CIPHERTEXT_LENGTH = 1088
private const val X_WING_PUBLIC_KEY_LENGTH = 1216
private const val X_WING_SECRET_KEY_LENGTH = 32
private const val X_WING_CIPHERTEXT_LENGTH = 1120
private const val SHARED_SECRET_LENGTH = 32

@OptIn(ExperimentalForeignApi::class)
internal actual fun sodiumMlKem768KeyPair(seed: ByteArray): ByteArray =
    output(ML_KEM_768_KEY_PAIR_LENGTH) { o ->
        seed.pinned { s -> crypto_kem_mlkem768_seed_keypair(o, (o + ML_KEM_768_PUBLIC_KEY_LENGTH)!!, s) }
    }.checked("crypto_kem_mlkem768_seed_keypair")

@OptIn(ExperimentalForeignApi::class)
internal actual fun sodiumMlKem768Encapsulate(
    publicKey: ByteArray,
    seed: ByteArray,
): ByteArray? =
    output(ML_KEM_768_CIPHERTEXT_LENGTH + SHARED_SECRET_LENGTH) { o ->
        publicKey.pinned { p ->
            seed.pinned { s -> crypto_kem_mlkem768_enc_deterministic(o, (o + ML_KEM_768_CIPHERTEXT_LENGTH)!!, p, s) }
        }
    }

@OptIn(ExperimentalForeignApi::class)
internal actual fun sodiumMlKem768Decapsulate(
    secretKey: ByteArray,
    ciphertext: ByteArray,
): ByteArray =
    output(SHARED_SECRET_LENGTH) { o ->
        secretKey.pinned { s -> ciphertext.pinned { c -> crypto_kem_mlkem768_dec(o, c, s) } }
    }.checked("crypto_kem_mlkem768_dec")

@OptIn(ExperimentalForeignApi::class)
internal actual fun sodiumXWingPublicKey(seed: ByteArray): ByteArray =
    memScoped {
        val secretKey = allocArray<UByteVar>(X_WING_SECRET_KEY_LENGTH)
        val publicKey =
            output(X_WING_PUBLIC_KEY_LENGTH) { p ->
                seed.pinned { s -> crypto_kem_xwing_seed_keypair(p, secretKey, s) }
            }
        sodium_memzero(secretKey, X_WING_SECRET_KEY_LENGTH.convert())
        publicKey.checked("crypto_kem_xwing_seed_keypair")
    }

@OptIn(ExperimentalForeignApi::class)
internal actual fun sodiumXWingEncapsulate(
    publicKey: ByteArray,
    seed: ByteArray,
): ByteArray? =
    output(X_WING_CIPHERTEXT_LENGTH + SHARED_SECRET_LENGTH) { o ->
        publicKey.pinned { p ->
            seed.pinned { s -> crypto_kem_xwing_enc_deterministic(o, (o + X_WING_CIPHERTEXT_LENGTH)!!, p, s) }
        }
    }

@OptIn(ExperimentalForeignApi::class)
internal actual fun sodiumXWingDecapsulate(
    secretKey: ByteArray,
    ciphertext: ByteArray,
): ByteArray? =
    output(SHARED_SECRET_LENGTH) { o ->
        secretKey.pinned { s -> ciphertext.pinned { c -> crypto_kem_xwing_dec(o, c, s) } }
    }
