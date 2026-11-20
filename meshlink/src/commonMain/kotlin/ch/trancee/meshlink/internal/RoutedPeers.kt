package ch.trancee.meshlink.internal

import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.Peer
import ch.trancee.meshlink.Reachability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Instant

/**
 * The peers an `unsecured` node reaches on a route (ADR 0007 decision 10, ADR 0008 amendment): of the
 * last [KNOWN_MAX] nodes it sent to or heard from, those with an active route found by route
 * discovery to their tag for the current epoch or either next to it. Each is listed in [roster] as
 * [Reachability.Routed] with the route's hop count, until the route expires.
 *
 * Runs on [scope]'s serial dispatcher.
 */
internal class RoutedPeers(
    crypto: CryptoProvider,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val routes: Routes,
    private val roster: Roster,
) {
    private val derivations = Derivations(crypto)

    // Each known node, the oldest first.
    private val known = LinkedHashMap<NodeId, Known>()
    private var expiry: Job? = null

    /** This node sends to [id]. */
    fun sending(id: NodeId) {
        remember(id, known[id]?.heard ?: 0)
    }

    /** A message from [id] arrived. */
    fun heard(id: NodeId) {
        remember(id, clock.wallClockMillis())
    }

    /** Lists the peers anew, and again when the first listed route expires. */
    fun update() {
        expiry?.cancel()
        val now = clock.monotonicMillis()
        val epoch = Derivations.epoch(clock.wallClockMillis() / MILLIS_PER_SECOND)
        val listed = known.mapNotNull { (id, node) -> route(id, node, epoch, now)?.let { Triple(id, node.heard, it) } }
        roster.routed(
            listed.map { (id, heard, route) ->
                val lastSeen = Instant.fromEpochMilliseconds(maxOf(heard, route.heardMillis))
                Peer(id, Reachability.Routed(route.hops), secured = false, lastSeen, proximity = null)
            },
        )
        val first = listed.minOfOrNull { it.third.expiry } ?: return
        expiry =
            scope.launch {
                delay(first - now)
                update()
            }
    }

    /** The run ended: no node is known or listed. */
    fun end() {
        expiry?.cancel()
        known.clear()
        roster.routed(emptyList())
    }

    private fun remember(
        id: NodeId,
        heard: Long,
    ) {
        val node = known.remove(id) ?: Known()
        known[id] = node.also { it.heard = heard }
        if (known.size > KNOWN_MAX) known -= known.keys.first()
        update()
    }

    // The active route found by route discovery to [id]'s tag for [epoch], or else the one before or after.
    private fun route(
        id: NodeId,
        node: Known,
        epoch: UInt,
        now: Long,
    ): Routes.Route? {
        val tags =
            node.tags?.takeIf { node.epoch == epoch }
                ?: listOf(epoch, epoch - 1u, epoch + 1u).map { derivations.unsecuredTag(id.toByteArray(), it) }
        node.epoch = epoch
        node.tags = tags
        return tags.firstNotNullOfOrNull { tag -> routes.active(tag, now)?.takeIf { it.sequence != 0u } }
    }

    // When this node last heard a message from a known node by the wall clock, and its tags for [epoch].
    private class Known(
        var heard: Long = 0,
        var epoch: UInt = 0u,
        var tags: List<ByteArray>? = null,
    )

    companion object {
        /** The most nodes known. */
        const val KNOWN_MAX: Int = 1_024

        private const val MILLIS_PER_SECOND = 1_000L
    }
}
