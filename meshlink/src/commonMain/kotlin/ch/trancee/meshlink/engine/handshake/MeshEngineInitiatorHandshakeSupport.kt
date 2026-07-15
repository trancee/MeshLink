package ch.trancee.meshlink.engine.handshake

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.NoiseXXHandshakeResult
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.engine.internal.HopSession
import ch.trancee.meshlink.engine.internal.PendingInitiatorHandshake
import ch.trancee.meshlink.engine.internal.SessionEstablishmentOutcome
import ch.trancee.meshlink.engine.internal.unexpectedFramePrefixHex
import ch.trancee.meshlink.engine.transport.DirectWireFrame
import ch.trancee.meshlink.engine.trust.MeshEngineTrustSupport
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.routing.RoutingAdvertisement
import ch.trancee.meshlink.routing.RoutingMutation
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.trust.TrustRecord
import ch.trancee.meshlink.wire.WireFrame

internal class MeshEngineInitiatorHandshakeSupport(
    private val localIdentity: LocalIdentity,
    private val trustSupport: MeshEngineTrustSupport,
    private val state: MeshEngineHandshakeState,
    private val routingContext: MeshEngineHandshakeRoutingContext,
    private val callbacks: MeshEngineHandshakeCallbacks,
) {
    suspend fun handleHandshakeMessage2(peerId: PeerId, payload: ByteArray): Unit {
        val pending = pendingInitiatorHandshake(peerId, payload)
        if (pending != null) {
            if (!state.sessionRegistry.tryBeginProcessingMessage2(pending)) {
                // A concurrent delivery of message2 for this same pending handshake is already
                // being processed (redundant GATT/L2CAP side-link transports can duplicate
                // message2 just like they can duplicate other handshake frames). Processing both
                // concurrently would race the same NoiseXXHandshakeManager instance and abort the
                // handshake entirely, so this duplicate is safely ignored instead.
                callbacks.emitHopSessionFailed(
                    peerId,
                    "transport.handshake.message2.duplicateInFlightIgnored",
                    DiagnosticReason.DELIVERY_FAILURE,
                    mapOf(
                        "payloadBytes" to payload.size.toString(),
                        "attemptId" to pending.attemptId.toString(),
                    ),
                )
                return
            }
            val result =
                processHandshakeMessage2(peerId = peerId, payload = payload, pending = pending)
            if (result != null) {
                val resolvedPeerId =
                    rebindTemporaryInitiatorPeerIfNeeded(
                        peerId = peerId,
                        pending = pending,
                        result = result,
                    ) ?: return
                val trustRecord =
                    verifyHandshakeMessage2Trust(
                        peerId = resolvedPeerId,
                        pending = pending,
                        result = result,
                    )
                if (trustRecord != null) {
                    sendHandshakeMessage3(
                        peerId = resolvedPeerId,
                        pending = pending,
                        result = result,
                        trustRecord = trustRecord,
                        message2 = payload,
                    )
                }
            }
        }
    }

    private suspend fun pendingInitiatorHandshake(
        peerId: PeerId,
        payload: ByteArray,
    ): PendingInitiatorHandshake? {
        val pending =
            (state.sessionRegistry.initiatorHandshakeReservation(peerId)
                    as? InitiatorHandshakeReservation.Pending)
                ?.pendingHandshake
        if (pending == null) {
            // No pendingInitiatorHandshake exists for this peer -- either this device never
            // reserved one (e.g. this frame is a stray/duplicate delivery), or a hop session is
            // already Established and the retry/rebind bookkeeping in
            // MeshEngineSessionRegistry dropped the reservation before this frame arrived. If the
            // payload exactly matches the message2 that completed the current session, this is a
            // confirmed redundant delivery (the same GATT/L2CAP side-link transports that can
            // duplicate message1 can duplicate message2 too) rather than a genuinely unexpected
            // frame, so it is logged and ignored quietly instead of as a delivery-failure warning.
            val lastMessage2 = state.sessionRegistry.lastInitiatorMessage2(peerId)
            if (lastMessage2 != null && lastMessage2.contentEquals(payload)) {
                callbacks.emitHopSessionFailed(
                    peerId,
                    "transport.handshake.message2.duplicateIgnored",
                    DiagnosticReason.DELIVERY_FAILURE,
                    mapOf(
                        "payloadBytes" to payload.size.toString(),
                        "payloadPrefixHex" to payload.unexpectedFramePrefixHex(),
                    ),
                )
                return null
            }
            // Surface enough of the received payload to distinguish those cases from hardware
            // captures without needing a raw wire trace.
            callbacks.emitHopSessionFailed(
                peerId,
                "transport.handshake.message2.unexpected",
                DiagnosticReason.DELIVERY_FAILURE,
                mapOf(
                    "payloadBytes" to payload.size.toString(),
                    "payloadPrefixHex" to payload.unexpectedFramePrefixHex(),
                ),
            )
        }
        return pending
    }

    private suspend fun processHandshakeMessage2(
        peerId: PeerId,
        payload: ByteArray,
        pending: PendingInitiatorHandshake,
    ): NoiseXXHandshakeResult? {
        // Try any recently-superseded attempts for this peer FIRST, before ever invoking the
        // current attempt's manager. NoiseXXHandshakeManager mutates its internal transcript
        // state (mixHash/mixKey) irreversibly as soon as it starts processing a message2, even if
        // decryption ultimately throws CryptoFailure -- so trying a stale reply against the
        // *current* attempt's manager first would permanently corrupt it, and the current
        // attempt's own later genuine message2 would then also fail even though the stale
        // delivery was correctly identified and dropped. Checking superseded attempts first
        // means the current manager is only ever touched once no stale match is found.
        val staleAttemptId = supersededAttemptIdMatching(peerId, payload)
        if (staleAttemptId != null) {
            // A physically real BLE transport can deliver a handshake attempt's message2 reply
            // late, after that attempt has already been superseded (e.g. it timed out, or was
            // interrupted mid-flight by a lifecycle pause/resume) by a newer attempt for the
            // same peer. This message2 successfully decrypted against one of those superseded
            // attempts' managers -- a cryptographically verified match -- so it is a stale
            // reply, not a genuinely corrupt/unexpected frame. Drop it quietly instead of
            // failing the current, still-legitimate pending attempt. The current attempt was
            // claimed via tryBeginProcessingMessage2 before this call; release that claim so a
            // later, genuinely new message2 delivery for it is not permanently blocked as a
            // duplicate-in-flight.
            state.sessionRegistry.endProcessingMessage2(pending)
            callbacks.emitHopSessionFailed(
                peerId,
                "transport.handshake.message2.staleAttemptIgnored",
                DiagnosticReason.DELIVERY_FAILURE,
                mapOf(
                    "payloadBytes" to payload.size.toString(),
                    "staleAttemptId" to staleAttemptId.toString(),
                    "attemptId" to pending.attemptId.toString(),
                ),
            )
            return null
        }
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
                            metadata =
                                mapOf(
                                    "cause" to exception::class.simpleName.orEmpty(),
                                    "attemptId" to pending.attemptId.toString(),
                                ),
                        ),
                )
                null
            }
    }

    /**
     * Returns the [PendingInitiatorHandshake.attemptId] of a recently-superseded initiator
     * handshake attempt for [peerId] whose message2 reply is [payload], or null if none match.
     *
     * First checks whether [payload] was already confirmed to match a superseded attempt by an
     * earlier call (recorded via [MeshEngineSessionRegistry.recordSupersededAttemptMatch]) -- a
     * cheap equality check that correctly recognizes a *repeat* delivery of the same stale payload
     * (the same redundant GATT/L2CAP side-link transports that can duplicate other handshake frames
     * can duplicate this one too) without needing to re-invoke a superseded attempt's
     * NoiseXXHandshakeManager.
     *
     * Otherwise, atomically claims each not-yet-claimed superseded attempt (so a concurrent
     * duplicate delivery cannot race the same manager instance) and trial-decrypts [payload]
     * against each in turn via [NoiseXXHandshakeManager.tryProcessMessage2AndCreateMessage3], which
     * does not mutate a manager that fails to decrypt -- so a non-matching attempt's claim is
     * released afterwards, leaving it safely retryable later against a different message2 delivery
     * (for example, its own genuine stale reply arriving after an unrelated frame was tried against
     * it first). A successful decrypt is a definitive, cryptographically verified match (Noise's
     * AEAD authentication can only succeed against the manager whose message1 this payload actually
     * answers), which is then recorded for future repeat deliveries.
     */
    private suspend fun supersededAttemptIdMatching(peerId: PeerId, payload: ByteArray): Long? {
        state.sessionRegistry.previouslyMatchedSupersededAttemptId(peerId, payload)?.let {
            return it
        }
        val claimedAttempts = state.sessionRegistry.tryClaimSupersededAttemptsForMessage2(peerId)
        for (superseded in claimedAttempts.asReversed()) {
            val matched =
                superseded.manager.tryProcessMessage2AndCreateMessage3(
                    localIdentity.noiseIdentity,
                    payload,
                ) != null
            if (matched) {
                state.sessionRegistry.recordSupersededAttemptMatch(
                    peerId,
                    superseded.attemptId,
                    payload,
                )
                return superseded.attemptId
            }
            state.sessionRegistry.releaseSupersededAttemptClaim(peerId, superseded.attemptId)
        }
        return null
    }

    private suspend fun rebindTemporaryInitiatorPeerIfNeeded(
        peerId: PeerId,
        pending: PendingInitiatorHandshake,
        result: NoiseXXHandshakeResult,
    ): PeerId? {
        val canonicalPeerId =
            canonicalPeerIdForTemporaryTransportPeer(
                peerId = peerId,
                remoteEd25519PublicKey = result.remoteEd25519PublicKey,
                remoteX25519PublicKey = result.remoteStaticPublicKey,
                cryptoProvider = localIdentity.cryptoProvider,
            )
        if (canonicalPeerId.value == peerId.value) {
            return peerId
        }
        val rebound =
            state.sessionRegistry.rebindPendingInitiatorHandshake(
                fromPeerId = peerId,
                toPeerId = canonicalPeerId,
                pendingHandshake = pending,
            )
        if (!rebound) {
            callbacks.emitHopSessionFailed(
                peerId,
                "transport.handshake.message2.rebind",
                DiagnosticReason.DELIVERY_FAILURE,
                mapOf("canonicalPeerId" to canonicalPeerId.value),
            )
            return null
        }
        callbacks.promoteTemporaryPeer(peerId, canonicalPeerId)
        return canonicalPeerId
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
        message2: ByteArray,
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
                    message2 = message2,
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
        message2: ByteArray,
    ): Unit {
        val session = HopSession(sendKey = result.sendKey, receiveKey = result.receiveKey)
        val completed =
            state.sessionRegistry.completeInitiatorHandshake(
                peerId = peerId,
                pendingHandshake = pending,
                session = session,
                message2 = message2,
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
        routingContext.routingSupport.dispatchMutation(
            mutation =
                RoutingMutation(
                    advertisements =
                        listOf(
                            RoutingAdvertisement(
                                targetPeerId = peerId,
                                frame = localSelfRouteUpdateFrame(),
                            )
                        ),
                    routeChanges = emptyList(),
                ),
            stage = "transport.handshake.message2.selfRouteUpdate",
            metadata = mapOf("connectedPeerId" to peerId.value),
        )
    }

    private fun localSelfRouteUpdateFrame(): WireFrame.RouteUpdate {
        return WireFrame.RouteUpdate(
            destinationPeerId = localIdentity.peerId,
            nextHopPeerId = localIdentity.peerId,
            metrics =
                WireFrame.RouteUpdateMetrics(
                    metric = 1,
                    seqNo = routingContext.localSelfRouteSeqNo,
                    feasibilityMetric = 1,
                ),
            publicKeys =
                WireFrame.RouteUpdatePublicKeys(
                    destinationEd25519PublicKey = localIdentity.ed25519PublicKey,
                    destinationX25519PublicKey = localIdentity.x25519PublicKey,
                ),
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
