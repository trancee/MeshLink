package ch.trancee.meshlink.internal

import ch.trancee.meshlink.DiagnosticEvent
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.Peer
import ch.trancee.meshlink.Reachability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Instant

/**
 * The peers a node reaches on a route (ADR 0007 decision 10, ADR 0008 amendment): of the last
 * [KNOWN_MAX] nodes it sent to or heard from, those with an active route found by route discovery
 * to one of their [tags] for the current epoch or either next to it. Each is listed in [roster] as
 * [Reachability.Routed] with the route's hop count, until the route expires or the key the tags
 * derive from lapses: in a secured mesh, only while a valid certificate of the node is held.
 *
 * Runs on [scope]'s serial dispatcher.
 */
internal class RoutedPeers(
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val routes: Routes,
    private val roster: Roster,
    private val tags: PeerTags,
    private val diagnostics: DiagnosticsHub,
) {
    // Each known node, the oldest first.
    private val known = LinkedHashMap<NodeId, Known>()
    private var listedIds = emptySet<NodeId>()
    private var expiry: Job? = null

    /** This node sends to [id]. */
    fun sending(id: NodeId) {
        remember(id, known[id]?.heard ?: 0)
    }

    /** A message from [id] arrived. */
    fun heard(id: NodeId) {
        remember(id, clock.wallClockMillis())
    }

    /** Lists the peers anew, and again when the first listed route expires or key lapses. */
    fun update() {
        expiry?.cancel()
        val now = clock.monotonicMillis()
        val wall = clock.wallClockMillis()
        val epoch = Derivations.epoch(wall / MILLIS_PER_SECOND)
        val listed =
            known.mapNotNull { (id, node) ->
                val key = tags.key(id)
                key?.let { route(node, it.bytes, epoch, now) }?.let { Listed(id, node.heard, it, key.untilMillis) }
            }
        roster.routed(
            listed.map { (id, heard, route) ->
                val lastSeen = Instant.fromEpochMilliseconds(maxOf(heard, route.heardMillis))
                Peer(id, Reachability.Routed(route.hops), tags.secured, lastSeen, proximity = null)
            },
        )
        val ids = listed.map { it.id }.toSet()
        listed.filter { it.id !in listedIds }.forEach {
            diagnostics.report(DiagnosticEvent.RouteFound(diagnostics.peer(it.id), it.route.hops))
        }
        (listedIds - ids).forEach { diagnostics.report(DiagnosticEvent.RouteLost(diagnostics.peer(it))) }
        listedIds = ids
        val first = listed.minOfOrNull { minOf(it.route.expiry - now, it.untilMillis - wall) } ?: return
        expiry =
            scope.launch {
                delay(first)
                update()
            }
    }

    /** The run ended: no node is known or listed. */
    fun end() {
        expiry?.cancel()
        known.clear()
        listedIds = emptySet()
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

    // The active route found by route discovery to the tag of [key] for [epoch], or else the one before or after.
    private fun route(
        node: Known,
        key: ByteArray,
        epoch: UInt,
        now: Long,
    ): Routes.Route? {
        val cached = node.tags?.takeIf { node.epoch == epoch && node.key.contentEquals(key) }
        val tags = cached ?: listOf(epoch, epoch - 1u, epoch + 1u).map { this.tags.tag(key, it) }
        node.epoch = epoch
        node.key = key
        node.tags = tags
        return tags.firstNotNullOfOrNull { tag -> routes.active(tag, now)?.takeIf { it.sequence != 0u } }
    }

    // When this node last heard a message from a known node by the wall clock, and its tags for [epoch]
    // derived from [key].
    private class Known(
        var heard: Long = 0,
        var epoch: UInt = 0u,
        var key: ByteArray = ByteArray(0),
        var tags: List<ByteArray>? = null,
    )

    // A listed peer: its route, and the wall-clock millisecond its key lapses.
    private data class Listed(
        val id: NodeId,
        val heard: Long,
        val route: Routes.Route,
        val untilMillis: Long,
    )

    companion object {
        /** The most nodes known. */
        const val KNOWN_MAX: Int = 1_024

        private const val MILLIS_PER_SECOND = 1_000L
    }
}

/** How [RoutedPeers] finds the routing tags of other nodes. */
internal interface PeerTags {
    /** Whether a routed peer's messages are end-to-end encrypted to its authenticated keys. */
    val secured: Boolean

    /** The key [id]'s tags derive from, or `null` while there is none. */
    fun key(id: NodeId): PeerKey?

    /** The tag of [key] for [epoch]. */
    fun tag(
        key: ByteArray,
        epoch: UInt,
    ): ByteArray
}

/** The key [bytes] a node's tags derive from, until the wall-clock millisecond [untilMillis]. */
internal class PeerKey(
    val bytes: ByteArray,
    val untilMillis: Long,
)
