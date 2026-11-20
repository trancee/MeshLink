package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.ReceivedMessage

/**
 * This node's end of `unsecured` messages: drafts its messages, tags and frames each attempt for the
 * epoch it is written in (spec "Origin behavior"), and hands messages for it to [inbox].
 *
 * Runs on the node's serial dispatcher.
 */
internal class UnsecuredEnd(
    private val crypto: CryptoProvider,
    private val clock: Clock,
    private val inbox: Inbox,
) {
    private val derivations = Derivations(crypto)
    private lateinit var codec: UnsecuredMessaging

    /** Starts a run of the node whose NodeId is [nodeId]. */
    fun begin(nodeId: ByteArray) {
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

    /** Whether [tag] is this node's at Unix second [nowSeconds]. */
    fun owns(
        tag: ByteArray,
        nowSeconds: Long,
    ): Boolean = codec.owns(tag, nowSeconds)

    /** Opens a `MESSAGE` [frame] for this node; returns whether [inbox] took it. */
    fun deliver(
        frame: ByteArray,
        nowSeconds: Long,
    ): Boolean {
        val delivery = codec.accept(frame, nowSeconds) ?: return false
        return inbox.offer(ReceivedMessage(NodeId(delivery.sender), delivery.payload))
    }

    private fun epoch(): UInt = Derivations.epoch(clock.wallClockMillis() / MILLIS_PER_SECOND)

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
