package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId

/**
 * What a secured end tells its node: whom it [heard] from, each route a route discovery [found], each
 * peer a session [opened] with, whose waiting messages go now, and each delivery or read receipt to [send].
 */
internal class SecuredHooks(
    val heard: (sender: NodeId) -> Unit,
    val found: (tag: ByteArray) -> Unit,
    val opened: (peer: NodeId) -> Unit,
    val send: (receipt: Outgoing) -> Unit,
)

/** How a delivery receipt goes back to the sender of a message: in the message's [conversation]. */
internal sealed interface Reply {
    val conversation: Conversation

    /**
     * The receipt for a first message: its `IK` message 2 [answer], which opens the session when it first
     * goes and goes unchanged for every later copy (spec "Sessions").
     */
    class First(
        override val conversation: Conversation,
        val answer: ReceiptDraft,
    ) : Reply {
        /** Whether the answer went: the session is open. */
        var answered = false
    }

    /** A receipt sealed in the session, batched with the others for it. */
    data class Session(
        override val conversation: Conversation,
    ) : Reply
}

/**
 * A delivery or read receipt ready to route from [origin] to [destination], framed with [frame] for the
 * current epoch.
 */
internal class Outgoing(
    val destination: ByteArray,
    val origin: ByteArray,
    val messageId: ByteArray,
    val frame: (hopLimit: Int) -> ByteArray,
) {
    /** The read receipt [draft], tagged for the [epoch] of now and framed for the epoch it is written in. */
    constructor(draft: ReadDraft, epoch: () -> UInt) :
        this(draft.destination(epoch()), draft.source(epoch()), draft.messageId, { draft.frame(epoch(), it) })
}

/**
 * This node's end of a secured mesh's messages (spec "First messages", "Sessions" and "Routing tags").
 * It seals each message to a destination with the current one of its [sessions], else as a first
 * message to the destination's certificate, which [lookups] hold or look up, with one first message
 * outstanding per destination. It tags and frames each attempt for the epoch it is written in. It opens
 * the first messages and session frames for this node into [inbox], telling its [hooks] whom it heard
 * from and holding a first message's sender certificate. Its [acknowledger] sends each first message's
 * answer and each session's receipts through [receipts], and adopts the answers to its own first
 * messages: each opens a session, whose waiting messages its hooks release.
 *
 * It owns the public tags of every certificate whose [keys] it still holds (spec "Static-key
 * rotation"), the reply-to tags of its own first messages, which their answers come back to, and its
 * sessions' tags. It answers no route request from a route it knows: it has no tag of its own to
 * answer from.
 *
 * Runs on the node's serial dispatcher.
 */
internal class SecuredEnd(
    crypto: CryptoProvider,
    private val clock: Clock,
    private val inbox: Inbox,
    private val lookups: Lookups,
    private val receipts: Receipts<Reply>,
    private val hooks: SecuredHooks,
) : Origin {
    private val tags = EndToEndTags(Derivations(crypto))
    private val firsts = Firsts()
    private val conversations = Conversations { draft, epoch -> keys.codec.sourceTag(draft, epoch) }

    /** This node's static keys. */
    val keys = KeyRing(crypto, clock)

    /** This node's end-to-end sessions. */
    val sessions = Sessions()

    /**
     * [entry]'s message sealed with the current session to its destination; else as a first message, or
     * `null` while that can't be: see [Firsts.draft].
     */
    override fun draft(entry: Outbox.Entry): Draft? {
        val session = sessions.current(entry.to, clock.seconds())
        val sealed =
            session?.draft(entry.payload, entry.expiry.toUInt(), entry.readReceipt)?.also {
                sessions.sealed(session, it)
            }
        return sealed ?: firsts.draft(entry)
    }

    override fun destination(draft: Draft): ByteArray =
        when (val sealer = draft.sealer) {
            is Conversation -> sealer.destination(clock.epoch())
            else -> tags.publicTag(draft.destination, clock.epoch())
        }

    override fun origin(draft: Draft): ByteArray =
        when (val sealer = draft.sealer) {
            is Conversation -> sealer.source(clock.epoch())
            else -> keys.codec.sourceTag(draft, clock.epoch())
        }

    override fun frame(
        draft: Draft,
        hopLimit: Int,
    ): ByteArray =
        when (val sealer = draft.sealer) {
            is Conversation -> sealer.frame(draft, clock.epoch(), hopLimit)
            else -> keys.codec.frame(draft, clock.epoch(), hopLimit)
        }

    override fun owns(
        tag: ByteArray,
        nowSeconds: Long,
    ): Boolean =
        keys.owns(tag, nowSeconds) ||
            conversations.owns(tag, nowSeconds) ||
            sessions.owner(tag, nowSeconds) != null

    /** Opens a first message or session [frame] for this node; returns whether [inbox] took it. */
    override fun deliver(
        frame: ByteArray,
        nowSeconds: Long,
    ): Boolean = acknowledger.deliver(frame, nowSeconds)

    override val acknowledger: Receipting = Receipting()

    override fun carries(kind: Int): Boolean = kind != Container.UNSECURED

    override fun tag(): ByteArray? = null

    override fun found(tag: ByteArray) {
        hooks.found(tag)
    }

    /**
     * Whether [draft], sent [retries] timed retries after it was drafted without a receipt, lapsed: its
     * message is drafted anew for its next send. A message sealed with a session that got no receipt by
     * its second retry ends the session (spec "Sessions").
     */
    fun lapsed(
        draft: Draft,
        retries: Int,
    ): Boolean {
        val conversation = (draft.sealer as? Conversation)?.takeIf { retries >= LAPSE_RETRIES }
        conversation?.let { sessions.lapsed(it, draft.messageId) }
        return conversation != null
    }

    /** This node's first messages that wait for their answers, one per destination. */
    private inner class Firsts {
        private val outstanding = mutableMapOf<NodeId, Pair<Outbox.Entry, Draft>>()

        /**
         * [entry]'s message as a first message to its destination's certificate with this session's
         * keys; `null` while there are none, while the certificate is not held, which starts a lookup,
         * while another first message to the destination is outstanding, or if the certificate's keys
         * can't be sealed to.
         */
        fun draft(entry: Outbox.Entry): Draft? {
            val codec = keys.current() ?: return null
            val certificate = lookups.certificate(entry.to)
            outstanding.values.removeAll { it.first.done }
            if (certificate == null) lookups.find(entry.to, entry.deadlineMillis)
            val free = certificate?.takeIf { entry.to !in outstanding }
            val draft = free?.let { codec.draft(it, entry.payload, entry.expiry.toUInt(), entry.readReceipt) }
            draft?.let {
                outstanding[entry.to] = entry to it
                conversations.open(it)
            }
            return draft
        }

        /** Adopts the answer in [frame] to the first message [receipt] acknowledges, if one waits for it. */
        fun adopt(
            frame: ByteArray,
            receipt: DeliveryReceipt,
            nowSeconds: Long,
        ) {
            val waiting = outstanding.values.firstOrNull { (_, draft) -> receipt.acknowledges(draft) }
            val opening = waiting?.let { (_, draft) -> draft.sealer as Opening }
            val conversation = opening?.adopt(frame, nowSeconds) ?: return
            outstanding.values.remove(waiting)
            sessions.open(conversation, nowSeconds)
            val peer = NodeId(conversation.peer)
            receipts.arrived(peer, listOf(waiting.second.messageId))
            hooks.opened(peer)
        }

        private fun DeliveryReceipt.acknowledges(draft: Draft): Boolean {
            val id = draft.messageId
            return tombstones.any { it.messageId.contentEquals(id) }
        }
    }

    /**
     * The delivery and read receipts of this node: it acknowledges each message it opened in the message's
     * conversation, and takes the answers to its first messages and its sessions' receipts. Its read
     * receipts go in the current session with their destination once it is confirmed, else as first
     * messages to its certificate; it takes those that come either way.
     */
    inner class Receipting : Acknowledger {
        // The messages opened, by message_id, with how their receipts go, until they expire.
        private val opened = mutableMapOf<List<Byte>, Pair<Reply, Delivery>>()

        /** Opens a first message or session [frame] for this node; returns whether [inbox] took it. */
        fun deliver(
            frame: ByteArray,
            nowSeconds: Long,
        ): Boolean {
            val delivery = first(frame, nowSeconds) ?: transport(frame, nowSeconds)
            return delivery != null && take(delivery.first, delivery.second, nowSeconds)
        }

        override fun repeated(
            frame: ByteArray,
            nowSeconds: Long,
        ) {
            val messageId = (MessageFrame.decode(frame) as? Decoded.Valid)?.let { it.value.header.messageId }
            forget(nowSeconds)
            messageId?.let { opened[it.toList()] }?.let { (reply, delivery) ->
                receipts.again(reply, delivery.tombstone, delivery.expiry)
            }
        }

        override fun acknowledged(
            frame: ByteArray,
            nowSeconds: Long,
        ) {
            val receipt =
                when (val decoded = DeliveryReceipt.decode(frame)) {
                    is Decoded.Valid -> decoded.value
                    else -> return
                }
            if (receipt.container is Container.IkResponder) return firsts.adopt(frame, receipt, nowSeconds)
            val conversation = sessions.owner(receipt.header.destinationTag, nowSeconds)
            conversation?.acknowledged(frame, nowSeconds)?.let { messageIds ->
                sessions.answered(conversation, messageIds)
                receipts.arrived(NodeId(conversation.peer), messageIds)
            }
        }

        override fun read(
            frame: ByteArray,
            nowSeconds: Long,
        ) {
            val tag = (ReadReceipt.decode(frame) as? Decoded.Valid)?.let { it.value.header.destinationTag }
            val conversation = tag?.let { sessions.owner(it, nowSeconds) }
            val session = conversation?.read(frame, nowSeconds)?.let { Acknowledgement(conversation.peer, it) }
            val read = session ?: keys.read(frame, nowSeconds)
            read?.let { receipts.reads.arrived(NodeId(it.sender), it.messageIds) }
        }

        /**
         * Sends a read receipt from this node to [to] for [messageIds], expiring at Unix second [expiry]:
         * in the current session with [to] if confirmed, else as a first message to its certificate; none
         * without either, or before this node has keys. A session is confirmed once this node initiated
         * it or opened a frame of [to]'s in it: before that, [to] may never have had the answer that opens it.
         */
        fun readReceipt(
            to: NodeId,
            messageIds: List<ByteArray>,
            expiry: UInt,
        ) {
            val confirmed = sessions.current(to, clock.seconds())?.takeIf { it.initiator || it.received > 0 }
            val session = confirmed?.readReceipt(messageIds, expiry)
            val draft =
                session ?: keys.current()?.let { codec ->
                    lookups.certificate(to)?.let { codec.readReceipt(it, messageIds, expiry) }
                }
            draft?.let { hooks.send(Outgoing(it, clock::epoch)) }
        }

        /**
         * Sends the delivery receipt that goes as [reply] for [tombstones] with the latest [expiry]: a
         * first message's answer, whose session opens as it first goes, or a receipt sealed in the
         * session; none once the session's nonces run out.
         */
        fun reply(
            reply: Reply,
            tombstones: List<Tombstone>,
            expiry: UInt,
        ) {
            val conversation = reply.conversation
            val receipt =
                when (reply) {
                    is Reply.First -> reply.answer.also { if (!reply.answered) open(reply) }
                    is Reply.Session -> conversation.receipt(tombstones, expiry)
                }
            val epoch = clock.epoch()
            receipt?.let {
                val outgoing =
                    Outgoing(conversation.destination(epoch), conversation.source(epoch), it.messageId) { hopLimit ->
                        conversation.frame(it, clock.epoch(), hopLimit)
                    }
                hooks.send(outgoing)
            }
        }

        private fun open(reply: Reply.First) {
            reply.answered = true
            sessions.open(reply.conversation, clock.seconds())
            hooks.opened(NodeId(reply.conversation.peer))
        }

        private fun first(
            frame: ByteArray,
            nowSeconds: Long,
        ): Pair<Reply, Delivery>? =
            keys.accept(frame, nowSeconds)?.let {
                lookups.linked(NodeId(it.message.sender), it.certificate)
                Reply.First(it.conversation, it.answer) to it.message
            }

        private fun transport(
            frame: ByteArray,
            nowSeconds: Long,
        ): Pair<Reply, Delivery>? {
            val tag = (MessageFrame.decode(frame) as? Decoded.Valid)?.let { it.value.header.destinationTag }
            val conversation = tag?.let { sessions.owner(it, nowSeconds) }
            val delivery = conversation?.accept(frame, nowSeconds)
            return delivery?.let { Reply.Session(conversation) to it }
        }

        private fun take(
            reply: Reply,
            delivery: Delivery,
            nowSeconds: Long,
        ): Boolean {
            val sender = NodeId(delivery.sender)
            hooks.heard(sender)
            forget(nowSeconds)
            opened[delivery.tombstone.messageId.toList()] = reply to delivery
            val message = receipts.reads.message(sender, delivery)
            return inbox.offer(message) { receipts.taken(reply, delivery.tombstone, delivery.expiry) }
        }

        private fun forget(nowSeconds: Long) {
            opened.values.removeAll { (_, delivery) ->
                delivery.expiry.toLong() + UnsecuredMessaging.CLOCK_SKEW_SECONDS < nowSeconds
            }
        }
    }

    private companion object {
        // A session message with no receipt by its second retry lapses.
        const val LAPSE_RETRIES = 2
    }
}

/**
 * This node's static keys for first messages (spec "Static-key rotation"): the current session's, and
 * earlier sessions' through their grace.
 */
internal class KeyRing(
    private val crypto: CryptoProvider,
    private val clock: Clock,
) {
    private val keys = mutableListOf<Held>()

    /** The current keys' first messages. */
    val codec: FirstMessages get() = keys.first().codec

    /** This node's [keys] for a new session; those of earlier sessions stay while their grace lasts. */
    fun certify(keys: SecuredKeys) {
        val now = clock.seconds()
        this.keys.removeAll { it.until <= now }
        this.keys.add(0, Held(FirstMessages(crypto, clock, keys), keys.certificate.notAfter.toLong() + GRACE_SECONDS))
    }

    /** The current keys' first messages, or `null` before the first keys. */
    fun current(): FirstMessages? = keys.firstOrNull()?.codec

    /** Whether [tag] is the public tag of any keys held at Unix second [nowSeconds]. */
    fun owns(
        tag: ByteArray,
        nowSeconds: Long,
    ): Boolean = keys.any { it.codec.owns(tag, nowSeconds) }

    /** The first message for this node in [frame], opened with any keys held. */
    fun accept(
        frame: ByteArray,
        nowSeconds: Long,
    ): FirstDelivery? = keys.firstNotNullOfOrNull { it.codec.accept(frame, nowSeconds) }

    /** What the read receipt sent as a first message for this node in [frame] reports, opened with any keys held. */
    fun read(
        frame: ByteArray,
        nowSeconds: Long,
    ): Acknowledgement? = keys.firstNotNullOfOrNull { it.codec.read(frame, nowSeconds) }

    // A session's first-message [codec], whose keys are kept until Unix second [until].
    private class Held(
        val codec: FirstMessages,
        val until: Long,
    )

    private companion object {
        /** `static-key-grace`: how long after its certificate's `not_after` a node keeps a static key. */
        const val GRACE_SECONDS = 3_630L
    }
}

private const val MILLIS_PER_SECOND = 1_000L

private fun Clock.seconds(): Long = wallClockMillis() / MILLIS_PER_SECOND

private fun Clock.epoch(): UInt = Derivations.epoch(seconds())

/**
 * The conversations this node opened with its first messages, each until its message expires: it owns
 * their reply-to tags, made by [tag], for the epoch of now and either next to it.
 */
internal class Conversations(
    private val tag: (draft: Draft, epoch: UInt) -> ByteArray,
) {
    private val open = mutableListOf<Draft>()
    private var windowEpoch: UInt? = null
    private var window: List<ByteArray> = emptyList()

    /** This node sent the first message [draft] of a new conversation. */
    fun open(draft: Draft) {
        open += draft
        windowEpoch = null
    }

    /** Whether [tag] is the reply-to tag of an open conversation at Unix second [nowSeconds]. */
    fun owns(
        tag: ByteArray,
        nowSeconds: Long,
    ): Boolean {
        val epoch = Derivations.epoch(nowSeconds)
        val lapsed = open.removeAll { it.expiry.toLong() + UnsecuredMessaging.CLOCK_SKEW_SECONDS < nowSeconds }
        if (lapsed) windowEpoch = null
        if (epoch != windowEpoch) {
            window = open.flatMap { draft -> listOf(epoch - 1u, epoch, epoch + 1u).map { tag(draft, it) } }
            windowEpoch = epoch
        }
        return window.any { it.contentEquals(tag) }
    }
}

/**
 * The routing tags of other nodes for [RoutedPeers] in a secured mesh: the public tags of the
 * certificate [lookups] hold for a node, which lapses at its `not_after`.
 */
internal class SecuredPeerTags(
    crypto: CryptoProvider,
    private val lookups: Lookups,
) : PeerTags {
    private val tags = EndToEndTags(Derivations(crypto))

    override val secured: Boolean = true

    override fun key(id: NodeId): PeerKey? =
        lookups.certificate(id)?.let { PeerKey(it.x25519Key, it.notAfter.toLong() * MILLIS_PER_SECOND) }

    override fun tag(
        key: ByteArray,
        epoch: UInt,
    ): ByteArray = tags.publicTag(key, epoch)
}
