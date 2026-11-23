package ch.trancee.meshlink.internal

/** What sealed a secured [Draft]: the [Opening] of a first message, or the [Conversation] of a session frame. */
public sealed interface Sealer

/**
 * A first message's open handshake (spec "Sessions", "Adopting"): [replay] gives the initiator's `IK`
 * or `IKhfs` state after message 1 of [messageId] afresh, so a message 2 that fails leaves it for the
 * real one; and the conversation [secret] between [initiatorNodeId] and [responderNodeId]. It adopts
 * one message 2.
 */
public class Opening internal constructor(
    private val crypto: CryptoProvider,
    private val replay: () -> NoiseHandshake,
    internal val secret: ByteArray,
    private val initiatorNodeId: ByteArray,
    private val responderNodeId: ByteArray,
    private val messageId: ByteArray,
) : Sealer {
    private var adopted = false

    /**
     * The initiator's session from the message 2 in [bytes], or `null` to ignore it. It must decode
     * as a `DELIVERY_RECEIPT` with an `IK_RESPONDER` container whose only tombstone is this first
     * message's, read as message 2, and hold a receipt plaintext as [receiptIds] checks at
     * [nowSeconds]. Only the first message 2 that reads is adopted.
     */
    public fun adopt(
        bytes: ByteArray,
        nowSeconds: Long,
    ): Conversation? {
        val frame = (DeliveryReceipt.decode(bytes) as? Decoded.Valid)?.value
        val container = frame?.container as? Container.IkResponder
        val tombstone = frame?.let { it.tombstones.singleOrNull() }
        val mine = tombstone != null && tombstone.messageId.contentEquals(messageId)
        val read =
            container?.takeIf { mine && !adopted }?.let {
                val handshake = replay()
                val plaintext = (handshake.readMessage(it.noiseMessage) as? NoiseResult.Ok)?.value
                plaintext?.let { handshake to plaintext }
            }
        val checked = read?.takeIf { receiptIds(frame, it.second, nowSeconds, secured = true) != null }
        val (handshake, _) = checked ?: return null
        adopted = true
        val session = (handshake.session() as NoiseResult.Ok).value
        return Conversation(crypto, session, secret, initiatorNodeId, responderNodeId, initiator = true)
    }
}

/**
 * One end of an end-to-end session (spec "Sessions", "Conversation tags"): seals this node's
 * messages and receipts to the [peer] as kind `0x03` frames, tags them for the conversation [secret]'s
 * direction away from this end, and opens the peer's frames to the other direction. [initiator] says
 * whether this end sent the first message; [initiatorNodeId] is that end's NodeId. [sent] and
 * [received] count its frames.
 *
 * Not thread-safe: [owns] caches the incoming tags. A node calls it from its own dispatcher.
 */
public class Conversation(
    private val crypto: CryptoProvider,
    private val session: EndToEndSession,
    private val secret: ByteArray,
    public val initiatorNodeId: ByteArray,
    responderNodeId: ByteArray,
    public val initiator: Boolean,
) : Sealer {
    private val derivations = Derivations(crypto)
    private val tags = EndToEndTags(derivations)
    private val outgoing = if (initiator) INITIATOR_TO_RESPONDER else RESPONDER_TO_INITIATOR
    private val incoming = if (initiator) RESPONDER_TO_INITIATOR else INITIATOR_TO_RESPONDER
    private var windowEpoch: UInt? = null
    private var window: List<ByteArray> = emptyList()

    /** The other end's NodeId. */
    public val peer: ByteArray = if (initiator) responderNodeId else initiatorNodeId

    /** The frames this end sealed. */
    public var sent: Int = 0
        private set

    /** The frames this end opened. */
    public var received: Int = 0
        private set

    /**
     * A new message of [payload] to [peer] with a fresh `message_id` and tombstone secret, sealed at
     * the next nonce; `null` once the nonces run out. [readReceipt] asks the peer for a read receipt.
     */
    public fun draft(
        payload: ByteArray,
        expiry: UInt,
        readReceipt: Boolean = false,
    ): Draft? {
        val messageId = crypto.randomBytes(MESSAGE_ID)
        val k = crypto.randomBytes(K)
        val requested = listOfNotNull(Tlv(READ_RECEIPT_REQUESTED, ByteArray(0)).takeIf { readReceipt })
        val sealed = seal(MessageContent(messageId, expiry, k, requested, payload).encode(secured = true))
        return sealed?.let { Draft(peer, messageId, expiry, derivations.tombstoneCommitment(k), it, this) }
    }

    /**
     * A `DELIVERY_RECEIPT` to [peer] for [tombstones], oldest first, with a fresh `message_id`, the
     * latest [expiry] of the acknowledged messages, and their IDs in `MESSAGE_IDS`; `null` once the
     * nonces run out.
     */
    public fun receipt(
        tombstones: List<Tombstone>,
        expiry: UInt,
    ): ReceiptDraft? {
        val messageId = crypto.randomBytes(MESSAGE_ID)
        val sealed = seal(receiptPlaintext(messageId, expiry, tombstones, secured = true))
        return sealed?.let { ReceiptDraft(peer, messageId, expiry, tombstones, it) }
    }

    /**
     * A `READ_RECEIPT` to [peer] listing [messageIds] in the order they were read, with a fresh
     * `message_id` and the latest [expiry] of the listed messages; `null` once the nonces run out.
     *
     * @param messageIds 1 to [ReadReceipt.IDS_MAX] message IDs.
     */
    public fun readReceipt(
        messageIds: List<ByteArray>,
        expiry: UInt,
    ): ReadDraft? {
        val messageId = crypto.randomBytes(MESSAGE_ID)
        val sealed = seal(readPlaintext(messageId, expiry, messageIds, emptyList(), secured = true))
        return sealed?.let { ReadDraft(messageId, expiry, it, ::destination, ::source) }
    }

    /** The destination tag of this end's frames for [epoch]. */
    public fun destination(epoch: UInt): ByteArray = tags.conversationTag(secret, outgoing, epoch)

    /** The source tag of this end's frames for [epoch], the peer's destination tag. */
    public fun source(epoch: UInt): ByteArray = tags.conversationTag(secret, incoming, epoch)

    /**
     * Whether [tag] is the peer's destination tag for epoch e − 1, e or e + 1, where e is the epoch of
     * [nowSeconds].
     */
    public fun owns(
        tag: ByteArray,
        nowSeconds: Long,
    ): Boolean {
        val epoch = Derivations.epoch(nowSeconds)
        if (epoch != windowEpoch) {
            window = listOf(epoch - 1u, epoch, epoch + 1u).map { source(it) }
            windowEpoch = epoch
        }
        return window.any { it.contentEquals(tag) }
    }

    /**
     * The peer's message in [bytes], or `null` to drop it. It must decode, carry one of the tags
     * [owns] at [nowSeconds], open as a kind `0x03` frame (spec "Transport"), and hold a plaintext
     * bound to its header (spec "Plaintext").
     */
    public fun accept(
        bytes: ByteArray,
        nowSeconds: Long,
    ): Delivery? {
        val frame = (MessageFrame.decode(bytes) as? Decoded.Valid)?.value
        val owned = frame?.takeIf { owns(it.header.destinationTag, nowSeconds) } ?: return null
        val plaintext = open(owned.container)
        val content = plaintext?.let { MessageContent.decode(it, secured = true) as? Decoded.Valid }?.value
        return content?.takeIf { derivations.bound(owned, it, nowSeconds) }?.let {
            Delivery(peer, it.payload, Tombstone(it.messageId, it.k), it.expiry, it.readReceiptRequested)
        }
    }

    /**
     * The IDs the peer's `READ_RECEIPT` in [bytes] reports read, or `null` to ignore it. It must
     * decode, carry one of the tags [owns] at [nowSeconds], open as a kind `0x03` frame, and hold a
     * read receipt plaintext as [readIds] checks.
     */
    public fun read(
        bytes: ByteArray,
        nowSeconds: Long,
    ): List<ByteArray>? {
        val frame = (ReadReceipt.decode(bytes) as? Decoded.Valid)?.value
        val owned = frame?.takeIf { owns(it.header.destinationTag, nowSeconds) } ?: return null
        val plaintext = open(owned.container)
        val content = plaintext?.let { (ReceiptContent.decode(it, secured = true) as? Decoded.Valid)?.value }
        return content?.let { readIds(owned.header, it, nowSeconds) }
    }

    /**
     * The IDs the peer's `DELIVERY_RECEIPT` in [bytes] acknowledges, or `null` to ignore it. It must
     * decode, carry one of the tags [owns] at [nowSeconds], open as a kind `0x03` frame, and hold a
     * receipt plaintext as [receiptIds] checks.
     */
    public fun acknowledged(
        bytes: ByteArray,
        nowSeconds: Long,
    ): List<ByteArray>? {
        val frame = (DeliveryReceipt.decode(bytes) as? Decoded.Valid)?.value
        val owned = frame?.takeIf { owns(it.header.destinationTag, nowSeconds) }
        val plaintext = owned?.let { open(it.container) }
        return plaintext?.let { receiptIds(owned, it, nowSeconds, secured = true) }
    }

    private fun seal(plaintext: ByteArray): Container.Transport? = session.seal(plaintext)?.also { sent++ }

    private fun open(container: Container): ByteArray? {
        val opened = if (container is Container.Transport) session.open(container) else null
        if (opened !is EndToEndSession.Opened.Frame) return null
        received++
        return opened.plaintext
    }

    private companion object {
        const val MESSAGE_ID = 12
        const val K = 16
        const val INITIATOR_TO_RESPONDER = 0x01
        const val RESPONDER_TO_INITIATOR = 0x02
    }
}

/** The `MESSAGE` bytes of [draft], tagged for [epoch] with [hopLimit]. */
public fun Conversation.frame(
    draft: Draft,
    epoch: UInt,
    hopLimit: Int,
): ByteArray {
    val header = header(draft.messageId, draft.expiry, epoch, hopLimit)
    return MessageFrame(header, draft.commitment, draft.container).encode()
}

/** The `DELIVERY_RECEIPT` bytes of [receipt], tagged for [epoch] with [hopLimit]. */
public fun Conversation.frame(
    receipt: ReceiptDraft,
    epoch: UInt,
    hopLimit: Int,
): ByteArray {
    val header = header(receipt.messageId, receipt.expiry, epoch, hopLimit)
    return DeliveryReceipt(header, receipt.tombstones, receipt.container).encode()
}

private fun Conversation.header(
    messageId: ByteArray,
    expiry: UInt,
    epoch: UInt,
    hopLimit: Int,
): UnicastHeader = UnicastHeader(hopLimit, messageId, expiry, destination(epoch), source(epoch))
