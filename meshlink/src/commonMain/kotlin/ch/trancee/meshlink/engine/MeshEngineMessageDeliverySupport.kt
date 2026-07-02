package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.InboundMessage
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.MessageSealer
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.platform.currentEpochMillis
import ch.trancee.meshlink.trust.TrustRecord
import kotlinx.coroutines.flow.MutableSharedFlow

internal class MeshEngineMessageDeliverySupport(
    private val localIdentity: LocalIdentity,
    private val runtimeGate: MeshEngineRuntimeGate,
    private val trustSupport: MeshEngineTrustSupport,
    private val mutableMessages: MutableSharedFlow<InboundMessage>,
    private val emitHopSessionFailed:
        suspend (PeerId, String, DiagnosticReason, Map<String, String>) -> Unit,
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
    suspend fun deliverInnerEnvelope(
        immediatePeerId: PeerId,
        originPeerId: PeerId,
        encryptedPayload: ByteArray,
        priority: DeliveryPriority,
        hardRunToken: MeshEngineHardRunToken,
    ): Unit {
        when (runtimeGate.awaitActive(hardRunToken)) {
            MeshEngineRuntimeAwaitActiveResult.Active -> Unit
            MeshEngineRuntimeAwaitActiveResult.HardRunEnded -> return
        }
        val envelope = decodeInnerEnvelope(immediatePeerId, encryptedPayload)
        if (envelope != null) {
            val senderTrust = verifyInnerEnvelopeSenderTrust(envelope)
            if (senderTrust != null) {
                val plaintext = openInnerEnvelope(envelope, senderTrust)
                if (plaintext != null) {
                    emitInboundMessage(
                        immediatePeerId = immediatePeerId,
                        originPeerId = originPeerId,
                        payload = plaintext,
                        priority = priority,
                    )
                }
            }
        }
    }

    private suspend fun decodeInnerEnvelope(
        immediatePeerId: PeerId,
        encryptedPayload: ByteArray,
    ): DirectMessageEnvelope? {
        return runCatching { DirectMessageEnvelope.decode(encryptedPayload) }
            .getOrElse { exception ->
                emitHopSessionFailed(
                    immediatePeerId,
                    "transport.data.messageEnvelope",
                    DiagnosticReason.DELIVERY_FAILURE,
                    mapOf("cause" to exception::class.simpleName.orEmpty()),
                )
                null
            }
    }

    private suspend fun verifyInnerEnvelopeSenderTrust(
        envelope: DirectMessageEnvelope
    ): TrustRecord? {
        return trustSupport.verifyAndPersistTrust(
            peerId = envelope.senderPeerId,
            remoteEd25519PublicKey = envelope.senderEd25519PublicKey,
            remoteX25519PublicKey = envelope.senderX25519PublicKey,
            expectedFingerprintBytes = envelope.senderFingerprintBytes,
        )
    }

    private fun openInnerEnvelope(
        envelope: DirectMessageEnvelope,
        senderTrust: TrustRecord,
    ): ByteArray? {
        return runCatching {
                MessageSealer.open(
                    sealedPayload = envelope.ciphertext,
                    recipientIdentity = localIdentity,
                    senderTrust = senderTrust,
                )
            }
            .getOrElse { exception ->
                emitDiagnostic(
                    DiagnosticCode.TRUST_FAILURE,
                    DiagnosticSeverity.ERROR,
                    "transport.data.open",
                    envelope.senderPeerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
                    DiagnosticReason.TRUST_FAILURE,
                    mapOf("cause" to exception::class.simpleName.orEmpty()),
                )
                null
            }
    }

    private suspend fun emitInboundMessage(
        immediatePeerId: PeerId,
        originPeerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
    ): Unit {
        mutableMessages.emit(
            InboundMessage(
                originPeerId = originPeerId,
                payload = payload,
                receivedAtEpochMillis = currentEpochMillis(),
                priority = priority,
            )
        )
        emitDiagnostic(
            DiagnosticCode.DELIVERY_SUCCEEDED,
            DiagnosticSeverity.INFO,
            "transport.data.deliver",
            originPeerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
            null,
            mapOf(
                "peerId" to originPeerId.value,
                "originPeerId" to originPeerId.value,
                "immediatePeerId" to immediatePeerId.value,
                "payloadBytes" to payload.size.toString(),
            ),
        )
    }
}

internal fun buildMeshEngineRuntimeMessageDeliverySupport(
    localIdentity: LocalIdentity,
    runtimeGate: MeshEngineRuntimeGate,
    trustSupport: MeshEngineTrustSupport,
    mutableMessages: MutableSharedFlow<InboundMessage>,
    emitHopSessionFailed: suspend (PeerId, String, DiagnosticReason, Map<String, String>) -> Unit,
    emitDiagnostic:
        (
            DiagnosticCode,
            DiagnosticSeverity,
            String,
            String?,
            DiagnosticReason?,
            Map<String, String>,
        ) -> Unit,
): MeshEngineMessageDeliverySupport {
    return MeshEngineMessageDeliverySupport(
        localIdentity = localIdentity,
        runtimeGate = runtimeGate,
        trustSupport = trustSupport,
        mutableMessages = mutableMessages,
        emitHopSessionFailed = emitHopSessionFailed,
        emitDiagnostic = emitDiagnostic,
    )
}
