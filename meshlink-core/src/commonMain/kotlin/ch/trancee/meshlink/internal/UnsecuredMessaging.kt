package ch.trancee.meshlink.internal

/**
 * Direct messages in the `unsecured` profile (spec "MESSAGE", "Containers", "Plaintext"): drafts a
 * node's messages, frames them with the routing tags of the epoch they are written in, and accepts
 * the ones addressed to it. The claimed sender is not authenticated (ADR 0012 decision 12).
 *
 * Not thread-safe: [accept] caches this node's tags. A node calls it from its own dispatcher.
 */
public class UnsecuredMessaging(
    private val crypto: CryptoProvider,
    private val nodeId: ByteArray,
) {
    private val derivations = Derivations(crypto)
    private var windowEpoch: UInt? = null
    private var window: List<ByteArray> = emptyList()

    /**
     * A new message from this node to [destination] with a fresh random `message_id` and tombstone
     * secret `k`.
     *
     * @param payload at most [PAYLOAD_MAX] bytes, so the frame is a small frame.
     * @param expiry Unix seconds; the caller adds the message lifetime to the current time.
     */
    public fun draft(
        destination: ByteArray,
        payload: ByteArray,
        expiry: UInt,
    ): Draft {
        require(payload.size <= PAYLOAD_MAX) { "payload must be at most $PAYLOAD_MAX bytes, not ${payload.size}" }
        val messageId = crypto.randomBytes(MESSAGE_ID)
        val k = crypto.randomBytes(K)
        val plaintext = MessageContent(messageId, expiry, k, emptyList(), payload).encode(secured = false)
        val container = Container.Unsecured(nodeId, plaintext)
        return Draft(destination, messageId, expiry, derivations.tombstoneCommitment(k), container)
    }

    /**
     * The `MESSAGE` bytes of [draft], tagged for [epoch] with [hopLimit]; a retry re-tags the same
     * draft.
     */
    public fun frame(
        draft: Draft,
        epoch: UInt,
        hopLimit: Int,
    ): ByteArray {
        val header =
            UnicastHeader(
                hopLimit,
                draft.messageId,
                draft.expiry,
                derivations.unsecuredTag(draft.destination, epoch),
                derivations.unsecuredTag(nodeId, epoch),
            )
        return MessageFrame(header, draft.commitment, draft.container).encode()
    }

    /**
     * A `DELIVERY_RECEIPT` from this node to [destination] for [tombstones], oldest first, with a
     * fresh `message_id` and the given [expiry]: the latest expiry of the acknowledged messages.
     */
    public fun receipt(
        destination: ByteArray,
        tombstones: List<Tombstone>,
        expiry: UInt,
    ): ReceiptDraft {
        val messageId = crypto.randomBytes(MESSAGE_ID)
        val plaintext = receiptPlaintext(messageId, expiry, tombstones, secured = false)
        return ReceiptDraft(destination, messageId, expiry, tombstones, Container.Unsecured(nodeId, plaintext))
    }

    /** The `DELIVERY_RECEIPT` bytes of [receipt], tagged for [epoch] with [hopLimit]. */
    public fun frame(
        receipt: ReceiptDraft,
        epoch: UInt,
        hopLimit: Int,
    ): ByteArray {
        val destination = derivations.unsecuredTag(receipt.destination, epoch)
        val source = derivations.unsecuredTag(nodeId, epoch)
        val header = UnicastHeader(hopLimit, receipt.messageId, receipt.expiry, destination, source)
        return DeliveryReceipt(header, receipt.tombstones, receipt.container).encode()
    }

    /**
     * What the `DELIVERY_RECEIPT` in [bytes] acknowledges, or `null` to ignore it. It must decode,
     * carry this node's tag like [accept], use an `unsecured` container whose plaintext repeats the
     * header's `message_id` and `expiry`, and list in `MESSAGE_IDS` the IDs of its tombstones in the
     * same order. Its expiry must lie within [LIFETIME_MAX_SECONDS] of [nowSeconds], allowing
     * [CLOCK_SKEW_SECONDS] either way.
     */
    public fun acknowledged(
        bytes: ByteArray,
        nowSeconds: Long,
    ): Acknowledgement? {
        val frame = (DeliveryReceipt.decode(bytes) as? Decoded.Valid)?.value
        return frame?.takeIf { owns(it.header.destinationTag, nowSeconds) }?.let { acknowledgement(it, nowSeconds) }
    }

    /**
     * The message in [bytes] if it is for this node, or `null` to drop it. It must decode, carry
     * this node's tag for epoch e − 1, e or e + 1, where e is the epoch of [nowSeconds], use an
     * `unsecured` container whose plaintext repeats the header's `message_id` and `expiry`, commit
     * to its `k`, and not have expired, allowing [CLOCK_SKEW_SECONDS] either way.
     */
    public fun accept(
        bytes: ByteArray,
        nowSeconds: Long,
    ): Delivery? {
        val frame = (MessageFrame.decode(bytes) as? Decoded.Valid)?.value
        return frame?.takeIf { owns(it.header.destinationTag, nowSeconds) }?.let { delivery(it, nowSeconds) }
    }

    /**
     * Whether [tag] is this node's routing tag for epoch e − 1, e or e + 1, where e is the epoch of
     * [nowSeconds].
     */
    public fun owns(
        tag: ByteArray,
        nowSeconds: Long,
    ): Boolean {
        val tags = window(Derivations.epoch(nowSeconds))
        return tags.any { it.contentEquals(tag) }
    }

    private fun delivery(
        frame: MessageFrame,
        nowSeconds: Long,
    ): Delivery? {
        val container = frame.container as? Container.Unsecured ?: return null
        val content = (MessageContent.decode(container.plaintext, secured = false) as? Decoded.Valid)?.value
        return content?.takeIf { derivations.bound(frame, it, nowSeconds) }?.let {
            Delivery(container.senderNodeId, it.payload, Tombstone(it.messageId, it.k), it.expiry)
        }
    }

    private fun acknowledgement(
        frame: DeliveryReceipt,
        nowSeconds: Long,
    ): Acknowledgement? {
        val container = frame.container as? Container.Unsecured ?: return null
        val ids = receiptIds(frame, container.plaintext, nowSeconds, secured = false)
        return ids?.let { Acknowledgement(container.senderNodeId, it) }
    }

    private fun window(epoch: UInt): List<ByteArray> {
        if (epoch != windowEpoch) {
            window = listOf(epoch - 1u, epoch, epoch + 1u).map { derivations.unsecuredTag(nodeId, it) }
            windowEpoch = epoch
        }
        return window
    }

    public companion object {
        /**
         * The largest payload, in bytes: `unicast-payload-small-max` (spec "Size classes"). A frame
         * longer than a record body travels as `FRAGMENT`s.
         */
        public const val PAYLOAD_MAX: Int = 4_096

        /** `clock-skew`: how far, in seconds, a receiver trusts another node's clock (spec "Routing headers"). */
        public const val CLOCK_SKEW_SECONDS: Int = 30

        /** `message-lifetime-max`, in seconds. */
        public const val LIFETIME_MAX_SECONDS: Int = 3_600

        private const val MESSAGE_ID = 12
        private const val K = 16
    }
}

private const val MESSAGE_IDS = 0x83

/** The expiries a frame received at Unix second [nowSeconds] may carry, allowing `clock-skew` either way. */
private fun live(nowSeconds: Long): LongRange {
    val skew = UnsecuredMessaging.CLOCK_SKEW_SECONDS
    return nowSeconds - skew..nowSeconds + UnsecuredMessaging.LIFETIME_MAX_SECONDS + skew
}

/**
 * Whether [content] belongs to [frame] at Unix second [nowSeconds] (spec "Plaintext"): it repeats the
 * header's `message_id` and `expiry`, commits to its `k`, and has not expired, allowing `clock-skew`
 * either way.
 */
internal fun Derivations.bound(
    frame: MessageFrame,
    content: MessageContent,
    nowSeconds: Long,
): Boolean =
    content.messageId.contentEquals(frame.header.messageId) &&
        content.expiry == frame.header.expiry &&
        tombstoneCommitment(content.k).contentEquals(frame.commitment) &&
        content.expiry.toLong() in live(nowSeconds)

/**
 * A receipt plaintext (spec "Plaintext") of [messageId] and [expiry] listing the IDs of [tombstones]
 * in `MESSAGE_IDS`.
 */
internal fun receiptPlaintext(
    messageId: ByteArray,
    expiry: UInt,
    tombstones: List<Tombstone>,
    secured: Boolean,
): ByteArray {
    val ids = Tlv(MESSAGE_IDS, tombstones.fold(ByteArray(0)) { bytes, tombstone -> bytes + tombstone.messageId })
    return ReceiptContent(messageId, expiry, listOf(ids)).encode(secured)
}

/**
 * The IDs [frame] acknowledges if its opened [plaintext] is a receipt for it at Unix second
 * [nowSeconds] (spec "Delivery receipts"), else `null`: it decodes, padded if [secured], repeats the
 * header's `message_id` and `expiry`, and lists in `MESSAGE_IDS` the IDs of the tombstones in the same
 * order; and its expiry lies within `message-lifetime-max` of now, allowing `clock-skew` either way.
 */
internal fun receiptIds(
    frame: DeliveryReceipt,
    plaintext: ByteArray,
    nowSeconds: Long,
    secured: Boolean,
): List<ByteArray>? {
    val content = (ReceiptContent.decode(plaintext, secured) as? Decoded.Valid)?.value
    val ids = frame.tombstones.map { it.messageId }
    val listed = content?.let { receipt -> receipt.extensions.firstOrNull { it.type == MESSAGE_IDS }?.value }
    val matches =
        content != null && listed != null &&
            content.messageId.contentEquals(frame.header.messageId) &&
            content.expiry == frame.header.expiry &&
            listed.contentEquals(ids.fold(ByteArray(0)) { bytes, id -> bytes + id }) &&
            frame.header.expiry.toLong() in live(nowSeconds)
    return ids.takeIf { matches }
}

/**
 * A drafted message to [destination]: everything its frames keep across retries. Each write sets
 * the routing tags for its epoch ([UnsecuredMessaging.frame], [FirstMessages.frame],
 * [Conversation.frame]). A first message's [destination] is the responder's certified X25519 key and
 * its [sealer] the [Opening] its source tags and session come from; a session frame's [destination]
 * is the peer's NodeId and its [sealer] the [Conversation] that sealed it. An `unsecured` draft has none.
 */
public class Draft internal constructor(
    public val destination: ByteArray,
    public val messageId: ByteArray,
    public val expiry: UInt,
    public val commitment: ByteArray,
    public val container: Container,
    public val sealer: Sealer? = null,
)

/**
 * An accepted message: the claimed [sender] NodeId, the [payload], and the [tombstone] and [expiry]
 * its delivery receipt carries.
 */
public class Delivery(
    public val sender: ByteArray,
    public val payload: ByteArray,
    public val tombstone: Tombstone,
    public val expiry: UInt,
)

/** A drafted `DELIVERY_RECEIPT` to [destination]: everything its frames keep across epochs. */
public class ReceiptDraft internal constructor(
    public val destination: ByteArray,
    public val messageId: ByteArray,
    public val expiry: UInt,
    public val tombstones: List<Tombstone>,
    public val container: Container,
)

/** An accepted delivery receipt: the claimed [sender] NodeId and the [messageIds] it acknowledges, in order. */
public class Acknowledgement(
    public val sender: ByteArray,
    public val messageIds: List<ByteArray>,
)
