package ch.trancee.meshlink.internal

/**
 * The link-local frames this node handles (spec "Link-local frame summary"). [decode] reports
 * every other link-local type as [DecodeError.UNKNOWN_TYPE], which the receiver drops without
 * closing the link, and types `0x00` and `0xC0`–`0xFF` as [DecodeError.RESERVED_TYPE].
 */
public sealed interface LinkFrame {
    /** The frame's bytes. */
    public fun encode(): ByteArray

    /**
     * `FRAGMENT` (spec "Fragments"): piece [number] of stream [streamId], [last] if it ends the
     * stream, carrying 1 to [DATA_MAX] bytes of the fragmented frame.
     */
    public class Fragment(
        public val streamId: Int,
        public val number: Int,
        public val last: Boolean,
        public val data: ByteArray,
    ) : LinkFrame {
        init {
            require(streamId in 0..BYTE_MASK) { "stream_id must be a byte, not $streamId" }
            require(number in 0..NUMBER_MAX) { "number must be in 0..$NUMBER_MAX, not $number" }
            require(data.size in DATA_LENGTH) { "data must be 1 to $DATA_MAX bytes, not ${data.size}" }
        }

        override fun encode(): ByteArray {
            val index = number or if (last) LAST else 0
            return byteArrayOf(TYPE.toByte(), streamId.toByte()) + u16(index) + data
        }

        public companion object {
            /** The most data one fragment carries: a record body less the 4-byte header. */
            public const val DATA_MAX: Int = 1_020

            /** The largest fragment number; bit 15 of the index marks the last fragment. */
            public const val NUMBER_MAX: Int = 0x7FFF
            internal const val TYPE = 0x01

            /** The fragment header: type, `stream_id` and `index`. */
            public const val HEADER: Int = 4
            private const val STREAM = 1
            private const val INDEX = 2
            private const val LAST = 0x8000
            private val DATA_LENGTH = 1..DATA_MAX

            /** [frame] as stream [streamId]: pieces of [dataMax] bytes, numbered from 0, the last marked. */
            public fun split(
                streamId: Int,
                frame: ByteArray,
                dataMax: Int,
            ): List<Fragment> {
                val count = (frame.size + dataMax - 1) / dataMax
                return List(count) {
                    val end = minOf(frame.size, (it + 1) * dataMax)
                    Fragment(streamId, it, it == count - 1, frame.copyOfRange(it * dataMax, end))
                }
            }

            internal fun decode(bytes: ByteArray): Decoded<LinkFrame> =
                when {
                    bytes.size < HEADER -> {
                        Decoded.Invalid(DecodeError.TRUNCATED)
                    }

                    bytes.size - HEADER !in DATA_LENGTH -> {
                        Decoded.Invalid(DecodeError.OUT_OF_RANGE)
                    }

                    else -> {
                        val index = bytes.u16(INDEX)
                        val data = bytes.copyOfRange(HEADER, bytes.size)
                        Decoded.Valid(Fragment(bytes.u8(STREAM), index and NUMBER_MAX, index >= LAST, data))
                    }
                }
        }
    }

    /**
     * `HANDSHAKE` (spec "HANDSHAKE frame"): the selected protocol [version], the Noise message's
     * [step] (1 to 3) and the [noiseMessage] itself, 1 to [NOISE_MESSAGE_MAX] bytes.
     */
    public class Handshake(
        public val version: Int,
        public val step: Int,
        public val noiseMessage: ByteArray,
    ) : LinkFrame {
        init {
            require(version in 1..BYTE_MASK) { "version must be in 1..255, not $version" }
            require(step in STEPS) { "step must be in 1..3, not $step" }
            require(noiseMessage.size in MESSAGE_LENGTH) { "noise_message must be 1 to $NOISE_MESSAGE_MAX bytes" }
        }

        override fun encode(): ByteArray = byteArrayOf(TYPE.toByte(), version.toByte(), step.toByte()) + noiseMessage

        public companion object {
            /** The largest Noise message a `HANDSHAKE` frame carries, in bytes (D-20). */
            public const val NOISE_MESSAGE_MAX: Int = 4_096

            /** The largest `HANDSHAKE` frame: the 3-byte header and [NOISE_MESSAGE_MAX]. */
            public const val FRAME_MAX: Int = 3 + NOISE_MESSAGE_MAX
            internal const val TYPE = 0x02
            private const val VERSION = 1
            private const val STEP = 2
            private const val MESSAGE = 3
            private val STEPS = 1..3
            private val MESSAGE_LENGTH = 1..NOISE_MESSAGE_MAX

            internal fun decode(bytes: ByteArray): Decoded<LinkFrame> =
                when {
                    bytes.size < MESSAGE -> {
                        Decoded.Invalid(DecodeError.TRUNCATED)
                    }

                    bytes.u8(VERSION) == 0 || bytes.u8(STEP) !in STEPS || bytes.size - MESSAGE !in MESSAGE_LENGTH -> {
                        Decoded.Invalid(DecodeError.OUT_OF_RANGE)
                    }

                    else -> {
                        val message = bytes.copyOfRange(MESSAGE, bytes.size)
                        Decoded.Valid(Handshake(bytes.u8(VERSION), bytes.u8(STEP), message))
                    }
                }
        }
    }

    /**
     * `PLAIN_HELLO` (spec "Unsecured link"): the sender's selected protocol [version], its mesh
     * identifier digest and its claimed NodeId. The profile byte is always `unsecured`.
     */
    public class PlainHello(
        public val version: Int,
        public val meshIdDigest: ByteArray,
        public val nodeId: ByteArray,
    ) : LinkFrame {
        init {
            require(version in 1..BYTE_MASK) { "version must be in 1..255, not $version" }
            require(meshIdDigest.size == ID_LENGTH) { "mesh_id_digest must be $ID_LENGTH bytes" }
            require(nodeId.size == ID_LENGTH) { "node_id must be $ID_LENGTH bytes" }
        }

        override fun encode(): ByteArray =
            byteArrayOf(TYPE.toByte(), version.toByte(), WireProfile.UNSECURED.code.toByte()) + meshIdDigest + nodeId

        internal companion object {
            const val TYPE = 0x03
            const val LENGTH = 67
            const val ID_LENGTH = 32
            private const val VERSION = 1
            private const val PROFILE = 2
            private const val DIGEST = 3

            fun decode(bytes: ByteArray): Decoded<LinkFrame> =
                when {
                    bytes.size != LENGTH -> {
                        Decoded.Invalid(sizeError(bytes, LENGTH))
                    }

                    bytes.u8(VERSION) == 0 || bytes.u8(PROFILE) != WireProfile.UNSECURED.code -> {
                        Decoded.Invalid(DecodeError.OUT_OF_RANGE)
                    }

                    else -> {
                        Decoded.Valid(
                            PlainHello(
                                bytes.u8(VERSION),
                                bytes.copyOfRange(DIGEST, DIGEST + ID_LENGTH),
                                bytes.copyOfRange(DIGEST + ID_LENGTH, LENGTH),
                            ),
                        )
                    }
                }
        }
    }

    /** `CLOSE` (spec "Close"): the sender disconnects next. [code] is kept as received. */
    public class Close(
        public val code: Int,
    ) : LinkFrame {
        public constructor(reason: CloseReason) : this(reason.code)

        init {
            require(code in 0..BYTE_MASK) { "reason must be a byte, not $code" }
        }

        /** The reason; an unknown code means [CloseReason.NORMAL]. */
        public val reason: CloseReason get() = CloseReason.of(code)

        override fun encode(): ByteArray = byteArrayOf(TYPE.toByte(), code.toByte())

        internal companion object {
            const val TYPE = 0x04
            const val LENGTH = 2

            fun decode(bytes: ByteArray): Decoded<LinkFrame> =
                if (bytes.size == LENGTH) {
                    Decoded.Valid(Close(bytes.u8(1)))
                } else {
                    Decoded.Invalid(sizeError(bytes, LENGTH))
                }
        }
    }

    /** `BUSY` (spec "Busy"): the receiver refused stream [streamId]. [code] is kept as received. */
    public class Busy(
        public val streamId: Int,
        public val code: Int,
    ) : LinkFrame {
        public constructor(streamId: Int, reason: BusyReason) : this(streamId, reason.code)

        init {
            require(streamId in 0..BYTE_MASK) { "stream_id must be a byte, not $streamId" }
            require(code in 0..BYTE_MASK) { "reason must be a byte, not $code" }
        }

        /** The reason; an unknown code means [BusyReason.REASSEMBLY_SLOTS]. */
        public val reason: BusyReason get() = BusyReason.of(code)

        override fun encode(): ByteArray = byteArrayOf(TYPE.toByte(), streamId.toByte(), code.toByte())

        internal companion object {
            const val TYPE = 0x06
            const val LENGTH = 3

            fun decode(bytes: ByteArray): Decoded<LinkFrame> =
                if (bytes.size == LENGTH) {
                    Decoded.Valid(Busy(bytes.u8(1), bytes.u8(2)))
                } else {
                    Decoded.Invalid(sizeError(bytes, LENGTH))
                }
        }
    }

    /**
     * `RESOLVING_KEY_UPDATE` (spec "Resolving key update", D-41): the sender's next resolving key
     * [next] with the Unix second it expires.
     */
    public class ResolvingKeyUpdate(
        public val next: ResolvingKey,
    ) : LinkFrame {
        override fun encode(): ByteArray = byteArrayOf(TYPE.toByte()) + next.encode()

        internal companion object {
            const val TYPE = 0x0E

            fun decode(bytes: ByteArray): Decoded<LinkFrame> =
                when (val key = ResolvingKey.decode(bytes.copyOfRange(1, bytes.size))) {
                    is Decoded.Valid -> Decoded.Valid(ResolvingKeyUpdate(key.value))
                    is Decoded.Invalid -> key
                }
        }
    }

    public companion object {
        private const val RESERVED_FROM = 0xC0

        /** Decodes one frame; never throws. */
        public fun decode(bytes: ByteArray): Decoded<LinkFrame> {
            val type = if (bytes.isEmpty()) -1 else bytes.u8(0)
            return when {
                type < 0 -> Decoded.Invalid(DecodeError.TRUNCATED)
                type == 0 || type >= RESERVED_FROM -> Decoded.Invalid(DecodeError.RESERVED_TYPE)
                type == Fragment.TYPE -> Fragment.decode(bytes)
                type == Handshake.TYPE -> Handshake.decode(bytes)
                type == PlainHello.TYPE -> PlainHello.decode(bytes)
                type == Close.TYPE -> Close.decode(bytes)
                type == Busy.TYPE -> Busy.decode(bytes)
                type == ResolvingKeyUpdate.TYPE -> ResolvingKeyUpdate.decode(bytes)
                else -> Decoded.Invalid(DecodeError.UNKNOWN_TYPE)
            }
        }

        private fun sizeError(
            bytes: ByteArray,
            length: Int,
        ): DecodeError = if (bytes.size < length) DecodeError.TRUNCATED else DecodeError.TRAILING_BYTES
    }
}

/**
 * Why a link closed (spec "Close"). Reasons are diagnostic, except that [DUPLICATE_LINK] keeps the
 * peer. The registry's codes run from 0x00 without gaps, so entries stay in code order.
 */
public enum class CloseReason {
    NORMAL,
    DUPLICATE_LINK,
    PROTOCOL_ERROR,
    HANDSHAKE_FAILED,
    UNSUPPORTED_VERSION,
    WRONG_MESH,
    RESOURCE_LIMIT,
    SHUTTING_DOWN,
    ;

    /** The one-byte wire code. */
    public val code: Int get() = ordinal

    public companion object {
        /** The reason with [code], or [NORMAL] for an unknown code. */
        public fun of(code: Int): CloseReason = entries.getOrElse(code) { NORMAL }
    }
}

/** Why a receiver refused a stream (spec "Busy"). Codes start at 0x01 and run without gaps. */
public enum class BusyReason {
    /** The link already reassembles its maximum number of streams, or its one large stream. */
    REASSEMBLY_SLOTS,

    /** The node's shared budget for large streams is spent. */
    LARGE_BUDGET,
    ;

    /** The one-byte wire code. */
    public val code: Int get() = ordinal + 1

    public companion object {
        /** The reason with [code], or [REASSEMBLY_SLOTS] for an unknown code. */
        public fun of(code: Int): BusyReason = entries.getOrElse(code - 1) { REASSEMBLY_SLOTS }
    }
}
