package ch.trancee.meshlink.internal

/**
 * The only way MeshLink code reaches cryptographic primitives (ADR 0004). The shipped
 * implementation binds libsodium; a later provider may replace it without changing the wire format.
 *
 * The interface holds exactly the primitives of the `Unsecured`, `Classical` and `PostQuantum`
 * profiles (ADR 0002): binding only what is called keeps the native surface small (ADR 0004
 * "Binding").
 *
 * Arguments whose length is fixed by the caller (keys, seeds, nonces, KEM ciphertexts the wire codec
 * has already sized) must have that length; a wrong length throws [IllegalArgumentException] before
 * any native call. Bytes a peer controls can still be refused: those outcomes are a [CryptoResult].
 *
 * Key generation and encapsulation come in two variants. The seeded one is deterministic, so the
 * sans-IO core draws its seeds from the injected `Random` (ADR 0017 decision 1) and vectors use
 * fixed seeds. The unseeded one draws its seed from the operating system's secure random source.
 *
 * The primitives are grouped by family ([Hashing], [Signatures], [KeyAgreement], [Aead],
 * [KeyEncapsulation]) so an implementation can bind each family in its own unit.
 */
public interface CryptoProvider :
    Hashing,
    Signatures,
    KeyAgreement,
    Aead,
    KeyEncapsulation,
    RandomBytes {
    /** The byte lengths of the primitives' inputs and outputs. */
    public companion object {
        /** The largest HKDF-SHA-256 output: 255 blocks of 32 bytes (RFC 5869). */
        public const val HKDF_SHA256_MAX_LENGTH: Int = 255 * 32

        /** An Ed25519 seed. */
        public const val ED25519_SEED_LENGTH: Int = 32

        /** An X25519 secret or public key, and its shared secret. */
        public const val X25519_KEY_LENGTH: Int = 32

        /** An ML-KEM-768 key-generation seed `d ‖ z`. */
        public const val ML_KEM_768_SEED_LENGTH: Int = 64

        /** An ML-KEM-768 encapsulation seed `m`. */
        public const val ML_KEM_768_ENCAPSULATION_SEED_LENGTH: Int = 32

        /** An X-Wing seed, which is also its secret key. */
        public const val X_WING_SEED_LENGTH: Int = 32

        /** An X-Wing encapsulation seed. */
        public const val X_WING_ENCAPSULATION_SEED_LENGTH: Int = 64
    }
}

/** The operating system's secure random source. */
public interface RandomBytes {
    /** [count] bytes from the operating system's secure random source. */
    public fun randomBytes(count: Int): ByteArray
}

/** Hashes and key derivation: BLAKE2b, SHA-256 and HKDF-SHA-256. */
public interface Hashing {
    /**
     * BLAKE2b of [input], [outLength] bytes long, keyed with [key] if it is not `null`.
     *
     * @param outLength 16 to 64 bytes. BLAKE2b binds the length into its parameter block, so a
     *   shorter output is not a prefix of a longer one.
     * @param key `null`, or 16 to 64 bytes.
     */
    public fun blake2b(
        outLength: Int,
        input: ByteArray,
        key: ByteArray? = null,
    ): ByteArray

    /** SHA-256 of [input] (FIPS 180-4), the Noise hash. */
    public fun sha256(input: ByteArray): ByteArray

    /**
     * HKDF-SHA-256 extract (RFC 5869): the 32-byte pseudo-random key of [ikm] under [salt]. An empty
     * [salt] acts as 32 zero bytes, as RFC 5869 defines.
     */
    public fun hkdfSha256Extract(
        salt: ByteArray,
        ikm: ByteArray,
    ): ByteArray

    /**
     * HKDF-SHA-256 expand (RFC 5869): [length] bytes from the 32-byte [prk] and [info].
     *
     * @param length 1 to [CryptoProvider.HKDF_SHA256_MAX_LENGTH] bytes.
     */
    public fun hkdfSha256Expand(
        prk: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray
}

/** Pure Ed25519 signatures (RFC 8032). */
public interface Signatures {
    /** A new Ed25519 key pair from a fresh random seed. */
    public fun ed25519KeyPair(): Ed25519KeyPair

    /** The Ed25519 key pair of a 32-byte [seed] (the RFC 8032 secret key). */
    public fun ed25519KeyPair(seed: ByteArray): Ed25519KeyPair

    /**
     * The 64-byte pure Ed25519 (RFC 8032) signature of [message] by the key pair of the 32-byte
     * [seed]. The public key is derived from [seed], never taken from the caller, so a mismatched
     * pair can't leak the secret key.
     */
    public fun ed25519Sign(
        seed: ByteArray,
        message: ByteArray,
    ): ByteArray

    /**
     * Whether [signature] is a valid pure Ed25519 signature of [message] by the 32-byte [publicKey].
     * A signature that is not 64 bytes is invalid.
     */
    public fun ed25519Verify(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray,
    ): Boolean
}

/** X25519 key agreement (RFC 7748). */
public interface KeyAgreement {
    /** A new X25519 key pair from a fresh random secret key. */
    public fun x25519KeyPair(): KeyPair

    /** The X25519 key pair of the 32-byte secret key [seed] (RFC 7748; clamped when used). */
    public fun x25519KeyPair(seed: ByteArray): KeyPair

    /**
     * The 32-byte X25519 shared secret of [secretKey] and the peer's [publicKey] (RFC 7748). A
     * low-order [publicKey], whose result would be all zero, is [CryptoFailure.INVALID_PUBLIC_KEY]:
     * never a key.
     */
    public fun x25519(
        secretKey: ByteArray,
        publicKey: ByteArray,
    ): CryptoResult<ByteArray>
}

/** ChaCha20-Poly1305 authenticated encryption, IETF variant (RFC 8439). */
public interface Aead {
    /**
     * ChaCha20-Poly1305, IETF (RFC 8439): [plaintext] encrypted under the 32-byte [key] and
     * 12-byte [nonce], with its 16-byte tag appended, authenticating [associatedData].
     */
    public fun chaCha20Poly1305Seal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray

    /**
     * The plaintext of [ciphertext] (with its 16-byte tag) under the 32-byte [key] and 12-byte
     * [nonce], or [CryptoFailure.AUTHENTICATION_FAILED] if the tag doesn't verify over [ciphertext]
     * and [associatedData]. A ciphertext shorter than the tag fails the same way. A failure never
     * returns partial plaintext.
     */
    public fun chaCha20Poly1305Open(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): CryptoResult<ByteArray>
}

/** Key encapsulation: ML-KEM-768 (FIPS 203) and the X-Wing hybrid. */
public interface KeyEncapsulation {
    /** A new ML-KEM-768 key pair from a fresh random seed. */
    public fun mlKem768KeyPair(): KeyPair

    /**
     * The ML-KEM-768 key pair (FIPS 203) of the 64-byte [seed] `d ‖ z`: a 1,184-byte encapsulation
     * key and a 2,400-byte decapsulation key.
     */
    public fun mlKem768KeyPair(seed: ByteArray): KeyPair

    /** ML-KEM-768 encapsulation to [publicKey] with fresh randomness. */
    public fun mlKem768Encapsulate(publicKey: ByteArray): CryptoResult<KemEncapsulation>

    /**
     * ML-KEM-768 encapsulation (FIPS 203) to the 1,184-byte [publicKey] with the 32-byte message
     * [seed] `m`: a 1,088-byte ciphertext and a 32-byte shared secret. A [publicKey] that fails the
     * FIPS 203 modulus check is [CryptoFailure.INVALID_PUBLIC_KEY].
     */
    public fun mlKem768Encapsulate(
        publicKey: ByteArray,
        seed: ByteArray,
    ): CryptoResult<KemEncapsulation>

    /**
     * The 32-byte ML-KEM-768 shared secret of the 1,088-byte [ciphertext] under the 2,400-byte
     * [secretKey]. A ciphertext that doesn't decrypt to itself gets the implicit-rejection key, a
     * pseudo-random secret that matches no sender's: never an error.
     */
    public fun mlKem768Decapsulate(
        secretKey: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray

    /** A new X-Wing key pair from a fresh random seed. */
    public fun xWingKeyPair(): KeyPair

    /**
     * The X-Wing (MLKEM768-X25519, HPKE KEM `0x647a`) key pair of the 32-byte [seed]: the seed
     * itself is the secret key, and the public key is 1,216 bytes.
     */
    public fun xWingKeyPair(seed: ByteArray): KeyPair

    /** X-Wing encapsulation to [publicKey] with fresh randomness. */
    public fun xWingEncapsulate(publicKey: ByteArray): CryptoResult<KemEncapsulation>

    /**
     * X-Wing encapsulation to the 1,216-byte [publicKey] with the 64-byte [seed] (the ML-KEM
     * message, then the ephemeral X25519 secret key): a 1,120-byte ciphertext and a 32-byte shared
     * secret. A [publicKey] whose ML-KEM part fails the modulus check, or whose X25519 part has low
     * order, is [CryptoFailure.INVALID_PUBLIC_KEY].
     */
    public fun xWingEncapsulate(
        publicKey: ByteArray,
        seed: ByteArray,
    ): CryptoResult<KemEncapsulation>

    /**
     * The 32-byte X-Wing shared secret of the 1,120-byte [ciphertext] under the 32-byte
     * [secretKey]. A tampered ML-KEM part gets ML-KEM's implicit-rejection key, so the secret
     * matches no sender's. libsodium refuses a low-order X25519 part, which no honest sender
     * produces: [CryptoFailure.INVALID_CIPHERTEXT].
     */
    public fun xWingDecapsulate(
        secretKey: ByteArray,
        ciphertext: ByteArray,
    ): CryptoResult<ByteArray>
}

/** An Ed25519 key pair: the 32-byte [seed] (the RFC 8032 secret key) and its 32-byte [publicKey]. */
public class Ed25519KeyPair(
    public val seed: ByteArray,
    public val publicKey: ByteArray,
)

/** A key pair of one algorithm: its [secretKey] and [publicKey], sized as the function that made it says. */
public class KeyPair(
    public val secretKey: ByteArray,
    public val publicKey: ByteArray,
)

/** A KEM encapsulation: the [ciphertext] for the recipient and the [sharedSecret] both sides derive. */
public class KemEncapsulation(
    public val ciphertext: ByteArray,
    public val sharedSecret: ByteArray,
)

/** The outcome of a primitive that can refuse bytes a peer controls: [Ok] or [Failed]. */
public sealed interface CryptoResult<out T> {
    /** The primitive succeeded with [value]. */
    public class Ok<out T>(
        public val value: T,
    ) : CryptoResult<T>

    /** The primitive refused its input for [reason]; no partial output exists. */
    public class Failed(
        public val reason: CryptoFailure,
    ) : CryptoResult<Nothing>
}

/** Why a primitive refused bytes a peer controls. */
public enum class CryptoFailure {
    /** A public key is invalid: an X25519 key of low order, or an ML-KEM key that fails its modulus check. */
    INVALID_PUBLIC_KEY,

    /** A KEM ciphertext is refused: an X-Wing ciphertext whose X25519 part has low order. */
    INVALID_CIPHERTEXT,

    /** An AEAD tag didn't verify. */
    AUTHENTICATION_FAILED,
}
