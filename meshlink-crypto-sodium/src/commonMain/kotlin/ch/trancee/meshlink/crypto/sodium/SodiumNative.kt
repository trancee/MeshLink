package ch.trancee.meshlink.crypto.sodium

// The libsodium calls behind SodiumCryptoProvider: JNI on Android, cinterop on iOS. Callers validate
// every argument first; an implementation only adapts arrays and calls libsodium.

/** `crypto_generichash_blake2b`, [outLength] bytes over [input], keyed with [key] if not `null`. */
internal expect fun sodiumBlake2b(
    outLength: Int,
    input: ByteArray,
    key: ByteArray?,
): ByteArray

/** The public key from `crypto_sign_ed25519_seed_keypair` for a 32-byte [seed]. */
internal expect fun sodiumEd25519PublicKey(seed: ByteArray): ByteArray

/** `randombytes_buf` into a new array of [count] bytes. */
internal expect fun sodiumRandomBytes(count: Int): ByteArray
