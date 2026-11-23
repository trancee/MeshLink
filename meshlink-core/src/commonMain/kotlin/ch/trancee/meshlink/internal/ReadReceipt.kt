package ch.trancee.meshlink.internal

/**
 * `READ_RECEIPT` (spec "Read receipts"): the unicast [header] and the end-to-end [container], whose
 * receipt plaintext lists in `MESSAGE_IDS` the messages the recipient's app marked read. Relays read
 * only the header.
 */
public class ReadReceipt(
    public val header: UnicastHeader,
    public val container: Container,
) {
    /** The frame's bytes. */
    public fun encode(): ByteArray = header.encode(TYPE) + container.encode()

    public companion object {
        /** The frame type, `0x42`. */
        public const val TYPE: Int = 0x42

        /** `read-receipt-batch-max`: the most messages one read receipt lists. */
        public const val IDS_MAX: Int = 64

        private val KINDS = setOf(Container.IK_INITIATOR, Container.TRANSPORT, Container.UNSECURED)

        /** Decodes one `READ_RECEIPT`; never throws. Any other type is [DecodeError.UNKNOWN_TYPE]. */
        public fun decode(bytes: ByteArray): Decoded<ReadReceipt> =
            when {
                bytes.isEmpty() -> {
                    Decoded.Invalid(DecodeError.TRUNCATED)
                }

                bytes.u8(0) != TYPE -> {
                    Decoded.Invalid(DecodeError.UNKNOWN_TYPE)
                }

                bytes.size < UnicastHeader.LENGTH -> {
                    Decoded.Invalid(DecodeError.TRUNCATED)
                }

                UnicastHeader.spent(bytes) -> {
                    Decoded.Invalid(DecodeError.OUT_OF_RANGE)
                }

                else -> {
                    Container.decode(bytes, UnicastHeader.LENGTH, KINDS).then {
                        Decoded.Valid(ReadReceipt(UnicastHeader.of(bytes), it))
                    }
                }
            }
    }
}
