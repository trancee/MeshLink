package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.ForgetPeerResult
import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.MeshLinkApi
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
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.storage.SecureStorage
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.trust.TofuTrustStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

internal class MeshEngineRuntime
private constructor(
    publishedSurface: MeshEnginePublishedRuntimeSurface,
    facadeOperations: MeshEngineRuntimeFacadeOperationsPhase,
) : MeshLinkApi {
    private val lifecycleSupport: MeshEngineLifecycleSupport = facadeOperations.lifecycleSupport
    private val sendSupport: MeshEngineSendSupport = facadeOperations.sendSupport
    private val peerForgetSupport: MeshEnginePeerForgetSupport = facadeOperations.peerForgetSupport

    override val state: StateFlow<MeshLinkState> = publishedSurface.state
    override val peerEvents: Flow<PeerEvent> = publishedSurface.peerEvents
    override val diagnosticEvents: Flow<DiagnosticEvent> = publishedSurface.diagnosticEvents
    override val messages: Flow<InboundMessage> = publishedSurface.messages

    override suspend fun start(): StartResult {
        return lifecycleSupport.start()
    }

    override suspend fun pause(): PauseResult {
        return lifecycleSupport.pause()
    }

    override suspend fun resume(): ResumeResult {
        return lifecycleSupport.resume()
    }

    override suspend fun stop(): StopResult {
        return lifecycleSupport.stop()
    }

    override suspend fun send(
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
    ): SendResult {
        return sendSupport.send(peerId = peerId, payload = payload, priority = priority)
    }

    override suspend fun forgetPeer(peerId: PeerId): ForgetPeerResult {
        return peerForgetSupport.forgetPeer(peerId)
    }

    override fun updateBattery(level: Float, isCharging: Boolean): Unit {
        lifecycleSupport.updateBattery(level = level, isCharging = isCharging)
    }

    internal companion object {
        internal fun assembleMeshEngineRuntime(
            config: MeshLinkConfig,
            localIdentity: LocalIdentity,
            secureStorage: SecureStorage,
            bleTransport: ch.trancee.meshlink.transport.BleTransport? = null,
            diagnosticSink: DiagnosticSink? = null,
            coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ): MeshEngineRuntime {
            val environment =
                buildMeshEngineRuntimeAssemblyEnvironment(
                    config = config,
                    localIdentity = localIdentity,
                    secureStorage = secureStorage,
                    bleTransport = bleTransport,
                    diagnosticSink = diagnosticSink,
                    coroutineScope = coroutineScope,
                )
            val support = buildMeshEngineRuntimeAssemblySupport(environment)
            val lateBindingContext = buildMeshEngineRuntimeLateBindingContext()
            val facadeOperations =
                RuntimeGraphAssembler(
                        environment = environment,
                        support = support,
                        lateBindingContext = lateBindingContext,
                    )
                    .assemble()
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
    diagnosticSink: DiagnosticSink?,
    coroutineScope: CoroutineScope,
): MeshEngineRuntimeAssemblyEnvironment {
    val runtimeSurface = MeshEngineRuntimeSurface(diagnosticSink = diagnosticSink)
    return MeshEngineRuntimeAssemblyEnvironment(
        config = config,
        localIdentity = localIdentity,
        trustStore = TofuTrustStore(secureStorage),
        coroutineScope = coroutineScope,
        platformBridge = MeshEnginePlatformBridge(bleTransport),
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

private class RuntimeGraphAssembler(
    private val environment: MeshEngineRuntimeAssemblyEnvironment,
    private val support: MeshEngineRuntimeAssemblySupport,
    private val lateBindingContext: MeshEngineRuntimeLateBindingContext,
) {
    private val config: MeshLinkConfig
        get() = environment.config

    private val localIdentity: LocalIdentity
        get() = environment.localIdentity

    private val trustStore: TofuTrustStore
        get() = environment.trustStore

    private val coroutineScope: CoroutineScope
        get() = environment.coroutineScope

    private val platformBridge: MeshEnginePlatformBridge
        get() = environment.platformBridge

    private val runtimeSurface: MeshEngineCompatibilityRuntimeSurface
        get() = environment.compatibilitySurface

    fun assemble(): MeshEngineRuntimeFacadeOperationsPhase {
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
        return buildMeshEngineRuntimeFacadeOperationsPhase(
            environment = environment,
            support = support,
            foundation = foundation,
            session = session,
            transferAndInbound = transferAndInbound,
        )
    }

    private suspend fun sendDirectWireFrame(
        peerId: PeerId,
        frame: DirectWireFrame,
        action: String,
        preferredMode: TransportMode? = null,
    ): TransportSendResult {
        return support.sendDirectWireFrame(peerId, frame, action, preferredMode)
    }

    @Suppress("LongParameterList")
    private fun emitDiagnostic(
        code: DiagnosticCode,
        severity: DiagnosticSeverity,
        stage: String,
        peerSuffix: String? = null,
        reason: DiagnosticReason? = null,
        metadata: Map<String, String> = emptyMap(),
    ): Unit {
        support.emitDiagnostic(code, severity, stage, peerSuffix, reason, metadata)
    }
}
