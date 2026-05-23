package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendFailureReason
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.crypto.MessageSealer
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.platform.currentEpochMillis
import ch.trancee.meshlink.routing.RouteCoordinator
import ch.trancee.meshlink.routing.RouteEntry
import ch.trancee.meshlink.transfer.OutboundTransferSession
import ch.trancee.meshlink.transfer.TransferChunkPlan
import ch.trancee.meshlink.transfer.TransferSessionRoute
import ch.trancee.meshlink.trust.TofuTrustStore
import ch.trancee.meshlink.trust.TrustPublicKeys
import ch.trancee.meshlink.trust.TrustRecord

internal data class MeshEngineOutboundPreparationState(
    val outboundTransfers: MutableMap<String, OutboundTransferSession>
)

internal data class MeshEngineOutboundPreparationRoutingContext(
    val routeCoordinator: RouteCoordinator,
    val routingSupport: MeshEngineRoutingSupport,
)

internal data class MeshEngineOutboundPreparationCallbacks(
    val createMessageId: () -> String,
    val createTransferId: () -> String,
    val emitTransferEncryptFailure: (PeerId, String) -> Unit,
)

internal class MeshEngineOutboundPreparationSupport(
    private val localIdentity: LocalIdentity,
    private val trustStore: TofuTrustStore,
    private val state: MeshEngineOutboundPreparationState,
    private val routingContext: MeshEngineOutboundPreparationRoutingContext,
    private val callbacks: MeshEngineOutboundPreparationCallbacks,
    private val emitDiagnostic:
        (
            DiagnosticCode,
            DiagnosticSeverity,
            String,
            String?,
            DiagnosticReason?,
            Map<String, String>,
        ) -> Unit,
) {
    suspend fun resolveRecipientTrust(peerId: PeerId): TrustRecord? {
        val existingTrust = trustStore.read(peerId.value)
        val route = routingContext.routeCoordinator.routeFor(peerId)

        return when {
            existingTrust != null -> existingTrust
            route == null -> null
            else -> learnRecipientTrustFromRoute(peerId = peerId, route = route)
        }
    }

    suspend fun prepareOutboundTransferSession(
        peerId: PeerId,
        payload: ByteArray,
        hardRunToken: MeshEngineHardRunToken,
    ): OutboundTransferPreparation {
        val recipientTrust = resolveRecipientTrust(peerId)

        return if (recipientTrust == null) {
            OutboundTransferPreparation.PendingRoute
        } else {
            prepareResolvedOutboundTransferSession(
                peerId = peerId,
                payload = payload,
                recipientTrust = recipientTrust,
                hardRunToken = hardRunToken,
            )
        }
    }

    private suspend fun learnRecipientTrustFromRoute(
        peerId: PeerId,
        route: RouteEntry,
    ): TrustRecord {
        val learnedAtEpochMillis = currentEpochMillis()
        val learnedTrust =
            TrustRecord(
                peerIdValue = route.destinationPeerId.value,
                identityFingerprintBytes =
                    localIdentity.cryptoProvider.sha256(
                        route.ed25519PublicKey + route.x25519PublicKey
                    ),
                firstSeenAtEpochMillis = learnedAtEpochMillis,
                lastVerifiedAtEpochMillis = learnedAtEpochMillis,
                publicKeys =
                    TrustPublicKeys(
                        ed25519PublicKey = route.ed25519PublicKey,
                        x25519PublicKey = route.x25519PublicKey,
                    ),
            )
        trustStore.write(learnedTrust)
        emitDiagnostic(
            DiagnosticCode.TRUST_ESTABLISHED,
            DiagnosticSeverity.INFO,
            "trust.routeUpdate",
            peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
            DiagnosticReason.STATE_CHANGE,
            emptyMap(),
        )
        return learnedTrust
    }

    private suspend fun prepareResolvedOutboundTransferSession(
        peerId: PeerId,
        payload: ByteArray,
        recipientTrust: TrustRecord,
        hardRunToken: MeshEngineHardRunToken,
    ): OutboundTransferPreparation {
        val sealedPayload = sealOutboundTransferPayload(peerId, payload, recipientTrust)
        return if (sealedPayload == null) {
            OutboundTransferPreparation.Failed(SendResult.NotSent(SendFailureReason.TRUST_FAILURE))
        } else {
            val session = createOutboundTransferSession(peerId, sealedPayload, hardRunToken)
            state.outboundTransfers[session.transferId] = session
            emitTransferStartedDiagnostic(peerId)
            OutboundTransferPreparation.Ready(session)
        }
    }

    private fun sealOutboundTransferPayload(
        peerId: PeerId,
        payload: ByteArray,
        recipientTrust: TrustRecord,
    ): ByteArray? {
        return runCatching {
                MessageSealer.seal(
                    plaintext = payload,
                    senderIdentity = localIdentity,
                    recipientTrust = recipientTrust,
                )
            }
            .getOrElse { exception ->
                callbacks.emitTransferEncryptFailure(peerId, exception::class.simpleName.orEmpty())
                null
            }
    }

    private fun createOutboundTransferSession(
        peerId: PeerId,
        sealedPayload: ByteArray,
        @Suppress("UNUSED_PARAMETER") hardRunToken: MeshEngineHardRunToken,
    ): OutboundTransferSession {
        val innerEnvelope =
            DirectMessageEnvelope(
                    senderPeerId = localIdentity.peerId,
                    senderFingerprintBytes = localIdentity.identityFingerprintBytes,
                    senderEd25519PublicKey = localIdentity.ed25519PublicKey,
                    senderX25519PublicKey = localIdentity.x25519PublicKey,
                    ciphertext = sealedPayload,
                )
                .encode()
        return OutboundTransferSession.fromOwnedPlan(
            route =
                TransferSessionRoute(
                    transferId = callbacks.createTransferId(),
                    messageId = callbacks.createMessageId(),
                    originPeerId = localIdentity.peerId,
                    destinationPeerId = peerId,
                ),
            chunkPlan =
                TransferChunkPlan(
                    chunks = chunkTransferPayload(innerEnvelope, TRANSFER_CHUNK_PAYLOAD_BYTES),
                    totalBytes = innerEnvelope.size,
                    maxChunkPayloadBytes = TRANSFER_CHUNK_PAYLOAD_BYTES,
                ),
        )
    }

    private fun emitTransferStartedDiagnostic(peerId: PeerId): Unit {
        emitDiagnostic(
            DiagnosticCode.TRANSFER_STARTED,
            DiagnosticSeverity.INFO,
            "transfer.send.start",
            peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
            null,
            routingContext.routingSupport.peerRouteMetadata(peerId),
        )
    }

    private companion object {
        private const val TRANSFER_CHUNK_PAYLOAD_BYTES: Int = 392
    }
}
