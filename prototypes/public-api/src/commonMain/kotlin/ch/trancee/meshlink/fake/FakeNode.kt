package ch.trancee.meshlink.fake

import ch.trancee.meshlink.DeliveryStatus
import ch.trancee.meshlink.DiagnosticCode
import ch.trancee.meshlink.DiagnosticEvent
import ch.trancee.meshlink.DutyCycle
import ch.trancee.meshlink.FailureReason
import ch.trancee.meshlink.MeshLinkConfig
import ch.trancee.meshlink.MeshLinkException
import ch.trancee.meshlink.MeshState
import ch.trancee.meshlink.NodeId
import ch.trancee.meshlink.OutgoingMessage
import ch.trancee.meshlink.Peer
import ch.trancee.meshlink.Proximity
import ch.trancee.meshlink.Reachability
import ch.trancee.meshlink.ReceivedMessage
import ch.trancee.meshlink.SecurityProfile
import ch.trancee.meshlink.SendOptions
import ch.trancee.meshlink.internal.DiagnosticsBuffer
import ch.trancee.meshlink.internal.Inbox
import ch.trancee.meshlink.internal.NodeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** One node on a [FakeMesh]. Every mutable field is confined to the medium's serial dispatcher. */
internal class FakeNode(
    private val mesh: FakeMesh,
    val host: FakeHost,
    val config: MeshLinkConfig,
) : NodeEngine {
    private val settings = config.settings
    private val job = SupervisorJob(mesh.scope.coroutineContext[Job])
    private val scope = CoroutineScope(job + mesh.serial)
    private val stateFlow = MutableStateFlow<MeshState>(MeshState.Stopped)
    private val peersFlow = MutableStateFlow<List<Peer>>(emptyList())
    private val inbox = Inbox(settings.inboxCapacity)
    private val diagnosticsBuffer = DiagnosticsBuffer(config.diagnosticsBufferCapacity, config.debugDiagnostics, mesh.clock)
    private val knownPeers = mutableMapOf<NodeId, Peer>()
    private val reportedMismatches = mutableSetOf<NodeId>()
    private val pending = mutableListOf<Outbound>()
    private val hopDelay: Duration = when (settings.dutyCycle) {
        DutyCycle.LowLatency -> 5.milliseconds
        DutyCycle.Balanced -> 20.milliseconds
        DutyCycle.LowPower -> 50.milliseconds
    }

    @Volatile
    var isClosed: Boolean = false
        private set

    val isActive: Boolean get() = !isClosed && host.present && stateFlow.value == MeshState.Running

    override val localNodeId: NodeId = host.nodeId
    override val state: StateFlow<MeshState> = stateFlow.asStateFlow()
    override val peers: StateFlow<List<Peer>> = peersFlow.asStateFlow()
    override val messages: Flow<ReceivedMessage> = flow {
        while (true) emit(withContext(mesh.serial) { inbox.take() } ?: break)
    }
    override val diagnostics: Flow<DiagnosticEvent> = flow {
        while (true) emit(withContext(mesh.serial) { diagnosticsBuffer.take() } ?: break)
    }

    private class Outbound(
        val message: OutgoingMessage,
        val status: MutableStateFlow<DeliveryStatus>,
        val payload: ByteArray,
        val options: SendOptions,
    ) {
        var attempts = 0
        var inFlight = false
        var acknowledged = false
        var finished = false
    }

    override suspend fun start() = withContext(mesh.serial) {
        if (isClosed) throw MeshLinkException.Closed()
        if (stateFlow.value != MeshState.Stopped) return@withContext
        stateFlow.value = MeshState.Starting
        if (config.securityProfile == SecurityProfile.None) diagnosticsBuffer.emit(DiagnosticCode.SecurityProfileNone)
        reevaluateRequirements()
        mesh.topologyChanged()
    }

    override suspend fun stop() = withContext(mesh.serial) {
        if (isClosed) throw MeshLinkException.Closed()
        if (stateFlow.value == MeshState.Stopped) return@withContext
        stateFlow.value = MeshState.Stopping
        stateFlow.value = MeshState.Stopped
        mesh.topologyChanged()
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        job.cancel()
        mesh.scope.launch {
            stateFlow.value = MeshState.Stopped
            inbox.close()
            diagnosticsBuffer.close()
            mesh.topologyChanged()
        }
    }

    fun reevaluateRequirements() {
        if (stateFlow.value == MeshState.Stopped || stateFlow.value == MeshState.Stopping) return
        val missing = host.missingRequirements()
        val next = if (missing.isEmpty()) MeshState.Running else MeshState.Blocked(missing)
        if (next != stateFlow.value && next is MeshState.Blocked) {
            diagnosticsBuffer.emit(DiagnosticCode.StartBlocked, mapOf("requirements" to missing.joinToString()))
        }
        stateFlow.value = next
    }

    fun refreshPeers() {
        val now = mesh.clock.now()
        val reached = mesh.hopsFrom(this)
        for ((node, hops) in reached) {
            val link = if (hops == 1) mesh.link(host, node.host) else null
            knownPeers[node.localNodeId] = Peer(
                node.localNodeId,
                if (hops == 1) Reachability.Direct else Reachability.Routed(hops),
                config.securityProfile != SecurityProfile.None,
                now,
                link?.let { Proximity.fromRssi(it.smoothedRssiDbm, it.measuredAt) } ?: Proximity.Unknown,
            )
        }
        val reachedIds = reached.keys.map { it.localNodeId }.toSet()
        for ((id, peer) in knownPeers.toList()) {
            if (id !in reachedIds && peer.reachability != Reachability.Unreachable) {
                knownPeers[id] = Peer(id, Reachability.Unreachable, peer.secured, peer.lastSeen, Proximity.Unknown)
            }
        }
        peersFlow.value = knownPeers.values.sortedBy { it.nodeId.toHex() }
    }

    fun reportMeshMismatches() {
        if (!isActive) return
        for (other in mesh.nodes()) {
            if (other === this || !other.isActive || mesh.link(host, other.host) == null) continue
            if (other.config.sameMeshAs(config) || !reportedMismatches.add(other.localNodeId)) continue
            val mismatch = when {
                other.config.meshId != config.meshId -> "meshId"
                other.config.securityProfile != config.securityProfile -> "securityProfile"
                else -> "meshKey"
            }
            diagnosticsBuffer.emit(
                DiagnosticCode.PeerRejectedMeshMismatch,
                mapOf("peer" to other.localNodeId.redacted(), "mismatch" to mismatch),
            )
        }
    }

    override fun send(to: NodeId, payload: ByteArray, options: SendOptions, lifetimeSeconds: Int): OutgoingMessage {
        val status = MutableStateFlow<DeliveryStatus>(DeliveryStatus.Queued)
        val outbound = Outbound(OutgoingMessage(mesh.nextMessageId(), to, status.asStateFlow()), status, payload, options)
        scope.launch {
            pending += outbound
            attempt(outbound)
        }
        scope.launch {
            delay(lifetimeSeconds.seconds)
            if (!outbound.acknowledged && !outbound.finished) {
                finish(outbound, DeliveryStatus.Expired)
                diagnosticsBuffer.emit(DiagnosticCode.MessageExpired)
            }
        }
        return outbound.message
    }

    override fun broadcast(payload: ByteArray, hopLimit: Int): OutgoingMessage {
        val status = MutableStateFlow<DeliveryStatus>(DeliveryStatus.Queued)
        val message = OutgoingMessage(mesh.nextMessageId(), null, status.asStateFlow())
        scope.launch {
            status.value = DeliveryStatus.Sent
            for ((node, hops) in mesh.hopsFrom(this@FakeNode).filterValues { it <= hopLimit }) {
                scope.launch {
                    delay(hopDelay * hops)
                    node.receive(message, localNodeId, payload, hops, broadcast = true, readReceipt = false, onTaken = {}, onRead = {})
                }
            }
        }
        return message
    }

    fun retryPending() {
        pending.filter { !it.inFlight && !it.finished }.forEach { attempt(it) }
    }

    private fun attempt(outbound: Outbound) {
        if (outbound.finished || outbound.inFlight || !isActive) return
        val target = mesh.node(outbound.message.destination!!)
        val hops = target?.let { mesh.hopsFrom(this)[it] }
        when {
            outbound.options.directOnly && hops != 1 -> finish(outbound, DeliveryStatus.Failed(FailureReason.NoDirectLink))
            target == null || hops == null ->
                if (settings.storeAndForward) {
                    outbound.status.value = DeliveryStatus.Held
                } else {
                    finish(outbound, DeliveryStatus.Failed(FailureReason.NoRoute))
                }
            else -> transmit(outbound, target, hops)
        }
    }

    private fun transmit(outbound: Outbound, target: FakeNode, hops: Int) {
        outbound.inFlight = true
        outbound.attempts++
        outbound.status.value = DeliveryStatus.Sent
        scope.launch {
            delay(hopDelay * hops)
            if (outbound.finished) return@launch
            val accepted = target.receive(
                outbound.message, localNodeId, outbound.payload, hops, broadcast = false,
                readReceipt = outbound.options.readReceipt,
                onTaken = { if (outbound.options.deliveryReceipt) receipt(outbound, DeliveryStatus.Delivered, hops) },
                onRead = { receipt(outbound, DeliveryStatus.Read, hops) },
            )
            if (accepted) {
                outbound.acknowledged = true
                pending -= outbound
            } else if (outbound.attempts >= MAX_ATTEMPTS) {
                finish(outbound, DeliveryStatus.Failed(FailureReason.NotAcknowledged))
            } else {
                delay(RETRY_BACKOFF * (1 shl (outbound.attempts - 1)))
                outbound.inFlight = false
                attempt(outbound)
            }
        }
    }

    private fun receipt(outbound: Outbound, status: DeliveryStatus, hops: Int) {
        scope.launch {
            delay(hopDelay * hops)
            if (outbound.status.value != DeliveryStatus.Read) outbound.status.value = status
        }
    }

    private fun finish(outbound: Outbound, status: DeliveryStatus) {
        outbound.finished = true
        outbound.status.value = status
        pending -= outbound
    }

    private fun receive(
        message: OutgoingMessage,
        sender: NodeId,
        payload: ByteArray,
        hops: Int,
        broadcast: Boolean,
        readReceipt: Boolean,
        onTaken: () -> Unit,
        onRead: () -> Unit,
    ): Boolean {
        if (!isActive) return false
        val received = ReceivedMessage(message.messageId, sender, payload, hops, broadcast, readReceipt, mesh.clock.now()) {
            mesh.scope.launch { onRead() }
        }
        return when (inbox.offer(received, onTaken)) {
            Inbox.Admission.Accepted -> true
            Inbox.Admission.SenderShareExceeded -> {
                diagnosticsBuffer.emit(DiagnosticCode.InboxSenderShareExceeded, mapOf("sender" to sender.redacted()))
                false
            }
            Inbox.Admission.Full -> {
                diagnosticsBuffer.emit(DiagnosticCode.InboxFull)
                false
            }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
        val RETRY_BACKOFF = 500.milliseconds
    }
}
