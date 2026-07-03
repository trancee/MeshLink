package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.MessageSealer
import ch.trancee.meshlink.identity.LocalIdentity

internal sealed class MeshEngineOutboundDirectEnvelopePreparation {
    internal class Ready internal constructor(internal val envelopeBytes: ByteArray) :
        MeshEngineOutboundDirectEnvelopePreparation()

    internal data object MissingTrust : MeshEngineOutboundDirectEnvelopePreparation()

    internal data object EncryptFailure : MeshEngineOutboundDirectEnvelopePreparation()
}

internal class MeshEngineOutboundDirectEnvelopeSupport(
    private val localIdentity: LocalIdentity,
    private val recipientTrustSupport: MeshEngineOutboundRecipientTrustSupport,
) {
    suspend fun prepare(
        peerId: PeerId,
        payload: ByteArray,
        emitEncryptFailure: suspend (PeerId, String) -> Unit,
    ): MeshEngineOutboundDirectEnvelopePreparation {
        val recipientTrust = recipientTrustSupport.resolveRecipientTrust(peerId)
        if (recipientTrust == null) {
            return MeshEngineOutboundDirectEnvelopePreparation.MissingTrust
        }
        val sealedPayload =
            runCatching {
                    MessageSealer.seal(
                        plaintext = payload,
                        senderIdentity = localIdentity,
                        recipientTrust = recipientTrust,
                    )
                }
                .getOrElse { exception ->
                    emitEncryptFailure(peerId, exception::class.simpleName.orEmpty())
                    return MeshEngineOutboundDirectEnvelopePreparation.EncryptFailure
                }
        return MeshEngineOutboundDirectEnvelopePreparation.Ready(
            createOutboundDirectEnvelope(sealedPayload)
        )
    }

    private fun createOutboundDirectEnvelope(sealedPayload: ByteArray): ByteArray {
        return DirectMessageEnvelope(
                senderPeerId = localIdentity.peerId,
                ciphertext = sealedPayload,
            )
            .encode()
    }
}

internal fun buildMeshEngineRuntimeOutboundDirectEnvelopeSupport(
    localIdentity: LocalIdentity,
    recipientTrustSupport: MeshEngineOutboundRecipientTrustSupport,
): MeshEngineOutboundDirectEnvelopeSupport {
    return MeshEngineOutboundDirectEnvelopeSupport(
        localIdentity = localIdentity,
        recipientTrustSupport = recipientTrustSupport,
    )
}
