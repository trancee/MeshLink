package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId

/**
 * A node's end-to-end sessions (spec "Sessions"; D-33, D-34), at most [capacity] of them: the least
 * recently used one is evicted beyond that. A peer's current session is the one this node seals new
 * frames with. A session ends after `e2e-session-max-age`, after `e2e-session-max-messages` frames
 * sealed or opened, when a message sealed with it lapses, or when it loses a crossing: of two sessions
 * with one peer, the one whose initiator has the lower NodeId stays, or the later one if both have the
 * same initiator. An ended session seals nothing more but opens the peer's frames until each message
 * it sealed is answered or expires.
 *
 * Runs on the node's serial dispatcher.
 */
internal class Sessions(
    private val capacity: Int = CAPACITY,
) {
    // Least recently used first.
    private val records = mutableListOf<Record>()

    /** [conversation] opened at Unix second [nowSeconds]; settles a crossing with the peer's current session. */
    fun open(
        conversation: Conversation,
        nowSeconds: Long,
    ) {
        if (records.any { it.conversation === conversation }) return
        val opened = Record(conversation, nowSeconds)
        records.firstOrNull { !it.ended && it.peer == opened.peer }?.let { current ->
            val earlier = LinkPolicy.compareNodeIds(current.initiator, conversation.initiatorNodeId) < 0
            end(if (earlier) opened else current)
        }
        records += opened
        if (records.size > capacity) records.removeAt(0)
    }

    /** The session this node seals new frames to [peer] with at Unix second [nowSeconds], if any. */
    fun current(
        peer: NodeId,
        nowSeconds: Long,
    ): Conversation? {
        prune(nowSeconds)
        return records.firstOrNull { !it.ended && it.peer == peer }?.let(::use)
    }

    /** The session whose peer's frames come to [tag] at Unix second [nowSeconds], if any. */
    fun owner(
        tag: ByteArray,
        nowSeconds: Long,
    ): Conversation? {
        prune(nowSeconds)
        return records.firstOrNull { it.conversation.owns(tag, nowSeconds) }?.let(::use)
    }

    /** [conversation] sealed the message [draft], which waits for its receipt. */
    fun sealed(
        conversation: Conversation,
        draft: Draft,
    ) {
        records.firstOrNull { it.conversation === conversation }?.let { it.outstanding.add(draft) }
    }

    /** A receipt answered the messages of [messageIds], in whichever session each was sealed. */
    fun answered(messageIds: List<ByteArray>) {
        settle { draft -> messageIds.any { it.contentEquals(draft.messageId) } }
    }

    /**
     * A message sealed with [conversation] got no receipt in time: the session ends, but still opens the
     * peer's late receipt for it until it is [answered] or expires.
     */
    fun lapsed(conversation: Conversation) {
        records.firstOrNull { it.conversation === conversation }?.let(::end)
    }

    /** Forgets every session. */
    fun clear() {
        records.clear()
    }

    private fun use(record: Record): Conversation {
        records -= record
        records += record
        return record.conversation
    }

    private fun end(record: Record) {
        record.ended = true
    }

    // Drops the messages that [match], then each ended session with none left.
    private fun settle(match: (Draft) -> Boolean) {
        records.forEach { it.outstanding.removeAll(match) }
        records.removeAll { it.ended && it.outstanding.isEmpty() }
    }

    private fun prune(nowSeconds: Long) {
        records.forEach { record ->
            record.outstanding.removeAll { it.expiry.toLong() + UnsecuredMessaging.CLOCK_SKEW_SECONDS < nowSeconds }
            val conversation = record.conversation
            val worn = maxOf(conversation.sent, conversation.received) >= MAX_FRAMES
            if (worn || nowSeconds - record.openedSeconds >= MAX_AGE_SECONDS) end(record)
        }
        records.removeAll { it.ended && it.outstanding.isEmpty() }
    }

    private class Record(
        val conversation: Conversation,
        val openedSeconds: Long,
    ) {
        val peer = NodeId(conversation.peer)
        val initiator = conversation.initiatorNodeId
        val outstanding = mutableListOf<Draft>()
        var ended = false
    }

    companion object {
        /** The session cache size until ticket 53 sets one per preset. */
        const val CAPACITY: Int = 64

        // e2e-session-max-age.
        private const val MAX_AGE_SECONDS = 21_600L

        // e2e-session-max-messages.
        private const val MAX_FRAMES = 4_096
    }
}
