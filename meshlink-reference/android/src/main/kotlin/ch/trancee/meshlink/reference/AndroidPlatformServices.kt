package ch.trancee.meshlink.reference

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.api.MeshLinkBootstrap
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.api.PeerId
import kotlin.time.Duration.Companion.seconds
import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.config.PowerMode
import ch.trancee.meshlink.config.RegulatoryRegion
import ch.trancee.meshlink.api.meshLink
import ch.trancee.meshlink.reference.automation.AUTOMATION_MODE_LIVE_PROOF
import ch.trancee.meshlink.reference.automation.AUTOMATION_MODE_SCRIPTED_UI
import ch.trancee.meshlink.reference.automation.AUTOMATION_ROLE_PASSIVE
import ch.trancee.meshlink.reference.automation.AUTOMATION_SCENARIO_DIRECT_GUIDED
import ch.trancee.meshlink.reference.platform.DefaultPlatformServices
import ch.trancee.meshlink.reference.platform.DefaultPlatformServicesOptions
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.PeerConnectionSnapshotState
import ch.trancee.meshlink.reference.model.PeerSnapshot
import ch.trancee.meshlink.reference.model.PeerTrustState
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import ch.trancee.meshlink.reference.platform.PlatformServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

import ch.trancee.meshlink.reference.model.REFERENCE_AUTHORITY_MODE_LIVE

private const val AUTOMATION_LOG_TAG = "MeshLinkReferenceAutomation"
private const val PEER_SUFFIX_LENGTH: Int = 6

internal fun createPlatformServices(context: Context): AndroidPlatformServices {
    Log.i("MeshLinkReferenceAutomation", "REFERENCE_AUTOMATION android.factory.begin scripted")
    return AndroidPlatformServices(
        context = context.applicationContext,
        readinessGuidance = readinessGuidance(),
        readinessBlockersFactory = { readinessBlockers(context) },
        meshLinkControllerFactory = {
            createPublicMeshLinkController(
                PublicMeshLinkControllerArgs(
                    appId = "demo.meshlink.reference",
                    authorityMode = REFERENCE_AUTHORITY_MODE_LIVE,
                    scenarioId = AUTOMATION_SCENARIO_DIRECT_GUIDED,
                    storageSubdirectory = "default",
                    bootstrap = createMeshLinkBootstrap(context),
                    currentTimeMillis = { System.currentTimeMillis() },
                ),
            )
        },
    )
}

internal fun createAutomationPlatformServices(
    context: Context,
    storageSubdirectory: String,
    blocked: Boolean,
): AndroidPlatformServices {
    Log.i(
        "MeshLinkReferenceAutomation",
        "REFERENCE_AUTOMATION android.factory.begin live blocked=$blocked storageSubdirectory=$storageSubdirectory",
    )
    Log.i("MeshLinkReferenceAutomation", "REFERENCE_AUTOMATION android.factory.controller.ready live")
    return AndroidPlatformServices(
        context = context.applicationContext,
        readinessGuidance = readinessGuidance(),
        readinessBlockersFactory = { if (blocked) readinessBlockers(context) else emptyList() },
        meshLinkControllerFactory = {
            createPublicMeshLinkController(
                PublicMeshLinkControllerArgs(
                    appId = "demo.meshlink.reference.automation",
                    authorityMode = REFERENCE_AUTHORITY_MODE_LIVE,
                    scenarioId = AUTOMATION_SCENARIO_DIRECT_GUIDED,
                    storageSubdirectory = normalizeAutomationStorageSubdirectory(storageSubdirectory, "default"),
                    bootstrap = createMeshLinkBootstrap(context),
                    currentTimeMillis = { System.currentTimeMillis() },
                )
            )
        },
    )
}

@Suppress("LongMethod")
internal fun createLiveAutomationPlatformServices(
    args: LiveAutomationPlatformServicesArgs,
): AndroidPlatformServices {
    Log.i(
        "MeshLinkReferenceAutomation",
        "REFERENCE_AUTOMATION android.live.factory.begin role=${args.role} " +
            "storageSubdirectory=${args.storageSubdirectory}",
    )
    val guidance = readinessGuidance()
    Log.i(
        "MeshLinkReferenceAutomation",
        "REFERENCE_AUTOMATION android.live.factory.guidance.ready role=${args.role}",
    )
    Log.i(
        "MeshLinkReferenceAutomation",
        "REFERENCE_AUTOMATION android.live.factory.construct.begin role=${args.role}",
    )
    val appContext = args.context.applicationContext
    Log.i(
        "MeshLinkReferenceAutomation",
        "REFERENCE_AUTOMATION android.live.factory.appContext.ready role=${args.role}",
    )
    val automationStorageSubdirectory =
        normalizeAutomationStorageSubdirectory(args.storageSubdirectory, "default")
    Log.i(
        "MeshLinkReferenceAutomation",
        "REFERENCE_AUTOMATION android.live.factory.storage.ready role=${args.role} " +
            "storageSubdirectory=$automationStorageSubdirectory",
    )
    Log.i(
        "MeshLinkReferenceAutomation",
        "REFERENCE_AUTOMATION android.live.factory.config.skipped role=${args.role}",
    )
    Log.i(
        "MeshLinkReferenceAutomation",
        "REFERENCE_AUTOMATION android.live.factory.before.bootstrap role=${args.role}",
    )
    val meshLinkBootstrap = createMeshLinkBootstrap(appContext)
    Log.i(
        "MeshLinkReferenceAutomation",
        "REFERENCE_AUTOMATION android.live.factory.after.bootstrap role=${args.role}",
    )
    Log.i(
        "MeshLinkReferenceAutomation",
        "REFERENCE_AUTOMATION android.live.factory.before.services role=${args.role}",
    )
    val services =
        AndroidPlatformServices(
            context = appContext,
            readinessGuidance = guidance,
            readinessBlockersFactory = { emptyList() },
            meshLinkControllerFactory = {
                createPublicMeshLinkController(
                    PublicMeshLinkControllerArgs(
                        appId = args.appId,
                        authorityMode = REFERENCE_AUTHORITY_MODE_LIVE,
                        scenarioId = args.scenario,
                        storageSubdirectory = automationStorageSubdirectory,
                        bootstrap = meshLinkBootstrap,
                        currentTimeMillis = { System.currentTimeMillis() },
                    ),
                )
            },
        )
    Log.i(
        "MeshLinkReferenceAutomation",
        "REFERENCE_AUTOMATION android.live.factory.end role=${args.role}",
    )
    return services
}

internal class AndroidPlatformServices(
    private val context: Context,
    val readinessGuidance: List<String>,
    private val readinessBlockersFactory: () -> List<String> = { emptyList() },
    private val meshLinkControllerFactory: () -> ReferenceMeshLinkController,
) {
    private var readinessBlockersMemo: List<String>? = null
    private var meshLinkControllerMemo: ReferenceMeshLinkController? = null

    val readinessBlockers: List<String>
        get() = readinessBlockersMemo ?: readinessBlockersFactory().also { readinessBlockersMemo = it }

    val meshLinkController: ReferenceMeshLinkController
        get() = meshLinkControllerMemo ?: meshLinkControllerFactory().also { meshLinkControllerMemo = it }
    val platformName: String = "Android"
    val defaultAuthorityMode: String = REFERENCE_AUTHORITY_MODE_LIVE
    val powerMitigationStatus: String? = null
    val documentStore: Any? = null

    fun stopPowerMitigation(): Unit = Unit

    fun currentTimeMillis(): Long = System.currentTimeMillis()

    fun emitAutomationLog(message: String): Unit {
        Log.i(AUTOMATION_LOG_TAG, message)
    }
}

private fun createMeshLinkBootstrap(context: Context): MeshLinkBootstrap {
    Log.i(
        "MeshLinkReferenceAutomation",
        "REFERENCE_AUTOMATION android.live.bootstrap.before context role=bootstrap",
    )
    val appContext = context.applicationContext
    Log.i(
        "MeshLinkReferenceAutomation",
        "REFERENCE_AUTOMATION android.live.bootstrap.after context role=bootstrap",
    )
    val bootstrap = ch.trancee.meshlink.api.android.meshLinkBootstrap(appContext)
    Log.i(
        "MeshLinkReferenceAutomation",
        "REFERENCE_AUTOMATION android.live.bootstrap.after ctor role=bootstrap",
    )
    return bootstrap
}

internal data class LiveAutomationPlatformServicesArgs(
    val context: Context,
    val storageSubdirectory: String,
    val appId: String,
    val role: String,
    val requiredPeerCount: Int = 1,
    val targetPeerIndex: Int = 0,
    val targetPeerId: String? = null,
    val scenario: String = AUTOMATION_SCENARIO_DIRECT_GUIDED,
)

private data class PublicMeshLinkControllerArgs(
    val appId: String,
    val authorityMode: String,
    val scenarioId: String,
    val storageSubdirectory: String,
    val bootstrap: ch.trancee.meshlink.api.MeshLinkBootstrap,
    val currentTimeMillis: () -> Long,
)

private fun createPublicMeshLinkController(
    args: PublicMeshLinkControllerArgs,
): ReferenceMeshLinkController {
    val meshLinkRuntime: MeshLink =
        meshLink(
            config =
                MeshLinkConfig(
                    appId = args.appId,
                    regulatoryRegion = RegulatoryRegion.DEFAULT,
                    powerMode = PowerMode.Automatic,
                    deliveryRetryDeadline = 15.seconds,
                ),
            bootstrap = args.bootstrap,
        )
    return PublicMeshLinkController(
        meshLinkRuntime = meshLinkRuntime,
        currentTimeMillis = args.currentTimeMillis,
        authorityMode = args.authorityMode,
        scenarioId = args.scenarioId,
        storageSubdirectory = args.storageSubdirectory,
        appId = args.appId,
    )
}

private class PublicMeshLinkController(
    private val meshLinkRuntime: MeshLink,
    private val currentTimeMillis: () -> Long,
    authorityMode: String,
    scenarioId: String,
    storageSubdirectory: String,
    appId: String,
) : ReferenceMeshLinkController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionId = "$appId-${currentTimeMillis()}"
    private val startedAt = currentTimeMillis()
    private val peers: LinkedHashMap<String, PeerSnapshot> = linkedMapOf()
    private val timeline: MutableList<TimelineEntry> = mutableListOf()
    private val updateSnapshot: ((ReferenceControllerSnapshot) -> ReferenceControllerSnapshot) -> Unit =
        { transform ->
            _snapshot.value = transform(_snapshot.value)
        }
    private val timelineContext =
        TimelineAppendContext(
            sessionId = sessionId,
            currentTimeMillis = currentTimeMillis,
            timeline = timeline,
            updateSnapshot = updateSnapshot,
        )
    private val _snapshot =
        MutableStateFlow(
            ReferenceControllerSnapshot(
                session =
                    ReferenceSession(
                        sessionId = sessionId,
                        scenarioId = scenarioId,
                        authorityMode = authorityMode,
                        startedAtEpochMillis = startedAt,
                        meshStateLabel = MeshLinkState.Uninitialized.toString(),
                        configurationSnapshot =
                            mapOf(
                                "appId" to appId,
                                "storageSubdirectory" to storageSubdirectory,
                                "scenarioId" to scenarioId,
                            ),
                    ),
                peers = emptyList(),
                timeline = emptyList(),
                activePowerModeLabel = PowerMode.Automatic::class.simpleName ?: "Automatic",
            )
        )

    override val snapshot: StateFlow<ReferenceControllerSnapshot> = _snapshot.asStateFlow()

    init {
        scope.launch {
            meshLinkRuntime.state.collect { state ->
                updateSnapshot { current ->
                    current.copy(
                        session = current.session.copy(meshStateLabel = state.toString()),
                    )
                }
            }
        }
        scope.launch {
            meshLinkRuntime.peerEvents.collect { event ->
                when (event) {
                    is PeerEvent.Found -> updatePeer(event.peerId.value, event.state, "found")
                    is PeerEvent.StateChanged ->
                        updatePeer(event.peerId.value, event.state, "state-changed")
                    is PeerEvent.Lost -> removePeer(event.peerId.value)
                }
            }
        }
        scope.launch {
            meshLinkRuntime.diagnosticEvents.collect { event ->
                appendTimeline(
                    timelineContext,
                    TimelineAppendSpec(
                        family = TimelineFamily.DIAGNOSTIC,
                        severity = TimelineSeverity.DEBUG,
                        title = "diagnostic",
                        detail = event.toString(),
                    ),
                )
            }
        }
        scope.launch {
            meshLinkRuntime.messages.collect { message ->
                appendTimeline(
                    timelineContext,
                    TimelineAppendSpec(
                        family = TimelineFamily.MESSAGE,
                        severity = TimelineSeverity.SUCCESS,
                        title = "message",
                        detail = message.toString(),
                        peerSuffix = message.originPeerId.value.takeLast(PEER_SUFFIX_LENGTH),
                        payloadPreview = String(message.payload),
                        payloadSizeBytes = message.payload.size,
                    ),
                )
            }
        }
    }

    override suspend fun start(): Unit {
        val result = meshLinkRuntime.start()
        appendTimeline(
            timelineContext,
            TimelineAppendSpec(
                TimelineFamily.LIFECYCLE,
                TimelineSeverity.SUCCESS,
                "start",
                result.toString(),
            ),
        )
    }

    override suspend fun pause(): Unit {
        val result = meshLinkRuntime.pause()
        appendTimeline(
            timelineContext,
            TimelineAppendSpec(
                TimelineFamily.LIFECYCLE,
                TimelineSeverity.INFO,
                "pause",
                result.toString(),
            ),
        )
    }

    override suspend fun resume(): Unit {
        val result = meshLinkRuntime.resume()
        appendTimeline(
            timelineContext,
            TimelineAppendSpec(
                TimelineFamily.LIFECYCLE,
                TimelineSeverity.INFO,
                "resume",
                result.toString(),
            ),
        )
    }

    override suspend fun stop(): Unit {
        val result = meshLinkRuntime.stop()
        appendTimeline(
            timelineContext,
            TimelineAppendSpec(
                TimelineFamily.LIFECYCLE,
                TimelineSeverity.INFO,
                "stop",
                result.toString(),
            ),
        )
        updateSnapshot { current ->
            current.copy(session = current.session.copy(endedAtEpochMillis = currentTimeMillis()))
        }
    }

    override suspend fun sendPayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
    ): Unit {
        val result =
            meshLinkRuntime.send(
                peerId = PeerId(peerId),
                payload = payloadText.encodeToByteArray(),
                priority = priority,
            )
        appendTimeline(
            timelineContext,
            TimelineAppendSpec(
                TimelineFamily.MESSAGE,
                TimelineSeverity.SUCCESS,
                "send",
                result.toString(),
                peerSuffix = peerId.takeLast(PEER_SUFFIX_LENGTH),
                payloadPreview = payloadText,
                payloadSizeBytes = payloadText.encodeToByteArray().size,
            ),
        )
    }

    override suspend fun forgetPeer(peerId: String): Unit {
        val result = meshLinkRuntime.forgetPeer(PeerId(peerId))
        appendTimeline(
            timelineContext,
            TimelineAppendSpec(
                TimelineFamily.PEER,
                TimelineSeverity.INFO,
                "forget",
                result.toString(),
                peerSuffix = peerId.takeLast(PEER_SUFFIX_LENGTH),
            ),
        )
    }

    override suspend fun close(): Unit {
        scope.cancel()
        meshLinkRuntime.stop()
    }

    private fun updatePeer(
        peerId: String,
        state: ch.trancee.meshlink.api.PeerConnectionState,
        reason: String,
    ): Unit {
        val peer =
            peers[peerId]
                ?: PeerSnapshot(
                    peerId = peerId,
                    peerSuffix = peerId.takeLast(PEER_SUFFIX_LENGTH),
                    trustState = PeerTrustState.UNKNOWN,
                    connectionState = PeerConnectionSnapshotState.DISCONNECTED,
                )
        peers[peerId] =
            peer.copy(
                connectionState =
                    when (state) {
                        ch.trancee.meshlink.api.PeerConnectionState.CONNECTED ->
                            PeerConnectionSnapshotState.CONNECTED
                        ch.trancee.meshlink.api.PeerConnectionState.DISCONNECTED ->
                            PeerConnectionSnapshotState.DISCONNECTED
                    },
                lastSeenAtEpochMillis = currentTimeMillis(),
            )
        appendTimeline(
            timelineContext,
            TimelineAppendSpec(
                family = TimelineFamily.PEER,
                severity = TimelineSeverity.INFO,
                title = reason,
                detail = state.toString(),
                peerSuffix = peerId.takeLast(PEER_SUFFIX_LENGTH),
            ),
        )
        if (state == ch.trancee.meshlink.api.PeerConnectionState.CONNECTED) {
            appendTimeline(
                timelineContext,
                TimelineAppendSpec(
                    family = TimelineFamily.DIAGNOSTIC,
                    severity = TimelineSeverity.INFO,
                    title = "route.available",
                    detail = "peerId=$peerId routeAvailable=true",
                    peerSuffix = peerId.takeLast(PEER_SUFFIX_LENGTH),
                ),
            )
        }
        refreshSnapshotPeers()
    }

    private fun removePeer(peerId: String): Unit {
        val peer =
            peers[peerId]
                ?: return
        peers[peerId] =
            peer.copy(
                connectionState = PeerConnectionSnapshotState.DISCONNECTED,
                trustState = PeerTrustState.FORGOTTEN,
            )
        appendTimeline(
            timelineContext,
            TimelineAppendSpec(
                family = TimelineFamily.PEER,
                severity = TimelineSeverity.INFO,
                title = "lost",
                detail = peerId,
                peerSuffix = peerId.takeLast(PEER_SUFFIX_LENGTH),
            ),
        )
        refreshSnapshotPeers()
    }

    private fun refreshSnapshotPeers(): Unit {
        updateSnapshot { current -> current.copy(peers = peers.values.toList()) }
    }

}

private fun readinessGuidance(): List<String> {
    return listOf(
        "Confirm Bluetooth is enabled and the Android device is on API 26 or newer.",
        "Use the debug install path so runtime permissions are granted where the platform allows it.",
        "Keep the device awake during direct proof on aggressive OEM builds, or rely on " +
            "the live-proof foreground wake-lock mitigation when the reference app starts it; " +
            "doze can stall BLE discovery on some devices.",
        "Keep the device offline and near the peer before starting the guided exchange.",
    )
}

private fun readinessBlockers(context: Context): List<String> {
    val missingPermissions =
        requiredPermissions(Build.VERSION.SDK_INT).filterNot { permission ->
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
    return readinessBlockers(
        missingPermissions = missingPermissions,
        powerManagement = powerManagementBlockers(context),
    )
}

private fun requiredPermissions(sdkInt: Int): List<String> {
    return if (sdkInt >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

private fun readinessBlockers(
    missingPermissions: List<String>,
    powerManagement: List<String> = emptyList(),
): List<String> {
    val blockers = mutableListOf<String>()
    if (missingPermissions.isNotEmpty()) {
        val permissionNames =
            missingPermissions.joinToString(separator = ", ") { permission ->
                permission.substringAfterLast('.')
            }
        blockers +=
            "Grant the required Android BLE permissions before starting MeshLink: $permissionNames."
        blockers +=
            "Some Android devices also require Location permission before BLE scan results become visible."
    }
    blockers += powerManagement
    return blockers
}



private fun normalizeAutomationStorageSubdirectory(
    raw: String?,
    defaultValue: String = "default",
): String {
    val candidate = raw?.trim().orEmpty()
    return when {
        candidate.isBlank() -> defaultValue
        candidate == "." || candidate == ".." -> defaultValue
        candidate.any { it == '/' || it == '\\' || it.isISOControl() } -> defaultValue
        else -> candidate
    }
}
