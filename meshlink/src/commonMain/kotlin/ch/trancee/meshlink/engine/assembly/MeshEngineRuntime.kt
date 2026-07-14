package ch.trancee.meshlink.engine.assembly

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.ForgetPeerResult
import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.MeshLink
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.api.PauseResult
import ch.trancee.meshlink.api.PeerEvent
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.ResumeResult
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.api.StartResult
import ch.trancee.meshlink.api.StopResult
import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticEvent
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.diagnostics.DiagnosticSink
import ch.trancee.meshlink.engine.transport.DirectWireFrame
import ch.trancee.meshlink.engine.transport.MeshEnginePlatformBridge
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.power.BatteryMonitor
import ch.trancee.meshlink.storage.SecureStorage
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.trust.TofuTrustStore
import ch.trancee.meshlink.wire.WireFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

internal data class MeshEngineRuntimeAssemblyEnvironment(
    val config: MeshLinkConfig,
    val localIdentity: LocalIdentity,
    val trustStore: TofuTrustStore,
    val coroutineScope: CoroutineScope,
    val platformBridge: MeshEnginePlatformBridge,
    val batteryMonitor: BatteryMonitor,
    val publishedSurface: MeshEnginePublishedRuntimeSurface,
    val compatibilitySurface: MeshEngineCompatibilityRuntimeSurface,
)

internal data class MeshEngineRuntimeAssemblySupport(
    val emitDiagnostic:
        (
            DiagnosticCode,
            DiagnosticSeverity,
            String,
            String?,
            DiagnosticReason?,
            Map<String, String>,
        ) -> Unit,
    val sendDirectWireFrame:
        suspend (PeerId, DirectWireFrame, String, TransportMode?) -> TransportSendResult,
)

internal class MeshEngineRuntimeLateBindingContext {
    private var routingAdvertisementSender:
        (suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean)? =
        null

    fun registerRoutingAdvertisementSender(
        sender: suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean
    ): Unit {
        check(routingAdvertisementSender == null) {
            "routingAdvertisementSender is already registered"
        }
        routingAdvertisementSender = sender
    }

    fun routingAdvertisementSender():
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean {
        return routingAdvertisementSender ?: error("routingAdvertisementSender is not registered")
    }
}

internal class MeshEngineRuntime
constructor(
    publishedSurface: MeshEnginePublishedRuntimeSurface,
    private val facadeOperations: MeshEngineRuntimeFacadeOperations,
) : MeshLink {
    override val state: StateFlow<MeshLinkState> = publishedSurface.state
    override val peerEvents: Flow<PeerEvent> = publishedSurface.peerEvents
    override val diagnosticEvents: Flow<DiagnosticEvent> = publishedSurface.diagnosticEvents
    override val messages: Flow<InboundMessage> = publishedSurface.messages

    override suspend fun start(): StartResult {
        return facadeOperations.start()
    }

    override suspend fun pause(): PauseResult {
        return facadeOperations.pause()
    }

    override suspend fun resume(): ResumeResult {
        return facadeOperations.resume()
    }

    override suspend fun stop(): StopResult {
        return facadeOperations.stop()
    }

    override suspend fun send(
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
    ): SendResult {
        return facadeOperations.send(peerId = peerId, payload = payload, priority = priority)
    }

    override suspend fun forgetPeer(peerId: PeerId): ForgetPeerResult {
        return facadeOperations.forgetPeer(peerId)
    }

    internal companion object {
        internal fun assembleMeshEngineRuntime(
            config: MeshLinkConfig,
            localIdentity: LocalIdentity,
            secureStorage: SecureStorage,
            bleTransport: ch.trancee.meshlink.transport.BleTransport? = null,
            batteryMonitor: BatteryMonitor,
            diagnosticSink: DiagnosticSink? = null,
            coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ): MeshEngineRuntime {
            val environment =
                buildMeshEngineRuntimeAssemblyEnvironment(
                    config = config,
                    localIdentity = localIdentity,
                    secureStorage = secureStorage,
                    bleTransport = bleTransport,
                    batteryMonitor = batteryMonitor,
                    diagnosticSink = diagnosticSink,
                    coroutineScope = coroutineScope,
                )
            val support = buildMeshEngineRuntimeAssemblySupport(environment)
            val lateBindingContext = buildMeshEngineRuntimeLateBindingContext()
            val foundation =
                buildMeshEngineRuntimeFoundationAssembly(
                    environment = environment,
                    support = support,
                    lateBindingContext = lateBindingContext,
                )
            val session =
                buildMeshEngineRuntimeSessionAssembly(
                    environment = environment,
                    support = support,
                    foundation = foundation,
                    lateBindingContext = lateBindingContext,
                )
            val transferAndInbound =
                buildMeshEngineRuntimeTransferAndInboundPhase(
                    environment = environment,
                    support = support,
                    foundation = foundation,
                    session = session,
                )
            val facadeOperations =
                buildMeshEngineRuntimeFacadeOperations(
                    environment = environment,
                    support = support,
                    foundation = foundation,
                    session = session,
                    transferAndInbound = transferAndInbound,
                )
            return MeshEngineRuntime(
                publishedSurface = environment.publishedSurface,
                facadeOperations = facadeOperations,
            )
        }
    }
}

private fun buildMeshEngineRuntimeAssemblyEnvironment(
    config: MeshLinkConfig,
    localIdentity: LocalIdentity,
    secureStorage: SecureStorage,
    bleTransport: ch.trancee.meshlink.transport.BleTransport?,
    batteryMonitor: BatteryMonitor,
    diagnosticSink: DiagnosticSink?,
    coroutineScope: CoroutineScope,
): MeshEngineRuntimeAssemblyEnvironment {
    val runtimeSurface =
        MeshEngineRuntimeSurface(
            initialState = MeshLinkState.Configured,
            diagnosticSink = diagnosticSink,
        )
    return MeshEngineRuntimeAssemblyEnvironment(
        config = config,
        localIdentity = localIdentity,
        trustStore = TofuTrustStore(secureStorage),
        coroutineScope = coroutineScope,
        platformBridge = MeshEnginePlatformBridge(bleTransport),
        batteryMonitor = batteryMonitor,
        publishedSurface = runtimeSurface,
        compatibilitySurface = runtimeSurface,
    )
}

private fun buildMeshEngineRuntimeAssemblySupport(
    environment: MeshEngineRuntimeAssemblyEnvironment
): MeshEngineRuntimeAssemblySupport {
    return MeshEngineRuntimeAssemblySupport(
        emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
            environment.compatibilitySurface.emitDiagnostic(
                code = code,
                severity = severity,
                stage = stage,
                peerSuffix = peerSuffix,
                reason = reason,
                metadata = metadata,
            )
        },
        sendDirectWireFrame = { peerId, frame, action, preferredMode ->
            environment.platformBridge.send(
                frame =
                    OutboundFrame(
                        peerId = peerId,
                        payload = frame.encode(),
                        preferredMode = preferredMode,
                    ),
                action = action,
            )
        },
    )
}

private fun buildMeshEngineRuntimeLateBindingContext(): MeshEngineRuntimeLateBindingContext {
    return MeshEngineRuntimeLateBindingContext()
}
