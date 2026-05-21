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
        state.pendingResponderHandshakes[peerId.value] = PendingResponderHandshake(manager)
        when (
            callbacks.sendDirectWireFrame(
                peerId,
                DirectWireFrame.HandshakeMessage2(message2),
                "handshake.message2",
            )
        ) {
            TransportSendResult.Delivered -> Unit
            is TransportSendResult.Dropped -> {
                state.pendingResponderHandshakes.remove(peerId.value)
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
                val trustRecord = verifyHandshakeMessage3Trust(peerId = peerId, result = result)
                if (trustRecord != null) {
                    completeResponderHandshake(
                        peerId = peerId,
                        result = result,
                        trustRecord = trustRecord,
                    )
                }
            }
        }
    }

    private fun pendingResponderHandshake(peerId: PeerId): PendingResponderHandshake? {
        val pending = state.pendingResponderHandshakes.remove(peerId.value)
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

    private fun processHandshakeMessage3(
        peerId: PeerId,
        payload: ByteArray,
        pending: PendingResponderHandshake,
    ): NoiseXXResponderResult? {
        return runCatching { pending.manager.processMessage3(localIdentity.noiseIdentity, payload) }
            .getOrElse { exception ->
                callbacks.emitHopSessionFailed(
                    peerId,
                    "transport.handshake.message3.process",
                    DiagnosticReason.DELIVERY_FAILURE,
                    mapOf("cause" to exception::class.simpleName.orEmpty()),
                )
                null
            }
    }

    private suspend fun verifyHandshakeMessage3Trust(
        peerId: PeerId,
        result: NoiseXXResponderResult,
    ): TrustRecord? {
        val trustRecord =
            trustSupport.verifyAndPersistTrust(
                peerId = peerId,
                remoteEd25519PublicKey = result.remoteEd25519PublicKey,
                remoteX25519PublicKey = result.remoteStaticPublicKey,
            )
        if (trustRecord == null) {
            callbacks.emitHopSessionFailed(
                peerId,
                "transport.handshake.message3.trust",
                DiagnosticReason.TRUST_FAILURE,
                emptyMap(),
            )
        }
        return trustRecord
    }

    private fun completeResponderHandshake(
        peerId: PeerId,
        result: NoiseXXResponderResult,
        trustRecord: TrustRecord,
    ): Unit {
        state.hopSessions[peerId.value] =
            HopSession(sendKey = result.sendKey, receiveKey = result.receiveKey)
        callbacks.emitHopSessionEstablished(peerId, "transport.handshake.message3.complete")
        routingContext.routingSupport.dispatchMutation(
            mutation = routingContext.routeCoordinator.onPeerConnected(peerId, trustRecord),
            stage = "transport.handshake.message3.complete",
            metadata = mapOf("connectedPeerId" to peerId.value),
        )
    }
}
