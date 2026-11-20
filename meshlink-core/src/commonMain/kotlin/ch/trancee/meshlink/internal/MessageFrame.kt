package ch.trancee.meshlink.internal

/**
 * The unicast routing header that starts every frame of types `0x40`–`0x7F` (spec "Routing
 * headers"), without its type byte.
 */
public class UnicastHeader(
    public val hopLimit: Int,
    public val messageId: ByteArray,
    public val expiry: UInt,
    public val destinationTag: ByteArray,
    public val sourceTag: ByteArray,
) {
    init {
        require(hopLimit in 1..BYTE_MASK) { "hop_limit must be in 1..255, not $hopLimit" }
        require(messageId.size == MESSAGE_ID) { "message_id must be $MESSAGE_ID bytes" }
        require(destinationTag.size == TAG && sourceTag.size == TAG) { "tags must be $TAG bytes" }
    }

    /** The header's bytes, starting with [type]. */
    public fun encode(type: Int): ByteArray =
        byteArrayOf(type.toByte(), hopLimit.toByte()) + messageId + u32(expiry) + destinationTag + sourceTag

    public companion object {
        /** The header's size, including the type byte. */
        public const val LENGTH: Int = 34

        private const val MESSAGE_ID = 12
        private const val TAG = 8
        private const val HOP_LIMIT = 1
        private const val MESSAGE_ID_AT = 2
        private const val EXPIRY_AT = MESSAGE_ID_AT + MESSAGE_ID
        private const val DESTINATION_AT = EXPIRY_AT + 4
        private const val SOURCE_AT = DESTINATION_AT + TAG

        /** Whether the header in [bytes], at least [LENGTH] long, has the malformed `hop_limit` 0. */
        internal fun spent(bytes: ByteArray): Boolean = bytes.u8(HOP_LIMIT) == 0

        /** The header at the start of [bytes], at least [LENGTH] long, whose `hop_limit` is not 0. */
        internal fun of(bytes: ByteArray): UnicastHeader =
            UnicastHeader(
                bytes.u8(HOP_LIMIT),
                bytes.copyOfRange(MESSAGE_ID_AT, EXPIRY_AT),
                bytes.u32(EXPIRY_AT),
                bytes.copyOfRange(DESTINATION_AT, SOURCE_AT),
                bytes.copyOfRange(SOURCE_AT, LENGTH),
            )
    }
}

/**
 * `MESSAGE` (spec "MESSAGE"): the unicast [header], the tombstone [commitment] and the end-to-end
 * [container]. Relays read only the header and the commitment.
 */
public class MessageFrame(
    public val header: UnicastHeader,
    public val commitment: ByteArray,
    public val container: Container,
) {
    init {
        require(commitment.size == COMMITMENT) { "commitment must be $COMMITMENT bytes" }
    }

    /** The frame's bytes. */
    public fun encode(): ByteArray = header.encode(TYPE) + commitment + container.encode()

    public companion object {
        /** The frame type, `0x40`. */
        public const val TYPE: Int = 0x40

        /** The bytes before the container: the 34 B unicast header and the 16 B commitment. */
        public const val OVERHEAD: Int = 50

        private const val COMMITMENT = 16
        private val KINDS = setOf(Container.IK_INITIATOR, Container.TRANSPORT, Container.UNSECURED)

        /** Decodes one `MESSAGE`; never throws. Any other type is [DecodeError.UNKNOWN_TYPE]. */
        public fun decode(bytes: ByteArray): Decoded<MessageFrame> =
            when {
                bytes.isEmpty() -> {
                    Decoded.Invalid(DecodeError.TRUNCATED)
                }

                bytes.u8(0) != TYPE -> {
                    Decoded.Invalid(DecodeError.UNKNOWN_TYPE)
                }

                bytes.size < OVERHEAD -> {
                    Decoded.Invalid(DecodeError.TRUNCATED)
                }

                UnicastHeader.spent(bytes) -> {
                    Decoded.Invalid(DecodeError.OUT_OF_RANGE)
                }

                else -> {
                    Container.decode(bytes, OVERHEAD, KINDS).then {
                        val commitment = bytes.copyOfRange(UnicastHeader.LENGTH, OVERHEAD)
                        Decoded.Valid(MessageFrame(UnicastHeader.of(bytes), commitment, it))
                    }
                }
            }
    }
}

/**
 * Any unicast frame, types `0x40`–`0x7F` (spec "Routing headers"): its [type], unicast [header] and
 * the [body] after it, which only the destination reads. A relay forwards every unicast type by
 * its header, an unknown one included.
 */
public class UnicastFrame(
    public val type: Int,
    public val header: UnicastHeader,
    public val body: ByteArray,
) {
    init {
        require(type in FIRST..LAST) { "a unicast type is 0x40..0x7F, not $type" }
    }

    /** The frame's bytes. */
    public fun encode(): ByteArray = header.encode(type) + body

    public companion object {
        private const val FIRST = 0x40
        private const val LAST = 0x7F

        /**
         * Decodes one unicast frame by its header; never throws. A type outside `0x40`–`0x7F` is
         * [DecodeError.UNKNOWN_TYPE].
         */
        public fun decode(bytes: ByteArray): Decoded<UnicastFrame> =
            when {
                bytes.isEmpty() -> {
                    Decoded.Invalid(DecodeError.TRUNCATED)
                }

                bytes.u8(0) !in FIRST..LAST -> {
                    Decoded.Invalid(DecodeError.UNKNOWN_TYPE)
                }

                bytes.size < UnicastHeader.LENGTH -> {
                    Decoded.Invalid(DecodeError.TRUNCATED)
                }

                UnicastHeader.spent(bytes) -> {
                    Decoded.Invalid(DecodeError.OUT_OF_RANGE)
                }

                else -> {
                    val body = bytes.copyOfRange(UnicastHeader.LENGTH, bytes.size)
                    Decoded.Valid(UnicastFrame(bytes.u8(0), UnicastHeader.of(bytes), body))
                }
            }
    }
}
