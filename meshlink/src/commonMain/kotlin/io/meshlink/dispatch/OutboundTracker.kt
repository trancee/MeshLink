package io.meshlink.dispatch

import io.meshlink.crypto.ReplayGuard
import io.meshlink.util.ByteArrayKey

/**
 * Tracks outbound message state: which peer each message targets,
 * which next-hop is used for routed sends, and the monotonic replay
 * counter for routed messages.
 *
 * Consolidates three correlated maps and the replay guard that were
 * previously scattered across MeshLink fields.
 */
internal class OutboundTracker {
    private val recipients = mutableMapOf<ByteArrayKey, ByteArray>()
    private val nextHops = mutableMapOf<ByteArrayKey, ByteArrayKey>()
    private val replayGuard = ReplayGuard()

    /** Register a direct send: message key → recipient peerId. */
    fun registerRecipient(key: ByteArrayKey, recipient: ByteArray) {
        recipients[key] = recipient
    }

    /** Register a routed send: message key → next-hop peer ID. */
    fun registerNextHop(key: ByteArrayKey, nextHopId: ByteArrayKey) {
        nextHops[key] = nextHopId
    }

    /** Look up the recipient for a message key. */
    fun recipient(key: ByteArrayKey): ByteArray? = recipients[key]

    /** Remove and return the recipient for a message key. */
    fun removeRecipient(key: ByteArrayKey): ByteArray? = recipients.remove(key)

    /** Remove and return the next-hop ID for a message key. */
    fun removeNextHop(key: ByteArrayKey): ByteArrayKey? = nextHops.remove(key)

    /** Advance the replay counter and return the new value. */
    fun advanceReplayCounter(): ULong = replayGuard.advance()

    /** Snapshot of all tracked message keys (for iteration during shutdown). */
    fun allRecipientKeys(): Set<ByteArrayKey> = recipients.keys.toSet()

    /** Clear all tracked state. */
    fun clear() {
        recipients.clear()
        nextHops.clear()
    }
}
