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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

import ch.trancee.meshlink.reference.model.REFERENCE_AUTHORITY_MODE_LIVE

private const val AUTOMATION_LOG_TAG: String = "MeshLinkReferenceAutomation"
private const val PEER_SUFFIX_LENGTH: Int = 6
private const val FNV_OFFSET_BASIS: UInt = 0x811C9DC5u
private const val FNV_PRIME: UInt = 16777619u
private const val BYTE_MASK: UInt = 0xFFu
private const val USHORT_MASK: UInt = 0xFFFFu
private const val FNV_FOLD_SHIFT: Int = 16

internal fun createPlatformServices(
    context: Context,
    appId: String,
    targetPeerId: String? = null,
): AndroidPlatformServices {
    Log.i("MeshLinkReferenceAutomation", "REFERENCE_AUTOMATION android.factory.begin scripted")
    val meshHash = computeAppMeshHash(appId)
    Log.i(
        AUTOMATION_LOG_TAG,
        buildString {
            append("REFERENCE_AUTOMATION startup.meshHashSummary appId=")
            append(appId)
            append(" activeMeshHash=")
            append(meshHash)
            append(" advertisedMeshHash=")
            append(meshHash)
        },
    )
    return AndroidPlatformServices(
        context = context.applicationContext,
        readinessGuidance = readinessGuidance(),
        readinessBlockersFactory = { readinessBlockers(context) },
        meshLinkControllerFactory = {
            val bootstrap = createMeshLinkBootstrap(context)
            createPublicMeshLinkController(
                PublicMeshLinkControllerArgs(
                    appId = appId,
                    authorityMode = REFERENCE_AUTHORITY_MODE_LIVE,
                    scenarioId = "direct-guided",
                    storageSubdirectory = "default",
                    bootstrap = bootstrap,
                    currentTimeMillis = { System.currentTimeMillis() },
                    targetPeerId = targetPeerId,
                ),
            )
        },
    )
}

private data class PublicMeshLinkControllerArgs(
    val appId: String,
    val authorityMode: String,
    val scenarioId: String,
    val storageSubdirectory: String,
    val bootstrap: ch.trancee.meshlink.api.MeshLinkBootstrap,
    val currentTimeMillis: () -> Long,
    val targetPeerId: String? = null,
)

@Suppress("LongMethod")
private fun createPublicMeshLinkController(
    args: PublicMeshLinkControllerArgs,
): ReferenceMeshLinkController {
    val controller =
        PublicMeshLinkController(
            meshLinkRuntimeFactory = {
                Log.i(
                    AUTOMATION_LOG_TAG,
                    buildString {
                        append("REFERENCE_AUTOMATION android.meshlink.begin create appId=")
                        append(args.appId)
                        append(" scenario=")
                        append(args.scenarioId)
                        append(" storage=")
                        append(args.storageSubdirectory)
                    },
                )
                Log.i(
                    AUTOMATION_LOG_TAG,
                    buildString {
                        append("REFERENCE_AUTOMATION android.meshlink.construct.begin appId=")
                        append(args.appId)
                        append(" scenario=")
                        append(args.scenarioId)
                        append(" thread=")
                        append(Thread.currentThread().name)
                    },
                )
                val runtime =
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
                Log.i(
                    AUTOMATION_LOG_TAG,
                    buildString {
                        append("REFERENCE_AUTOMATION android.meshlink.construct.end appId=")
                        append(args.appId)
                        append(" scenario=")
                        append(args.scenarioId)
                        append(" thread=")
                        append(Thread.currentThread().name)
                    },
                )
                Log.i(
                    AUTOMATION_LOG_TAG,
                    buildString {
                        append("REFERENCE_AUTOMATION android.meshlink.end create appId=")
                        append(args.appId)
                        append(" scenario=")
                        append(args.scenarioId)
                        append(" storage=")
                        append(args.storageSubdirectory)
                    },
                )
                runtime
            },
            currentTimeMillis = args.currentTimeMillis,
            authorityMode = args.authorityMode,
            scenarioId = args.scenarioId,
            storageSubdirectory = args.storageSubdirectory,
            appId = args.appId,
            targetPeerId = args.targetPeerId,
        )
    return controller
}

@Suppress("TooManyFunctions", "LongParameterList")
private class PublicMeshLinkController(
    private val meshLinkRuntimeFactory: () -> MeshLink,
    private val currentTimeMillis: () -> Long,
    private val targetPeerId: String?,
    authorityMode: String,
    scenarioId: String,
    storageSubdirectory: String,
    appId: String,
) : ReferenceMeshLinkController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runtimeMutex = Mutex()
    @Volatile private var meshLinkRuntime: MeshLink? = null
    @Volatile private var runtimeCollectorsStarted: Boolean = false
    @Volatile private var discoveryWatchScheduled: Boolean = false
    private val sessionId = "$appId-${currentTimeMillis()}"
    private val targetPeerSuffix: String? = targetPeerId?.takeLast(PEER_SUFFIX_LENGTH)
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

    private suspend fun resolveMeshLinkRuntime(): MeshLink =
        meshLinkRuntime
            ?: runtimeMutex.withLock {
                meshLinkRuntime
                    ?: withContext(Dispatchers.Default) { meshLinkRuntimeFactory() }
                        .also { created ->
                            meshLinkRuntime = created
                            if (!runtimeCollectorsStarted) {
                                runtimeCollectorsStarted = true
                                startRuntimeCollectors(created)
                            }
                        }
            }

    private fun startRuntimeCollectors(runtime: MeshLink): Unit {
        observeMeshState(runtime)
        observePeerEvents(runtime)
        observeDiagnosticEvents(runtime)
        observeMessages(runtime)
    }

    private fun observeMeshState(runtime: MeshLink): Unit {
        scope.launch {
            runtime.state.collect { state ->
                Log.i(
                    AUTOMATION_LOG_TAG,
                    buildString {
                        append("REFERENCE_AUTOMATION runtime.state value=")
                        append(state)
                    },
                )
                updateSnapshot { current ->
                    current.copy(
                        session = current.session.copy(meshStateLabel = state.toString()),
                    )
                }
                logPeerSnapshot("meshState=$state")
                if (state == MeshLinkState.Running && !discoveryWatchScheduled) {
                    discoveryWatchScheduled = true
                    scope.launch {
                        delay(3.seconds)
                        val current = snapshot.value
                        if (current.peers.isEmpty()) {
                            Log.i(
                                AUTOMATION_LOG_TAG,
                                buildString {
                                    append("REFERENCE_AUTOMATION peer.discovery.pending elapsedSeconds=3.0 count=")
                                    append(current.peers.size)
                                    append(" selectedPeerId=")
                                    append(current.session.selectedPeerId ?: "none")
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun observePeerEvents(runtime: MeshLink): Unit {
        scope.launch {
            Log.i(AUTOMATION_LOG_TAG, "REFERENCE_AUTOMATION runtime.peerEvents.collect.begin")
            runtime.peerEvents.collect { event ->
                when (event) {
                    is PeerEvent.Found -> {
                        if (!shouldTrackPeer(event.peerId.value)) return@collect
                        Log.i(
                            AUTOMATION_LOG_TAG,
                            buildString {
                                append("REFERENCE_AUTOMATION runtime.peerEvent type=Found peerId=")
                                append(event.peerId.value)
                                append(" state=")
                                append(event.state)
                            },
                        )
                        updatePeer(event.peerId.value, event.state, "found")
                    }
                    is PeerEvent.StateChanged -> {
                        if (!shouldTrackPeer(event.peerId.value)) return@collect
                        Log.i(
                            AUTOMATION_LOG_TAG,
                            buildString {
                                append("REFERENCE_AUTOMATION runtime.peerEvent type=StateChanged peerId=")
                                append(event.peerId.value)
                                append(" state=")
                                append(event.state)
                            },
                        )
                        updatePeer(event.peerId.value, event.state, "state-changed")
                    }
                    is PeerEvent.Lost -> {
                        if (!shouldTrackPeer(event.peerId.value)) return@collect
                        Log.i(
                            AUTOMATION_LOG_TAG,
                            buildString {
                                append("REFERENCE_AUTOMATION runtime.peerEvent type=Lost peerId=")
                                append(event.peerId.value)
                            },
                        )
                        removePeer(event.peerId.value)
                    }
                }
            }
        }
    }

    private fun observeDiagnosticEvents(runtime: MeshLink): Unit {
        scope.launch {
            Log.i(AUTOMATION_LOG_TAG, "REFERENCE_AUTOMATION runtime.diagnosticEvents.collect.begin")
            runtime.diagnosticEvents.collect { event ->
                if (!shouldTrackPeerSuffix(event.peerSuffix)) return@collect
                Log.i(
                    AUTOMATION_LOG_TAG,
                    buildString {
                        append("REFERENCE_AUTOMATION runtime.diagnostic code=")
                        append(event.code)
                        append(" severity=")
                        append(event.severity)
                        append(" stage=")
                        append(event.stage)
                        append(" peerSuffix=")
                        append(event.peerSuffix ?: "none")
                        append(" reason=")
                        append(event.reason ?: "none")
                    },
                )
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
    }

    private fun observeMessages(runtime: MeshLink): Unit {
        scope.launch {
            runtime.messages.collect { message ->
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
        val result = resolveMeshLinkRuntime().start()
        Log.i(
            AUTOMATION_LOG_TAG,
            buildString {
                append("REFERENCE_AUTOMATION runtime.start result=")
                append(result)
            },
        )
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
        val result = resolveMeshLinkRuntime().pause()
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
        val result = resolveMeshLinkRuntime().resume()
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
        val result = resolveMeshLinkRuntime().stop()
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
            resolveMeshLinkRuntime().send(
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
        val result = resolveMeshLinkRuntime().forgetPeer(PeerId(peerId))
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
        meshLinkRuntime?.let { runCatching { it.stop() } }
    }

    private fun logPeerSnapshot(reason: String): Unit {
        val current = snapshot.value
        Log.i(
            AUTOMATION_LOG_TAG,
            buildString {
                append("REFERENCE_AUTOMATION peer.snapshot reason=")
                append(reason)
                append(" count=")
                append(current.peers.size)
                append(" selectedPeerId=")
                append(current.session.selectedPeerId ?: "none")
                append(" suffixes=")
                append(current.peers.joinToString(prefix = "[", postfix = "]") { it.peerSuffix })
            },
        )
    }

    private fun shouldTrackPeer(peerId: String): Boolean {
        return targetPeerId == null || peerId == targetPeerId
    }

    private fun shouldTrackPeerSuffix(peerSuffix: String?): Boolean {
        return targetPeerSuffix == null || peerSuffix == targetPeerSuffix
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
        logPeerSnapshot("updatePeer reason=$reason peerId=$peerId state=$state")
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
        logPeerSnapshot("removePeer peerId=$peerId")
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

private fun computeAppMeshHash(appId: String): UShort {
    val bytes = appId.encodeToByteArray()
    val seedBytes = if (bytes.isNotEmpty()) bytes else byteArrayOf(0)
    var state = FNV_OFFSET_BASIS
    for (byte in seedBytes) {
        state = (state xor (byte.toUInt() and BYTE_MASK)) * FNV_PRIME
    }
    val folded = (((state shr FNV_FOLD_SHIFT) xor (state and USHORT_MASK)) and USHORT_MASK)
    return folded.toUShort()
}


