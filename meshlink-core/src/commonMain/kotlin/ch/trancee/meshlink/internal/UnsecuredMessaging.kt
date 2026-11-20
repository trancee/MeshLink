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
     * @param hopLimit 1 for a direct-only send.
     */
    public fun draft(
        destination: ByteArray,
        payload: ByteArray,
        expiry: UInt,
        hopLimit: Int,
    ): Draft {
        require(payload.size <= PAYLOAD_MAX) { "payload must be at most $PAYLOAD_MAX bytes, not ${payload.size}" }
        val messageId = crypto.randomBytes(MESSAGE_ID)
        val k = crypto.randomBytes(K)
        val plaintext = MessageContent(messageId, expiry, k, emptyList(), payload).encode(secured = false)
        val container = Container.Unsecured(nodeId, plaintext)
        return Draft(destination, hopLimit, messageId, expiry, derivations.tombstoneCommitment(k), container)
    }

    /**
     * The `MESSAGE` bytes of [draft], tagged for [epoch] with [hopLimit]; a retry re-tags the same
     * draft.
     */
    public fun frame(
        draft: Draft,
        epoch: UInt,
        hopLimit: Int = draft.hopLimit,
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
        return content?.takeIf { bound(frame, it, nowSeconds) }?.let { Delivery(container.senderNodeId, it.payload) }
    }

    private fun bound(
        frame: MessageFrame,
        content: MessageContent,
        nowSeconds: Long,
    ): Boolean {
        val expiry = content.expiry.toLong()
        val window = nowSeconds - CLOCK_SKEW_SECONDS..nowSeconds + LIFETIME_MAX_SECONDS + CLOCK_SKEW_SECONDS
        return content.messageId.contentEquals(frame.header.messageId) &&
            content.expiry == frame.header.expiry &&
            derivations.tombstoneCommitment(content.k).contentEquals(frame.commitment) &&
            expiry in window
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

/**
 * A drafted message to [destination]: everything its frames keep across retries. Each write sets
 * the routing tags for its epoch ([UnsecuredMessaging.frame]).
 */
public class Draft internal constructor(
    public val destination: ByteArray,
    public val hopLimit: Int,
    public val messageId: ByteArray,
    public val expiry: UInt,
    public val commitment: ByteArray,
    public val container: Container,
)

/** An accepted message: the claimed [sender] NodeId and the [payload]. */
public class Delivery(
    public val sender: ByteArray,
    public val payload: ByteArray,
)
