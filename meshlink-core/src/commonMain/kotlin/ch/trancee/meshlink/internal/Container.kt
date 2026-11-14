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

    public companion object {
        /** Kind `0x01`, [IkInitiator]. */
        public const val IK_INITIATOR: Int = 0x01

        /** Kind `0x03`, [Transport]. */
        public const val TRANSPORT: Int = 0x03

        /** Kind `0x04`, [Unsecured]. */
        public const val UNSECURED: Int = 0x04

        private const val KIND = 1
        private const val LENGTH = 4
        private const val NONCE = 8
        private const val NODE_ID = 32
        private const val TRANSPORT_MINIMUM = 17

        private val LAYOUTS: Map<Int, Layout> =
            mapOf(
                IK_INITIATOR to Layout(KIND + LENGTH, 1, ::ikInitiator),
                TRANSPORT to Layout(KIND + NONCE + LENGTH, TRANSPORT_MINIMUM, ::transport),
                UNSECURED to Layout(KIND + NODE_ID + LENGTH, 0, ::unsecured),
            )

        /**
         * Decodes the container that starts at [offset] and runs to the end of [bytes]. [allowed]
         * lists the kinds the frame allows, each one of [IK_INITIATOR], [TRANSPORT] or [UNSECURED].
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

        private fun body(
            bytes: ByteArray,
            body: Int,
            layout: Layout,
        ): Decoded<Container> {
            val end = if (body <= bytes.size) body + bytes.u32(body - LENGTH).toLong() else Long.MAX_VALUE
            return when {
                end - body < layout.minimum -> Decoded.Invalid(DecodeError.OUT_OF_RANGE)
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
    }

    /** A kind's fixed [prefix] size, its least [minimum] body length, and how to [build] it from the body offset. */
    private class Layout(
        val prefix: Int,
        val minimum: Int,
        val build: (ByteArray, Int) -> Container,
    )
}
