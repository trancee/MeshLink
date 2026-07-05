package ch.trancee.meshlink.transport

// Bounded, increasing backoff schedule for automatic L2CAP reconnect attempts after a transient
// disconnect. Previously this guard allowed exactly one automatic retry per peer for the entire
// transport lifecycle (the retried-hint-id set was only ever cleared in stopTransports()) --
// on peers with genuinely flaky radio conditions (dense environments, marginal RF), that single
// retry was frequently not enough, and every subsequent transient disconnect fell straight through
// to a full PeerLost/rediscovery cycle instead of a quick reconnect. Allowing a small, capped
// number of increasingly-spaced retries gives transient conditions more chances to clear on their
// own before giving up, while the cap and backoff still bound how much time/radio activity is
// spent retrying a peer that is genuinely gone.
private val RECONNECT_BACKOFF_SCHEDULE_MILLIS: List<Long> = listOf(500L, 1_000L, 2_000L)

internal class L2capReconnectGuard(
    private val maxAttempts: Int = RECONNECT_BACKOFF_SCHEDULE_MILLIS.size
) {
    private val attemptCounts: MutableMap<String, Int> = linkedMapOf()

    internal fun shouldRetry(hintPeerIdValue: String, reason: String): Boolean {
        if (!reason.isTransientL2capDisconnect()) {
            return false
        }
        val attempts = attemptCounts.getOrElse(hintPeerIdValue) { 0 }
        if (attempts >= maxAttempts) {
            return false
        }
        attemptCounts[hintPeerIdValue] = attempts + 1
        return true
    }

    /** Backoff delay to use for the most recently approved retry of [hintPeerIdValue]. */
    internal fun backoffMillisFor(hintPeerIdValue: String): Long {
        val attempts = attemptCounts.getOrElse(hintPeerIdValue) { 1 }
        val scheduleIndex = (attempts - 1).coerceIn(0, RECONNECT_BACKOFF_SCHEDULE_MILLIS.lastIndex)
        return RECONNECT_BACKOFF_SCHEDULE_MILLIS[scheduleIndex]
    }

    /** Resets the retry budget once a peer reconnects successfully. */
    internal fun resetSuccess(hintPeerIdValue: String): Unit {
        attemptCounts.remove(hintPeerIdValue)
    }

    internal fun clear(): Unit {
        attemptCounts.clear()
    }
}

internal fun String.isTransientL2capDisconnect(): Boolean {
    return startsWith("socket closed") || startsWith("send failed:")
}
