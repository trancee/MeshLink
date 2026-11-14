package ch.trancee.meshlink.internal

/**
 * The only way MeshLink code reaches cryptographic primitives (ADR 0004). The shipped
 * implementation binds libsodium; a later provider may replace it without changing the wire format.
 *
 * This interface grows one primitive at a time, as protocol code needs it: binding only what is
 * called keeps the native surface small (ADR 0004 "Binding").
 */
public interface CryptoProvider {
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

    /** A new Ed25519 key pair from a fresh random seed. */
    public fun ed25519KeyPair(): Ed25519KeyPair

    /** The Ed25519 key pair of a 32-byte [seed] (the RFC 8032 secret key). */
    public fun ed25519KeyPair(seed: ByteArray): Ed25519KeyPair

    /** [count] bytes from the operating system's secure random source. */
    public fun randomBytes(count: Int): ByteArray
}

/** An Ed25519 key pair: the 32-byte [seed] (the RFC 8032 secret key) and its 32-byte [publicKey]. */
public class Ed25519KeyPair(
    public val seed: ByteArray,
    public val publicKey: ByteArray,
)
