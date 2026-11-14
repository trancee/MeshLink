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

    public companion object {
        private const val RESERVED_FROM = 0xC0

        /** Decodes one frame; never throws. */
        public fun decode(bytes: ByteArray): Decoded<LinkFrame> {
            val type = if (bytes.isEmpty()) -1 else bytes.u8(0)
            return when {
                type < 0 -> Decoded.Invalid(DecodeError.TRUNCATED)
                type == 0 || type >= RESERVED_FROM -> Decoded.Invalid(DecodeError.RESERVED_TYPE)
                type == PlainHello.TYPE -> PlainHello.decode(bytes)
                type == Close.TYPE -> Close.decode(bytes)
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
