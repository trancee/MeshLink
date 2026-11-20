package ch.trancee.meshlink.internal

/**
 * The flood routing header that starts every frame of types `0x80`–`0xBF` (spec "Routing
 * headers"), without its type byte.
 */
public class FloodHeader(
    public val hopLimit: Int,
    public val messageId: ByteArray,
    public val expiry: UInt,
) {
    init {
        require(hopLimit in 1..BYTE_MASK) { "hop_limit must be in 1..255, not $hopLimit" }
        require(messageId.size == MESSAGE_ID) { "message_id must be $MESSAGE_ID bytes" }
    }

    /** The header's bytes, starting with [type]. */
    public fun encode(type: Int): ByteArray = byteArrayOf(type.toByte(), hopLimit.toByte()) + messageId + u32(expiry)

    public companion object {
        /** The header's size, including the type byte. */
        public const val LENGTH: Int = 18

        private const val MESSAGE_ID = 12
        private const val HOP_LIMIT = 1
        private const val MESSAGE_ID_AT = 2
        private const val EXPIRY_AT = MESSAGE_ID_AT + MESSAGE_ID

        /** Whether the header in [bytes], at least [LENGTH] long, has the malformed `hop_limit` 0. */
        internal fun spent(bytes: ByteArray): Boolean = bytes.u8(HOP_LIMIT) == 0

        /** The header at the start of [bytes], at least [LENGTH] long, whose `hop_limit` is not 0. */
        internal fun of(bytes: ByteArray): FloodHeader =
            FloodHeader(bytes.u8(HOP_LIMIT), bytes.copyOfRange(MESSAGE_ID_AT, EXPIRY_AT), bytes.u32(EXPIRY_AT))
    }
}

/**
 * `ROUTE_REQUEST` (spec "ROUTE_REQUEST"): the flood [header], the [targetTag] the origin looks for,
 * the origin's reply-to [originTag] and [originSequence], the last [targetSequence] it knows (0 =
 * unknown) and the [hopCount] traveled.
 */
public class RouteRequest(
    public val header: FloodHeader,
    public val targetTag: ByteArray,
    public val originTag: ByteArray,
    public val originSequence: UInt,
    public val targetSequence: UInt,
    public val hopCount: Int,
) {
    init {
        require(targetTag.size == TAG && originTag.size == TAG) { "tags must be $TAG bytes" }
        require(originSequence != 0u) { "origin_sequence must be at least 1" }
        require(hopCount in 0..BYTE_MASK) { "hop_count must be in 0..255, not $hopCount" }
    }

    /** The frame's bytes. */
    public fun encode(): ByteArray =
        header.encode(TYPE) + targetTag + originTag + u32(originSequence) + u32(targetSequence) +
            hopCount.toByte()

    public companion object {
        /** The frame type, `0x81`. */
        public const val TYPE: Int = 0x81

        /** The frame's size. */
        public const val LENGTH: Int = 43

        private const val TARGET_AT = FloodHeader.LENGTH
        private const val ORIGIN_AT = TARGET_AT + TAG
        private const val ORIGIN_SEQUENCE_AT = ORIGIN_AT + TAG
        private const val TARGET_SEQUENCE_AT = ORIGIN_SEQUENCE_AT + 4
        private const val HOP_COUNT_AT = TARGET_SEQUENCE_AT + 4

        /** Decodes one `ROUTE_REQUEST`; never throws. Any other type is [DecodeError.UNKNOWN_TYPE]. */
        public fun decode(bytes: ByteArray): Decoded<RouteRequest> =
            when {
                bytes.isEmpty() -> {
                    Decoded.Invalid(DecodeError.TRUNCATED)
                }

                bytes.u8(0) != TYPE -> {
                    Decoded.Invalid(DecodeError.UNKNOWN_TYPE)
                }

                else -> {
                    exact(bytes, LENGTH) {
                        val origin = bytes.u32(ORIGIN_SEQUENCE_AT)
                        if (FloodHeader.spent(bytes) || origin == 0u) {
                            Decoded.Invalid(DecodeError.OUT_OF_RANGE)
                        } else {
                            Decoded.Valid(
                                RouteRequest(
                                    FloodHeader.of(bytes),
                                    bytes.copyOfRange(TARGET_AT, ORIGIN_AT),
                                    bytes.copyOfRange(ORIGIN_AT, ORIGIN_SEQUENCE_AT),
                                    origin,
                                    bytes.u32(TARGET_SEQUENCE_AT),
                                    bytes.u8(HOP_COUNT_AT),
                                ),
                            )
                        }
                    }
                }
            }
    }
}

/**
 * `ROUTE_REPLY` (spec "ROUTE_REPLY"): the unicast [header] to the request's origin, the
 * [targetTag] the route leads to, its [targetSequence] and the [hopCount] from the replier's side
 * to the target.
 */
public class RouteReply(
    public val header: UnicastHeader,
    public val targetTag: ByteArray,
    public val targetSequence: UInt,
    public val hopCount: Int,
) {
    init {
        require(targetTag.size == TAG) { "target_tag must be $TAG bytes" }
        require(targetSequence != 0u) { "target_sequence must be at least 1" }
        require(hopCount in 0..BYTE_MASK) { "hop_count must be in 0..255, not $hopCount" }
    }

    /** The frame's bytes. */
    public fun encode(): ByteArray = header.encode(TYPE) + targetTag + u32(targetSequence) + hopCount.toByte()

    public companion object {
        /** The frame type, `0x43`. */
        public const val TYPE: Int = 0x43

        /** The frame's size. */
        public const val LENGTH: Int = 47

        private const val TARGET_AT = UnicastHeader.LENGTH
        private const val SEQUENCE_AT = TARGET_AT + TAG
        private const val HOP_COUNT_AT = SEQUENCE_AT + 4

        /** Decodes one `ROUTE_REPLY`; never throws. Any other type is [DecodeError.UNKNOWN_TYPE]. */
        public fun decode(bytes: ByteArray): Decoded<RouteReply> =
            when {
                bytes.isEmpty() -> {
                    Decoded.Invalid(DecodeError.TRUNCATED)
                }

                bytes.u8(0) != TYPE -> {
                    Decoded.Invalid(DecodeError.UNKNOWN_TYPE)
                }

                else -> {
                    exact(bytes, LENGTH) {
                        val sequence = bytes.u32(SEQUENCE_AT)
                        if (UnicastHeader.spent(bytes) || sequence == 0u) {
                            Decoded.Invalid(DecodeError.OUT_OF_RANGE)
                        } else {
                            val target = bytes.copyOfRange(TARGET_AT, SEQUENCE_AT)
                            Decoded.Valid(RouteReply(UnicastHeader.of(bytes), target, sequence, bytes.u8(HOP_COUNT_AT)))
                        }
                    }
                }
            }
    }
}

private const val TAG = 8

// A fixed-size frame: shorter is truncated, longer has trailing bytes.
private fun <T> exact(
    bytes: ByteArray,
    length: Int,
    decode: () -> Decoded<T>,
): Decoded<T> =
    when {
        bytes.size < length -> Decoded.Invalid(DecodeError.TRUNCATED)
        bytes.size > length -> Decoded.Invalid(DecodeError.TRAILING_BYTES)
        else -> decode()
    }
