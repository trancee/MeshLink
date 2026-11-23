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
     * @param readReceipt whether the plaintext asks for a read receipt (`READ_RECEIPT_REQUESTED`).
     */
    public fun draft(
        destination: ByteArray,
        payload: ByteArray,
        expiry: UInt,
        readReceipt: Boolean = false,
    ): Draft {
        require(payload.size <= PAYLOAD_MAX) { "payload must be at most $PAYLOAD_MAX bytes, not ${payload.size}" }
        val messageId = crypto.randomBytes(MESSAGE_ID)
        val k = crypto.randomBytes(K)
        val requested = listOfNotNull(Tlv(READ_RECEIPT_REQUESTED, ByteArray(0)).takeIf { readReceipt })
        val plaintext = MessageContent(messageId, expiry, k, requested, payload).encode(secured = false)
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
     * A `READ_RECEIPT` from this node to [destination] listing [messageIds], in the order they were
     * read, with a fresh `message_id` and the given [expiry]: the reader's default message lifetime from now.
     *
     * @param messageIds 1 to [ReadReceipt.IDS_MAX] message IDs.
     */
    public fun readReceipt(
        destination: ByteArray,
        messageIds: List<ByteArray>,
        expiry: UInt,
    ): ReadDraft {
        val messageId = crypto.randomBytes(MESSAGE_ID)
        val plaintext = readPlaintext(messageId, expiry, messageIds, emptyList(), secured = false)
        return ReadDraft(
            messageId,
            expiry,
            Container.Unsecured(nodeId, plaintext),
            { derivations.unsecuredTag(destination, it) },
            { derivations.unsecuredTag(nodeId, it) },
        )
    }

    /**
     * What the `READ_RECEIPT` in [bytes] reports read, or `null` to ignore it. It must decode, carry
     * this node's tag like [accept], use an `unsecured` container whose plaintext repeats the header's
     * `message_id` and `expiry` and lists 1 to [ReadReceipt.IDS_MAX] IDs in `MESSAGE_IDS`, and its
     * expiry must lie within [LIFETIME_MAX_SECONDS] of [nowSeconds], allowing [CLOCK_SKEW_SECONDS]
     * either way.
     */
    public fun read(
        bytes: ByteArray,
        nowSeconds: Long,
    ): Acknowledgement? {
        val frame = (ReadReceipt.decode(bytes) as? Decoded.Valid)?.value
        val owned = frame?.takeIf { owns(it.header.destinationTag, nowSeconds) } ?: return null
        return (owned.container as? Container.Unsecured)?.let { container ->
            val content = (ReceiptContent.decode(container.plaintext, secured = false) as? Decoded.Valid)?.value
            content?.let { readIds(owned.header, it, nowSeconds) }?.let { Acknowledgement(container.senderNodeId, it) }
        }
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
        val epoch = Derivations.epoch(nowSeconds)
        if (epoch != windowEpoch) {
            window = listOf(epoch - 1u, epoch, epoch + 1u).map { derivations.unsecuredTag(nodeId, it) }
            windowEpoch = epoch
        }
        return window.any { it.contentEquals(tag) }
    }

    private fun delivery(
        frame: MessageFrame,
        nowSeconds: Long,
    ): Delivery? {
        val container = frame.container as? Container.Unsecured ?: return null
        val content = (MessageContent.decode(container.plaintext, secured = false) as? Decoded.Valid)?.value
        return content?.takeIf { derivations.bound(frame, it, nowSeconds) }?.let {
            val tombstone = Tombstone(it.messageId, it.k)
            Delivery(container.senderNodeId, it.payload, tombstone, it.expiry, it.readReceiptRequested)
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
private const val ID = 12

/** The zero-length plaintext TLV asking the recipient for a read receipt (spec "Plaintext"). */
internal const val READ_RECEIPT_REQUESTED = 0x04

/** Whether this content asks for a read receipt (`READ_RECEIPT_REQUESTED`). */
internal val MessageContent.readReceiptRequested: Boolean
    get() = extensions.any { it.type == READ_RECEIPT_REQUESTED }

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
 * A read receipt plaintext (spec "Plaintext") of [messageId] and [expiry]: [extensions], then the
 * concatenated [ids] in `MESSAGE_IDS`, padded if [secured].
 */
internal fun readPlaintext(
    messageId: ByteArray,
    expiry: UInt,
    ids: List<ByteArray>,
    extensions: List<Tlv>,
    secured: Boolean,
): ByteArray {
    require(ids.size in 1..ReadReceipt.IDS_MAX) {
        "a read receipt lists 1 to ${ReadReceipt.IDS_MAX} ids, not ${ids.size}"
    }
    val listed = Tlv(MESSAGE_IDS, ids.fold(ByteArray(0)) { bytes, id -> bytes + id })
    return ReceiptContent(messageId, expiry, extensions + listed).encode(secured)
}

/**
 * The IDs a read receipt with [header] reports read if its opened [content] belongs to it at Unix
 * second [nowSeconds] (spec "Read receipts"), else `null`: it repeats the header's `message_id` and
 * `expiry`, lists 1 to [ReadReceipt.IDS_MAX] whole IDs in `MESSAGE_IDS`, and its expiry lies within
 * `message-lifetime-max` of now, allowing `clock-skew` either way.
 */
internal fun readIds(
    header: UnicastHeader,
    content: ReceiptContent,
    nowSeconds: Long,
): List<ByteArray>? {
    val listed = (content.extensions.firstOrNull { it.type == MESSAGE_IDS } ?: return null).value
    val matches =
        listed.size in ID..ID * ReadReceipt.IDS_MAX && listed.size % ID == 0 &&
            content.messageId.contentEquals(header.messageId) &&
            content.expiry == header.expiry &&
            header.expiry.toLong() in live(nowSeconds)
    return if (matches) List(listed.size / ID) { listed.copyOfRange(it * ID, it * ID + ID) } else null
}

/**
 * A drafted `READ_RECEIPT`: everything its frames keep across epochs. It writes its own routing tags
 * for each epoch ([destination], [source]).
 */
public class ReadDraft internal constructor(
    public val messageId: ByteArray,
    public val expiry: UInt,
    public val container: Container,
    private val destinationTag: (UInt) -> ByteArray,
    private val sourceTag: (UInt) -> ByteArray,
) {
    /** The destination routing tag for [epoch]. */
    public fun destination(epoch: UInt): ByteArray = destinationTag(epoch)

    /** The source routing tag for [epoch]. */
    public fun source(epoch: UInt): ByteArray = sourceTag(epoch)

    /** The `READ_RECEIPT` bytes, tagged for [epoch] with [hopLimit]. */
    public fun frame(
        epoch: UInt,
        hopLimit: Int,
    ): ByteArray {
        val header = UnicastHeader(hopLimit, messageId, expiry, destination(epoch), source(epoch))
        return ReadReceipt(header, container).encode()
    }
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
 * An accepted message: the claimed [sender] NodeId, the [payload], the [tombstone] and [expiry] its
 * delivery receipt carries, and whether the sender asked for a [readReceipt].
 */
public class Delivery(
    public val sender: ByteArray,
    public val payload: ByteArray,
    public val tombstone: Tombstone,
    public val expiry: UInt,
    public val readReceipt: Boolean,
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
