package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.MeshLinkApi
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.PeerConnectionState
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.meshLink
import ch.trancee.meshlink.config.PowerMode
import ch.trancee.meshlink.config.RegulatoryRegion
import ch.trancee.meshlink.config.meshLinkConfig
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** Shared controller abstraction for the reference app. */
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

    public suspend fun close(): Unit = Unit
}

@Serializable
public data class ReferenceControllerSnapshot(
    public val session: ReferenceSession,
    public val peers: List<PeerSnapshot>,
    public val timeline: List<TimelineEntry>,
    public val activePowerModeLabel: String,
)

/** Live shared controller that wraps the existing MeshLink SDK and emits app-facing state. */
public class LiveReferenceMeshLinkController(
    private val platformName: String,
    private val authorityMode: ReferenceAuthorityMode,
    private val appId: String,
    private val nowProvider: () -> Long,
    private val surfaceOfOrigin: String = "main-guided",
    private val platformContext: Any? = null,
    private val runtimeLogger: (String) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ReferenceMeshLinkController {
    private val startedAtEpochMillis: Long = nowProvider()
    private val sessionId: String = "${platformName.lowercase()}-$startedAtEpochMillis"
    private val stateStore: ReferenceControllerStateStore =
        ReferenceControllerStateStore(
            initialSnapshot =
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
                                    "surface" to surfaceOfOrigin,
                                    "appId" to appId,
                                    "regulatoryRegion" to RegulatoryRegion.DEFAULT.name,
                                    "powerMode" to PowerMode.Automatic.toString(),
                                    "deliveryRetryDeadline" to "15s",
                                ),
                            historyStatus = ReferenceHistoryStatus.LIVE,
                        ),
                    peers = emptyList(),
                    timeline =
                        listOf(
                            ReferenceTimelineEvent(
                                    family = TimelineFamily.USER,
                                    severity = TimelineSeverity.INFO,
                                    title = "Reference session created",
                                    detail =
                                        "The guided first-exchange controller is ready on $platformName.",
                                )
                                .toTimelineEntry(
                                    sessionId = sessionId,
                                    entryIndex = 1,
                                    occurredAtEpochMillis = nowProvider(),
                                )
                        ),
                    activePowerModeLabel = "Automatic",
                ),
            sessionId = sessionId,
            nowProvider = nowProvider,
        )
    private val meshLinkApi: MeshLinkApi by lazy {
        val config = meshLinkConfig {
            appId = this@LiveReferenceMeshLinkController.appId
            regulatoryRegion = RegulatoryRegion.DEFAULT
            powerMode = PowerMode.Automatic
        }
        if (platformContext != null) {
            meshLink(config = config, context = platformContext)
        } else {
            meshLink(config = config)
        }
    }
    private val sendRecorder: LiveReferenceSendRecorder = LiveReferenceSendRecorder(stateStore)
    private val sessionProjector: LiveReferenceSessionProjector =
        LiveReferenceSessionProjector(stateStore = stateStore, runtimeLogger = runtimeLogger)
    private var flowsBound: Boolean = false

    override val snapshot: StateFlow<ReferenceControllerSnapshot> = stateStore.snapshot

    override suspend fun start(): Unit {
        ensureBindings()
        val outcome = runCatching { meshLinkApi.start() }
        sessionProjector.recordMeshCall(
            result = outcome,
            successTitle = "Mesh started",
            successDetail = { result -> "mesh.start() -> $result" },
            errorTitle = "Mesh start failed",
        )
    }

    override suspend fun pause(): Unit {
        val outcome = runCatching { meshLinkApi.pause() }
        sessionProjector.recordMeshCall(
            result = outcome,
            successTitle = "Mesh paused",
            successDetail = { result -> "mesh.pause() -> $result" },
            errorTitle = "Mesh pause failed",
        )
    }

    override suspend fun resume(): Unit {
        val outcome = runCatching { meshLinkApi.resume() }
        sessionProjector.recordMeshCall(
            result = outcome,
            successTitle = "Mesh resumed",
            successDetail = { result -> "mesh.resume() -> $result" },
            errorTitle = "Mesh resume failed",
        )
    }

    override suspend fun stop(): Unit {
        val outcome = runCatching { meshLinkApi.stop() }
        sessionProjector.recordMeshCall(
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
        val outcome = runCatching {
            meshLinkApi.send(
                peerId = PeerId(peerId),
                payload = payloadText.encodeToByteArray(),
                priority = priority,
            )
        }
        outcome
            .onSuccess { result ->
                sendRecorder.recordOutcome(
                    peerId = peerId,
                    payloadText = payloadText,
                    priority = priority,
                    result = result,
                )
            }
            .onFailure { error ->
                sendRecorder.recordFailure(
                    peerId = peerId,
                    payloadText = payloadText,
                    error = error,
                )
            }
    }

    override suspend fun forgetPeer(peerId: String): Unit {
        val outcome = runCatching { meshLinkApi.forgetPeer(PeerId(peerId)) }
        outcome
            .onSuccess { result -> sessionProjector.recordPeerTrustReset(peerId, result) }
            .onFailure { error -> sessionProjector.recordPeerTrustResetFailure(peerId, error) }
    }

    override suspend fun close(): Unit {
        runCatching { meshLinkApi.stop() }
        scope.cancel()
    }

    private fun ensureBindings(): Unit {
        if (flowsBound) {
            return
        }
        flowsBound = true
        scope.launch {
            meshLinkApi.state.collect { meshState ->
                stateStore.updateSession(meshStateLabel = meshState.toString())
            }
        }
        scope.launch {
            meshLinkApi.peerEvents.collect { event ->
                applyPeerEvent(stateStore = stateStore, nowProvider = nowProvider, event = event)
            }
        }
        scope.launch {
            meshLinkApi.diagnosticEvents.collect { event ->
                sessionProjector.recordDiagnostic(event)
            }
        }
        scope.launch {
            meshLinkApi.messages.collect { message ->
                sessionProjector.recordInboundMessage(message)
            }
        }
    }
}

/** Preview fallback used when the live controller cannot be created. */
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
                            peerSuffix =
                                platformName.take(PREVIEW_PEER_PREFIX_LENGTH).uppercase() + "0001",
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

    override suspend fun close(): Unit = Unit
}

internal fun PeerConnectionState.toSnapshotState(): PeerConnectionSnapshotState {
    return when (this) {
        PeerConnectionState.CONNECTED -> PeerConnectionSnapshotState.CONNECTED
        PeerConnectionState.DISCONNECTED -> PeerConnectionSnapshotState.DISCONNECTED
    }
}

internal fun redactedSuffix(peerId: String): String {
    return peerId.takeLast(REDACTED_PEER_SUFFIX_LENGTH)
}

private const val REDACTED_PEER_SUFFIX_LENGTH: Int = 6
private const val PREVIEW_PEER_PREFIX_LENGTH: Int = 2
