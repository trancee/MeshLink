package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.ReceivedMessage

/**
 * This node's end of a secured mesh's messages (spec "First messages" and "Routing tags"): seals each
 * message as a first message to its destination's certificate, which [lookups] hold or look up, with
 * one first message outstanding per destination; tags and frames each attempt for the epoch it is
 * written in; and opens the first messages for this node into [inbox], telling whom it [heard] from
 * and holding the sender's certificate. Hears of each route a route discovery [found].
 *
 * It owns the public tags of every certificate whose keys it still holds (spec "Static-key rotation")
 * and the reply-to tags of its own messages' conversations, whose route replies and replies come back
 * there. It answers no route request from a route it knows: it has no tag of its own to answer from.
 *
 * Runs on the node's serial dispatcher.
 */
internal class SecuredEnd(
    private val crypto: CryptoProvider,
    private val clock: Clock,
    private val inbox: Inbox,
    private val lookups: Lookups,
    private val heard: (sender: NodeId) -> Unit,
    private val found: (tag: ByteArray) -> Unit,
) : Origin {
    private val tags = EndToEndTags(Derivations(crypto))
    private val keys = mutableListOf<Held>()
    private val firsts = mutableMapOf<NodeId, Outbox.Entry>()
    private val conversations = Conversations { draft, epoch -> keys.first().codec.sourceTag(draft, epoch) }

    /** This node's [keys] for a new session; those of earlier sessions stay while their grace lasts. */
    fun certify(keys: SecuredKeys) {
        val now = clock.wallClockMillis() / MILLIS_PER_SECOND
        this.keys.removeAll { it.until <= now }
        this.keys.add(0, Held(FirstMessages(crypto, clock, keys), keys.certificate.notAfter.toLong() + GRACE_SECONDS))
    }

    /**
     * [entry]'s message sealed to its destination's certificate with this session's keys; `null` while
     * there are none, while the certificate is not held, which starts a lookup, while another first
     * message to the destination is outstanding, or if the certificate's keys can't be sealed to.
     */
    override fun draft(entry: Outbox.Entry): Draft? {
        val held = keys.firstOrNull() ?: return null
        val certificate = lookups.certificate(entry.to)
        firsts.values.removeAll { it.done }
        if (certificate == null) lookups.find(entry.to, entry.deadlineMillis)
        val free = certificate?.takeIf { entry.to !in firsts }
        val draft = free?.let { held.codec.draft(it, entry.payload, entry.expiry.toUInt(), readReceipt = false) }
        draft?.let {
            firsts[entry.to] = entry
            conversations.open(it)
        }
        return draft
    }

    override fun destination(draft: Draft): ByteArray = tags.publicTag(draft.destination, epoch())

    override fun origin(draft: Draft): ByteArray = keys.first().codec.sourceTag(draft, epoch())

    override fun frame(
        draft: Draft,
        hopLimit: Int,
    ): ByteArray = keys.first().codec.frame(draft, epoch(), hopLimit)

    override fun owns(
        tag: ByteArray,
        nowSeconds: Long,
    ): Boolean = keys.any { it.codec.owns(tag, nowSeconds) } || conversations.owns(tag, nowSeconds)

    /** Opens a first [frame] for this node; returns whether [inbox] took it. */
    override fun deliver(
        frame: ByteArray,
        nowSeconds: Long,
    ): Boolean {
        val delivery = keys.firstNotNullOfOrNull { it.codec.accept(frame, nowSeconds) } ?: return false
        val sender = NodeId(delivery.sender)
        lookups.linked(sender, delivery.certificate)
        heard(sender)
        return inbox.offer(ReceivedMessage(sender, delivery.payload, broadcast = false))
    }

    override fun carries(kind: Int): Boolean = kind in SECURED_KINDS

    override fun tag(): ByteArray? = null

    override fun found(tag: ByteArray) {
        found.invoke(tag)
    }

    private fun epoch(): UInt = Derivations.epoch(clock.wallClockMillis() / MILLIS_PER_SECOND)

    // A session's first-message [codec], whose keys are kept until Unix second [until].
    private class Held(
        val codec: FirstMessages,
        val until: Long,
    )

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L

        /** `static-key-grace`: how long after its certificate's `not_after` a node keeps a static key. */
        const val GRACE_SECONDS = 3_630L
    }
}

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

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}

private val SECURED_KINDS = setOf(Container.IK_INITIATOR, Container.TRANSPORT)
