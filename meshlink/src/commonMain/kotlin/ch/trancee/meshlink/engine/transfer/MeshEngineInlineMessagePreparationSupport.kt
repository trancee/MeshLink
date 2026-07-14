package ch.trancee.meshlink.engine.transfer

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.identity.LocalIdentity
import ch.trancee.meshlink.wire.WireFrame

internal data class MeshEngineInlineMessagePreparationCallbacks(
    val createMessageId: suspend () -> String,
    val emitEncryptFailure: suspend (PeerId, String) -> Unit,
)

internal sealed class MeshEngineOutboundInlineMessagePreparation {
    internal class Ready internal constructor(internal val message: WireFrame.Message) :
        MeshEngineOutboundInlineMessagePreparation()

    internal data object MissingTrust : MeshEngineOutboundInlineMessagePreparation()

    internal data object EncryptFailure : MeshEngineOutboundInlineMessagePreparation()
}

internal class MeshEngineInlineMessagePreparationSupport(
    private val localIdentity: LocalIdentity,
    private val directEnvelopeSupport: MeshEngineOutboundDirectEnvelopeSupport,
    private val callbacks: MeshEngineInlineMessagePreparationCallbacks,
) {
    suspend fun prepare(
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
        ttlMillis: Int,
    ): MeshEngineOutboundInlineMessagePreparation {
        return when (
            val envelopePreparation =
                directEnvelopeSupport.prepare(
                    peerId = peerId,
                    payload = payload,
                    emitEncryptFailure = callbacks.emitEncryptFailure,
                )
        ) {
            MeshEngineOutboundDirectEnvelopePreparation.MissingTrust ->
                MeshEngineOutboundInlineMessagePreparation.MissingTrust
            MeshEngineOutboundDirectEnvelopePreparation.EncryptFailure ->
                MeshEngineOutboundInlineMessagePreparation.EncryptFailure
            is MeshEngineOutboundDirectEnvelopePreparation.Ready ->
                MeshEngineOutboundInlineMessagePreparation.Ready(
                    WireFrame.Message(
                        messageId = callbacks.createMessageId(),
                        originPeerId = localIdentity.peerId,
                        destinationPeerId = peerId,
                        priority = priority,
                        ttlMillis = ttlMillis,
                        encryptedPayload = envelopePreparation.envelopeBytes,
                    )
                )
        }
    }
}

internal fun buildMeshEngineRuntimeInlineMessagePreparationSupport(
    localIdentity: LocalIdentity,
    directEnvelopeSupport: MeshEngineOutboundDirectEnvelopeSupport,
    createMessageId: suspend () -> String,
    emitEncryptFailure: suspend (PeerId, String) -> Unit,
): MeshEngineInlineMessagePreparationSupport {
    return MeshEngineInlineMessagePreparationSupport(
        localIdentity = localIdentity,
        directEnvelopeSupport = directEnvelopeSupport,
        callbacks =
            MeshEngineInlineMessagePreparationCallbacks(
                createMessageId = createMessageId,
                emitEncryptFailure = emitEncryptFailure,
            ),
    )
}
