/*
 * JNI bridge from SodiumJni (androidMain) to libsodium (ADR 0004 "Binding"). It calls only
 * sodium_init, crypto_generichash_blake2b, crypto_sign_ed25519_seed_keypair, randombytes_buf and
 * sodium_memzero. JNI_OnLoad registers the natives, so it is the only exported symbol; the Kotlin
 * side validates every argument, so a libsodium error here is a bug and throws IllegalStateException.
 */
#include <jni.h>
#include <sodium.h>

#define SODIUM_NATIVE "ch/trancee/meshlink/crypto/sodium/SodiumJni"

static void throw_state(JNIEnv *env, const char *message) {
    jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (type != NULL) {
        (*env)->ThrowNew(env, type, message);
    }
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

static const JNINativeMethod METHODS[] = {
    {"blake2b", "(I[B[B)[B", (void *) blake2b},
    {"ed25519PublicKey", "([B)[B", (void *) ed25519_public_key},
    {"randomBytes", "(I)[B", (void *) random_bytes},
};

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK || sodium_init() < 0) {
        return JNI_ERR;
    }
    jclass type = (*env)->FindClass(env, SODIUM_NATIVE);
    if (type == NULL ||
        (*env)->RegisterNatives(env, type, METHODS, (jint) (sizeof METHODS / sizeof METHODS[0])) != JNI_OK) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
