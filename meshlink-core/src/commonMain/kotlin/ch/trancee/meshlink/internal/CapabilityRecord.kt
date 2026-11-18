package ch.trancee.meshlink.internal

/**
 * The capability record a peripheral serves (spec "Capability record"; ADR 0012 decisions 6 and 7):
 * the protocol [versions] it speaks, strictly descending, then capability TLVs. Unknown
 * non-critical TLVs are kept, so a record re-encodes to the bytes it was decoded from.
 */
public class CapabilityRecord(
    public val versions: List<Int>,
    public val extensions: List<Tlv>,
) {
    init {
        require(versionViolation(versions) == null) {
            "versions must be 1 to 8 values in 1..255, descending: $versions"
        }
        require(Tlv.violation(extensions, KNOWN) == null) { "extensions break the TLV rules" }
        require(encode().size <= MAX) { "a capability record is at most $MAX bytes" }
    }

    /** The peripheral's L2CAP CoC PSM, or `null` if it accepts no CoC. */
    public val l2capPsm: Int? get() = extension(L2CAP_PSM)?.u16(0)

    /** The peripheral's current advertisement payload, or `null` if the record omits it. */
    public val advertisement: ByteArray? get() = extension(ADVERTISEMENT)

    /** The record's bytes. */
    public fun encode(): ByteArray =
        byteArrayOf(versions.size.toByte()) + versions.map { it.toByte() }.toByteArray() + Tlv.encode(extensions)

    private fun extension(type: Int): ByteArray? = extensions.firstOrNull { it.type == type }?.let { it.value.copyOf() }

    public companion object {
        /** TLV `L2CAP_PSM`: the `u16` PSM of the peripheral's CoC listener. */
        public const val L2CAP_PSM: Int = 0x01

        /** TLV `ADVERTISEMENT`: the peripheral's current 16-byte advertisement payload. */
        public const val ADVERTISEMENT: Int = 0x02

        /** The most versions a record lists (spec `versions-max`). */
        public const val VERSIONS_MAX: Int = 8

        /** The largest record in bytes (spec `capability-record-max`). */
        public const val MAX: Int = RadioPort.CAPABILITY_RECORD_MAX

        private const val PSM_LENGTH = 2
        private val KNOWN = mapOf(L2CAP_PSM to PSM_LENGTH, ADVERTISEMENT to RadioPort.ADVERTISEMENT_LENGTH)

        /**
         * The record a node serves: [versions], the [psm] of its CoC listener if it listens, and its
         * current [advertisement].
         */
        public fun of(
            versions: List<Int>,
            advertisement: ByteArray,
            psm: Int? = null,
        ): CapabilityRecord {
            val listener = if (psm == null) emptyList() else listOf(Tlv(L2CAP_PSM, u16(psm)))
            return CapabilityRecord(versions, listener + Tlv(ADVERTISEMENT, advertisement.copyOf()))
        }

        /** Decodes [bytes]; never throws. */
        public fun decode(bytes: ByteArray): Decoded<CapabilityRecord> {
            val count = if (bytes.isEmpty()) 0 else bytes.u8(0)
            val error =
                when {
                    bytes.size > MAX -> DecodeError.TOO_LONG
                    bytes.isEmpty() -> DecodeError.TRUNCATED
                    count !in 1..VERSIONS_MAX -> DecodeError.OUT_OF_RANGE
                    bytes.size < 1 + count -> DecodeError.TRUNCATED
                    else -> null
                }
            val versions = if (error == null) (1..count).map { bytes.u8(it) } else emptyList()
            return when (val tlvs = error?.let { Decoded.Invalid(it) } ?: Tlv.decode(bytes, 1 + count, KNOWN)) {
                is Decoded.Invalid -> {
                    tlvs
                }

                is Decoded.Valid -> {
                    versionViolation(versions)?.let { Decoded.Invalid(it) }
                        ?: Decoded.Valid(CapabilityRecord(versions, tlvs.value))
                }
            }
        }

        private fun versionViolation(versions: List<Int>): DecodeError? =
            when {
                versions.size !in 1..VERSIONS_MAX || versions.any { it !in 1..BYTE_MASK } -> DecodeError.OUT_OF_RANGE
                versions.zipWithNext().any { (a, b) -> a <= b } -> DecodeError.VERSION_ORDER
                else -> null
            }
    }
}
