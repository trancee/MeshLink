package ch.trancee.meshlink.internal

/** The outcome of decoding untrusted bytes: [Valid] or [Invalid]. Decoders never throw. */
public sealed interface Decoded<out T> {
    /** The bytes decoded to [value]. */
    public class Valid<out T>(
        public val value: T,
    ) : Decoded<T>

    /** The bytes were rejected for [error]. */
    public class Invalid(
        public val error: DecodeError,
    ) : Decoded<Nothing>
}

/** Why a decoder rejected its input; [code] is the error name the spec vectors use. */
public enum class DecodeError(
    public val code: String,
) {
    /** The input is not the length the layout requires. */
    BAD_LENGTH("bad-length"),

    /** The input names a layout version this decoder doesn't speak. */
    UNKNOWN_VERSION("unknown-version"),
}
