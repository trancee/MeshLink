@file:OptIn(ExperimentalObjCRefinement::class)

package ch.trancee.meshlink

import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import ch.trancee.meshlink.internal.CryptoProvider
import ch.trancee.meshlink.internal.Derivations
import ch.trancee.meshlink.internal.Discovery
import ch.trancee.meshlink.internal.InstallationIdentity
import ch.trancee.meshlink.internal.LinkPolicy
import ch.trancee.meshlink.internal.Links
import ch.trancee.meshlink.internal.PeerHandle
import ch.trancee.meshlink.internal.PlatformPort
import ch.trancee.meshlink.internal.RadioFailure
import ch.trancee.meshlink.internal.RadioRegistry
import ch.trancee.meshlink.internal.Scanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC

/**
 * A MeshLink node: one member of a Bluetooth Low Energy mesh (ADR 0007 decisions 1 and 2).
 *
 * Create it with [create], then [start] and [stop] it as often as needed and [close] it when done.
 * Every function can be called from any thread; the node does its work on its own dispatcher.
 */
public class MeshLink private constructor(
    /** The settings this node was created with. */
    public val config: MeshLinkConfig,
    private val port: PlatformPort,
    private val crypto: CryptoProvider,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + port.dispatcher)
    private val mutableState = MutableStateFlow<MeshState>(MeshState.Stopped)
    private val closed = MutableStateFlow(false)

    // Guards watcher; start and stop run on port.dispatcher.
    private val lifecycle = Mutex()
    private var watcher: Job? = null

    // Set by the first start, before the watcher runs; read only by sessions.
    private var nodeId = ByteArray(0)

    private val meshId = config.meshId.encodeToByteArray()
    private val scanner = Scanner(port.radio, port.clock, Discovery(crypto, meshId, config.securityProfile.wireProfile))
    private val mutablePeers = MutableStateFlow<List<Peer>>(emptyList())

    /** The lifecycle state; it starts as [MeshState.Stopped]. */
    public val state: StateFlow<MeshState> = mutableState.asStateFlow()

    /**
     * The peers this node has a link to, ordered by NodeId; empty while the node is not running. A
     * peer leaves the list when its last link closes, whether it closed the link, stopped, or went
     * out of range.
     */
    public val peers: StateFlow<List<Peer>> = mutablePeers.asStateFlow()

    /**
     * Starts the node and returns once it is [MeshState.Running] or [MeshState.Blocked]. A blocked
     * node moves to running by itself. Does nothing if the node is already started. On the first
     * start on a platform, creates the installation identity.
     *
     * @throws MeshLinkException.Closed if the node is closed.
     */
    @Throws(MeshLinkException::class, CancellationException::class)
    public suspend fun start() {
        ensureOpen()
        withContext(port.dispatcher) {
            lifecycle.withLock {
                if (watcher == null) {
                    mutableState.value = MeshState.Starting
                    nodeId = InstallationIdentity.loadOrCreate(port.identityStore, crypto).nodeId
                    watcher = scope.launch { watch() }
                    mutableState.first { it != MeshState.Starting }
                }
            }
        }
    }

    /**
     * Stops the node and returns once it is [MeshState.Stopped]. Does nothing if the node is
     * already stopped.
     *
     * @throws MeshLinkException.Closed if the node is closed.
     */
    @Throws(MeshLinkException::class, CancellationException::class)
    public suspend fun stop() {
        ensureOpen()
        withContext(port.dispatcher) {
            lifecycle.withLock {
                watcher?.let {
                    mutableState.value = MeshState.Stopping
                    it.cancelAndJoin()
                    watcher = null
                    mutableState.value = MeshState.Stopped
                }
            }
        }
    }

    /**
     * Stops the node for good and frees its radio for another node. Afterwards [start] and [stop]
     * throw [MeshLinkException.Closed]; calling [close] again does nothing.
     */
    override fun close() {
        if (closed.compareAndSet(expect = false, update = true)) {
            scope.cancel()
            RadioRegistry.release(port, this)
            mutableState.value = MeshState.Stopped
        }
    }

    /** The peers this node found on the air and may link to next; for `meshlink-testing`. */
    @InternalMeshLinkApi
    @HiddenFromObjC
    public fun linkCandidates(): Set<PeerHandle> = scanner.candidates.value.keys

    /** Why the radio last refused to advertise or scan, or `null`; for `meshlink-testing`. */
    @InternalMeshLinkApi
    @HiddenFromObjC
    public fun radioFailure(): RadioFailure? = scanner.failure

    private fun ensureOpen() {
        if (closed.value) throw MeshLinkException.Closed()
    }

    /**
     * Holds the radio when it is free and keeps [state] at running or blocked until cancelled, and
     * discovers and links to peers while running. Each write expects the state the watcher wrote last, so it
     * never overwrites a concurrent [close].
     */
    private suspend fun watch() {
        var written: MeshState = MeshState.Starting
        try {
            combine(port.requirements, RadioRegistry.holders, ::Pair)
                .map { (missing, holders) ->
                    val holder = holders[port]
                    val holding = holder === this || (holder == null && RadioRegistry.tryAcquire(port, this, holders))
                    val blockers = if (holding) missing else missing + Requirement.RadioInUse
                    val next = if (blockers.isEmpty()) MeshState.Running else MeshState.Blocked(blockers)
                    mutableState.compareAndSet(written, next)
                    written = next
                    next == MeshState.Running
                }.distinctUntilChanged()
                .collectLatest { running -> if (running) session() }
        } finally {
            RadioRegistry.release(port, this)
        }
    }

    /** Discovers peers and links to them until cancelled or the radio refuses discovery. */
    private suspend fun session() =
        coroutineScope {
            val digest = Derivations(crypto).meshIdDigest(meshId, config.securityProfile.wireProfile)
            val links = Links(port.radio, port.clock, LinkPolicy(digest, nodeId), nodeId, this, mutablePeers)
            val linking = launch { scanner.candidates.collect { links.connectTo(it.keys) } }
            scanner.run(links::accept)
            linking.cancel()
        }

    /** Creates nodes. */
    public companion object {
        /** Creates a stopped node with [config] on [platform]. Doesn't touch the radio. */
        public fun create(
            config: MeshLinkConfig,
            platform: MeshPlatform,
        ): MeshLink = MeshLink(config, platform.port, SodiumCryptoProvider)
    }
}
