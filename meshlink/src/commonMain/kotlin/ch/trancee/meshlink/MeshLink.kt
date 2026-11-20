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
import ch.trancee.meshlink.internal.Recognition
import ch.trancee.meshlink.internal.ResolvingKeys
import ch.trancee.meshlink.internal.Router
import ch.trancee.meshlink.internal.Scanner
import ch.trancee.meshlink.internal.WireProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
    private val scanner = Scanner(port.radio, port.clock, port.random, Discovery(crypto, meshId, profile, meshKey))
    private val links = MutableStateFlow<Links?>(null)
    private val secured = profile != WireProfile.UNSECURED
    private val post = Post(crypto, port.clock, scope, config.messageLifetimeSeconds, secured) { scanner.density }
    private val diagnostics = MutableSharedFlow<LinkDiagnostic>(extraBufferCapacity = DIAGNOSTICS_BUFFER)

    /** The lifecycle state; it starts as [MeshState.Stopped]. */
    public val state: StateFlow<MeshState> = mutableState.asStateFlow()

    /**
     * The peers this node reaches, ordered by NodeId; empty while the node is not running. A
     * [Reachability.Direct] peer leaves the list when its last link closes, whether it closed the
     * link, stopped, or went out of range. In `Unsecured`, a node this node sent to or heard from is
     * listed as [Reachability.Routed] while a route found by route discovery leads to it, and leaves
     * when the route expires.
     */
    public val peers: StateFlow<List<Peer>> = post.roster.peers

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
     * [SendResult.Accepted.message] reports its [DeliveryStatus]. The message travels over other
     * nodes when [to] is not linked (ADR 0008), and MeshLink sends it again on a schedule until it
     * expires. While this node has no link at all, the message waits as [DeliveryStatus.Held].
     *
     * Rejects the message with [RejectionReason.NotRunning] unless the node is [MeshState.Running]
     * or [MeshState.Blocked] on anything but [Requirement.IdentityUnavailable];
     * [RejectionReason.UnknownPeer] if [to] is this node's own NodeId;
     * [RejectionReason.TooLarge] above 4,096 bytes; and [RejectionReason.QueueFull] while the payloads
     * of unfinished messages reach 1 MiB, after which the caller awaits [awaitSendCapacity].
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
     * Broadcasts [payload] to every node of the mesh within [hopLimit] hops, 255 by default (ADR 0007 amendment
     * "Broadcast"). Doesn't suspend: the broadcast is queued, and [SendResult.Accepted.message],
     * whose [OutgoingMessage.to] is `null`, reports its [DeliveryStatus]. The broadcast floods: each
     * node delivers it once on [messages] with [ReceivedMessage.broadcast] `true` and forwards it on
     * its other links with one hop less. It is written once to every current link, or to the first
     * link that opens if this node has none; it is [DeliveryStatus.Sent] at its first written copy
     * and [DeliveryStatus.Expired] at its expiry plus 30 s of clock skew. Broadcasts get no receipts.
     *
     * In [SecurityProfile.Unsecured] the receivers learn the sender's NodeId as it claims it, without
     * authentication, and every node in range can read the payload. A node of a secured profile
     * doesn't sign broadcasts yet: its broadcasts stay [DeliveryStatus.Queued] until they expire, and
     * it neither delivers nor forwards another node's broadcast.
     *
     * Rejects the broadcast with [RejectionReason.NotRunning] as [send] does;
     * [RejectionReason.InvalidHopLimit] unless [hopLimit] is 1 to 255;
     * [RejectionReason.TooLarge] above 1,024 bytes; and [RejectionReason.QueueFull] while the
     * payloads of unfinished messages and broadcasts reach 1 MiB.
     *
     * @throws MeshLinkException.Closed if the node is closed.
     */
    @Throws(MeshLinkException::class)
    public fun broadcast(
        payload: ByteArray,
        hopLimit: Int = Router.HOP_LIMIT_MAX,
    ): SendResult {
        ensureOpen(closed)
        return post.broadcasts.send(payload, hopLimit, mutableState.value)
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

    /**
     * Whether this node moves its links to L2CAP CoC where both ends can (spec "Bearer switch"),
     * from its next start: as peripheral it listens and offers its PSM, as central it opens a
     * channel to a peer that offers one. Off, it links over GATT only. Ticket 53 makes it a toggle.
     */
    @InternalMeshLinkApi
    @HiddenFromObjC
    public var coc: Boolean = true

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
    public val radioFailure: RadioFailure? get() = scanner.failure

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
                    val keys = meshKey?.let { ResolvingKeys(crypto, port.clock.wallClockMillis()) }
                    Handshakes(crypto, port.clock, credentials, meshKey, diagnostics, keys)
                } else {
                    null
                }
            val policy = LinkPolicy(digest, identity.nodeId)
            val admission = LinkAdmission(policy, handshakes, coc) { diagnostics.tryEmit(it) }
            val session = Links(port, admission, this, post.roster, post) { scanner.density.links }
            links.value = session
            try {
                val linking = launch { scanner.candidates.collect { session.connectTo(it, scanner::vet) } }
                val keys = handshakes?.keys
                // Announces each next resolving key a lead before use (spec "Resolving key update").
                val announcing =
                    launch {
                        while (keys != null) {
                            delay(keys.nextAnnouncementMillis() - port.clock.wallClockMillis())
                            session.announce(keys.announce(port.clock.wallClockMillis()))
                        }
                    }
                val recognition = Recognition(keys, session.neighbors) { session.handles }
                scanner.run(restrictedLinkRole, recognition, coc, session::accept, session.proximities::sighted)
                linking.cancel()
                announcing.cancel()
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
