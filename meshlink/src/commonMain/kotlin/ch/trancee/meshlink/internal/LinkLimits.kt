package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DiagnosticEvent
import kotlinx.coroutines.delay

/**
 * A node's connect retries and peripheral link cap (spec "Connect retries and link caps"; ADR 0009
 * decisions 4 and 6): retries a transient connect failure, counts the bearers held as peripheral
 * against [RadioPort.peripheralLinkCap], remembers the candidates that refused this node for their
 * cap, and names the central links to reconnect after a transient loss. Not thread-safe: [Links]
 * calls it on the node's serial dispatcher.
 */
internal class LinkLimits(
    private val radio: RadioPort,
    private val diagnostics: DiagnosticsHub,
) {
    // A platform's cap never changes while the node runs.
    private val cap = radio.peripheralLinkCap

    private var held = 0

    // Candidates that closed this node's bearer with RESOURCE_LIMIT before its link opened.
    private val refused = mutableSetOf<PeerHandle>()

    // This node's open central links and the address each was opened to, until their disconnect.
    private val centrals = mutableMapOf<RadioLink, PeerHandle>()

    /** The [candidates] to dial; forgets the refusals of candidates no longer among them. */
    fun dialable(candidates: Set<PeerHandle>): List<PeerHandle> {
        refused.retainAll(candidates)
        return candidates.filter { it !in refused }
    }

    /**
     * Connects to [handle] as central, retrying a [RadioFailure.TRANSIENT] failure up to
     * [CONNECT_RETRIES] times, [RETRY_BACKOFF_MILLIS] × n before retry n; reports each failure.
     */
    suspend fun connect(handle: PeerHandle): CentralLink? {
        val ref = diagnostics.link()
        var attempt = 1
        while (true) {
            when (val connected = radio.connect(handle)) {
                is RadioResult.Ok -> {
                    centrals[connected.value] = handle
                    return connected.value
                }

                is RadioResult.Failed -> {
                    diagnostics.report(DiagnosticEvent.ConnectFailed(ref, connected.reason.connectReason(), attempt))
                    if (connected.reason != RadioFailure.TRANSIENT || attempt > CONNECT_RETRIES) return null
                    delay(RETRY_BACKOFF_MILLIS * attempt)
                    attempt++
                }
            }
        }
    }

    /** Whether the peripheral bearer a central just opened fits the cap; counts it if so. */
    fun admit(): Boolean {
        if (cap != null && held >= cap) {
            diagnostics.report(DiagnosticEvent.LinkCapReached(cap))
            return false
        }
        held++
        return true
    }

    /** A bearer [admit] counted has closed. */
    fun released() {
        held--
    }

    /** Answers a bearer beyond the cap with `CLOSE` `RESOURCE_LIMIT`, before any other frame, and drops it. */
    suspend fun refuse(link: RadioLink) {
        try {
            link.write(LinkRecord.encode(LinkFrame.Close(CloseReason.RESOURCE_LIMIT).encode()))
        } finally {
            link.disconnect()
        }
    }

    /** [handle] refused this node's bearer for its cap; this node won't dial it while it stays a candidate. */
    fun refusedBy(handle: PeerHandle) {
        refused += handle
    }

    /** The address to reconnect to after [event], if it closed one of this node's central links transiently. */
    fun lost(event: RadioEvent.Disconnected): PeerHandle? = centrals.remove(event.link)?.takeIf { event.transient }

    companion object {
        /** How often a transient connect failure is retried (spec `connect-retries`). */
        const val CONNECT_RETRIES: Int = 3

        /** The wait before retry n is n times this (spec `connect-retry-backoff`). */
        const val RETRY_BACKOFF_MILLIS: Long = 500
    }
}
