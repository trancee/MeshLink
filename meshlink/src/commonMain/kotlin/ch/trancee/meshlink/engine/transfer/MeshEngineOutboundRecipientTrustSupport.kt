package ch.trancee.meshlink.engine.transfer

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.engine.handshake.EndToEndSessionEstablishmentOutcome
import ch.trancee.meshlink.trust.TofuTrustStore
import ch.trancee.meshlink.trust.TrustRecord

/**
 * Resolves the pinned trust record to use when sealing an outbound direct message for [peerId].
 * Trust is never learned from gossiped route metadata or other self-asserted data: if no trust is
 * already pinned, an end-to-end Noise XX handshake is performed (or awaited, if already in flight)
 * via [ensureEndToEndSession], and only the trust cryptographically pinned by that handshake's
 * completion is used.
 */
internal class MeshEngineOutboundRecipientTrustSupport(
    private val trustStore: TofuTrustStore,
    private val ensureEndToEndSession: suspend (PeerId) -> EndToEndSessionEstablishmentOutcome,
) {
    suspend fun resolveRecipientTrust(peerId: PeerId): TrustRecord? {
        trustStore.read(peerId.value)?.let { existingTrust ->
            return existingTrust
        }
        return when (ensureEndToEndSession(peerId)) {
            is EndToEndSessionEstablishmentOutcome.Established -> trustStore.read(peerId.value)
            EndToEndSessionEstablishmentOutcome.TrustFailure,
            EndToEndSessionEstablishmentOutcome.Unreachable -> null
        }
    }
}

internal fun buildMeshEngineRuntimeOutboundRecipientTrustSupport(
    trustStore: TofuTrustStore,
    ensureEndToEndSession: suspend (PeerId) -> EndToEndSessionEstablishmentOutcome,
): MeshEngineOutboundRecipientTrustSupport {
    return MeshEngineOutboundRecipientTrustSupport(
        trustStore = trustStore,
        ensureEndToEndSession = ensureEndToEndSession,
    )
}
