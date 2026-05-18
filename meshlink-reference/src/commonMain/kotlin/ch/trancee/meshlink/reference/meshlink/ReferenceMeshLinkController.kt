package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.ForgetPeerResult
import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.PauseResult
import ch.trancee.meshlink.api.PeerConnectionState
import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.ResumeResult
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.api.StartResult
import ch.trancee.meshlink.api.StopResult
import ch.trancee.meshlink.config.PowerMode
import ch.trancee.meshlink.config.RegulatoryRegion
import ch.trancee.meshlink.config.meshLinkConfig
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticEvent
import ch.trancee.meshlink.reference.model.PeerConnectionSnapshotState
import ch.trancee.meshlink.reference.model.PeerSnapshot
import ch.trancee.meshlink.reference.model.PeerTrustState
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Shared controller abstraction for the reference app.
 */
public interface ReferenceMeshLinkController {
    public val snapshot: StateFlow<ReferenceControllerSnapshot>

    public suspend fun start(): Unit

    public suspend fun pause(): Unit

    public suspend fun resume(): Unit

    public suspend fun stop(): Unit

    public suspend fun sendSamplePayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority = DeliveryPriority.NORMAL,
    ): Unit

    public suspend fun forgetPeer(peerId: String): Unit
}

public data class ReferenceControllerSnapshot(
    public val session: ReferenceSession,
    public val peers: List<PeerSnapshot>,
    public val timeline: List<TimelineEntry>,
    public val activePowerModeLabel: String,
)

/**
 * Live shared controller that wraps the existing MeshLink SDK and emits app-facing state.
 */
public class LiveReferenceMeshLinkController(
    private val platformName: String,
    private val authorityMode: ReferenceAuthorityMode,
    private val appId: String,
    private val nowProvider: () -> Long,
    private val platformContext: Any? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ReferenceMeshLinkController {
    private val startedAtEpochMillis: Long = nowProvider()
    private val sessionId: String = "${platformName.lowercase()}-$startedAtEpochMillis"
    private val stateFlow: MutableStateFlow<ReferenceControllerSnapshot> =
        MutableStateFlow(
            ReferenceControllerSnapshot(
                session =
                    ReferenceSession(
                        sessionId = sessionId,
                        scenarioId = "guided-first-exchange",
                        authorityMode = authorityMode,
                        startedAtEpochMillis = startedAtEpochMillis,
                        meshStateLabel = MeshLinkState.Uninitialized.toString(),
                        configurationSnapshot =
                            mapOf(
                                "platform" to platformName,
                                "surface" to "main-guided",
                                "appId" to appId,
                            ),
                        historyStatus = ReferenceHistoryStatus.LIVE,
                    ),
                peers = emptyList(),
                timeline =
                    listOf(
                        timelineEntry(
                            index = 1,
                            family = TimelineFamily.USER,
                            severity = TimelineSeverity.INFO,
                            title = "Reference session created",
                            detail = "The guided first-exchange controller is ready on $platformName.",
                        )
                    ),
                activePowerModeLabel = "Automatic",
            )
        )
    private val meshLinkApi: MeshLinkApi by lazy {
        val config =
            meshLinkConfig {
                appId = this@LiveReferenceMeshLinkController.appId
                regulatoryRegion = RegulatoryRegion.DEFAULT
                powerMode = PowerMode.Automatic
            }
        if (platformContext != null) {
            MeshLink.create(config = config, context = platformContext)
        } else {
            MeshLink.create(config = config)
        }
    }
    private var flowsBound: Boolean = false

    override val snapshot: StateFlow<ReferenceControllerSnapshot> = stateFlow.asStateFlow()

    override suspend fun start(): Unit {
        ensureBindings()
        val outcome = runCatching { meshLinkApi.start() }
        handleMeshCall(
            result = outcome,
            successTitle = "Mesh started",
            successDetail = { result -> "mesh.start() -> $result" },
            errorTitle = "Mesh start failed",
        )
    }

    override suspend fun pause(): Unit {
        val outcome = runCatching { meshLinkApi.pause() }
        handleMeshCall(
            result = outcome,
            successTitle = "Mesh paused",
            successDetail = { result -> "mesh.pause() -> $result" },
            errorTitle = "Mesh pause failed",
        )
    }

    override suspend fun resume(): Unit {
        val outcome = runCatching { meshLinkApi.resume() }
        handleMeshCall(
            result = outcome,
            successTitle = "Mesh resumed",
            successDetail = { result -> "mesh.resume() -> $result" },
            errorTitle = "Mesh resume failed",
        )
    }

    override suspend fun stop(): Unit {
        val outcome = runCatching { meshLinkApi.stop() }
        handleMeshCall(
            result = outcome,
            successTitle = "Mesh stopped",
            successDetail = { result -> "mesh.stop() -> $result" },
            errorTitle = "Mesh stop failed",
        )
    }

    override suspend fun sendSamplePayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
    ): Unit {
        val outcome =
            runCatching {
                meshLinkApi.send(
                    peerId = PeerId(peerId),
                    payload = payloadText.encodeToByteArray(),
                    priority = priority,
                )
            }
        outcome.onSuccess { result ->
            when (result) {
                SendResult.Sent -> {
                    appendEvent(
                        family = TimelineFamily.MESSAGE,
                        severity = TimelineSeverity.SUCCESS,
                        title = "Guided message sent",
                        detail = "First guided payload reached ${redactedSuffix(peerId)} with $priority priority.",
                        peerSuffix = redactedSuffix(peerId),
                        payloadPreview = payloadText,
                    )
                    updateSession(lastOutcomeSummary = "SendResult.Sent", selectedPeerId = peerId)
                }
                is SendResult.NotSent -> {
                    appendEvent(
                        family = TimelineFamily.MESSAGE,
                        severity = TimelineSeverity.ERROR,
                        title = "Guided message not sent",
                        detail = "First guided payload failed for ${redactedSuffix(peerId)} with ${result.reason}.",
                        peerSuffix = redactedSuffix(peerId),
                        payloadPreview = payloadText,
                    )
                    updateSession(
                        lastOutcomeSummary = "SendResult.NotSent(${result.reason})",
                        selectedPeerId = peerId,
                    )
                }
            }
        }.onFailure { error ->
            appendEvent(
                family = TimelineFamily.MESSAGE,
                severity = TimelineSeverity.ERROR,
                title = "Guided message failed",
                detail = error.message ?: error.toString(),
                peerSuffix = redactedSuffix(peerId),
                payloadPreview = payloadText,
            )
        }
    }

    override suspend fun forgetPeer(peerId: String): Unit {
        val outcome = runCatching { meshLinkApi.forgetPeer(PeerId(peerId)) }
        outcome.onSuccess { result ->
            val trustState =
                if (result == ForgetPeerResult.Forgotten) {
                    PeerTrustState.FORGOTTEN
                } else {
                    PeerTrustState.UNKNOWN
                }
            updatePeers { peers ->
                peers.map { peer ->
                    if (peer.peerId == peerId) {
                        peer.copy(trustState = trustState)
                    } else {
                        peer
                    }
                }
            }
            appendEvent(
                family = TimelineFamily.PEER,
                severity = TimelineSeverity.INFO,
                title = "Peer trust reset",
                detail = "forgetPeer(${redactedSuffix(peerId)}) -> $result",
                peerSuffix = redactedSuffix(peerId),
            )
        }.onFailure { error ->
            appendEvent(
                family = TimelineFamily.PEER,
                severity = TimelineSeverity.ERROR,
                title = "Peer trust reset failed",
                detail = error.message ?: error.toString(),
                peerSuffix = redactedSuffix(peerId),
            )
        }
    }

    private fun ensureBindings(): Unit {
        if (flowsBound) {
            return
        }
        flowsBound = true
        scope.launch {
            meshLinkApi.state.collect { meshState ->
                updateSession(meshStateLabel = meshState.toString())
            }
        }
        scope.launch {
            meshLinkApi.peerEvents.collect { event ->
                handlePeerEvent(event)
            }
        }
        scope.launch {
            meshLinkApi.diagnosticEvents.collect { event ->
                handleDiagnosticEvent(event)
            }
        }
        scope.launch {
            meshLinkApi.messages.collect { message ->
                handleInboundMessage(message)
            }
        }
    }

    private fun handleMeshCall(
        result: Result<Any>,
        successTitle: String,
        successDetail: (Any) -> String,
        errorTitle: String,
    ): Unit {
        result.onSuccess { value ->
            appendEvent(
                family = TimelineFamily.LIFECYCLE,
                severity = TimelineSeverity.SUCCESS,
                title = successTitle,
                detail = successDetail(value),
            )
            if (value is StartResult || value is ResumeResult || value is PauseResult || value is StopResult) {
                updateSession(lastOutcomeSummary = value.toString())
            }
        }.onFailure { error ->
            appendEvent(
                family = TimelineFamily.LIFECYCLE,
                severity = TimelineSeverity.ERROR,
                title = errorTitle,
                detail = error.message ?: error.toString(),
            )
            updateSession(lastOutcomeSummary = errorTitle)
        }
    }

    private fun handlePeerEvent(event: PeerEvent): Unit {
        when (event) {
            is PeerEvent.Found -> {
                val peerSnapshot =
                    PeerSnapshot(
                        peerId = event.peerId.value,
                        peerSuffix = redactedSuffix(event.peerId.value),
                        trustState = PeerTrustState.UNKNOWN,
                        connectionState = event.state.toSnapshotState(),
                        lastSeenAtEpochMillis = nowProvider(),
                        capabilityNotes = listOf("Discovered by live MeshLink flow"),
                    )
                updatePeers { peers ->
                    (peers.filterNot { existing -> existing.peerId == peerSnapshot.peerId } + peerSnapshot)
                        .sortedBy { peer -> peer.peerSuffix }
                }
                updateSession(selectedPeerId = event.peerId.value, lastOutcomeSummary = "Peer found")
                appendEvent(
                    family = TimelineFamily.PEER,
                    severity = TimelineSeverity.SUCCESS,
                    title = "Peer found",
                    detail = "Discovered ${peerSnapshot.peerSuffix} on the guided path.",
                    peerSuffix = peerSnapshot.peerSuffix,
                )
            }
            is PeerEvent.StateChanged -> {
                updatePeers { peers ->
                    peers.map { peer ->
                        if (peer.peerId == event.peerId.value) {
                            peer.copy(
                                connectionState = event.state.toSnapshotState(),
                                lastSeenAtEpochMillis = nowProvider(),
                            )
                        } else {
                            peer
                        }
                    }
                }
                appendEvent(
                    family = TimelineFamily.PEER,
                    severity = TimelineSeverity.INFO,
                    title = "Peer state changed",
                    detail = "${redactedSuffix(event.peerId.value)} -> ${event.state}",
                    peerSuffix = redactedSuffix(event.peerId.value),
                )
            }
            is PeerEvent.Lost -> {
                updatePeers { peers ->
                    peers.map { peer ->
                        if (peer.peerId == event.peerId.value) {
                            peer.copy(
                                connectionState = PeerConnectionSnapshotState.LOST,
                                lastSeenAtEpochMillis = nowProvider(),
                            )
                        } else {
                            peer
                        }
                    }
                }
                appendEvent(
                    family = TimelineFamily.PEER,
                    severity = TimelineSeverity.WARNING,
                    title = "Peer lost",
                    detail = "${redactedSuffix(event.peerId.value)} left the current guided view.",
                    peerSuffix = redactedSuffix(event.peerId.value),
                )
            }
        }
    }

    private fun handleDiagnosticEvent(event: DiagnosticEvent): Unit {
        val detail =
            buildString {
                append(event.code)
                append(" @ ")
                append(event.stage)
                if (!event.metadata.isEmpty()) {
                    append(" ")
                    append(event.metadata.entries.joinToString(prefix = "{", postfix = "}") { (key, value) -> "$key=$value" })
                }
            }
        appendEvent(
            family = TimelineFamily.DIAGNOSTIC,
            severity = event.severity.toTimelineSeverity(),
            title = event.code.name,
            detail = detail,
            peerSuffix = event.peerSuffix,
        )
        when (event.code) {
            DiagnosticCode.TRUST_ESTABLISHED -> updatePeerTrust(event.peerSuffix, PeerTrustState.TRUSTED)
            DiagnosticCode.TRUST_FAILURE -> updatePeerTrust(event.peerSuffix, PeerTrustState.CHANGED)
            DiagnosticCode.POWER_MODE_CHANGED -> {
                stateFlow.value = stateFlow.value.copy(activePowerModeLabel = event.metadata["tier"] ?: stateFlow.value.activePowerModeLabel)
            }
            else -> Unit
        }
    }

    private fun handleInboundMessage(message: InboundMessage): Unit {
        val preview = message.payload.decodeToString().take(80)
        appendEvent(
            family = TimelineFamily.MESSAGE,
            severity = TimelineSeverity.SUCCESS,
            title = "Inbound message",
            detail = "Received ${message.payload.size} bytes from ${redactedSuffix(message.originPeerId.value)}.",
            peerSuffix = redactedSuffix(message.originPeerId.value),
            payloadPreview = preview,
        )
        updateSession(
            lastOutcomeSummary = "Inbound message received",
            selectedPeerId = message.originPeerId.value,
        )
        updatePeers { peers ->
            peers.map { peer ->
                if (peer.peerId == message.originPeerId.value) {
                    peer.copy(lastDeliveryOutcome = "Inbound ${message.payload.size} bytes")
                } else {
                    peer
                }
            }
        }
    }

    private fun updatePeerTrust(peerSuffix: String?, trustState: PeerTrustState): Unit {
        if (peerSuffix == null) {
            return
        }
        updatePeers { peers ->
            peers.map { peer ->
                if (peer.peerSuffix == peerSuffix) {
                    peer.copy(trustState = trustState)
                } else {
                    peer
                }
            }
        }
    }

    private fun updatePeers(transform: (List<PeerSnapshot>) -> List<PeerSnapshot>): Unit {
        stateFlow.value = stateFlow.value.copy(peers = transform(stateFlow.value.peers))
    }

    private fun updateSession(
        meshStateLabel: String = stateFlow.value.session.meshStateLabel,
        lastOutcomeSummary: String? = stateFlow.value.session.lastOutcomeSummary,
        selectedPeerId: String? = stateFlow.value.session.selectedPeerId,
    ): Unit {
        stateFlow.value =
            stateFlow.value.copy(
                session =
                    stateFlow.value.session.copy(
                        meshStateLabel = meshStateLabel,
                        lastOutcomeSummary = lastOutcomeSummary,
                        selectedPeerId = selectedPeerId,
                    )
            )
    }

    private fun appendEvent(
        family: TimelineFamily,
        severity: TimelineSeverity,
        title: String,
        detail: String,
        peerSuffix: String? = null,
        payloadPreview: String? = null,
    ): Unit {
        val current = stateFlow.value
        val nextIndex = current.timeline.size + 1
        stateFlow.value =
            current.copy(
                timeline =
                    current.timeline +
                        timelineEntry(
                            index = nextIndex,
                            family = family,
                            severity = severity,
                            title = title,
                            detail = detail,
                            peerSuffix = peerSuffix,
                            payloadPreview = payloadPreview,
                        )
            )
    }

    private fun timelineEntry(
        index: Int,
        family: TimelineFamily,
        severity: TimelineSeverity,
        title: String,
        detail: String,
        peerSuffix: String? = null,
        payloadPreview: String? = null,
    ): TimelineEntry {
        return TimelineEntry(
            entryId = "$sessionId-$index",
            sessionId = sessionId,
            occurredAtEpochMillis = nowProvider(),
            family = family,
            severity = severity,
            title = title,
            detail = detail,
            peerSuffix = peerSuffix,
            searchText = listOf(title, detail, peerSuffix.orEmpty()).joinToString(" "),
            payloadPreview = payloadPreview,
        )
    }
}

/**
 * Preview fallback used when the live controller cannot be created.
 */
public class PreviewReferenceMeshLinkController(
    private val platformName: String,
    nowEpochMillis: Long,
) : ReferenceMeshLinkController {
    private val stateFlow: MutableStateFlow<ReferenceControllerSnapshot> =
        MutableStateFlow(
            ReferenceControllerSnapshot(
                session =
                    ReferenceSession(
                        sessionId = "preview-$platformName",
                        scenarioId = "guided-first-exchange",
                        authorityMode = ReferenceAuthorityMode.SOLO,
                        startedAtEpochMillis = nowEpochMillis,
                        meshStateLabel = MeshLinkState.Uninitialized.toString(),
                        configurationSnapshot =
                            mapOf(
                                "platform" to platformName,
                                "surface" to "main-guided",
                                "mode" to "solo",
                            ),
                        historyStatus = ReferenceHistoryStatus.LIVE,
                    ),
                peers =
                    listOf(
                        PeerSnapshot(
                            peerId = "preview-peer-$platformName",
                            peerSuffix = platformName.take(2).uppercase() + "0001",
                            trustState = PeerTrustState.UNKNOWN,
                            connectionState = PeerConnectionSnapshotState.DISCONNECTED,
                            capabilityNotes = listOf("Fallback preview data"),
                        )
                    ),
                timeline =
                    listOf(
                        TimelineEntry(
                            entryId = "preview-$platformName-1",
                            sessionId = "preview-$platformName",
                            occurredAtEpochMillis = nowEpochMillis,
                            family = TimelineFamily.USER,
                            severity = TimelineSeverity.INFO,
                            title = "Reference app initialized",
                            detail = "Preview fallback is active on $platformName.",
                        )
                    ),
                activePowerModeLabel = "Automatic",
            )
        )

    override val snapshot: StateFlow<ReferenceControllerSnapshot> = stateFlow.asStateFlow()

    override suspend fun start(): Unit = Unit

    override suspend fun pause(): Unit = Unit

    override suspend fun resume(): Unit = Unit

    override suspend fun stop(): Unit = Unit

    override suspend fun sendSamplePayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
    ): Unit = Unit

    override suspend fun forgetPeer(peerId: String): Unit = Unit
}

private fun PeerConnectionState.toSnapshotState(): PeerConnectionSnapshotState {
    return when (this) {
        PeerConnectionState.CONNECTED -> PeerConnectionSnapshotState.CONNECTED
        PeerConnectionState.DISCONNECTED -> PeerConnectionSnapshotState.DISCONNECTED
    }
}

private fun ch.trancee.meshlink.diagnostics.DiagnosticSeverity.toTimelineSeverity(): TimelineSeverity {
    return when (this) {
        ch.trancee.meshlink.diagnostics.DiagnosticSeverity.DEBUG -> TimelineSeverity.DEBUG
        ch.trancee.meshlink.diagnostics.DiagnosticSeverity.INFO -> TimelineSeverity.INFO
        ch.trancee.meshlink.diagnostics.DiagnosticSeverity.WARN -> TimelineSeverity.WARNING
        ch.trancee.meshlink.diagnostics.DiagnosticSeverity.ERROR -> TimelineSeverity.ERROR
    }
}

private fun redactedSuffix(peerId: String): String {
    return peerId.takeLast(6)
}
