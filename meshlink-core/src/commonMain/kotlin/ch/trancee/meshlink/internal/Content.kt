package ch.trancee.meshlink.internal

/**
 * The end-to-end plaintext's content (spec "Plaintext"; ADR 0012 decision 11): a [MessageContent]
 * or a [ReceiptContent]. [encode] frames it as `u32(content_length) ‖ content ‖ padding`, where a
 * secured plaintext pads with zeros to a multiple of 32 B and an `unsecured` one has no padding.
 */
public sealed interface Content {
    /** Equals the routing header's `message_id`. */
    public val messageId: ByteArray

    /** Equals the routing header's `expiry`. */
    public val expiry: UInt

    /** The end-to-end TLVs. */
    public val extensions: List<Tlv>

    /** The framed plaintext, padded when [secured]. */
    public fun encode(secured: Boolean): ByteArray

    public companion object {
        private const val LENGTH = 4
        private const val BLOCK = 32
        internal const val MESSAGE_ID = 12

        /** The end-to-end TLV registry: type to fixed length, or `null` when the length varies. */
        internal val EXTENSIONS: Map<Int, Int?> = mapOf(0x01 to null, 0x04 to 0, 0x82 to BLOCK, 0x83 to null)

        internal fun requireFields(content: Content) {
            require(content.messageId.size == MESSAGE_ID) { "message_id must be $MESSAGE_ID bytes" }
            require(Tlv.violation(content.extensions, EXTENSIONS) == null) { "extensions break the TLV rules" }
            require(Tlv.encode(content.extensions).size <= U16_MAX) { "extensions must fit a u16 length" }
        }

        internal fun frame(
            content: ByteArray,
            secured: Boolean,
        ): ByteArray {
            val framed = u32(content.size.toUInt()) + content
            return if (secured) framed + ByteArray((BLOCK - framed.size % BLOCK) % BLOCK) else framed
        }

        /** The content inside the framed [bytes], checking the padding rule for [secured]. */
        internal fun unframe(
            bytes: ByteArray,
            secured: Boolean,
        ): Decoded<ByteArray> {
            val end = if (bytes.size >= LENGTH) LENGTH + bytes.u32(0).toLong() else Long.MAX_VALUE
            return when {
                end > bytes.size -> Decoded.Invalid(DecodeError.TRUNCATED)
                !padded(bytes, end.toInt(), secured) -> Decoded.Invalid(DecodeError.BAD_PADDING)
                else -> Decoded.Valid(bytes.copyOfRange(LENGTH, end.toInt()))
            }
        }

        private fun padded(
            bytes: ByteArray,
            end: Int,
            secured: Boolean,
        ): Boolean {
            val padding = bytes.size - end
            return if (secured) {
                bytes.size % BLOCK == 0 && padding < BLOCK && (end until bytes.size).all { bytes[it] == ZERO }
            } else {
                padding == 0
            }
        }

        /** The TLVs between [start] and [end] of [content]; [end] must not pass the content's end. */
        internal fun extensions(
            content: ByteArray,
            start: Int,
            end: Int,
        ): Decoded<List<Tlv>> = Tlv.decode(content.copyOf(end), start, EXTENSIONS)

        private const val ZERO: Byte = 0
    }
}

/**
 * `MESSAGE` content: the header's [messageId] and [expiry], the tombstone secret [k], the
 * [extensions] and the app [payload].
 */
public class MessageContent(
    override val messageId: ByteArray,
    override val expiry: UInt,
    public val k: ByteArray,
    override val extensions: List<Tlv>,
    public val payload: ByteArray,
) : Content {
    init {
        Content.requireFields(this)
        require(k.size == K) { "k must be $K bytes" }
        require(payload.size <= PAYLOAD_MAX) { "payload must be at most $PAYLOAD_MAX bytes, not ${payload.size}" }
    }

    override fun encode(secured: Boolean): ByteArray {
        val tlvs = Tlv.encode(extensions)
        return Content.frame(messageId + u32(expiry) + k + u16(tlvs.size) + tlvs + payload, secured)
    }

    public companion object {
        /** The largest app payload, in bytes (spec "Plaintext"). */
        public const val PAYLOAD_MAX: Int = 131_072

        private const val K = 16
        private const val EXPIRY_AT = Content.MESSAGE_ID
        private const val K_AT = EXPIRY_AT + 4
        private const val EXTENSIONS_LENGTH_AT = K_AT + K
        private const val EXTENSIONS_AT = EXTENSIONS_LENGTH_AT + 2

        /** Decodes a framed message plaintext; never throws. */
        public fun decode(
            bytes: ByteArray,
            secured: Boolean,
        ): Decoded<MessageContent> = Content.unframe(bytes, secured).then(::content)

        private fun content(bytes: ByteArray): Decoded<MessageContent> {
            val complete = bytes.size >= EXTENSIONS_AT
            val end = if (complete) EXTENSIONS_AT + bytes.u16(EXTENSIONS_LENGTH_AT) else Int.MAX_VALUE
            return when {
                end > bytes.size -> Decoded.Invalid(DecodeError.TRUNCATED)
                bytes.size - end > PAYLOAD_MAX -> Decoded.Invalid(DecodeError.OUT_OF_RANGE)
                else -> Content.extensions(bytes, EXTENSIONS_AT, end).then { Decoded.Valid(message(bytes, it, end)) }
            }
        }

        private fun message(
            bytes: ByteArray,
            extensions: List<Tlv>,
            end: Int,
        ): MessageContent =
            MessageContent(
                bytes.copyOf(Content.MESSAGE_ID),
                bytes.u32(EXPIRY_AT),
                bytes.copyOfRange(K_AT, EXTENSIONS_LENGTH_AT),
                extensions,
                bytes.copyOfRange(end, bytes.size),
            )
    }
}

/** `DELIVERY_RECEIPT` and `READ_RECEIPT` content: the header's [messageId] and [expiry] and the [extensions]. */
public class ReceiptContent(
    override val messageId: ByteArray,
    override val expiry: UInt,
    override val extensions: List<Tlv>,
) : Content {
    init {
        Content.requireFields(this)
    }

    override fun encode(secured: Boolean): ByteArray {
        val tlvs = Tlv.encode(extensions)
        return Content.frame(messageId + u32(expiry) + u16(tlvs.size) + tlvs, secured)
    }

    public companion object {
        private const val EXPIRY_AT = Content.MESSAGE_ID
        private const val EXTENSIONS_LENGTH_AT = EXPIRY_AT + 4
        private const val EXTENSIONS_AT = EXTENSIONS_LENGTH_AT + 2

        /** Decodes a framed receipt plaintext; never throws. */
        public fun decode(
            bytes: ByteArray,
            secured: Boolean,
        ): Decoded<ReceiptContent> = Content.unframe(bytes, secured).then(::content)

        private fun content(bytes: ByteArray): Decoded<ReceiptContent> {
            val complete = bytes.size >= EXTENSIONS_AT
            val end = if (complete) EXTENSIONS_AT + bytes.u16(EXTENSIONS_LENGTH_AT) else Int.MAX_VALUE
            return when {
                end > bytes.size -> Decoded.Invalid(DecodeError.TRUNCATED)
                end < bytes.size -> Decoded.Invalid(DecodeError.TRAILING_BYTES)
                else -> Content.extensions(bytes, EXTENSIONS_AT, end).then { Decoded.Valid(receipt(bytes, it)) }
            }
        }

        private fun receipt(
            bytes: ByteArray,
            extensions: List<Tlv>,
        ): ReceiptContent = ReceiptContent(bytes.copyOf(Content.MESSAGE_ID), bytes.u32(EXPIRY_AT), extensions)
    }
}
