@file:OptIn(ExperimentalObjCRefinement::class)

package ch.trancee.meshlink

import ch.trancee.meshlink.crypto.sodium.SodiumCryptoProvider
import ch.trancee.meshlink.internal.CryptoProvider
import ch.trancee.meshlink.internal.Derivations
import ch.trancee.meshlink.internal.Discovery
import ch.trancee.meshlink.internal.Handshakes
import ch.trancee.meshlink.internal.InstallationIdentity
import ch.trancee.meshlink.internal.LinkAdmission
import ch.trancee.meshlink.internal.LinkCredentials
import ch.trancee.meshlink.internal.LinkDiagnostic
import ch.trancee.meshlink.internal.LinkPolicy
import ch.trancee.meshlink.internal.LinkRole
import ch.trancee.meshlink.internal.Links
import ch.trancee.meshlink.internal.PeerHandle
import ch.trancee.meshlink.internal.PlatformPort
import ch.trancee.meshlink.internal.Post
import ch.trancee.meshlink.internal.RadioFailure
import ch.trancee.meshlink.internal.RadioRegistry
import ch.trancee.meshlink.internal.Scanner
import ch.trancee.meshlink.internal.WireProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    // Set by each start that loads it, before the watcher runs; read only by sessions.
    private lateinit var identity: InstallationIdentity

    private val meshId = config.meshId.encodeToByteArray()
    private val profile = config.securityProfile.wireProfile
    private val meshKey = config.meshKey
    private val scanner = Scanner(port.radio, port.clock, Discovery(crypto, meshId, profile, meshKey))
    private val mutablePeers = MutableStateFlow<List<Peer>>(emptyList())
    private val links = MutableStateFlow<Links?>(null)
    private val secured = profile != WireProfile.UNSECURED
    private val post = Post(crypto, port.clock, scope, config.messageLifetimeSeconds, secured)
    private val diagnostics = MutableSharedFlow<LinkDiagnostic>(extraBufferCapacity = DIAGNOSTICS_BUFFER)

    /** The lifecycle state; it starts as [MeshState.Stopped]. */
    public val state: StateFlow<MeshState> = mutableState.asStateFlow()

    /**
     * The peers this node has a link to, ordered by NodeId; empty while the node is not running. A
     * peer leaves the list when its last link closes, whether it closed the link, stopped, or went
     * out of range.
     */
    public val peers: StateFlow<List<Peer>> = mutablePeers.asStateFlow()

    /**
     * The messages other nodes sent to this one (ADR 0007 decision 9). They wait in a bounded
     * inbox until collected: at most 1,024 messages and 1 MiB, of which one sender may hold a
     * quarter; a message beyond a bound is dropped. The flow completes when the node closes.
     *
     * It allows one collector at a time: a collector that starts while another collects gets
     * [MeshLinkException.AlreadyCollected], the only exception a MeshLink flow throws. Apps that
     * need several collectors share one with `shareIn`.
     */
    public val messages: Flow<ReceivedMessage> = post.inbox.messages

    /**
     * Starts the node and returns once it is [MeshState.Running] or [MeshState.Blocked]. A blocked
     * node moves to running by itself, unless it is blocked on [Requirement.IdentityUnavailable].
     * Does nothing if the node is already started, except that a node blocked on its identity tries
     * to load it again. On the first start on a platform, creates the installation identity.
     *
     * @throws MeshLinkException.Closed if the node is closed.
     */
    @Throws(MeshLinkException::class, CancellationException::class)
    public suspend fun start() {
        ensureOpen(closed)
        withContext(port.dispatcher) {
            lifecycle.withLock {
                if (watcher == null) {
                    mutableState.value = MeshState.Starting
                    val loaded = InstallationIdentity.loadOrCreate(port.identityStore, crypto)
                    if (loaded == null) {
                        mutableState.compareAndSet(MeshState.Starting, IDENTITY_UNAVAILABLE)
                    } else {
                        identity = loaded
                        post.begin(loaded.nodeId)
                        watcher = scope.launch { watch() }
                        mutableState.first { it != MeshState.Starting }
                    }
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
        ensureOpen(closed)
        withContext(port.dispatcher) { lifecycle.withLock { halt() } }
    }

    /**
     * Deletes this platform's installation identity, so the next [start] creates a new one with a
     * new [NodeId] (ADR 0003). The way out of [Requirement.IdentityUnavailable]: MeshLink never
     * replaces a stored identity on its own. A [MeshState.Blocked] node stops first; other nodes on
     * the platform keep the old identity until they restart.
     *
     * Returns `false` if the platform refused to delete the identity.
     *
     * @throws MeshLinkException.InvalidState if the node is neither [MeshState.Stopped] nor
     *   [MeshState.Blocked].
     * @throws MeshLinkException.Closed if the node is closed.
     */
    @Throws(MeshLinkException::class, CancellationException::class)
    public suspend fun resetIdentity(): Boolean {
        ensureOpen(closed)
        return withContext(port.dispatcher) {
            lifecycle.withLock {
                val current = mutableState.value
                if (current != MeshState.Stopped && current !is MeshState.Blocked) {
                    throw MeshLinkException.InvalidState(current)
                }
                halt()
                port.identityStore.delete()
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
            post.close()
            RadioRegistry.release(port, this)
            mutableState.value = MeshState.Stopped
        }
    }

    /**
     * Sends [payload] to [to] (ADR 0007 decision 7). Doesn't suspend: the message is queued, and
     * [SendResult.Accepted.message] reports its [DeliveryStatus]. A message to a peer without a
     * link waits as [DeliveryStatus.Held] until one forms or the message expires.
     *
     * Until routing arrives, every message travels only over a direct link to [to].
     *
     * Rejects the message with [RejectionReason.NotRunning] unless the node is [MeshState.Running]
     * or [MeshState.Blocked] on anything but [Requirement.IdentityUnavailable];
     * [RejectionReason.UnknownPeer] if [to] never completed a link with this node;
     * [RejectionReason.TooLarge] above 899 bytes; and [RejectionReason.QueueFull] while the payloads
     * not yet written reach 1 MiB, after which the caller awaits [awaitSendCapacity].
     *
     * @throws MeshLinkException.Closed if the node is closed.
     */
    @Throws(MeshLinkException::class)
    public fun send(
        to: NodeId,
        payload: ByteArray,
    ): SendResult {
        ensureOpen(closed)
        return post.send(to, payload, mutableState.value)
    }

    /**
     * Returns once the send queue has room again, or at once if it has. A caller that got
     * [RejectionReason.QueueFull] awaits this, then sends again; it never retries in a loop.
     *
     * @throws MeshLinkException.Closed if the node is closed.
     */
    @Throws(MeshLinkException::class, CancellationException::class)
    public suspend fun awaitSendCapacity() {
        ensureOpen(closed)
        post.awaitCapacity()
    }

    /** The peers this node found on the air and may link to next; for `meshlink-testing`. */
    @InternalMeshLinkApi
    @HiddenFromObjC
    public fun linkCandidates(): Set<PeerHandle> = scanner.candidates.value.keys

    /**
     * The only role this node takes on the links it opens from its next start, or `null` for either:
     * a central neither serves nor advertises, a peripheral doesn't scan. For the lab apps
     * (ADR 0017 decision 7); two nodes restricted to one role never link.
     */
    @InternalMeshLinkApi
    @HiddenFromObjC
    public var restrictedLinkRole: LinkRole? = null

    /** This node's role on each of its current links, by peer; for the lab apps. */
    @InternalMeshLinkApi
    @HiddenFromObjC
    public val linkRoles: Map<NodeId, LinkRole>
        get() {
            val session = links.value
            return if (session == null) emptyMap() else session.roles.value
        }

    /** Typed events about link handshakes (ADR 0020); for `meshlink-testing` until the diagnostics catalog. */
    @InternalMeshLinkApi
    @HiddenFromObjC
    public val linkDiagnostics: SharedFlow<LinkDiagnostic> = diagnostics.asSharedFlow()

    /** Why the radio last refused to advertise or scan, or `null`; for `meshlink-testing`. */
    @InternalMeshLinkApi
    @HiddenFromObjC
    public fun radioFailure(): RadioFailure? = scanner.failure

    /** Stops a started node; the caller holds [lifecycle]. A node blocked on its identity has no watcher. */
    private suspend fun halt() {
        if (watcher == null && mutableState.value !is MeshState.Blocked) return
        mutableState.value = MeshState.Stopping
        watcher?.let {
            it.cancelAndJoin()
            post.end()
        }
        watcher = null
        mutableState.value = MeshState.Stopped
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
    @OptIn(InternalMeshLinkApi::class)
    private suspend fun session() =
        coroutineScope {
            val digest = Derivations(crypto).meshIdDigest(meshId, profile)
            val handshakes =
                if (secured) {
                    val credentials = LinkCredentials.issue(crypto, port.clock, identity.keyPair, profile, digest)
                    Handshakes(crypto, port.clock, credentials, meshKey, diagnostics)
                } else {
                    null
                }
            val admission = LinkAdmission(LinkPolicy(digest, identity.nodeId), handshakes)
            val session = Links(port.radio, port.clock, admission, this, mutablePeers, post)
            links.value = session
            try {
                val linking = launch { scanner.candidates.collect { session.connectTo(it.keys) } }
                scanner.run(restrictedLinkRole, session::accept)
                linking.cancel()
            } finally {
                links.value = null
            }
        }

    /** Creates nodes. */
    public companion object {
        private val IDENTITY_UNAVAILABLE = MeshState.Blocked(setOf(Requirement.IdentityUnavailable))
        private const val DIAGNOSTICS_BUFFER = 64

        /** Creates a stopped node with [config] on [platform]. Doesn't touch the radio. */
        public fun create(
            config: MeshLinkConfig,
            platform: MeshPlatform,
        ): MeshLink = MeshLink(config, platform.port, SodiumCryptoProvider)
    }
}

private fun ensureOpen(closed: StateFlow<Boolean>) {
    if (closed.value) throw MeshLinkException.Closed()
}
