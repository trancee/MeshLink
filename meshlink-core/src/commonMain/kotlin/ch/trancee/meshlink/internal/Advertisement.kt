package ch.trancee.meshlink.internal

import kotlin.uuid.Uuid

/**
 * The 16-byte advertisement payload, layout `001` (spec "Payload layout"; ADR 0010 decision 1;
 * ADR 0012 decision 10). Byte 0 holds the layout version in bits 7–5 and [flags] in bits 4–0;
 * then come [meshTag], [resolvableTag] and [random].
 */
public class Advertisement(
    /** The five flag bits: [COC_AVAILABLE], [PHY_2M] and three reserved bits, kept as received. */
    public val flags: Int,
    /** The 4-byte mesh tag of the advertiser's mesh for its current epoch. */
    public val meshTag: ByteArray,
    /** The 8-byte resolvable tag, which is also the advertisement identifier. */
    public val resolvableTag: ByteArray,
    /** Three random bytes, fresh each epoch. */
    public val random: ByteArray,
) {
    init {
        require(flags in 0..FLAGS_MASK) { "flags must fit five bits, not $flags" }
        require(meshTag.size == MESH_TAG_LENGTH) { "mesh tag must be $MESH_TAG_LENGTH bytes, not ${meshTag.size}" }
        require(resolvableTag.size == RESOLVABLE_TAG_LENGTH) {
            "resolvable tag must be $RESOLVABLE_TAG_LENGTH bytes, not ${resolvableTag.size}"
        }
        require(random.size == RANDOM_LENGTH) { "random must be $RANDOM_LENGTH bytes, not ${random.size}" }
    }

    /** The 16 payload bytes. */
    public fun encode(): ByteArray {
        val layoutAndFlags = (LAYOUT_VERSION shl FLAG_BITS) or flags
        return byteArrayOf(layoutAndFlags.toByte()) + meshTag + resolvableTag + random
    }

    public companion object {
        /** The layout version this codec speaks (spec `advertisement-layout-version`). */
        public const val LAYOUT_VERSION: Int = 1

        /** Flag bit: the node can accept an L2CAP CoC upgrade. */
        public const val COC_AVAILABLE: Int = 1 shl 0

        /** Flag bit: the node supports the 2M PHY. */
        public const val PHY_2M: Int = 1 shl 1

        /** The mesh tag length in bytes. */
        public const val MESH_TAG_LENGTH: Int = 4

        /** The resolvable tag length in bytes. */
        public const val RESOLVABLE_TAG_LENGTH: Int = 8

        /** The random fill length in bytes. */
        public const val RANDOM_LENGTH: Int = 3

        private const val FLAG_BITS = 5
        private const val FLAGS_MASK = (1 shl FLAG_BITS) - 1
        private const val BYTE_MASK = 0xFF
        private const val MESH_TAG_START = 1
        private const val RESOLVABLE_TAG_START = MESH_TAG_START + MESH_TAG_LENGTH
        private const val RANDOM_START = RESOLVABLE_TAG_START + RESOLVABLE_TAG_LENGTH

        /**
         * Decodes [payload]: [DecodeError.BAD_LENGTH] unless it is 16 bytes, and
         * [DecodeError.UNKNOWN_VERSION] unless its layout version is `001`. Reserved flag bits are
         * kept, not rejected.
         */
        public fun decode(payload: ByteArray): Decoded<Advertisement> =
            when {
                payload.size != RadioPort.ADVERTISEMENT_LENGTH -> {
                    Decoded.Invalid(DecodeError.BAD_LENGTH)
                }

                (payload[0].toInt() and BYTE_MASK) shr FLAG_BITS != LAYOUT_VERSION -> {
                    Decoded.Invalid(DecodeError.UNKNOWN_VERSION)
                }

                else -> {
                    Decoded.Valid(
                        Advertisement(
                            flags = payload[0].toInt() and FLAGS_MASK,
                            meshTag = payload.copyOfRange(MESH_TAG_START, RESOLVABLE_TAG_START),
                            resolvableTag = payload.copyOfRange(RESOLVABLE_TAG_START, RANDOM_START),
                            random = payload.copyOfRange(RANDOM_START, RadioPort.ADVERTISEMENT_LENGTH),
                        ),
                    )
                }
            }

        /**
         * The rotating 128-bit service UUID that carries [payload]: byte 0 is the first two hex
         * digits of its canonical string [D-12]. Platform adapters convert through this order.
         *
         * @param payload exactly 16 bytes.
         */
        public fun serviceUuid(payload: ByteArray): Uuid = Uuid.fromByteArray(payload)
    }
}
