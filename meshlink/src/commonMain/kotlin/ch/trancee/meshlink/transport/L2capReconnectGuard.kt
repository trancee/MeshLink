package ch.trancee.meshlink.transport

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

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

@OptIn(ExperimentalAtomicApi::class)
internal class L2capReconnectGuard(
    private val maxAttempts: Int = RECONNECT_BACKOFF_SCHEDULE_MILLIS.size
) {
    // L2capReconnectGuard is commonMain and shared with the iOS L2CAP transport support, unlike
    // the Android-only PeerRegistry/BleTransportLinkRegistry classes this fix otherwise mirrors --
    // `synchronized(lock)` is a JVM/Android-only Kotlin intrinsic and does not compile for the
    // iOS/Native target. attemptCounts is instead guarded by an immutable-map compare-and-swap
    // loop over an AtomicReference, following the same established pattern already used by
    // PowerPolicyController's AtomicReference<T>.compareAndSetLoop in this same module, so
    // concurrent connect/disconnect paths for different peers can no longer corrupt or silently
    // reset each other's retry budget on any platform.
    private val attemptCounts = AtomicReference<Map<String, Int>>(emptyMap())

    internal fun shouldRetry(hintPeerIdValue: String, reason: String): Boolean {
        if (!reason.isTransientL2capDisconnect()) {
            return false
        }
        var retryApproved = false
        attemptCounts.compareAndSetLoop { current ->
            val attempts = current.getOrElse(hintPeerIdValue) { 0 }
            if (attempts >= maxAttempts) {
                retryApproved = false
                current
            } else {
                retryApproved = true
                current + (hintPeerIdValue to attempts + 1)
            }
        }
        return retryApproved
    }

    /** Backoff delay to use for the most recently approved retry of [hintPeerIdValue]. */
    internal fun backoffMillisFor(hintPeerIdValue: String): Long {
        val attempts = attemptCounts.load().getOrElse(hintPeerIdValue) { 1 }
        val scheduleIndex = (attempts - 1).coerceIn(0, RECONNECT_BACKOFF_SCHEDULE_MILLIS.lastIndex)
        return RECONNECT_BACKOFF_SCHEDULE_MILLIS[scheduleIndex]
    }

    /** Resets the retry budget once a peer reconnects successfully. */
    internal fun resetSuccess(hintPeerIdValue: String): Unit {
        attemptCounts.compareAndSetLoop { current -> current - hintPeerIdValue }
    }

    internal fun clear(): Unit {
        attemptCounts.compareAndSetLoop { emptyMap() }
    }
}

@OptIn(ExperimentalAtomicApi::class)
private fun <T> AtomicReference<T>.compareAndSetLoop(transform: (T) -> T): T {
    while (true) {
        val previous = load()
        val next = transform(previous)
        if (compareAndSet(previous, next)) {
            return next
        }
    }
}

internal fun String.isTransientL2capDisconnect(): Boolean {
    return startsWith("socket closed") ||
        startsWith("send failed:") ||
        startsWith("connect failed:")
}
