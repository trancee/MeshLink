package ch.trancee.meshlink.fake

import ch.trancee.meshlink.MessageId
import ch.trancee.meshlink.NodeId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * In-memory medium that links several MeshLink nodes in one process, with a configurable
 * topology, per-link RSSI and per-host radio toggles. Test support for the prototype only; not
 * part of the product API.
 *
 * All behavior runs on one serial view of [dispatcher]; pass a test dispatcher for virtual time.
 */
@OptIn(ExperimentalAtomicApi::class)
public class FakeMesh(dispatcher: CoroutineDispatcher, internal val clock: Clock) {
    /** A medium on [Dispatchers.Default] with the system clock, for Swift tests. */
    public constructor() : this(Dispatchers.Default, Clock.System)

    internal val serial: CoroutineDispatcher = dispatcher.limitedParallelism(1)
    internal val scope: CoroutineScope = CoroutineScope(SupervisorJob() + serial)

    private val hosts = AtomicReference(emptyList<FakeHost>())
    private val links = mutableMapOf<Set<FakeHost>, Link>()
    private val messageIds = AtomicLong(0)

    internal class Link(var smoothedRssiDbm: Int, var measuredAt: Instant)

    /** Adds a simulated phone named [name]; its node identity is derived from the name. */
    public fun addHost(name: String): FakeHost {
        val host = FakeHost(this, name)
        while (true) {
            val current = hosts.load()
            require(current.none { it.name == name }) { "Duplicate host name: $name" }
            if (hosts.compareAndSet(current, current + host)) return host
        }
    }

    /** Links [first] and [second] directly with an initial received signal strength. */
    @Throws(CancellationException::class)
    public suspend fun link(first: FakeHost, second: FakeHost, rssiDbm: Int) {
        onMedium {
            links[setOf(first, second)] = Link(rssiDbm, clock.now())
            topologyChanged()
        }
    }

    /** Removes the direct link between [first] and [second]. */
    @Throws(CancellationException::class)
    public suspend fun unlink(first: FakeHost, second: FakeHost) {
        onMedium {
            links.remove(setOf(first, second))
            topologyChanged()
        }
    }

    /** Feeds one RSSI sample into the link's exponential smoothing (factor ½). */
    @Throws(CancellationException::class)
    public suspend fun reportRssi(first: FakeHost, second: FakeHost, rssiDbm: Int) {
        onMedium {
            val link = checkNotNull(links[setOf(first, second)]) { "Not linked: ${first.name}–${second.name}" }
            link.smoothedRssiDbm = (link.smoothedRssiDbm + rssiDbm) / 2
            link.measuredAt = clock.now()
            topologyChanged()
        }
    }

    /** Closes every node and stops the medium. */
    public fun close() {
        hosts.load().forEach { it.engine?.close() }
        scope.cancel()
    }

    internal suspend fun <T> onMedium(block: () -> T): T = withContext(serial) { block() }

    internal fun nextMessageId(): MessageId = MessageId(messageIds.fetchAndIncrement())

    internal fun link(first: FakeHost, second: FakeHost): Link? = links[setOf(first, second)]

    internal fun nodes(): List<FakeNode> = hosts.load().mapNotNull { host -> host.engine?.takeIf { !it.isClosed } }

    internal fun node(id: NodeId): FakeNode? = nodes().firstOrNull { it.localNodeId == id }

    /** Direct neighbors of an active node that belong to the same mesh. */
    internal fun neighbors(node: FakeNode): List<FakeNode> = nodes().filter { other ->
        other !== node && other.isActive && link(node.host, other.host) != null && other.config.sameMeshAs(node.config)
    }

    /** Shortest hop count from [origin] to every reachable node of its mesh. */
    internal fun hopsFrom(origin: FakeNode): Map<FakeNode, Int> {
        if (!origin.isActive) return emptyMap()
        val hops = mutableMapOf(origin to 0)
        val frontier = ArrayDeque(listOf(origin))
        while (frontier.isNotEmpty()) {
            val current = frontier.removeFirst()
            for (next in neighbors(current)) {
                if (next !in hops) {
                    hops[next] = hops.getValue(current) + 1
                    frontier.addLast(next)
                }
            }
        }
        return hops - origin
    }

    internal fun topologyChanged() {
        val nodes = nodes()
        nodes.forEach { it.refreshPeers() }
        nodes.forEach { it.reportMeshMismatches() }
        nodes.forEach { it.retryPending() }
    }
}
