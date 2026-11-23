package ch.trancee.meshlink.internal

/**
 * A delivered unicast's tombstone (spec "Tombstones"): its [messageId] and the commitment's preimage
 * [k]. `DELIVERY_RECEIPT` and `CATCH_UP` carry it in clear.
 */
public class Tombstone(
    public val messageId: ByteArray,
    public val k: ByteArray,
) {
    init {
        require(messageId.size == MESSAGE_ID) { "message_id must be $MESSAGE_ID bytes" }
        require(k.size == K) { "k must be $K bytes" }
    }

    /** The tombstone's bytes: [messageId] then [k]. */
    public fun encode(): ByteArray = messageId + k

    public companion object {
        /** A tombstone's size in bytes. */
        public const val LENGTH: Int = 28

        private const val MESSAGE_ID = 12
        private const val K = 16
    }
}

/**
 * `DELIVERY_RECEIPT` (spec "Delivery receipts"): the unicast [header], the [tombstones] of the
 * acknowledged messages in clear, and the end-to-end [container], whose plaintext's `MESSAGE_IDS`
 * lists the same IDs. Relays read only the header and the tombstones.
 */
public class DeliveryReceipt(
    public val header: UnicastHeader,
    public val tombstones: List<Tombstone>,
    public val container: Container,
) {
    init {
        require(tombstones.size in 1..TOMBSTONES_MAX) { "a receipt carries 1..$TOMBSTONES_MAX tombstones" }
    }

    /** The frame's bytes. */
    public fun encode(): ByteArray =
        header.encode(TYPE) + byteArrayOf(tombstones.size.toByte()) +
            tombstones.fold(ByteArray(0)) { bytes, tombstone -> bytes + tombstone.encode() } + container.encode()

    public companion object {
        /** The frame type, `0x41`. */
        public const val TYPE: Int = 0x41

        /** `receipt-batch-max`: the most messages one receipt acknowledges. */
        public const val TOMBSTONES_MAX: Int = 32

        private const val MESSAGE_ID = 12
        private val KINDS = setOf(Container.IK_RESPONDER, Container.TRANSPORT, Container.UNSECURED)

        /** Decodes one `DELIVERY_RECEIPT`; never throws. Any other type is [DecodeError.UNKNOWN_TYPE]. */
        public fun decode(bytes: ByteArray): Decoded<DeliveryReceipt> {
            val count = if (bytes.size > UnicastHeader.LENGTH) bytes.u8(UnicastHeader.LENGTH) else null
            val container = UnicastHeader.LENGTH + 1 + (count ?: 0) * Tombstone.LENGTH
            return when {
                bytes.isEmpty() -> Decoded.Invalid(DecodeError.TRUNCATED)
                bytes.u8(0) != TYPE -> Decoded.Invalid(DecodeError.UNKNOWN_TYPE)
                count == null -> Decoded.Invalid(DecodeError.TRUNCATED)
                UnicastHeader.spent(bytes) || count !in 1..TOMBSTONES_MAX -> Decoded.Invalid(DecodeError.OUT_OF_RANGE)
                else -> Container.decode(bytes, container, KINDS).then { Decoded.Valid(receipt(bytes, count, it)) }
            }
        }

        private fun receipt(
            bytes: ByteArray,
            count: Int,
            container: Container,
        ): DeliveryReceipt {
            val tombstones =
                List(count) {
                    val at = UnicastHeader.LENGTH + 1 + it * Tombstone.LENGTH
                    Tombstone(
                        bytes.copyOfRange(at, at + MESSAGE_ID),
                        bytes.copyOfRange(at + MESSAGE_ID, at + Tombstone.LENGTH),
                    )
                }
            return DeliveryReceipt(UnicastHeader.of(bytes), tombstones, container)
        }
    }
}
