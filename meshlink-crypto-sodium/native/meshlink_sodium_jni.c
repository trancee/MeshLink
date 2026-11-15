/*
 * JNI bridge from the Sodium*Jni objects (androidMain) to libsodium (ADR 0004 "Binding"). It calls only sodium_init,
 * crypto_generichash_blake2b, crypto_hash_sha256, crypto_kdf_hkdf_sha256_extract and _expand,
 * crypto_sign_ed25519_seed_keypair, _detached and _verify_detached, crypto_scalarmult_curve25519 and
 * _base, crypto_aead_chacha20poly1305_ietf_encrypt and _decrypt, crypto_kem_mlkem768_seed_keypair,
 * _enc_deterministic and _dec, crypto_kem_xwing_seed_keypair, _enc_deterministic and _dec,
 * randombytes_buf and sodium_memzero. JNI_OnLoad registers the natives, so it is the only exported
 * symbol. The Kotlin side validates every length, so a libsodium error on caller-controlled input is a
 * bug and throws IllegalStateException; a refusal of peer-controlled bytes returns NULL with no
 * exception pending. Fixed-size secrets are copied into stack buffers that are zeroed before return.
 */
#include <jni.h>
#include <sodium.h>

#define SODIUM_PACKAGE "ch/trancee/meshlink/crypto/sodium/"
#define COUNT(array) ((jint) (sizeof array / sizeof array[0]))

static void throw_state(JNIEnv *env, const char *message) {
    jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (type != NULL) {
        (*env)->ThrowNew(env, type, message);
    }
}

/* A Java array's elements, borrowed read-only: give_back releases them without copying back. */
typedef struct {
    jbyteArray array;
    jbyte *bytes;
    jsize length;
} borrowed;

static int borrow(JNIEnv *env, jbyteArray array, borrowed *out) {
    out->array = array;
    out->length = (*env)->GetArrayLength(env, array);
    out->bytes = (*env)->GetByteArrayElements(env, array, NULL);
    return out->bytes != NULL;
}

static void give_back(JNIEnv *env, borrowed *b) {
    (*env)->ReleaseByteArrayElements(env, b->array, b->bytes, JNI_ABORT);
}

#define BYTES(b) ((const unsigned char *) (b).bytes)

static void copy_in(JNIEnv *env, jbyteArray array, unsigned char *out, jsize length) {
    (*env)->GetByteArrayRegion(env, array, 0, length, (jbyte *) out);
}

static jbyteArray new_array(JNIEnv *env, const unsigned char *bytes, jsize length) {
    jbyteArray result = (*env)->NewByteArray(env, length);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, length, (const jbyte *) bytes);
    }
    return result;
}

static jbyteArray blake2b(JNIEnv *env, jobject self, jint out_length, jbyteArray input, jbyteArray key) {
    (void) self;
    unsigned char out[crypto_generichash_blake2b_BYTES_MAX];
    unsigned char key_bytes[crypto_generichash_blake2b_KEYBYTES_MAX];
    jsize key_length = 0;
    if (key != NULL) {
        key_length = (*env)->GetArrayLength(env, key);
        (*env)->GetByteArrayRegion(env, key, 0, key_length, (jbyte *) key_bytes);
    }
    jsize input_length = (*env)->GetArrayLength(env, input);
    jbyte *input_bytes = (*env)->GetByteArrayElements(env, input, NULL);
    if (input_bytes == NULL) {
        sodium_memzero(key_bytes, sizeof key_bytes);
        return NULL;
    }
    int status = crypto_generichash_blake2b(out, (size_t) out_length, (const unsigned char *) input_bytes,
                                            (unsigned long long) input_length, key_length == 0 ? NULL : key_bytes,
                                            (size_t) key_length);
    (*env)->ReleaseByteArrayElements(env, input, input_bytes, JNI_ABORT);
    sodium_memzero(key_bytes, sizeof key_bytes);
    if (status != 0) {
        throw_state(env, "crypto_generichash_blake2b failed");
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, out_length);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, out_length, (const jbyte *) out);
    }
    return result;
}

static jbyteArray sha256(JNIEnv *env, jobject self, jbyteArray input) {
    (void) self;
    unsigned char out[crypto_hash_sha256_BYTES];
    borrowed in;
    if (!borrow(env, input, &in)) {
        return NULL;
    }
    int status = crypto_hash_sha256(out, BYTES(in), (unsigned long long) in.length);
    give_back(env, &in);
    if (status != 0) {
        throw_state(env, "crypto_hash_sha256 failed");
        return NULL;
    }
    return new_array(env, out, crypto_hash_sha256_BYTES);
}

static jbyteArray hkdf_sha256_extract(JNIEnv *env, jobject self, jbyteArray salt, jbyteArray ikm) {
    (void) self;
    unsigned char prk[crypto_kdf_hkdf_sha256_KEYBYTES];
    borrowed salt_in;
    borrowed ikm_in;
    if (!borrow(env, salt, &salt_in)) {
        return NULL;
    }
    if (!borrow(env, ikm, &ikm_in)) {
        give_back(env, &salt_in);
        return NULL;
    }
    int status = crypto_kdf_hkdf_sha256_extract(prk, BYTES(salt_in), (size_t) salt_in.length, BYTES(ikm_in),
                                                (size_t) ikm_in.length);
    give_back(env, &ikm_in);
    give_back(env, &salt_in);
    jbyteArray result = NULL;
    if (status != 0) {
        throw_state(env, "crypto_kdf_hkdf_sha256_extract failed");
    } else {
        result = new_array(env, prk, crypto_kdf_hkdf_sha256_KEYBYTES);
    }
    sodium_memzero(prk, sizeof prk);
    return result;
}

static jbyteArray hkdf_sha256_expand(JNIEnv *env, jobject self, jbyteArray prk, jbyteArray info, jint length) {
    (void) self;
    unsigned char prk_bytes[crypto_kdf_hkdf_sha256_KEYBYTES];
    copy_in(env, prk, prk_bytes, crypto_kdf_hkdf_sha256_KEYBYTES);
    jbyteArray result = (*env)->NewByteArray(env, length);
    jbyte *out = result == NULL ? NULL : (*env)->GetByteArrayElements(env, result, NULL);
    borrowed info_in;
    if (out == NULL || !borrow(env, info, &info_in)) {
        if (out != NULL) {
            (*env)->ReleaseByteArrayElements(env, result, out, JNI_ABORT);
        }
        sodium_memzero(prk_bytes, sizeof prk_bytes);
        return NULL;
    }
    int status = crypto_kdf_hkdf_sha256_expand((unsigned char *) out, (size_t) length, (const char *) info_in.bytes,
                                               (size_t) info_in.length, prk_bytes);
    give_back(env, &info_in);
    sodium_memzero(prk_bytes, sizeof prk_bytes);
    (*env)->ReleaseByteArrayElements(env, result, out, 0);
    if (status != 0) {
        throw_state(env, "crypto_kdf_hkdf_sha256_expand failed");
        return NULL;
    }
    return result;
}

static jbyteArray ed25519_public_key(JNIEnv *env, jobject self, jbyteArray seed) {
    (void) self;
    unsigned char seed_bytes[crypto_sign_ed25519_SEEDBYTES];
    unsigned char public_key[crypto_sign_ed25519_PUBLICKEYBYTES];
    unsigned char secret_key[crypto_sign_ed25519_SECRETKEYBYTES];
    (*env)->GetByteArrayRegion(env, seed, 0, crypto_sign_ed25519_SEEDBYTES, (jbyte *) seed_bytes);
    int status = crypto_sign_ed25519_seed_keypair(public_key, secret_key, seed_bytes);
    sodium_memzero(seed_bytes, sizeof seed_bytes);
    sodium_memzero(secret_key, sizeof secret_key);
    if (status != 0) {
        throw_state(env, "crypto_sign_ed25519_seed_keypair failed");
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, crypto_sign_ed25519_PUBLICKEYBYTES);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, crypto_sign_ed25519_PUBLICKEYBYTES, (const jbyte *) public_key);
    }
    return result;
}

static jbyteArray ed25519_sign(JNIEnv *env, jobject self, jbyteArray seed, jbyteArray message) {
    (void) self;
    unsigned char seed_bytes[crypto_sign_ed25519_SEEDBYTES];
    unsigned char public_key[crypto_sign_ed25519_PUBLICKEYBYTES];
    unsigned char secret_key[crypto_sign_ed25519_SECRETKEYBYTES];
    unsigned char signature[crypto_sign_ed25519_BYTES];
    borrowed message_in;
    if (!borrow(env, message, &message_in)) {
        return NULL;
    }
    copy_in(env, seed, seed_bytes, crypto_sign_ed25519_SEEDBYTES);
    int status = crypto_sign_ed25519_seed_keypair(public_key, secret_key, seed_bytes);
    if (status == 0) {
        status = crypto_sign_ed25519_detached(signature, NULL, BYTES(message_in), (unsigned long long) message_in.length,
                                              secret_key);
    }
    give_back(env, &message_in);
    sodium_memzero(seed_bytes, sizeof seed_bytes);
    sodium_memzero(secret_key, sizeof secret_key);
    if (status != 0) {
        throw_state(env, "crypto_sign_ed25519_detached failed");
        return NULL;
    }
    return new_array(env, signature, crypto_sign_ed25519_BYTES);
}

static jboolean ed25519_verify(JNIEnv *env, jobject self, jbyteArray public_key, jbyteArray message,
                               jbyteArray signature) {
    (void) self;
    unsigned char public_key_bytes[crypto_sign_ed25519_PUBLICKEYBYTES];
    unsigned char signature_bytes[crypto_sign_ed25519_BYTES];
    borrowed message_in;
    if (!borrow(env, message, &message_in)) {
        return JNI_FALSE;
    }
    copy_in(env, public_key, public_key_bytes, crypto_sign_ed25519_PUBLICKEYBYTES);
    copy_in(env, signature, signature_bytes, crypto_sign_ed25519_BYTES);
    int status = crypto_sign_ed25519_verify_detached(signature_bytes, BYTES(message_in),
                                                     (unsigned long long) message_in.length, public_key_bytes);
    give_back(env, &message_in);
    return status == 0 ? JNI_TRUE : JNI_FALSE;
}

static jbyteArray x25519_public_key(JNIEnv *env, jobject self, jbyteArray secret_key) {
    (void) self;
    unsigned char secret_key_bytes[crypto_scalarmult_curve25519_SCALARBYTES];
    unsigned char public_key[crypto_scalarmult_curve25519_BYTES];
    copy_in(env, secret_key, secret_key_bytes, crypto_scalarmult_curve25519_SCALARBYTES);
    int status = crypto_scalarmult_curve25519_base(public_key, secret_key_bytes);
    sodium_memzero(secret_key_bytes, sizeof secret_key_bytes);
    if (status != 0) {
        throw_state(env, "crypto_scalarmult_curve25519_base failed");
        return NULL;
    }
    return new_array(env, public_key, crypto_scalarmult_curve25519_BYTES);
}

static jbyteArray x25519(JNIEnv *env, jobject self, jbyteArray secret_key, jbyteArray public_key) {
    (void) self;
    unsigned char secret_key_bytes[crypto_scalarmult_curve25519_SCALARBYTES];
    unsigned char public_key_bytes[crypto_scalarmult_curve25519_BYTES];
    unsigned char shared[crypto_scalarmult_curve25519_BYTES];
    copy_in(env, secret_key, secret_key_bytes, crypto_scalarmult_curve25519_SCALARBYTES);
    copy_in(env, public_key, public_key_bytes, crypto_scalarmult_curve25519_BYTES);
    int status = crypto_scalarmult_curve25519(shared, secret_key_bytes, public_key_bytes);
    sodium_memzero(secret_key_bytes, sizeof secret_key_bytes);
    jbyteArray result = status == 0 ? new_array(env, shared, crypto_scalarmult_curve25519_BYTES) : NULL;
    sodium_memzero(shared, sizeof shared);
    return result;
}

static jbyteArray chacha20poly1305_seal(JNIEnv *env, jobject self, jbyteArray key, jbyteArray nonce,
                                        jbyteArray plaintext, jbyteArray associated_data) {
    (void) self;
    unsigned char key_bytes[crypto_aead_chacha20poly1305_ietf_KEYBYTES];
    unsigned char nonce_bytes[crypto_aead_chacha20poly1305_ietf_NPUBBYTES];
    borrowed message;
    borrowed ad;
    if (!borrow(env, plaintext, &message)) {
        return NULL;
    }
    if (!borrow(env, associated_data, &ad)) {
        give_back(env, &message);
        return NULL;
    }
    jsize sealed_length = message.length + crypto_aead_chacha20poly1305_ietf_ABYTES;
    jbyteArray result = (*env)->NewByteArray(env, sealed_length);
    jbyte *out = result == NULL ? NULL : (*env)->GetByteArrayElements(env, result, NULL);
    int status = -1;
    if (out != NULL) {
        copy_in(env, key, key_bytes, crypto_aead_chacha20poly1305_ietf_KEYBYTES);
        copy_in(env, nonce, nonce_bytes, crypto_aead_chacha20poly1305_ietf_NPUBBYTES);
        status = crypto_aead_chacha20poly1305_ietf_encrypt((unsigned char *) out, NULL, BYTES(message),
                                                           (unsigned long long) message.length, BYTES(ad),
                                                           (unsigned long long) ad.length, NULL, nonce_bytes,
                                                           key_bytes);
        sodium_memzero(key_bytes, sizeof key_bytes);
        (*env)->ReleaseByteArrayElements(env, result, out, 0);
    }
    give_back(env, &ad);
    give_back(env, &message);
    if (out == NULL) {
        return NULL;
    }
    if (status != 0) {
        throw_state(env, "crypto_aead_chacha20poly1305_ietf_encrypt failed");
        return NULL;
    }
    return result;
}

static jbyteArray chacha20poly1305_open(JNIEnv *env, jobject self, jbyteArray key, jbyteArray nonce,
                                        jbyteArray ciphertext, jbyteArray associated_data) {
    (void) self;
    unsigned char key_bytes[crypto_aead_chacha20poly1305_ietf_KEYBYTES];
    unsigned char nonce_bytes[crypto_aead_chacha20poly1305_ietf_NPUBBYTES];
    borrowed sealed;
    borrowed ad;
    if (!borrow(env, ciphertext, &sealed)) {
        return NULL;
    }
    if (!borrow(env, associated_data, &ad)) {
        give_back(env, &sealed);
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, sealed.length - crypto_aead_chacha20poly1305_ietf_ABYTES);
    jbyte *out = result == NULL ? NULL : (*env)->GetByteArrayElements(env, result, NULL);
    int status = -1;
    if (out != NULL) {
        copy_in(env, key, key_bytes, crypto_aead_chacha20poly1305_ietf_KEYBYTES);
        copy_in(env, nonce, nonce_bytes, crypto_aead_chacha20poly1305_ietf_NPUBBYTES);
        status = crypto_aead_chacha20poly1305_ietf_decrypt((unsigned char *) out, NULL, NULL, BYTES(sealed),
                                                           (unsigned long long) sealed.length, BYTES(ad),
                                                           (unsigned long long) ad.length, nonce_bytes, key_bytes);
        sodium_memzero(key_bytes, sizeof key_bytes);
        (*env)->ReleaseByteArrayElements(env, result, out, status == 0 ? 0 : JNI_ABORT);
    }
    give_back(env, &ad);
    give_back(env, &sealed);
    return status == 0 ? result : NULL;
}

static jbyteArray mlkem768_keypair(JNIEnv *env, jobject self, jbyteArray seed) {
    (void) self;
    unsigned char seed_bytes[crypto_kem_mlkem768_SEEDBYTES];
    unsigned char keys[crypto_kem_mlkem768_PUBLICKEYBYTES + crypto_kem_mlkem768_SECRETKEYBYTES];
    copy_in(env, seed, seed_bytes, crypto_kem_mlkem768_SEEDBYTES);
    int status = crypto_kem_mlkem768_seed_keypair(keys, keys + crypto_kem_mlkem768_PUBLICKEYBYTES, seed_bytes);
    sodium_memzero(seed_bytes, sizeof seed_bytes);
    jbyteArray result = NULL;
    if (status != 0) {
        throw_state(env, "crypto_kem_mlkem768_seed_keypair failed");
    } else {
        result = new_array(env, keys, (jsize) sizeof keys);
    }
    sodium_memzero(keys, sizeof keys);
    return result;
}

static jbyteArray mlkem768_encapsulate(JNIEnv *env, jobject self, jbyteArray public_key, jbyteArray seed) {
    (void) self;
    unsigned char public_key_bytes[crypto_kem_mlkem768_PUBLICKEYBYTES];
    unsigned char seed_bytes[32];
    unsigned char out[crypto_kem_mlkem768_CIPHERTEXTBYTES + crypto_kem_mlkem768_SHAREDSECRETBYTES];
    copy_in(env, public_key, public_key_bytes, crypto_kem_mlkem768_PUBLICKEYBYTES);
    copy_in(env, seed, seed_bytes, (jsize) sizeof seed_bytes);
    int status = crypto_kem_mlkem768_enc_deterministic(out, out + crypto_kem_mlkem768_CIPHERTEXTBYTES,
                                                       public_key_bytes, seed_bytes);
    sodium_memzero(seed_bytes, sizeof seed_bytes);
    jbyteArray result = status == 0 ? new_array(env, out, (jsize) sizeof out) : NULL;
    sodium_memzero(out, sizeof out);
    return result;
}

static jbyteArray mlkem768_decapsulate(JNIEnv *env, jobject self, jbyteArray secret_key, jbyteArray ciphertext) {
    (void) self;
    unsigned char secret_key_bytes[crypto_kem_mlkem768_SECRETKEYBYTES];
    unsigned char ciphertext_bytes[crypto_kem_mlkem768_CIPHERTEXTBYTES];
    unsigned char shared[crypto_kem_mlkem768_SHAREDSECRETBYTES];
    copy_in(env, secret_key, secret_key_bytes, crypto_kem_mlkem768_SECRETKEYBYTES);
    copy_in(env, ciphertext, ciphertext_bytes, crypto_kem_mlkem768_CIPHERTEXTBYTES);
    int status = crypto_kem_mlkem768_dec(shared, ciphertext_bytes, secret_key_bytes);
    sodium_memzero(secret_key_bytes, sizeof secret_key_bytes);
    jbyteArray result = NULL;
    if (status != 0) {
        throw_state(env, "crypto_kem_mlkem768_dec failed");
    } else {
        result = new_array(env, shared, crypto_kem_mlkem768_SHAREDSECRETBYTES);
    }
    sodium_memzero(shared, sizeof shared);
    return result;
}

static jbyteArray xwing_public_key(JNIEnv *env, jobject self, jbyteArray seed) {
    (void) self;
    unsigned char seed_bytes[crypto_kem_xwing_SEEDBYTES];
    unsigned char public_key[crypto_kem_xwing_PUBLICKEYBYTES];
    unsigned char secret_key[crypto_kem_xwing_SECRETKEYBYTES];
    copy_in(env, seed, seed_bytes, crypto_kem_xwing_SEEDBYTES);
    int status = crypto_kem_xwing_seed_keypair(public_key, secret_key, seed_bytes);
    sodium_memzero(seed_bytes, sizeof seed_bytes);
    sodium_memzero(secret_key, sizeof secret_key);
    if (status != 0) {
        throw_state(env, "crypto_kem_xwing_seed_keypair failed");
        return NULL;
    }
    return new_array(env, public_key, crypto_kem_xwing_PUBLICKEYBYTES);
}

static jbyteArray xwing_encapsulate(JNIEnv *env, jobject self, jbyteArray public_key, jbyteArray seed) {
    (void) self;
    unsigned char public_key_bytes[crypto_kem_xwing_PUBLICKEYBYTES];
    unsigned char seed_bytes[64];
    unsigned char out[crypto_kem_xwing_CIPHERTEXTBYTES + crypto_kem_xwing_SHAREDSECRETBYTES];
    copy_in(env, public_key, public_key_bytes, crypto_kem_xwing_PUBLICKEYBYTES);
    copy_in(env, seed, seed_bytes, (jsize) sizeof seed_bytes);
    int status = crypto_kem_xwing_enc_deterministic(out, out + crypto_kem_xwing_CIPHERTEXTBYTES, public_key_bytes,
                                                    seed_bytes);
    sodium_memzero(seed_bytes, sizeof seed_bytes);
    jbyteArray result = status == 0 ? new_array(env, out, (jsize) sizeof out) : NULL;
    sodium_memzero(out, sizeof out);
    return result;
}

static jbyteArray xwing_decapsulate(JNIEnv *env, jobject self, jbyteArray secret_key, jbyteArray ciphertext) {
    (void) self;
    unsigned char secret_key_bytes[crypto_kem_xwing_SECRETKEYBYTES];
    unsigned char ciphertext_bytes[crypto_kem_xwing_CIPHERTEXTBYTES];
    unsigned char shared[crypto_kem_xwing_SHAREDSECRETBYTES];
    copy_in(env, secret_key, secret_key_bytes, crypto_kem_xwing_SECRETKEYBYTES);
    copy_in(env, ciphertext, ciphertext_bytes, crypto_kem_xwing_CIPHERTEXTBYTES);
    int status = crypto_kem_xwing_dec(shared, ciphertext_bytes, secret_key_bytes);
    sodium_memzero(secret_key_bytes, sizeof secret_key_bytes);
    jbyteArray result = status == 0 ? new_array(env, shared, crypto_kem_xwing_SHAREDSECRETBYTES) : NULL;
    sodium_memzero(shared, sizeof shared);
    return result;
}

static jbyteArray random_bytes(JNIEnv *env, jobject self, jint count) {
    (void) self;
    jbyteArray result = (*env)->NewByteArray(env, count);
    if (result == NULL) {
        return NULL;
    }
    jbyte *bytes = (*env)->GetByteArrayElements(env, result, NULL);
    if (bytes == NULL) {
        return NULL;
    }
    randombytes_buf(bytes, (size_t) count);
    (*env)->ReleaseByteArrayElements(env, result, bytes, 0);
    return result;
}

static const JNINativeMethod HASHING[] = {
    {"blake2b", "(I[B[B)[B", (void *) blake2b},
    {"sha256", "([B)[B", (void *) sha256},
    {"hkdfSha256Extract", "([B[B)[B", (void *) hkdf_sha256_extract},
    {"hkdfSha256Expand", "([B[BI)[B", (void *) hkdf_sha256_expand},
};

static const JNINativeMethod CLASSICAL[] = {
    {"ed25519PublicKey", "([B)[B", (void *) ed25519_public_key},
    {"ed25519Sign", "([B[B)[B", (void *) ed25519_sign},
    {"ed25519Verify", "([B[B[B)Z", (void *) ed25519_verify},
    {"x25519PublicKey", "([B)[B", (void *) x25519_public_key},
    {"x25519", "([B[B)[B", (void *) x25519},
    {"chaCha20Poly1305Seal", "([B[B[B[B)[B", (void *) chacha20poly1305_seal},
    {"chaCha20Poly1305Open", "([B[B[B[B)[B", (void *) chacha20poly1305_open},
};

static const JNINativeMethod KEMS[] = {
    {"mlKem768KeyPair", "([B)[B", (void *) mlkem768_keypair},
    {"mlKem768Encapsulate", "([B[B)[B", (void *) mlkem768_encapsulate},
    {"mlKem768Decapsulate", "([B[B)[B", (void *) mlkem768_decapsulate},
    {"xWingPublicKey", "([B)[B", (void *) xwing_public_key},
    {"xWingEncapsulate", "([B[B)[B", (void *) xwing_encapsulate},
    {"xWingDecapsulate", "([B[B)[B", (void *) xwing_decapsulate},
};

static const JNINativeMethod RANDOM[] = {
    {"randomBytes", "(I)[B", (void *) random_bytes},
};

static int register_natives(JNIEnv *env, const char *name, const JNINativeMethod *methods, jint count) {
    jclass type = (*env)->FindClass(env, name);
    return type != NULL && (*env)->RegisterNatives(env, type, methods, count) == JNI_OK;
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK || sodium_init() < 0) {
        return JNI_ERR;
    }
    if (!register_natives(env, SODIUM_PACKAGE "SodiumHashingJni", HASHING, COUNT(HASHING)) ||
        !register_natives(env, SODIUM_PACKAGE "SodiumClassicalJni", CLASSICAL, COUNT(CLASSICAL)) ||
        !register_natives(env, SODIUM_PACKAGE "SodiumKemsJni", KEMS, COUNT(KEMS)) ||
        !register_natives(env, SODIUM_PACKAGE "SodiumRandomJni", RANDOM, COUNT(RANDOM))) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
