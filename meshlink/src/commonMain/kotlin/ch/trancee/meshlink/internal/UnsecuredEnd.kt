package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.ReceivedMessage

/**
 * This node's end of `unsecured` messages: drafts its messages, tags and frames each attempt for the
 * epoch it is written in (spec "Origin behavior"), and hands messages for it to [inbox], telling
 * whom it [heard] from. Hears of each route a route discovery [found].
 *
 * A [secured] mesh may not carry the unsecured container (spec "End-to-end"): it owns no tag, so it
 * takes no message and neither sends nor answers route requests, but still relays them.
 *
 * Runs on the node's serial dispatcher.
 */
internal class UnsecuredEnd(
    private val crypto: CryptoProvider,
    private val clock: Clock,
    private val inbox: Inbox,
    private val secured: Boolean,
    private val heard: (sender: NodeId) -> Unit,
    private val found: (tag: ByteArray) -> Unit,
) : Endpoint {
    private val derivations = Derivations(crypto)
    private lateinit var codec: UnsecuredMessaging
    private var nodeId = ByteArray(0)

    /** Starts a run of the node whose NodeId is [nodeId]. */
    fun begin(nodeId: ByteArray) {
        this.nodeId = nodeId
        codec = UnsecuredMessaging(crypto, nodeId)
    }

    /** A message of [payload] to [to] that expires at Unix second [expiry]. */
    fun draft(
        to: NodeId,
        payload: ByteArray,
        expiry: Long,
    ): Draft = codec.draft(to.toByteArray(), payload, expiry.toUInt(), Router.HOP_LIMIT_MAX)

    /** [to]'s routing tag for the current epoch. */
    fun tag(to: NodeId): ByteArray = derivations.unsecuredTag(to.toByteArray(), epoch())

    /** [draft]'s frame for the current epoch with [hopLimit]. */
    fun frame(
        draft: Draft,
        hopLimit: Int,
    ): ByteArray = codec.frame(draft, epoch(), hopLimit)

    override fun owns(
        tag: ByteArray,
        nowSeconds: Long,
    ): Boolean = !secured && codec.owns(tag, nowSeconds)

    /** Opens a `MESSAGE` [frame] for this node; returns whether [inbox] took it. */
    override fun deliver(
        frame: ByteArray,
        nowSeconds: Long,
    ): Boolean {
        val delivery = codec.accept(frame, nowSeconds) ?: return false
        val sender = NodeId(delivery.sender)
        heard(sender)
        return inbox.offer(ReceivedMessage(sender, delivery.payload))
    }

    /** This node's routing tag for the current epoch; `null` in a secured mesh. */
    override fun tag(): ByteArray? = if (secured) null else derivations.unsecuredTag(nodeId, epoch())

    override fun found(tag: ByteArray) {
        found.invoke(tag)
    }

    private fun epoch(): UInt = Derivations.epoch(clock.wallClockMillis() / MILLIS_PER_SECOND)

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
