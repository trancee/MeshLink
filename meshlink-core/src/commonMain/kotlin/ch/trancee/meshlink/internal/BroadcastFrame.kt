package ch.trancee.meshlink.internal

/**
 * `BROADCAST` (spec "BROADCAST"): the flood [header] and the [container], kind `0x04` in
 * `unsecured` or the signed kind `0x05` in the secured profiles, with at most [PAYLOAD_MAX] payload
 * bytes either way.
 */
public class BroadcastFrame(
    public val header: FloodHeader,
    public val container: Container,
) {
    init {
        require(container is Container.Unsecured || container is Container.SignedBroadcast) {
            "a broadcast carries kind 0x04 or 0x05, not ${container.kind}"
        }
        require(container !is Container.Unsecured || container.plaintext.size <= PAYLOAD_MAX) {
            "payload must be at most $PAYLOAD_MAX bytes"
        }
    }

    /** The frame's bytes. */
    public fun encode(): ByteArray = header.encode(TYPE) + container.encode()

    public companion object {
        /** The frame type, `0x80`. */
        public const val TYPE: Int = 0x80

        /** The most payload bytes a broadcast carries (ADR 0015 decision 2). */
        public const val PAYLOAD_MAX: Int = 1_024

        private val KINDS = setOf(Container.UNSECURED, Container.SIGNED_BROADCAST)

        /** Decodes one `BROADCAST`; never throws. Any other type is [DecodeError.UNKNOWN_TYPE]. */
        public fun decode(bytes: ByteArray): Decoded<BroadcastFrame> =
            when {
                bytes.isEmpty() -> {
                    Decoded.Invalid(DecodeError.TRUNCATED)
                }

                bytes.u8(0) != TYPE -> {
                    Decoded.Invalid(DecodeError.UNKNOWN_TYPE)
                }

                bytes.size < FloodHeader.LENGTH -> {
                    Decoded.Invalid(DecodeError.TRUNCATED)
                }

                FloodHeader.spent(bytes) -> {
                    Decoded.Invalid(DecodeError.OUT_OF_RANGE)
                }

                else -> {
                    Container.decode(bytes, FloodHeader.LENGTH, KINDS).then {
                        if (it is Container.Unsecured && it.plaintext.size > PAYLOAD_MAX) {
                            Decoded.Invalid(DecodeError.OUT_OF_RANGE)
                        } else {
                            Decoded.Valid(BroadcastFrame(FloodHeader.of(bytes), it))
                        }
                    }
                }
            }
    }
}

/**
 * Any flood frame, types `0x80`–`0xBF` (spec "Routing headers"): its [type], flood [header] and
 * the [body] after it. A node forwards a flood type it doesn't know by its header alone.
 */
public class FloodFrame(
    public val type: Int,
    public val header: FloodHeader,
    public val body: ByteArray,
) {
    init {
        require(type in FIRST..LAST) { "a flood type is 0x80..0xBF, not $type" }
    }

    /** The frame's bytes. */
    public fun encode(): ByteArray = header.encode(type) + body

    public companion object {
        private const val FIRST = 0x80
        private const val LAST = 0xBF

        /**
         * Decodes one flood frame by its header; never throws. A type outside `0x80`–`0xBF` is
         * [DecodeError.UNKNOWN_TYPE].
         */
        public fun decode(bytes: ByteArray): Decoded<FloodFrame> =
            when {
                bytes.isEmpty() -> {
                    Decoded.Invalid(DecodeError.TRUNCATED)
                }

                bytes.u8(0) !in FIRST..LAST -> {
                    Decoded.Invalid(DecodeError.UNKNOWN_TYPE)
                }

                bytes.size < FloodHeader.LENGTH -> {
                    Decoded.Invalid(DecodeError.TRUNCATED)
                }

                FloodHeader.spent(bytes) -> {
                    Decoded.Invalid(DecodeError.OUT_OF_RANGE)
                }

                else -> {
                    val body = bytes.copyOfRange(FloodHeader.LENGTH, bytes.size)
                    Decoded.Valid(FloodFrame(bytes.u8(0), FloodHeader.of(bytes), body))
                }
            }
    }
}
