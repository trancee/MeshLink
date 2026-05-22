package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.NoiseXXHandshakeManager
import ch.trancee.meshlink.crypto.NoiseXXResponderResult
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.trust.TrustRecord

internal class MeshEngineResponderHandshakeSupport(
    private val localIdentity: LocalIdentity,
    private val trustSupport: MeshEngineTrustSupport,
    private val state: MeshEngineHandshakeState,
    private val routingContext: MeshEngineHandshakeRoutingContext,
    private val callbacks: MeshEngineHandshakeCallbacks,
) {
    suspend fun handleHandshakeMessage1(peerId: PeerId, payload: ByteArray): Unit {
        val manager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
        val message2 =
            runCatching {
                    manager.processMessage1AndCreateMessage2(localIdentity.noiseIdentity, payload)
                }
                .getOrElse { exception ->
                    callbacks.emitHopSessionFailed(
                        peerId,
                        "transport.handshake.message1",
                        DiagnosticReason.DELIVERY_FAILURE,
                        mapOf("cause" to exception::class.simpleName.orEmpty()),
                    )
                    return
                }
        val pendingHandshake = PendingResponderHandshake(manager)
        state.sessionRegistry.storePendingResponderHandshake(peerId, pendingHandshake)
        when (
            callbacks.sendDirectWireFrame(
                peerId,
                DirectWireFrame.HandshakeMessage2(message2),
                "handshake.message2",
            )
        ) {
            TransportSendResult.Delivered -> Unit
            is TransportSendResult.Dropped -> {
                state.sessionRegistry.removePendingResponderHandshake(peerId, pendingHandshake)
                callbacks.emitHopSessionFailed(
                    peerId,
                    "transport.handshake.message2.send",
                    DiagnosticReason.DELIVERY_FAILURE,
                    emptyMap(),
                )
            }
        }
    }

    suspend fun handleHandshakeMessage3(peerId: PeerId, payload: ByteArray): Unit {
        val pending = pendingResponderHandshake(peerId)
        if (pending != null) {
            val result =
                processHandshakeMessage3(peerId = peerId, payload = payload, pending = pending)
            if (result != null) {
                val trustRecord =
                    verifyHandshakeMessage3Trust(
                        peerId = peerId,
                        pending = pending,
                        result = result,
                    )
                if (trustRecord != null) {
                    completeResponderHandshake(
                        peerId = peerId,
                        pending = pending,
                        result = result,
                        trustRecord = trustRecord,
                    )
                }
            }
        }
    }

    private suspend fun pendingResponderHandshake(peerId: PeerId): PendingResponderHandshake? {
        val pending = state.sessionRegistry.pendingResponderHandshake(peerId)
        if (pending == null) {
            callbacks.emitHopSessionFailed(
                peerId,
                "transport.handshake.message3.unexpected",
                DiagnosticReason.DELIVERY_FAILURE,
                emptyMap(),
            )
        }
        return pending
    }

    private suspend fun processHandshakeMessage3(
        peerId: PeerId,
        payload: ByteArray,
        pending: PendingResponderHandshake,
    ): NoiseXXResponderResult? {
        return runCatching { pending.manager.processMessage3(localIdentity.noiseIdentity, payload) }
            .getOrElse { exception ->
                failPendingResponderHandshake(
                    peerId = peerId,
                    pending = pending,
                    stage = "transport.handshake.message3.process",
                    reason = DiagnosticReason.DELIVERY_FAILURE,
                    metadata = mapOf("cause" to exception::class.simpleName.orEmpty()),
                )
                null
            }
    }

    private suspend fun verifyHandshakeMessage3Trust(
        peerId: PeerId,
        pending: PendingResponderHandshake,
        result: NoiseXXResponderResult,
    ): TrustRecord? {
        val trustRecord =
            trustSupport.verifyAndPersistTrust(
                peerId = peerId,
                remoteEd25519PublicKey = result.remoteEd25519PublicKey,
                remoteX25519PublicKey = result.remoteStaticPublicKey,
            )
        if (trustRecord == null) {
            failPendingResponderHandshake(
                peerId = peerId,
                pending = pending,
                stage = "transport.handshake.message3.trust",
                reason = DiagnosticReason.TRUST_FAILURE,
                metadata = emptyMap(),
            )
        }
        return trustRecord
    }

    private suspend fun completeResponderHandshake(
        peerId: PeerId,
        pending: PendingResponderHandshake,
        result: NoiseXXResponderResult,
        trustRecord: TrustRecord,
    ): Unit {
        val completed =
            state.sessionRegistry.completeResponderHandshake(
                peerId = peerId,
                pendingHandshake = pending,
                session = HopSession(sendKey = result.sendKey, receiveKey = result.receiveKey),
            )
        if (!completed) {
            return
        }
        callbacks.emitHopSessionEstablished(peerId, "transport.handshake.message3.complete")
        routingContext.routingSupport.dispatchMutation(
            mutation = routingContext.routeCoordinator.onPeerConnected(peerId, trustRecord),
            stage = "transport.handshake.message3.complete",
            metadata = mapOf("connectedPeerId" to peerId.value),
        )
    }

    private suspend fun failPendingResponderHandshake(
        peerId: PeerId,
        pending: PendingResponderHandshake,
        stage: String,
        reason: DiagnosticReason,
        metadata: Map<String, String>,
    ): Unit {
        val removed = state.sessionRegistry.removePendingResponderHandshake(peerId, pending)
        if (removed == null) {
            return
        }
        callbacks.emitHopSessionFailed(peerId, stage, reason, metadata)
    }
}
