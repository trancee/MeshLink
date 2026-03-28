package io.meshlink.dispatch

import io.meshlink.crypto.ReplayGuard

/**
 * Tracks outbound message state: which peer each message targets,
 * which next-hop is used for routed sends, and the monotonic replay
 * counter for routed messages.
 *
 * Consolidates three correlated maps and the replay guard that were
 * previously scattered across MeshLink fields.
 */
class OutboundTracker {
    private val recipients = mutableMapOf<String, ByteArray>()
    private val nextHops = mutableMapOf<String, String>()
    private val replayGuard = ReplayGuard()

    /** Register a direct send: message key → recipient peerId. */
    fun registerRecipient(key: String, recipient: ByteArray) {
        recipients[key] = recipient
    }

    /** Register a routed send: message key → next-hop peer hex. */
    fun registerNextHop(key: String, nextHopHex: String) {
        nextHops[key] = nextHopHex
    }

    /** Look up the recipient for a message key. */
    fun recipient(key: String): ByteArray? = recipients[key]

    /** Remove and return the recipient for a message key. */
    fun removeRecipient(key: String): ByteArray? = recipients.remove(key)

    /** Remove and return the next-hop hex for a message key. */
    fun removeNextHop(key: String): String? = nextHops.remove(key)

    /** Advance the replay counter and return the new value. */
    fun advanceReplayCounter(): ULong = replayGuard.advance()

    /** Snapshot of all tracked message keys (for iteration during shutdown). */
    fun allRecipientKeys(): Set<String> = recipients.keys.toSet()

    /** Clear all tracked state. */
    fun clear() {
        recipients.clear()
        nextHops.clear()
    }
}
