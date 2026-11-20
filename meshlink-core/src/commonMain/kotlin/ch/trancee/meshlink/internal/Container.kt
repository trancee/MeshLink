package ch.trancee.meshlink.internal

/**
 * The end-to-end container that ends a routed frame (spec "Containers"; ADR 0012 decision 5). Its
 * first byte is the [kind]; each frame allows only some kinds, and any other is
 * [DecodeError.UNKNOWN_KIND].
 */
public sealed interface Container {
    /** The one-byte kind code. */
    public val kind: Int

    /** The container's bytes. */
    public fun encode(): ByteArray

    /** Kind `0x01`: `IK` message 1, which opens a secured session. */
    public class IkInitiator(
        public val noiseMessage: ByteArray,
    ) : Container {
        init {
            require(noiseMessage.isNotEmpty()) { "noise_message must not be empty" }
        }

        override val kind: Int get() = IK_INITIATOR

        override fun encode(): ByteArray = byteArrayOf(kind.toByte()) + u32(noiseMessage.size.toUInt()) + noiseMessage
    }

    /** Kind `0x03`: a secured session's [ciphertext] under the explicit Noise transport [nonce]. */
    public class Transport(
        public val nonce: ULong,
        public val ciphertext: ByteArray,
    ) : Container {
        init {
            require(ciphertext.size >= TRANSPORT_MINIMUM) { "ciphertext must hold the 16 B tag and a byte" }
        }

        override val kind: Int get() = TRANSPORT

        override fun encode(): ByteArray {
            val header = byteArrayOf(kind.toByte()) + u64(nonce) + u32(ciphertext.size.toUInt())
            return header + ciphertext
        }
    }

    /** Kind `0x04`, `unsecured`: the claimed [senderNodeId] and the unpadded [plaintext]. */
    public class Unsecured(
        public val senderNodeId: ByteArray,
        public val plaintext: ByteArray,
    ) : Container {
        init {
            require(senderNodeId.size == NODE_ID) { "sender_node_id must be $NODE_ID bytes" }
        }

        override val kind: Int get() = UNSECURED

        override fun encode(): ByteArray {
            val header = byteArrayOf(kind.toByte()) + senderNodeId + u32(plaintext.size.toUInt())
            return header + plaintext
        }
    }

    /**
     * Kind `0x05`, a signed mesh broadcast (D-40): the origin's Ed25519 identity [originKey], the
     * [payload] of at most 1,024 B, and the [signature] by [originKey].
     */
    public class SignedBroadcast(
        public val originKey: ByteArray,
        public val payload: ByteArray,
        public val signature: ByteArray,
    ) : Container {
        init {
            require(originKey.size == ORIGIN_KEY) { "origin_key must be $ORIGIN_KEY bytes" }
            require(payload.size <= BroadcastFrame.PAYLOAD_MAX) { "payload must be at most 1,024 bytes" }
            require(signature.size == SIGNATURE) { "signature must be $SIGNATURE bytes" }
        }

        override val kind: Int get() = SIGNED_BROADCAST

        override fun encode(): ByteArray {
            val head = byteArrayOf(kind.toByte()) + originKey
            return head + u16(payload.size) + payload + signature
        }
    }

    public companion object {
        /** Kind `0x01`, [IkInitiator]. */
        public const val IK_INITIATOR: Int = 0x01

        /** Kind `0x03`, [Transport]. */
        public const val TRANSPORT: Int = 0x03

        /** Kind `0x04`, [Unsecured]. */
        public const val UNSECURED: Int = 0x04

        /** Kind `0x05`, [SignedBroadcast]. */
        public const val SIGNED_BROADCAST: Int = 0x05

        private const val KIND = 1
        private const val LENGTH = 4
        private const val SHORT_LENGTH = 2
        private const val NONCE = 8
        private const val NODE_ID = 32
        private const val TRANSPORT_MINIMUM = 17
        private const val ORIGIN_KEY = 32
        private const val SIGNATURE = 64

        private val LAYOUTS: Map<Int, Layout> =
            mapOf(
                IK_INITIATOR to Layout(KIND + LENGTH, 1, ::ikInitiator),
                TRANSPORT to Layout(KIND + NONCE + LENGTH, TRANSPORT_MINIMUM, ::transport),
                UNSECURED to Layout(KIND + NODE_ID + LENGTH, 0, ::unsecured),
                SIGNED_BROADCAST to
                    Layout(
                        KIND + ORIGIN_KEY + SHORT_LENGTH,
                        0,
                        ::signedBroadcast,
                        SHORT_LENGTH,
                        SIGNATURE,
                        BroadcastFrame.PAYLOAD_MAX.toLong(),
                    ),
            )

        /**
         * Decodes the container that starts at [offset] and runs to the end of [bytes]. [allowed]
         * lists the kinds the frame allows, each one of [IK_INITIATOR], [TRANSPORT], [UNSECURED] or
         * [SIGNED_BROADCAST].
         */
        internal fun decode(
            bytes: ByteArray,
            offset: Int,
            allowed: Set<Int>,
        ): Decoded<Container> {
            val kind = if (offset < bytes.size) bytes.u8(offset) else null
            return when {
                kind == null -> Decoded.Invalid(DecodeError.TRUNCATED)
                kind !in allowed -> Decoded.Invalid(DecodeError.UNKNOWN_KIND)
                else -> body(bytes, offset + LAYOUTS.getValue(kind).prefix, LAYOUTS.getValue(kind))
            }
        }

        // The body starts at [body], right after the length field; the suffix follows it.
        private fun body(
            bytes: ByteArray,
            body: Int,
            layout: Layout,
        ): Decoded<Container> {
            val size = if (body <= bytes.size) layout.size(bytes, body) else null
            val end = if (size != null) body + size + layout.suffix else Long.MAX_VALUE
            return when {
                size != null && size !in layout.minimum..layout.maximum -> Decoded.Invalid(DecodeError.OUT_OF_RANGE)
                end > bytes.size -> Decoded.Invalid(DecodeError.TRUNCATED)
                end < bytes.size -> Decoded.Invalid(DecodeError.TRAILING_BYTES)
                else -> Decoded.Valid(layout.build(bytes, body))
            }
        }

        private fun ikInitiator(
            bytes: ByteArray,
            body: Int,
        ): Container = IkInitiator(bytes.copyOfRange(body, bytes.size))

        private fun transport(
            bytes: ByteArray,
            body: Int,
        ): Container = Transport(bytes.u64(body - NONCE - LENGTH), bytes.copyOfRange(body, bytes.size))

        private fun unsecured(
            bytes: ByteArray,
            body: Int,
        ): Container {
            val sender = bytes.copyOfRange(body - NODE_ID - LENGTH, body - LENGTH)
            return Unsecured(sender, bytes.copyOfRange(body, bytes.size))
        }

        private fun signedBroadcast(
            bytes: ByteArray,
            body: Int,
        ): Container {
            val key = bytes.copyOfRange(body - ORIGIN_KEY - SHORT_LENGTH, body - SHORT_LENGTH)
            val signature = bytes.size - SIGNATURE
            return SignedBroadcast(key, bytes.copyOfRange(body, signature), bytes.copyOfRange(signature, bytes.size))
        }
    }

    /**
     * A kind's fixed [prefix] size, ending in a [length]-byte body length; the least [minimum] and
     * most [maximum] body length; the [suffix] after the body; and how to [build] it from the body
     * offset.
     */
    private class Layout(
        val prefix: Int,
        val minimum: Int,
        val build: (ByteArray, Int) -> Container,
        val length: Int = LENGTH,
        val suffix: Int = 0,
        val maximum: Long = Long.MAX_VALUE,
    ) {
        /** The body length in the field that ends at [body]. */
        fun size(
            bytes: ByteArray,
            body: Int,
        ): Long = if (length == SHORT_LENGTH) bytes.u16(body - length).toLong() else bytes.u32(body - length).toLong()
    }
}
