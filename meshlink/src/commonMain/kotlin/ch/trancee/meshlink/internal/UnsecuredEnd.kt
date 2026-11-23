package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.ReceivedMessage

/**
 * This node's end of `unsecured` messages: drafts its messages, tags and frames each attempt for the
 * epoch it is written in (spec "Origin behavior"), and hands messages for it to [inbox], telling
 * whom it [heard] from. Its [receipts] acknowledge each message the app takes; its [acknowledger]
 * acknowledges each later copy and takes the receipts for its own messages. Hears of each route a
 * route discovery [found].
 *
 * Runs on the node's serial dispatcher.
 */
internal class UnsecuredEnd(
    private val crypto: CryptoProvider,
    private val clock: Clock,
    private val inbox: Inbox,
    private val receipts: Receipts<NodeId>,
    private val heard: (sender: NodeId) -> Unit,
    private val found: (tag: ByteArray) -> Unit,
) : Origin {
    private val derivations = Derivations(crypto)
    private lateinit var codec: UnsecuredMessaging
    private var nodeId = ByteArray(0)

    /** Starts a run of the node whose NodeId is [nodeId]. */
    fun begin(nodeId: ByteArray) {
        this.nodeId = nodeId
        codec = UnsecuredMessaging(crypto, nodeId)
    }

    override fun draft(entry: Outbox.Entry): Draft {
        val expiry = entry.expiry.toUInt()
        return codec.draft(entry.to.toByteArray(), entry.payload, expiry)
    }

    override fun destination(draft: Draft): ByteArray = derivations.unsecuredTag(draft.destination, epoch())

    override fun origin(draft: Draft): ByteArray = tag()

    override fun frame(
        draft: Draft,
        hopLimit: Int,
    ): ByteArray = codec.frame(draft, epoch(), hopLimit)

    override fun owns(
        tag: ByteArray,
        nowSeconds: Long,
    ): Boolean = codec.owns(tag, nowSeconds)

    /** Opens a `MESSAGE` [frame] for this node; returns whether [inbox] took it. */
    override fun deliver(
        frame: ByteArray,
        nowSeconds: Long,
    ): Boolean {
        val delivery = codec.accept(frame, nowSeconds) ?: return false
        val sender = NodeId(delivery.sender)
        heard(sender)
        val message = ReceivedMessage(sender, delivery.payload, broadcast = false)
        return inbox.offer(message) { receipts.taken(sender, delivery.tombstone, delivery.expiry) }
    }

    override val acknowledger = Receipting()

    override fun carries(kind: Int): Boolean = kind == Container.UNSECURED

    /** This node's routing tag for the current epoch. */
    override fun tag(): ByteArray = derivations.unsecuredTag(nodeId, epoch())

    override fun found(tag: ByteArray) {
        found.invoke(tag)
    }

    private fun epoch(): UInt = Derivations.epoch(clock.wallClockMillis() / MILLIS_PER_SECOND)

    /** The delivery receipts of this node, sealed and opened with its messages' codec. */
    inner class Receipting : Acknowledger {
        override fun repeated(
            frame: ByteArray,
            nowSeconds: Long,
        ) {
            codec.accept(frame, nowSeconds)?.let { receipts.again(NodeId(it.sender), it.tombstone, it.expiry) }
        }

        override fun acknowledged(
            frame: ByteArray,
            nowSeconds: Long,
        ) {
            codec.acknowledged(frame, nowSeconds)?.let { receipts.arrived(NodeId(it.sender), it.messageIds) }
        }

        /** A delivery receipt from this node to [to] for [tombstones], expiring at Unix second [expiry]. */
        fun receipt(
            to: NodeId,
            tombstones: List<Tombstone>,
            expiry: UInt,
        ): ReceiptDraft = codec.receipt(to.toByteArray(), tombstones, expiry)

        /** [receipt]'s routing destination tag for the current epoch. */
        fun destination(receipt: ReceiptDraft): ByteArray = derivations.unsecuredTag(receipt.destination, epoch())

        /** [receipt] as a parcel framed for the current epoch with [hopLimit]. */
        fun parcel(
            receipt: ReceiptDraft,
            hopLimit: Int,
        ): Parcel = Parcel(receipt.messageId, relayed = false, { frame(receipt, hopLimit) }) {}

        private fun frame(
            receipt: ReceiptDraft,
            hopLimit: Int,
        ): ByteArray = codec.frame(receipt, epoch(), hopLimit)
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}

/** The routing tags of other nodes for [RoutedPeers] in an `unsecured` mesh: derived from their NodeIds. */
internal class UnsecuredPeerTags(
    crypto: CryptoProvider,
) : PeerTags {
    private val derivations = Derivations(crypto)

    override val secured: Boolean = false

    override fun key(id: NodeId): PeerKey = PeerKey(id.toByteArray(), Long.MAX_VALUE)

    override fun tag(
        key: ByteArray,
        epoch: UInt,
    ): ByteArray = derivations.unsecuredTag(key, epoch)
}
