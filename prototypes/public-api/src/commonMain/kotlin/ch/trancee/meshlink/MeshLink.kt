package ch.trancee.meshlink

import ch.trancee.meshlink.internal.NodeEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.cancellation.CancellationException

/**
 * The local node: one instance per process. Every member is callable from any thread; MeshLink
 * runs its work on its own dispatcher, so Swift callers hop to `@MainActor` themselves.
 *
 * @sample ch.trancee.meshlink.samples.createAndStart
 */
public class MeshLink private constructor(
    /** The configuration this node was created with. */
    public val config: MeshLinkConfig,
    private val platform: MeshLinkPlatform,
    private val platformClaim: Any,
) : AutoCloseable {
    private val engine: NodeEngine = platform.createEngine(config)

    /** Installation identity of the local node. */
    public val localNodeId: NodeId get() = engine.localNodeId

    /** Lifecycle state; [MeshState.Stopped] until [start]. */
    public val state: StateFlow<MeshState> get() = engine.state

    /** Known peers, ordered by node ID. Unreachable peers stay listed with their last-seen time. */
    public val peers: StateFlow<List<Peer>> get() = engine.peers

    /**
     * Messages for this node, from a bounded inbox. A message counts as taken, and a requested
     * delivery receipt is sent, when the collector receives it. Collect from one place only;
     * concurrent collectors split the messages between them. Never throws; completes on [close].
     *
     * @sample ch.trancee.meshlink.samples.receiveAndMarkRead
     */
    public val messages: Flow<ReceivedMessage> get() = engine.messages

    /**
     * Diagnostic events from a bounded buffer; overflow produces one
     * [DiagnosticCode.DiagnosticsOverflow] summary. Collect from one place only. Never throws;
     * completes on [close].
     *
     * @sample ch.trancee.meshlink.samples.watchDiagnostics
     */
    public val diagnostics: Flow<DiagnosticEvent> get() = engine.diagnostics

    /**
     * Joins the mesh. Idempotent. Returns once the node is [MeshState.Running] or
     * [MeshState.Blocked]; a blocked node resumes by itself once its requirements are met.
     *
     * @throws MeshLinkException.Closed if called after [close].
     */
    @Throws(MeshLinkException::class, CancellationException::class)
    public suspend fun start() {
        engine.start()
    }

    /**
     * Leaves the mesh and keeps held messages. Idempotent.
     *
     * @throws MeshLinkException.Closed if called after [close].
     */
    @Throws(MeshLinkException::class, CancellationException::class)
    public suspend fun stop() {
        engine.stop()
    }

    /** Stops immediately, completes [messages] and [diagnostics], and frees the platform. Idempotent. */
    override fun close() {
        engine.close()
        platform.release(platformClaim)
    }

    /**
     * Sends [payload] to [to], routed through other nodes unless [SendOptions.directOnly].
     * Does not suspend.
     *
     * @sample ch.trancee.meshlink.samples.sendAndTrack
     */
    public fun send(to: NodeId, payload: ByteArray, options: SendOptions): SendResult {
        val settings = config.settings
        val lifetime = options.messageLifetimeSeconds ?: settings.messageLifetimeSeconds
        val rejection = when {
            engine.state.value != MeshState.Running -> RejectionReason.NotRunning
            payload.size !in 1..settings.maxPayloadBytes -> RejectionReason.PayloadSize
            to == engine.localNodeId -> RejectionReason.InvalidDestination
            lifetime !in 1..settings.messageLifetimeSeconds -> RejectionReason.LifetimeOutOfRange
            options.deliveryReceipt && !settings.deliveryReceipts -> RejectionReason.ReceiptNotAllowed
            options.readReceipt && !settings.readReceipts -> RejectionReason.ReceiptNotAllowed
            else -> return SendResult.Accepted(engine.send(to, payload.copyOf(), options, lifetime))
        }
        return SendResult.Rejected(rejection)
    }

    /**
     * Sends [payload] as a mesh broadcast to every node within [hopLimit] hops. Does not suspend.
     *
     * @sample ch.trancee.meshlink.samples.broadcastAlert
     */
    public fun broadcast(payload: ByteArray, hopLimit: Int, options: BroadcastOptions): SendResult {
        val settings = config.settings
        val lifetime = options.messageLifetimeSeconds ?: settings.messageLifetimeSeconds
        val rejection = when {
            engine.state.value != MeshState.Running -> RejectionReason.NotRunning
            payload.size !in 1..settings.maxPayloadBytes -> RejectionReason.PayloadSize
            hopLimit !in 1..settings.maxHopLimit -> RejectionReason.HopLimitOutOfRange
            lifetime !in 1..settings.messageLifetimeSeconds -> RejectionReason.LifetimeOutOfRange
            else -> return SendResult.Accepted(engine.broadcast(payload.copyOf(), hopLimit))
        }
        return SendResult.Rejected(rejection)
    }

    /** Factory. */
    public companion object {
        /**
         * Creates the node for [platform] without starting it. Does not suspend.
         *
         * @throws MeshLinkException.InstanceAlreadyOpen if another instance for [platform] is not closed.
         */
        @Throws(MeshLinkException::class)
        public fun create(config: MeshLinkConfig, platform: MeshLinkPlatform): MeshLink {
            val claim = Any()
            if (!platform.claim(claim)) throw MeshLinkException.InstanceAlreadyOpen()
            return MeshLink(config, platform, claim)
        }
    }
}
