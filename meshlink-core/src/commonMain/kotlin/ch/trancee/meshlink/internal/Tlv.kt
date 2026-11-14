package ch.trancee.meshlink.internal

/**
 * One type-length-value extension (spec "TLVs"; ADR 0012 decision 7): a `u8` [type] whose bit 7
 * marks it critical, a `u16` length and the [value].
 */
public class Tlv(
    public val type: Int,
    public val value: ByteArray,
) {
    init {
        require(type in 0..BYTE_MASK) { "type must be a byte, not $type" }
        require(value.size <= U16_MAX) { "value must fit a u16 length, not ${value.size} bytes" }
    }

    internal companion object {
        private const val HEADER = 3
        private const val CRITICAL_BIT = 0x80

        fun encode(tlvs: List<Tlv>): ByteArray =
            tlvs.fold(ByteArray(0)) { bytes, tlv ->
                bytes + byteArrayOf(tlv.type.toByte()) + u16(tlv.value.size) + tlv.value
            }

        /**
         * Decodes the TLVs from [start] to the end of [bytes]. [known] maps each type this context
         * registers to its fixed length.
         */
        fun decode(
            bytes: ByteArray,
            start: Int,
            known: Map<Int, Int>,
        ): Decoded<List<Tlv>> {
            val tlvs = mutableListOf<Tlv>()
            var offset = start
            while (offset < bytes.size) {
                val end = if (offset + HEADER <= bytes.size) offset + HEADER + bytes.u16(offset + 1) else Int.MAX_VALUE
                if (end > bytes.size) return Decoded.Invalid(DecodeError.TRUNCATED)
                tlvs += Tlv(bytes.u8(offset), bytes.copyOfRange(offset + HEADER, end))
                offset = end
            }
            return violation(tlvs, known)?.let { Decoded.Invalid(it) } ?: Decoded.Valid(tlvs)
        }

        /** Why [tlvs] break the TLV rules for a context that registers [known], or `null`. */
        fun violation(
            tlvs: List<Tlv>,
            known: Map<Int, Int>,
        ): DecodeError? =
            when {
                tlvs.zipWithNext().any { (a, b) -> a.type >= b.type } -> DecodeError.TLV_ORDER
                tlvs.any { known[it.type]?.let { length -> length != it.value.size } == true } -> DecodeError.TLV_LENGTH
                tlvs.any { it.type !in known && it.type and CRITICAL_BIT != 0 } -> DecodeError.UNKNOWN_CRITICAL_TLV
                else -> null
            }
    }
}
