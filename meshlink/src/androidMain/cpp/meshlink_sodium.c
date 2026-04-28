/*
 * meshlink_sodium.c — JNI bridge between Kotlin's SodiumJni object and libsodium.
 *
 * Package: ch.trancee.meshlink.crypto
 * Kotlin companion: SodiumJni.kt (object SodiumJni, external @JvmStatic methods)
 *
 * Exposes all 10 CryptoProvider operations:
 *   1.  generateEd25519KeyPair  — crypto_sign_keypair
 *   2.  sign                    — crypto_sign_ed25519_detached
 *   3.  verify                  — crypto_sign_ed25519_verify_detached
 *   4.  generateX25519KeyPair   — crypto_kx_keypair
 *   5.  x25519SharedSecret      — crypto_scalarmult
 *   6.  aeadEncrypt             — crypto_aead_chacha20poly1305_ietf_encrypt
 *   7.  aeadDecrypt             — crypto_aead_chacha20poly1305_ietf_decrypt
 *   8.  sha256                  — crypto_hash_sha256
 *   9.  hmacSha256              — crypto_auth_hmacsha256_init/update/final
 *  10.  hkdfSha256              — HMAC-SHA256 extract + expand (RFC 5869)
 *
 * All functions return byte arrays on success or throw Java exceptions on failure.
 * sodium_init() is called once from a static initialiser in SodiumJni.kt.
 *
 * Build: see scripts/build-android-jni.sh
 */

#include <jni.h>
#include <sodium.h>
#include <string.h>
#include <stdint.h>
#include <android/log.h>

#define LOG_TAG "MeshLinkSodium"
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, fmt, ##__VA_ARGS__)

/* ─────────────────────────────────────────────────────────────────────────
 * Helper: throw a Java exception and return NULL / -1 from a JNI function.
 * ───────────────────────────────────────────────────────────────────────── */
static void throw_state(JNIEnv *env, const char *msg) {
    jclass cls = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (cls) (*env)->ThrowNew(env, cls, msg);
}

static void throw_arg(JNIEnv *env, const char *msg) {
    jclass cls = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
    if (cls) (*env)->ThrowNew(env, cls, msg);
}

/* ─────────────────────────────────────────────────────────────────────────
 * 1. sodium_init wrapper — called once from SodiumJni's companion object init
 * ───────────────────────────────────────────────────────────────────────── */
JNIEXPORT jint JNICALL
Java_ch_trancee_meshlink_crypto_SodiumJni_sodiumInit(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return sodium_init();
}

/* ─────────────────────────────────────────────────────────────────────────
 * 2. generateEd25519KeyPair → ByteArray[2]: [pubKey (32), privKey (64)]
 *    Returns a 2-element byte[][] array.
 * ───────────────────────────────────────────────────────────────────────── */
JNIEXPORT jobjectArray JNICALL
Java_ch_trancee_meshlink_crypto_SodiumJni_generateEd25519KeyPair(JNIEnv *env, jclass cls) {
    (void)cls;
    unsigned char pk[crypto_sign_ed25519_PUBLICKEYBYTES];
    unsigned char sk[crypto_sign_ed25519_SECRETKEYBYTES];

    if (crypto_sign_ed25519_keypair(pk, sk) != 0) {
        throw_state(env, "Ed25519 key generation failed");
        return NULL;
    }

    jclass byteArrayClass = (*env)->FindClass(env, "[B");
    jobjectArray result = (*env)->NewObjectArray(env, 2, byteArrayClass, NULL);

    jbyteArray jPk = (*env)->NewByteArray(env, crypto_sign_ed25519_PUBLICKEYBYTES);
    jbyteArray jSk = (*env)->NewByteArray(env, crypto_sign_ed25519_SECRETKEYBYTES);
    (*env)->SetByteArrayRegion(env, jPk, 0, crypto_sign_ed25519_PUBLICKEYBYTES, (jbyte *)pk);
    (*env)->SetByteArrayRegion(env, jSk, 0, crypto_sign_ed25519_SECRETKEYBYTES, (jbyte *)sk);

    (*env)->SetObjectArrayElement(env, result, 0, jPk);
    (*env)->SetObjectArrayElement(env, result, 1, jSk);
    sodium_memzero(sk, sizeof(sk));
    return result;
}

/* ─────────────────────────────────────────────────────────────────────────
 * 3. sign(privateKey: ByteArray, message: ByteArray): ByteArray (64 bytes)
 * ───────────────────────────────────────────────────────────────────────── */
JNIEXPORT jbyteArray JNICALL
Java_ch_trancee_meshlink_crypto_SodiumJni_sign(JNIEnv *env, jclass cls,
                                                jbyteArray jPrivKey, jbyteArray jMessage) {
    (void)cls;
    jsize skLen = (*env)->GetArrayLength(env, jPrivKey);
    if (skLen != crypto_sign_ed25519_SECRETKEYBYTES) {
        throw_arg(env, "Ed25519 private key must be 64 bytes");
        return NULL;
    }

    jbyte *sk  = (*env)->GetByteArrayElements(env, jPrivKey, NULL);
    jbyte *msg = (*env)->GetByteArrayElements(env, jMessage, NULL);
    jsize  msgLen = (*env)->GetArrayLength(env, jMessage);

    unsigned char sig[crypto_sign_ed25519_BYTES];
    unsigned long long sigLen;
    int rc = crypto_sign_ed25519_detached(sig, &sigLen,
                                          (unsigned char *)msg, (unsigned long long)msgLen,
                                          (unsigned char *)sk);
    (*env)->ReleaseByteArrayElements(env, jPrivKey, sk,  JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, jMessage,  msg, JNI_ABORT);

    if (rc != 0) {
        throw_state(env, "Ed25519 signing failed");
        return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, (jsize)sigLen);
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)sigLen, (jbyte *)sig);
    return result;
}

/* ─────────────────────────────────────────────────────────────────────────
 * 4. verify(publicKey, message, signature): Boolean (jboolean)
 * ───────────────────────────────────────────────────────────────────────── */
JNIEXPORT jboolean JNICALL
Java_ch_trancee_meshlink_crypto_SodiumJni_verify(JNIEnv *env, jclass cls,
                                                  jbyteArray jPubKey, jbyteArray jMessage,
                                                  jbyteArray jSig) {
    (void)cls;
    jsize pkLen  = (*env)->GetArrayLength(env, jPubKey);
    jsize sigLen = (*env)->GetArrayLength(env, jSig);
    if (pkLen != crypto_sign_ed25519_PUBLICKEYBYTES) {
        throw_arg(env, "Ed25519 public key must be 32 bytes");
        return JNI_FALSE;
    }
    if (sigLen != crypto_sign_ed25519_BYTES) {
        throw_arg(env, "Ed25519 signature must be 64 bytes");
        return JNI_FALSE;
    }

    jbyte *pk  = (*env)->GetByteArrayElements(env, jPubKey,  NULL);
    jbyte *msg = (*env)->GetByteArrayElements(env, jMessage, NULL);
    jbyte *sig = (*env)->GetByteArrayElements(env, jSig,     NULL);
    jsize  msgLen = (*env)->GetArrayLength(env, jMessage);

    int rc = crypto_sign_ed25519_verify_detached((unsigned char *)sig,
                                                  (unsigned char *)msg,
                                                  (unsigned long long)msgLen,
                                                  (unsigned char *)pk);
    (*env)->ReleaseByteArrayElements(env, jPubKey,  pk,  JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, jMessage, msg, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, jSig,     sig, JNI_ABORT);

    return (rc == 0) ? JNI_TRUE : JNI_FALSE;
}

/* ─────────────────────────────────────────────────────────────────────────
 * 5. generateX25519KeyPair → byte[][2]: [pubKey (32), privKey (32)]
 * ───────────────────────────────────────────────────────────────────────── */
JNIEXPORT jobjectArray JNICALL
Java_ch_trancee_meshlink_crypto_SodiumJni_generateX25519KeyPair(JNIEnv *env, jclass cls) {
    (void)cls;
    unsigned char pk[crypto_kx_PUBLICKEYBYTES];
    unsigned char sk[crypto_kx_SECRETKEYBYTES];

    if (crypto_kx_keypair(pk, sk) != 0) {
        throw_state(env, "X25519 key generation failed");
        return NULL;
    }

    jclass byteArrayClass = (*env)->FindClass(env, "[B");
    jobjectArray result = (*env)->NewObjectArray(env, 2, byteArrayClass, NULL);

    jbyteArray jPk = (*env)->NewByteArray(env, crypto_kx_PUBLICKEYBYTES);
    jbyteArray jSk = (*env)->NewByteArray(env, crypto_kx_SECRETKEYBYTES);
    (*env)->SetByteArrayRegion(env, jPk, 0, crypto_kx_PUBLICKEYBYTES, (jbyte *)pk);
    (*env)->SetByteArrayRegion(env, jSk, 0, crypto_kx_SECRETKEYBYTES, (jbyte *)sk);

    (*env)->SetObjectArrayElement(env, result, 0, jPk);
    (*env)->SetObjectArrayElement(env, result, 1, jSk);
    sodium_memzero(sk, sizeof(sk));
    return result;
}

/* ─────────────────────────────────────────────────────────────────────────
 * 6. x25519SharedSecret(privateKey, publicKey): ByteArray (32 bytes)
 *    Uses crypto_scalarmult (raw X25519 scalar multiplication).
 * ───────────────────────────────────────────────────────────────────────── */
JNIEXPORT jbyteArray JNICALL
Java_ch_trancee_meshlink_crypto_SodiumJni_x25519SharedSecret(JNIEnv *env, jclass cls,
                                                               jbyteArray jPrivKey,
                                                               jbyteArray jPubKey) {
    (void)cls;
    jsize skLen = (*env)->GetArrayLength(env, jPrivKey);
    jsize pkLen = (*env)->GetArrayLength(env, jPubKey);
    if (skLen != crypto_scalarmult_SCALARBYTES) {
        throw_arg(env, "X25519 private key must be 32 bytes");
        return NULL;
    }
    if (pkLen != crypto_scalarmult_BYTES) {
        throw_arg(env, "X25519 public key must be 32 bytes");
        return NULL;
    }

    jbyte *sk = (*env)->GetByteArrayElements(env, jPrivKey, NULL);
    jbyte *pk = (*env)->GetByteArrayElements(env, jPubKey,  NULL);

    unsigned char shared[crypto_scalarmult_BYTES];
    int rc = crypto_scalarmult(shared, (unsigned char *)sk, (unsigned char *)pk);

    (*env)->ReleaseByteArrayElements(env, jPrivKey, sk, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, jPubKey,  pk, JNI_ABORT);

    if (rc != 0) {
        throw_state(env, "X25519 scalar multiplication failed (possible low-order point)");
        return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, crypto_scalarmult_BYTES);
    (*env)->SetByteArrayRegion(env, result, 0, crypto_scalarmult_BYTES, (jbyte *)shared);
    sodium_memzero(shared, sizeof(shared));
    return result;
}

/* ─────────────────────────────────────────────────────────────────────────
 * 7. aeadEncrypt(key, nonce, plaintext, aad): ByteArray (ciphertext + 16-byte tag)
 *    Uses crypto_aead_chacha20poly1305_IETF (RFC 8439 AEAD).
 * ───────────────────────────────────────────────────────────────────────── */
JNIEXPORT jbyteArray JNICALL
Java_ch_trancee_meshlink_crypto_SodiumJni_aeadEncrypt(JNIEnv *env, jclass cls,
                                                       jbyteArray jKey, jbyteArray jNonce,
                                                       jbyteArray jPlaintext, jbyteArray jAad) {
    (void)cls;
    jsize keyLen   = (*env)->GetArrayLength(env, jKey);
    jsize nonceLen = (*env)->GetArrayLength(env, jNonce);
    if (keyLen != crypto_aead_chacha20poly1305_ietf_KEYBYTES) {
        throw_arg(env, "ChaCha20-Poly1305 key must be 32 bytes");
        return NULL;
    }
    if (nonceLen != crypto_aead_chacha20poly1305_ietf_NPUBBYTES) {
        throw_arg(env, "ChaCha20-Poly1305 nonce must be 12 bytes");
        return NULL;
    }

    jbyte *key       = (*env)->GetByteArrayElements(env, jKey,       NULL);
    jbyte *nonce     = (*env)->GetByteArrayElements(env, jNonce,     NULL);
    jbyte *plaintext = (*env)->GetByteArrayElements(env, jPlaintext, NULL);
    jbyte *aad       = (*env)->GetByteArrayElements(env, jAad,       NULL);
    jsize  ptLen     = (*env)->GetArrayLength(env, jPlaintext);
    jsize  aadLen    = (*env)->GetArrayLength(env, jAad);

    jsize  ctMaxLen  = ptLen + crypto_aead_chacha20poly1305_ietf_ABYTES;
    unsigned char *ct = (unsigned char *)sodium_malloc((size_t)ctMaxLen);
    unsigned long long ctActualLen = 0;

    int rc = crypto_aead_chacha20poly1305_ietf_encrypt(
        ct, &ctActualLen,
        (unsigned char *)plaintext, (unsigned long long)ptLen,
        (aadLen > 0) ? (unsigned char *)aad : NULL,
        (unsigned long long)aadLen,
        NULL,
        (unsigned char *)nonce,
        (unsigned char *)key);

    (*env)->ReleaseByteArrayElements(env, jKey,       key,       JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, jNonce,     nonce,     JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, jPlaintext, plaintext, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, jAad,       aad,       JNI_ABORT);

    if (rc != 0) {
        sodium_free(ct);
        throw_state(env, "AEAD encryption failed");
        return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, (jsize)ctActualLen);
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)ctActualLen, (jbyte *)ct);
    sodium_free(ct);
    return result;
}

/* ─────────────────────────────────────────────────────────────────────────
 * 8. aeadDecrypt(key, nonce, ciphertext, aad): ByteArray (plaintext)
 *    Throws IllegalStateException if authentication tag verification fails.
 * ───────────────────────────────────────────────────────────────────────── */
JNIEXPORT jbyteArray JNICALL
Java_ch_trancee_meshlink_crypto_SodiumJni_aeadDecrypt(JNIEnv *env, jclass cls,
                                                       jbyteArray jKey, jbyteArray jNonce,
                                                       jbyteArray jCiphertext, jbyteArray jAad) {
    (void)cls;
    jsize keyLen   = (*env)->GetArrayLength(env, jKey);
    jsize nonceLen = (*env)->GetArrayLength(env, jNonce);
    jsize ctLen    = (*env)->GetArrayLength(env, jCiphertext);
    if (keyLen != crypto_aead_chacha20poly1305_ietf_KEYBYTES) {
        throw_arg(env, "ChaCha20-Poly1305 key must be 32 bytes");
        return NULL;
    }
    if (nonceLen != crypto_aead_chacha20poly1305_ietf_NPUBBYTES) {
        throw_arg(env, "ChaCha20-Poly1305 nonce must be 12 bytes");
        return NULL;
    }
    if (ctLen < (jsize)crypto_aead_chacha20poly1305_ietf_ABYTES) {
        throw_arg(env, "Ciphertext too short to contain authentication tag");
        return NULL;
    }

    jbyte *key        = (*env)->GetByteArrayElements(env, jKey,        NULL);
    jbyte *nonce      = (*env)->GetByteArrayElements(env, jNonce,      NULL);
    jbyte *ciphertext = (*env)->GetByteArrayElements(env, jCiphertext, NULL);
    jbyte *aad        = (*env)->GetByteArrayElements(env, jAad,        NULL);
    jsize  aadLen     = (*env)->GetArrayLength(env, jAad);

    jsize  ptMaxLen   = ctLen - (jsize)crypto_aead_chacha20poly1305_ietf_ABYTES;
    unsigned char *pt = (unsigned char *)sodium_malloc((size_t)ptMaxLen > 0 ? (size_t)ptMaxLen : 1);
    unsigned long long ptActualLen = 0;

    int rc = crypto_aead_chacha20poly1305_ietf_decrypt(
        pt, &ptActualLen,
        NULL,
        (unsigned char *)ciphertext, (unsigned long long)ctLen,
        (aadLen > 0) ? (unsigned char *)aad : NULL,
        (unsigned long long)aadLen,
        (unsigned char *)nonce,
        (unsigned char *)key);

    (*env)->ReleaseByteArrayElements(env, jKey,        key,        JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, jNonce,      nonce,      JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, jCiphertext, ciphertext, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, jAad,        aad,        JNI_ABORT);

    if (rc != 0) {
        sodium_free(pt);
        throw_state(env, "AEAD authentication tag verification failed");
        return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, (jsize)ptActualLen);
    (*env)->SetByteArrayRegion(env, result, 0, (jsize)ptActualLen, (jbyte *)pt);
    sodium_free(pt);
    return result;
}

/* ─────────────────────────────────────────────────────────────────────────
 * 9. sha256(input): ByteArray (32 bytes)
 * ───────────────────────────────────────────────────────────────────────── */
JNIEXPORT jbyteArray JNICALL
Java_ch_trancee_meshlink_crypto_SodiumJni_sha256(JNIEnv *env, jclass cls, jbyteArray jInput) {
    (void)cls;
    jbyte *input    = (*env)->GetByteArrayElements(env, jInput, NULL);
    jsize  inputLen = (*env)->GetArrayLength(env, jInput);

    unsigned char hash[crypto_hash_sha256_BYTES];
    int rc = crypto_hash_sha256(hash, (unsigned char *)input, (unsigned long long)inputLen);
    (*env)->ReleaseByteArrayElements(env, jInput, input, JNI_ABORT);

    if (rc != 0) {
        throw_state(env, "SHA-256 hash failed");
        return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, crypto_hash_sha256_BYTES);
    (*env)->SetByteArrayRegion(env, result, 0, crypto_hash_sha256_BYTES, (jbyte *)hash);
    return result;
}

/* ─────────────────────────────────────────────────────────────────────────
 * 10. hmacSha256(key, data): ByteArray (32 bytes)
 *     HMAC-SHA-256 (RFC 2104) via libsodium's streaming HMAC state machine.
 * ───────────────────────────────────────────────────────────────────────── */
JNIEXPORT jbyteArray JNICALL
Java_ch_trancee_meshlink_crypto_SodiumJni_hmacSha256(JNIEnv *env, jclass cls,
                                                      jbyteArray jKey, jbyteArray jData) {
    (void)cls;
    jbyte *key     = (*env)->GetByteArrayElements(env, jKey,  NULL);
    jbyte *data    = (*env)->GetByteArrayElements(env, jData, NULL);
    jsize  keyLen  = (*env)->GetArrayLength(env, jKey);
    jsize  dataLen = (*env)->GetArrayLength(env, jData);

    unsigned char out[crypto_auth_hmacsha256_BYTES];
    crypto_auth_hmacsha256_state st;
    crypto_auth_hmacsha256_init(&st, (unsigned char *)key, (size_t)keyLen);
    if (dataLen > 0) {
        crypto_auth_hmacsha256_update(&st, (unsigned char *)data, (unsigned long long)dataLen);
    }
    crypto_auth_hmacsha256_final(&st, out);

    (*env)->ReleaseByteArrayElements(env, jKey,  key,  JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, jData, data, JNI_ABORT);

    jbyteArray result = (*env)->NewByteArray(env, crypto_auth_hmacsha256_BYTES);
    (*env)->SetByteArrayRegion(env, result, 0, crypto_auth_hmacsha256_BYTES, (jbyte *)out);
    return result;
}

/* ─────────────────────────────────────────────────────────────────────────
 * 11. hkdfSha256(salt, ikm, info, length): ByteArray
 *     RFC 5869 HKDF-SHA-256 implemented via libsodium HMAC-SHA-256 primitives.
 *
 *     Extract:  PRK = HMAC-SHA-256(salt, IKM)
 *               If salt is empty, use 32 zero bytes (RFC 5869 §2.2).
 *     Expand:   T(0) = ""
 *               T(i) = HMAC-SHA-256(PRK, T(i-1) || info || i)
 *               OKM  = T(1) || T(2) || ... truncated to `length` bytes
 *               Max length: 255 * 32 = 8160 bytes.
 * ───────────────────────────────────────────────────────────────────────── */
JNIEXPORT jbyteArray JNICALL
Java_ch_trancee_meshlink_crypto_SodiumJni_hkdfSha256(JNIEnv *env, jclass cls,
                                                      jbyteArray jSalt, jbyteArray jIkm,
                                                      jbyteArray jInfo, jint jLength) {
    (void)cls;
    if (jLength <= 0 || jLength > 255 * 32) {
        throw_arg(env, "HKDF output length must be between 1 and 8160 bytes");
        return NULL;
    }

    jbyte *salt    = (*env)->GetByteArrayElements(env, jSalt, NULL);
    jbyte *ikm     = (*env)->GetByteArrayElements(env, jIkm,  NULL);
    jbyte *info    = (*env)->GetByteArrayElements(env, jInfo, NULL);
    jsize  saltLen = (*env)->GetArrayLength(env, jSalt);
    jsize  ikmLen  = (*env)->GetArrayLength(env, jIkm);
    jsize  infoLen = (*env)->GetArrayLength(env, jInfo);

    /* ── Extract phase ─────────────────────────────────────────────────── */
    static const unsigned char zero_salt[crypto_auth_hmacsha256_KEYBYTES] = {0};
    const unsigned char *salt_ptr = (saltLen > 0)
        ? (unsigned char *)salt
        : zero_salt;
    size_t salt_actual = (saltLen > 0) ? (size_t)saltLen : sizeof(zero_salt);

    unsigned char prk[crypto_auth_hmacsha256_BYTES];
    crypto_auth_hmacsha256_state st;
    /* HKDF extract: HMAC key = salt, data = IKM.
     * crypto_auth_hmacsha256_* uses arbitrary-length key via init variant. */
    crypto_auth_hmacsha256_init(&st, salt_ptr, salt_actual);
    if (ikmLen > 0) {
        crypto_auth_hmacsha256_update(&st, (unsigned char *)ikm, (unsigned long long)ikmLen);
    }
    crypto_auth_hmacsha256_final(&st, prk);

    (*env)->ReleaseByteArrayElements(env, jSalt, salt, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, jIkm,  ikm,  JNI_ABORT);

    /* ── Expand phase ──────────────────────────────────────────────────── */
    int n = ((int)jLength + 31) / 32;  /* number of blocks needed */
    unsigned char *okm = (unsigned char *)sodium_malloc((size_t)(n * 32));
    unsigned char t[crypto_auth_hmacsha256_BYTES];  /* T(i) accumulator */
    int t_len = 0;  /* length of previous T; 0 for T(0) = "" */

    for (int i = 1; i <= n; i++) {
        unsigned char counter = (unsigned char)i;
        crypto_auth_hmacsha256_init(&st, prk, crypto_auth_hmacsha256_BYTES);
        if (t_len > 0) {
            crypto_auth_hmacsha256_update(&st, t, (unsigned long long)t_len);
        }
        if (infoLen > 0) {
            crypto_auth_hmacsha256_update(&st, (unsigned char *)info, (unsigned long long)infoLen);
        }
        crypto_auth_hmacsha256_update(&st, &counter, 1);
        crypto_auth_hmacsha256_final(&st, t);
        memcpy(okm + (i - 1) * 32, t, 32);
        t_len = 32;
    }

    (*env)->ReleaseByteArrayElements(env, jInfo, info, JNI_ABORT);

    jbyteArray result = (*env)->NewByteArray(env, jLength);
    (*env)->SetByteArrayRegion(env, result, 0, jLength, (jbyte *)okm);

    sodium_memzero(prk, sizeof(prk));
    sodium_memzero(t, sizeof(t));
    sodium_free(okm);
    return result;
}
