package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.NoiseXXHandshakeResult
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.trust.TrustRecord

internal class MeshEngineInitiatorHandshakeSupport(
    private val localIdentity: LocalIdentity,
    private val trustSupport: MeshEngineTrustSupport,
    private val state: MeshEngineHandshakeState,
    private val routingContext: MeshEngineHandshakeRoutingContext,
    private val callbacks: MeshEngineHandshakeCallbacks,
) {
    suspend fun handleHandshakeMessage2(peerId: PeerId, payload: ByteArray): Unit {
        val pending = pendingInitiatorHandshake(peerId)
        if (pending != null) {
            val result =
                processHandshakeMessage2(peerId = peerId, payload = payload, pending = pending)
            if (result != null) {
                val trustRecord =
                    verifyHandshakeMessage2Trust(
                        peerId = peerId,
                        pending = pending,
                        result = result,
                    )
                if (trustRecord != null) {
                    sendHandshakeMessage3(
                        peerId = peerId,
                        pending = pending,
                        result = result,
                        trustRecord = trustRecord,
                    )
                }
            }
        }
    }

    private suspend fun pendingInitiatorHandshake(peerId: PeerId): PendingInitiatorHandshake? {
        val pending =
            (state.sessionRegistry.initiatorHandshakeReservation(peerId)
                    as? InitiatorHandshakeReservation.Pending)
                ?.pendingHandshake
        if (pending == null) {
            callbacks.emitHopSessionFailed(
                peerId,
                "transport.handshake.message2.unexpected",
                DiagnosticReason.DELIVERY_FAILURE,
                emptyMap(),
            )
        }
        return pending
    }

    private suspend fun processHandshakeMessage2(
        peerId: PeerId,
        payload: ByteArray,
        pending: PendingInitiatorHandshake,
    ): NoiseXXHandshakeResult? {
        return runCatching {
                pending.manager.processMessage2AndCreateMessage3(
                    localIdentity.noiseIdentity,
                    payload,
                )
            }
            .getOrElse { exception ->
                failPendingInitiatorHandshake(
                    peerId = peerId,
                    pending = pending,
                    failure =
                        PendingInitiatorHandshakeFailure(
                            outcome = SessionEstablishmentOutcome.Unreachable,
                            stage = "transport.handshake.message2.process",
                            reason = DiagnosticReason.DELIVERY_FAILURE,
                            metadata = mapOf("cause" to exception::class.simpleName.orEmpty()),
                        ),
                )
                null
            }
    }

    private suspend fun verifyHandshakeMessage2Trust(
        peerId: PeerId,
        pending: PendingInitiatorHandshake,
        result: NoiseXXHandshakeResult,
    ): TrustRecord? {
        val trustRecord =
            trustSupport.verifyAndPersistTrust(
                peerId = peerId,
                remoteEd25519PublicKey = result.remoteEd25519PublicKey,
                remoteX25519PublicKey = result.remoteStaticPublicKey,
            )
        if (trustRecord == null) {
            failPendingInitiatorHandshake(
                peerId = peerId,
                pending = pending,
                failure =
                    PendingInitiatorHandshakeFailure(
                        outcome = SessionEstablishmentOutcome.TrustFailure,
                        stage = "transport.handshake.message2.trust",
                        reason = DiagnosticReason.TRUST_FAILURE,
                    ),
            )
        }
        return trustRecord
    }

    private suspend fun sendHandshakeMessage3(
        peerId: PeerId,
        pending: PendingInitiatorHandshake,
        result: NoiseXXHandshakeResult,
        trustRecord: TrustRecord,
    ): Unit {
        when (
            callbacks.sendDirectWireFrame(
                peerId,
                DirectWireFrame.HandshakeMessage3(result.message3),
                "handshake.message3",
                TransportMode.GATT,
            )
        ) {
            TransportSendResult.Delivered ->
                completeInitiatorHandshake(
                    peerId = peerId,
                    pending = pending,
                    result = result,
                    trustRecord = trustRecord,
                )
            is TransportSendResult.Dropped ->
                failPendingInitiatorHandshake(
                    peerId = peerId,
                    pending = pending,
                    failure =
                        PendingInitiatorHandshakeFailure(
                            outcome = SessionEstablishmentOutcome.Unreachable,
                            stage = "transport.handshake.message3.send",
                            reason = DiagnosticReason.DELIVERY_FAILURE,
                        ),
                )
        }
    }

    private suspend fun completeInitiatorHandshake(
        peerId: PeerId,
        pending: PendingInitiatorHandshake,
        result: NoiseXXHandshakeResult,
        trustRecord: TrustRecord,
    ): Unit {
        val session = HopSession(sendKey = result.sendKey, receiveKey = result.receiveKey)
        val completed =
            state.sessionRegistry.completeInitiatorHandshake(
                peerId = peerId,
                pendingHandshake = pending,
                session = session,
            )
        if (!completed) {
            return
        }
        pending.sessionDeferred.complete(SessionEstablishmentOutcome.Established(session))
        callbacks.emitHopSessionEstablished(peerId, "transport.handshake.message2.complete")
        routingContext.routingSupport.dispatchMutation(
            mutation = routingContext.routeCoordinator.onPeerConnected(peerId, trustRecord),
            stage = "transport.handshake.message2.complete",
            metadata = mapOf("connectedPeerId" to peerId.value),
        )
    }

    private suspend fun failPendingInitiatorHandshake(
        peerId: PeerId,
        pending: PendingInitiatorHandshake,
        failure: PendingInitiatorHandshakeFailure,
    ): Unit {
        val removed = state.sessionRegistry.failInitiatorHandshake(peerId, pending)
        if (!removed) {
            return
        }
        pending.sessionDeferred.complete(failure.outcome)
        callbacks.emitHopSessionFailed(peerId, failure.stage, failure.reason, failure.metadata)
    }
}

internal fun buildMeshEngineRuntimeInitiatorHandshakeSupport(
    localIdentity: LocalIdentity,
    trustSupport: MeshEngineTrustSupport,
    state: MeshEngineHandshakeState,
    routingContext: MeshEngineHandshakeRoutingContext,
    callbacks: MeshEngineHandshakeCallbacks,
): MeshEngineInitiatorHandshakeSupport {
    return MeshEngineInitiatorHandshakeSupport(
        localIdentity = localIdentity,
        trustSupport = trustSupport,
        state = state,
        routingContext = routingContext,
        callbacks = callbacks,
    )
}
