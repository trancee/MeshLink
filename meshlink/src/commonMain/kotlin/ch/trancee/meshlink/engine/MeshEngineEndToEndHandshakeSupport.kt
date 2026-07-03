package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.NoiseXXHandshakeManager
import ch.trancee.meshlink.crypto.NoiseXXHandshakeResult
import ch.trancee.meshlink.crypto.NoiseXXResponderResult
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.wire.WireFrame
import kotlinx.coroutines.CompletableDeferred

internal data class MeshEngineEndToEndHandshakeCallbacks(
    val sendFrameTowardsPeer: suspend (PeerId, WireFrame, String) -> Boolean,
    val createHandshakeId: suspend () -> String,
    val emitDiagnostic:
        (
            DiagnosticCode,
            DiagnosticSeverity,
            String,
            String?,
            DiagnosticReason?,
            Map<String, String>,
        ) -> Unit,
)

/**
 * Drives the relayed end-to-end (multi-hop) Noise XX handshake: initiating a session towards a
 * destination peer that may be several hops away, and processing handshake frames addressed to the
 * local peer, whether this node is acting as the initiator or the responder for that particular
 * handshake. Every handshake stage reuses [NoiseXXHandshakeManager], the same Noise XX state
 * machine used for single-hop sessions, so the cryptographic guarantees (mutual authentication,
 * forward secrecy) are identical; only the transport changes from "one direct connection" to "zero
 * or more relays forwarding opaque frames".
 */
internal class MeshEngineEndToEndHandshakeSupport(
    private val localIdentity: LocalIdentity,
    private val trustSupport: MeshEngineTrustSupport,
    private val registry: MeshEngineEndToEndSessionRegistry,
    private val callbacks: MeshEngineEndToEndHandshakeCallbacks,
) {
    /**
     * Returns an already-established session immediately, awaits an in-flight handshake to the same
     * peer, or initiates a brand-new handshake and awaits its outcome.
     */
    suspend fun ensureEndToEndSession(peerId: PeerId): EndToEndSessionEstablishmentOutcome {
        val reservation =
            registry.initiatorHandshakeReservation(peerId) { createInitiatorHandshake(peerId) }
        return when (reservation) {
            null -> EndToEndSessionEstablishmentOutcome.Unreachable
            is EndToEndInitiatorHandshakeReservation.Established ->
                EndToEndSessionEstablishmentOutcome.Established(reservation.session)
            is EndToEndInitiatorHandshakeReservation.Pending ->
                reservation.pendingHandshake.sessionDeferred.await()
            is EndToEndInitiatorHandshakeReservation.Created ->
                startInitiatorHandshake(peerId, reservation)
        }
    }

    suspend fun establishedEndToEndSession(peerId: PeerId): EndToEndSession? {
        return registry.session(peerId)
    }

    suspend fun handleLocalEndToEndHandshakeFrame(frame: WireFrame.EndToEndHandshakeFrame): Unit {
        when (frame) {
            is WireFrame.EndToEndHandshakeMessage1 -> handleHandshakeMessage1(frame)
            is WireFrame.EndToEndHandshakeMessage2 -> handleHandshakeMessage2(frame)
            is WireFrame.EndToEndHandshakeMessage3 -> handleHandshakeMessage3(frame)
        }
    }

    private suspend fun createInitiatorHandshake(
        peerId: PeerId
    ): CreatedEndToEndInitiatorHandshake {
        val manager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
        val message1 = manager.createMessage1()
        val handshakeId = callbacks.createHandshakeId()
        return CreatedEndToEndInitiatorHandshake(
            pendingHandshake =
                PendingEndToEndInitiatorHandshake(
                    handshakeId = handshakeId,
                    manager = manager,
                    sessionDeferred = CompletableDeferred(),
                ),
            message1Frame =
                WireFrame.EndToEndHandshakeMessage1(
                    route =
                        WireFrame.EndToEndHandshakeRoute(
                            handshakeId = handshakeId,
                            originPeerId = localIdentity.peerId,
                            destinationPeerId = peerId,
                        ),
                    payload = message1,
                ),
        )
    }

    private suspend fun startInitiatorHandshake(
        peerId: PeerId,
        reservation: EndToEndInitiatorHandshakeReservation.Created,
    ): EndToEndSessionEstablishmentOutcome {
        val pending = reservation.pendingHandshake
        val sent =
            callbacks.sendFrameTowardsPeer(
                peerId,
                reservation.message1Frame,
                "e2eHandshake.message1",
            )
        if (!sent) {
            failInitiatorHandshake(
                peerId = peerId,
                pending = pending,
                outcome = EndToEndSessionEstablishmentOutcome.Unreachable,
                stage = "e2eHandshake.message1.send",
                reason = DiagnosticReason.DELIVERY_FAILURE,
            )
        }
        return pending.sessionDeferred.await()
    }

    private suspend fun handleHandshakeMessage1(frame: WireFrame.EndToEndHandshakeMessage1): Unit {
        val originPeerId = frame.originPeerId
        val manager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
        val message2 =
            runCatching {
                    manager.processMessage1AndCreateMessage2(
                        localIdentity.noiseIdentity,
                        frame.payload,
                    )
                }
                .getOrElse { exception ->
                    callbacks.emitDiagnostic(
                        DiagnosticCode.TRANSPORT_FRAME_REJECTED,
                        DiagnosticSeverity.WARN,
                        "e2eHandshake.message1.process",
                        originPeerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
                        DiagnosticReason.DELIVERY_FAILURE,
                        mapOf("cause" to exception::class.simpleName.orEmpty()),
                    )
                    return
                }
        registry.storePendingResponderHandshake(
            originPeerId,
            PendingEndToEndResponderHandshake(handshakeId = frame.handshakeId, manager = manager),
        )
        val message2Frame =
            WireFrame.EndToEndHandshakeMessage2(
                route =
                    WireFrame.EndToEndHandshakeRoute(
                        handshakeId = frame.handshakeId,
                        originPeerId = localIdentity.peerId,
                        destinationPeerId = originPeerId,
                    ),
                payload = message2,
            )
        callbacks.sendFrameTowardsPeer(originPeerId, message2Frame, "e2eHandshake.message2")
    }

    private suspend fun handleHandshakeMessage2(frame: WireFrame.EndToEndHandshakeMessage2): Unit {
        val peerId = frame.originPeerId
        val pending =
            (registry.initiatorHandshakeReservation(peerId)
                    as? EndToEndInitiatorHandshakeReservation.Pending)
                ?.pendingHandshake
        if (pending == null) {
            callbacks.emitDiagnostic(
                DiagnosticCode.TRANSPORT_FRAME_REJECTED,
                DiagnosticSeverity.WARN,
                "e2eHandshake.message2.unexpected",
                peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
                DiagnosticReason.DELIVERY_FAILURE,
                emptyMap(),
            )
            return
        }
        val result = processHandshakeMessage2(peerId, frame, pending) ?: return
        verifyMessage2Trust(peerId, pending, result) ?: return
        sendHandshakeMessage3(peerId, frame, pending, result)
    }

    private suspend fun processHandshakeMessage2(
        peerId: PeerId,
        frame: WireFrame.EndToEndHandshakeMessage2,
        pending: PendingEndToEndInitiatorHandshake,
    ): NoiseXXHandshakeResult? {
        return runCatching {
                pending.manager.processMessage2AndCreateMessage3(
                    localIdentity.noiseIdentity,
                    frame.payload,
                )
            }
            .getOrElse { exception ->
                failInitiatorHandshake(
                    peerId = peerId,
                    pending = pending,
                    outcome = EndToEndSessionEstablishmentOutcome.Unreachable,
                    stage = "e2eHandshake.message2.process",
                    reason = DiagnosticReason.DELIVERY_FAILURE,
                    metadata = mapOf("cause" to exception::class.simpleName.orEmpty()),
                )
                null
            }
    }

    private suspend fun verifyMessage2Trust(
        peerId: PeerId,
        pending: PendingEndToEndInitiatorHandshake,
        result: NoiseXXHandshakeResult,
    ) =
        trustSupport.verifyAndPersistTrust(
            peerId = peerId,
            remoteEd25519PublicKey = result.remoteEd25519PublicKey,
            remoteX25519PublicKey = result.remoteStaticPublicKey,
        )
            ?: run {
                failInitiatorHandshake(
                    peerId = peerId,
                    pending = pending,
                    outcome = EndToEndSessionEstablishmentOutcome.TrustFailure,
                    stage = "e2eHandshake.message2.trust",
                    reason = DiagnosticReason.TRUST_FAILURE,
                )
                null
            }

    private suspend fun sendHandshakeMessage3(
        peerId: PeerId,
        frame: WireFrame.EndToEndHandshakeMessage2,
        pending: PendingEndToEndInitiatorHandshake,
        result: NoiseXXHandshakeResult,
    ): Unit {
        val message3Frame =
            WireFrame.EndToEndHandshakeMessage3(
                route =
                    WireFrame.EndToEndHandshakeRoute(
                        handshakeId = frame.handshakeId,
                        originPeerId = localIdentity.peerId,
                        destinationPeerId = peerId,
                    ),
                payload = result.message3,
            )
        val sent = callbacks.sendFrameTowardsPeer(peerId, message3Frame, "e2eHandshake.message3")
        if (!sent) {
            failInitiatorHandshake(
                peerId = peerId,
                pending = pending,
                outcome = EndToEndSessionEstablishmentOutcome.Unreachable,
                stage = "e2eHandshake.message3.send",
                reason = DiagnosticReason.DELIVERY_FAILURE,
            )
            return
        }
        completeInitiatorHandshake(peerId, pending, result)
    }

    private suspend fun completeInitiatorHandshake(
        peerId: PeerId,
        pending: PendingEndToEndInitiatorHandshake,
        result: NoiseXXHandshakeResult,
    ): Unit {
        val session = EndToEndSession(sendKey = result.sendKey, receiveKey = result.receiveKey)
        val completed = registry.completeInitiatorHandshake(peerId, pending, session)
        if (!completed) {
            return
        }
        pending.sessionDeferred.complete(EndToEndSessionEstablishmentOutcome.Established(session))
        callbacks.emitDiagnostic(
            DiagnosticCode.TRUST_ESTABLISHED,
            DiagnosticSeverity.INFO,
            "e2eHandshake.message2.complete",
            peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
            DiagnosticReason.STATE_CHANGE,
            emptyMap(),
        )
    }

    private suspend fun failInitiatorHandshake(
        peerId: PeerId,
        pending: PendingEndToEndInitiatorHandshake,
        outcome: EndToEndSessionEstablishmentOutcome,
        stage: String,
        reason: DiagnosticReason,
        metadata: Map<String, String> = emptyMap(),
    ): Unit {
        val removed = registry.failInitiatorHandshake(peerId, pending)
        if (!removed) {
            return
        }
        pending.sessionDeferred.complete(outcome)
        callbacks.emitDiagnostic(
            DiagnosticCode.TRUST_FAILURE,
            DiagnosticSeverity.WARN,
            stage,
            peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
            reason,
            metadata,
        )
    }

    private suspend fun handleHandshakeMessage3(frame: WireFrame.EndToEndHandshakeMessage3): Unit {
        val peerId = frame.originPeerId
        val pending = registry.pendingResponderHandshake(peerId)
        if (pending == null) {
            callbacks.emitDiagnostic(
                DiagnosticCode.TRANSPORT_FRAME_REJECTED,
                DiagnosticSeverity.WARN,
                "e2eHandshake.message3.unexpected",
                peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
                DiagnosticReason.DELIVERY_FAILURE,
                emptyMap(),
            )
            return
        }
        val result = processHandshakeMessage3(peerId, frame, pending) ?: return
        val trustRecord =
            trustSupport.verifyAndPersistTrust(
                peerId = peerId,
                remoteEd25519PublicKey = result.remoteEd25519PublicKey,
                remoteX25519PublicKey = result.remoteStaticPublicKey,
            )
        if (trustRecord == null) {
            registry.removePendingResponderHandshake(peerId, pending)
            callbacks.emitDiagnostic(
                DiagnosticCode.TRUST_FAILURE,
                DiagnosticSeverity.WARN,
                "e2eHandshake.message3.trust",
                peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
                DiagnosticReason.TRUST_FAILURE,
                emptyMap(),
            )
            return
        }
        val session = EndToEndSession(sendKey = result.sendKey, receiveKey = result.receiveKey)
        val completed = registry.completeResponderHandshake(peerId, pending, session)
        if (!completed) {
            return
        }
        callbacks.emitDiagnostic(
            DiagnosticCode.TRUST_ESTABLISHED,
            DiagnosticSeverity.INFO,
            "e2eHandshake.message3.complete",
            peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
            DiagnosticReason.STATE_CHANGE,
            emptyMap(),
        )
    }

    private suspend fun processHandshakeMessage3(
        peerId: PeerId,
        frame: WireFrame.EndToEndHandshakeMessage3,
        pending: PendingEndToEndResponderHandshake,
    ): NoiseXXResponderResult? {
        return runCatching { pending.manager.processMessage3(frame.payload) }
            .getOrElse { exception ->
                registry.removePendingResponderHandshake(peerId, pending)
                callbacks.emitDiagnostic(
                    DiagnosticCode.TRANSPORT_FRAME_REJECTED,
                    DiagnosticSeverity.WARN,
                    "e2eHandshake.message3.process",
                    peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
                    DiagnosticReason.DELIVERY_FAILURE,
                    mapOf("cause" to exception::class.simpleName.orEmpty()),
                )
                null
            }
    }
}

internal fun buildMeshEngineRuntimeEndToEndHandshakeSupport(
    localIdentity: LocalIdentity,
    trustSupport: MeshEngineTrustSupport,
    registry: MeshEngineEndToEndSessionRegistry,
    callbacks: MeshEngineEndToEndHandshakeCallbacks,
): MeshEngineEndToEndHandshakeSupport {
    return MeshEngineEndToEndHandshakeSupport(
        localIdentity = localIdentity,
        trustSupport = trustSupport,
        registry = registry,
        callbacks = callbacks,
    )
}
