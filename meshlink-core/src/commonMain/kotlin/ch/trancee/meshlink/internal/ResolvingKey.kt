package ch.trancee.meshlink.internal

/**
 * A node's resolving [key] and the Unix second [notAfter] it expires (spec "Resolvable tag"). On
 * the wire it is the key followed by `u32(not_after)`, 36 B, both in the `RESOLVING_KEY` handshake
 * TLV and in the body of a `RESOLVING_KEY_UPDATE` frame.
 */
public class ResolvingKey(
    public val key: ByteArray,
    public val notAfter: UInt,
) {
    init {
        require(key.size == KEY_LENGTH) { "resolving_key must be $KEY_LENGTH bytes, not ${key.size}" }
    }

    /** The key's 36 bytes. */
    public fun encode(): ByteArray = key + u32(notAfter)

    public companion object {
        /** The resolving key length in bytes. */
        public const val KEY_LENGTH: Int = 32

        /** The encoded length in bytes: the key and its `u32` `not_after`. */
        public const val LENGTH: Int = KEY_LENGTH + 4

        /** Decodes exactly [LENGTH] bytes; never throws. */
        public fun decode(bytes: ByteArray): Decoded<ResolvingKey> =
            when {
                bytes.size < LENGTH -> Decoded.Invalid(DecodeError.TRUNCATED)
                bytes.size > LENGTH -> Decoded.Invalid(DecodeError.TRAILING_BYTES)
                else -> Decoded.Valid(ResolvingKey(bytes.copyOf(KEY_LENGTH), bytes.u32(KEY_LENGTH)))
            }
    }
}
