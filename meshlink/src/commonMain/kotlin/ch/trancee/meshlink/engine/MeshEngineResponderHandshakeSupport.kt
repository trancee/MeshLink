package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.NoiseXXHandshakeManager
import ch.trancee.meshlink.crypto.NoiseXXResponderResult
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.identity.toHexString
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.trust.TrustRecord
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay

internal class MeshEngineResponderHandshakeSupport(
    private val localIdentity: LocalIdentity,
    private val trustSupport: MeshEngineTrustSupport,
    private val state: MeshEngineHandshakeState,
    private val routingContext: MeshEngineHandshakeRoutingContext,
    private val callbacks: MeshEngineHandshakeCallbacks,
) {
    suspend fun handleHandshakeMessage1(peerId: PeerId, payload: ByteArray): Unit {
        // Hardware captures confirmed that the redundant GATT/L2CAP side-link transports can
        // both deliver the exact same initiator message1 wire frame to this peer (see
        // transport.handshake.message1.duplicateIgnored evidence in bearer-policy verification
        // runs). Without this guard, every redundant delivery unconditionally built a fresh
        // NoiseXXHandshakeManager and regenerated a brand new message2, which the initiator -
        // having already completed (or abandoned) its own handshake attempt for the first
        // delivery - correctly rejected as "unexpected". Ignore a message1 only when its bytes
        // exactly match the frame that produced the peer's current pending responder handshake
        // or established session: a peer reconnecting under the same transport peerId with a
        // rotated identity sends a genuinely different message1 and must still be processed as a
        // fresh handshake attempt.
        val existingSession = state.sessionRegistry.hopSession(peerId)
        val existingPendingResponder = state.sessionRegistry.pendingResponderHandshake(peerId)
        if (existingSession != null || existingPendingResponder != null) {
            val lastMessage1 = state.sessionRegistry.lastResponderMessage1(peerId)
            if (lastMessage1 != null && lastMessage1.contentEquals(payload)) {
                callbacks.emitHopSessionFailed(
                    peerId,
                    "transport.handshake.message1.duplicateIgnored",
                    DiagnosticReason.DELIVERY_FAILURE,
                    mapOf(
                        "hasEstablishedSession" to (existingSession != null).toString(),
                        "hasPendingResponderHandshake" to
                            (existingPendingResponder != null).toString(),
                        "payloadBytes" to payload.size.toString(),
                        "payloadPrefixHex" to
                            payload
                                .copyOf(minOf(payload.size, UNEXPECTED_FRAME_HEX_SNIPPET_BYTES))
                                .toHexString(),
                    ),
                )
                return
            }
        }
        val manager = NoiseXXHandshakeManager(localIdentity.cryptoProvider)
        val message2 =
            runCatching {
                    manager.processMessage1AndCreateMessage2(
                        responderIdentity = localIdentity.noiseIdentity,
                        message1 = payload,
                        meshDomainHash = localIdentity.meshDomainHash,
                    )
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
        state.sessionRegistry.storePendingResponderHandshake(peerId, pendingHandshake, payload)
        when (sendHandshakeMessage2(peerId, message2)) {
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

    // Same shape of retry as the initiator's `sendHandshakeMessage1`: a transient "link not
    // ready" drop (for example a BLE GATT link mid-reconnect right as the reply is sent) is
    // retried within a bounded window instead of permanently failing the handshake on the first
    // attempt. Any other drop reason returns immediately, unretried.
    private suspend fun sendHandshakeMessage2(
        peerId: PeerId,
        message2: ByteArray,
    ): TransportSendResult {
        val retryWindow = TimeSource.Monotonic.markNow()
        while (true) {
            val result =
                callbacks.sendDirectWireFrame(
                    peerId,
                    DirectWireFrame.HandshakeMessage2(message2),
                    "handshake.message2",
                    TransportMode.GATT,
                )
            if (result !is TransportSendResult.Dropped || !result.isTransientLinkNotReady()) {
                return result
            }
            if (retryWindow.elapsedNow() >= MESSAGE2_SEND_RETRY_WINDOW) {
                return result
            }
            delay(MESSAGE2_SEND_RETRY_DELAY)
        }
    }

    suspend fun handleHandshakeMessage3(peerId: PeerId, payload: ByteArray): Unit {
        val pending = pendingResponderHandshake(peerId, payload)
        if (pending != null) {
            val result =
                processHandshakeMessage3(peerId = peerId, payload = payload, pending = pending)
            if (result != null) {
                val resolvedPeerId =
                    rebindTemporaryResponderPeerIfNeeded(
                        peerId = peerId,
                        pending = pending,
                        result = result,
                    ) ?: return
                val trustRecord =
                    verifyHandshakeMessage3Trust(
                        peerId = resolvedPeerId,
                        pending = pending,
                        result = result,
                    )
                if (trustRecord != null) {
                    completeResponderHandshake(
                        peerId = resolvedPeerId,
                        pending = pending,
                        result = result,
                        trustRecord = trustRecord,
                    )
                }
            }
        }
    }

    private suspend fun rebindTemporaryResponderPeerIfNeeded(
        peerId: PeerId,
        pending: PendingResponderHandshake,
        result: NoiseXXResponderResult,
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
            state.sessionRegistry.rebindPendingResponderHandshake(
                fromPeerId = peerId,
                toPeerId = canonicalPeerId,
                pendingHandshake = pending,
            )
        if (!rebound) {
            callbacks.emitHopSessionFailed(
                peerId,
                "transport.handshake.message3.rebind",
                DiagnosticReason.DELIVERY_FAILURE,
                mapOf("canonicalPeerId" to canonicalPeerId.value),
            )
            return null
        }
        callbacks.promoteTemporaryPeer(peerId, canonicalPeerId)
        return canonicalPeerId
    }

    private suspend fun pendingResponderHandshake(
        peerId: PeerId,
        payload: ByteArray,
    ): PendingResponderHandshake? {
        val pending = state.sessionRegistry.pendingResponderHandshake(peerId)
        if (pending == null) {
            // See the matching comment in MeshEngineInitiatorHandshakeSupport's
            // pendingInitiatorHandshake(): surface the received payload's size/prefix so hardware
            // captures can distinguish a genuine stray/duplicate frame from a reservation that was
            // dropped before this frame arrived.
            callbacks.emitHopSessionFailed(
                peerId,
                "transport.handshake.message3.unexpected",
                DiagnosticReason.DELIVERY_FAILURE,
                mapOf(
                    "payloadBytes" to payload.size.toString(),
                    "payloadPrefixHex" to
                        payload
                            .copyOf(minOf(payload.size, UNEXPECTED_FRAME_HEX_SNIPPET_BYTES))
                            .toHexString(),
                ),
            )
        }
        return pending
    }

    private suspend fun processHandshakeMessage3(
        peerId: PeerId,
        payload: ByteArray,
        pending: PendingResponderHandshake,
    ): NoiseXXResponderResult? {
        return runCatching { pending.manager.processMessage3(payload) }
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

internal fun buildMeshEngineRuntimeResponderHandshakeSupport(
    localIdentity: LocalIdentity,
    trustSupport: MeshEngineTrustSupport,
    state: MeshEngineHandshakeState,
    routingContext: MeshEngineHandshakeRoutingContext,
    callbacks: MeshEngineHandshakeCallbacks,
): MeshEngineResponderHandshakeSupport {
    return MeshEngineResponderHandshakeSupport(
        localIdentity = localIdentity,
        trustSupport = trustSupport,
        state = state,
        routingContext = routingContext,
        callbacks = callbacks,
    )
}

// See the matching helper in MeshEngineSessionSupport.kt for rationale: widened to match any
// bearer's transient "not ready" wording, not just the L2CAP-specific string.
private fun TransportSendResult.Dropped.isTransientLinkNotReady(): Boolean {
    return reason.contains("connection is not ready", ignoreCase = true) ||
        reason.contains("client not ready", ignoreCase = true)
}

// Widened from 3s -> 6s alongside HANDSHAKE_TIMEOUT in MeshEngineSessionSupport.kt for the same
// reason: slower BLE side-link setup on older hardware was consistently exceeding 3s.
private val MESSAGE2_SEND_RETRY_WINDOW = 6.seconds
private val MESSAGE2_SEND_RETRY_DELAY = 100.milliseconds
