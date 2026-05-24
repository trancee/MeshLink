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
import ch.trancee.meshlink.diagnostics.DiagnosticEvent
import ch.trancee.meshlink.diagnostics.DiagnosticSink
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.storage.SecureStorage
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.trust.TofuTrustStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

internal class MeshEngineRuntime
constructor(
    publishedSurface: MeshEnginePublishedRuntimeSurface,
    private val facadeOperations: MeshEngineRuntimeFacadeOperations,
) : MeshLinkApi {
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

    override fun updateBattery(level: Float, isCharging: Boolean): Unit {
        facadeOperations.updateBattery(level = level, isCharging = isCharging)
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
