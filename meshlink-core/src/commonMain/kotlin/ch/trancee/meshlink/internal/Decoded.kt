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

/** [block] applied to a [Decoded.Valid] value; an [Decoded.Invalid] passes through. */
internal fun <T, R> Decoded<T>.then(block: (T) -> Decoded<R>): Decoded<R> =
    when (this) {
        is Decoded.Valid -> block(value)
        is Decoded.Invalid -> this
    }

/** Why a decoder rejected its input; [code] is the error name the spec vectors use. */
public enum class DecodeError(
    public val code: String,
) {
    /** The input is not the length the layout requires. */
    BAD_LENGTH("bad-length"),

    /** The input names a layout version this decoder doesn't speak. */
    UNKNOWN_VERSION("unknown-version"),

    /** The input ends inside a field. */
    TRUNCATED("truncated"),

    /** Bytes remain after the last field. */
    TRAILING_BYTES("trailing-bytes"),

    /** A value, length or count is outside its allowed range. */
    OUT_OF_RANGE("out-of-range"),

    /** The whole structure exceeds its maximum size. */
    TOO_LONG("too-long"),

    /** A link-local frame type this decoder doesn't handle; the receiver drops the frame. */
    UNKNOWN_TYPE("unknown-type"),

    /** Frame type `0x00` or `0xC0`–`0xFF`, which no version may send. */
    RESERVED_TYPE("reserved-type"),

    /** A capability record's versions are not strictly descending. */
    VERSION_ORDER("version-order"),

    /** TLV types are not strictly ascending. */
    TLV_ORDER("tlv-order"),

    /** An unknown TLV has its critical bit set. */
    UNKNOWN_CRITICAL_TLV("unknown-critical-tlv"),

    /** A known TLV has the wrong length. */
    TLV_LENGTH("tlv-length"),

    /** A container kind the frame does not allow. */
    UNKNOWN_KIND("unknown-kind"),

    /** Plaintext padding is not zero, not shorter than 32 B or not aligned, or is present in `unsecured`. */
    BAD_PADDING("bad-padding"),
}

/** The big-endian `u16` at [offset]. */
internal fun ByteArray.u16(offset: Int): Int =
    (this[offset].toInt() and BYTE_MASK shl BYTE_BITS) or (this[offset + 1].toInt() and BYTE_MASK)

/** [value] as a big-endian `u16`. */
internal fun u16(value: Int): ByteArray = byteArrayOf((value shr BYTE_BITS).toByte(), value.toByte())

/** The big-endian `u32` at [offset]. */
internal fun ByteArray.u32(offset: Int): UInt =
    (0 until UInt.SIZE_BYTES).fold(0u) { value, index -> value shl BYTE_BITS or u8(offset + index).toUInt() }

/** [value] as a big-endian `u32`. */
internal fun u32(value: UInt): ByteArray {
    val size = UInt.SIZE_BYTES
    return ByteArray(size) { (value shr (BYTE_BITS * (size - 1 - it))).toByte() }
}

/** The big-endian `u64` at [offset]. */
internal fun ByteArray.u64(offset: Int): ULong =
    (0 until ULong.SIZE_BYTES).fold(0uL) { value, index -> value shl BYTE_BITS or u8(offset + index).toULong() }

/** [value] as a big-endian `u64`. */
internal fun u64(value: ULong): ByteArray {
    val size = ULong.SIZE_BYTES
    return ByteArray(size) { (value shr (BYTE_BITS * (size - 1 - it))).toByte() }
}

/** The unsigned byte at [offset]. */
internal fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and BYTE_MASK

internal const val BYTE_MASK: Int = 0xFF
internal const val U16_MAX: Int = 0xFFFF
private const val BYTE_BITS = 8
