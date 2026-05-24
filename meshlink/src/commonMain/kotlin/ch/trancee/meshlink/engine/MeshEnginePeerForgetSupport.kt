package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.ForgetPeerResult
import ch.trancee.meshlink.api.PeerId

internal data class MeshEnginePeerForgetCallbacks(
    val readFirstSeenAtEpochMillis: suspend (PeerId) -> Long?,
    val deleteTrust: suspend (PeerId) -> Unit,
    val clearPeer: suspend (PeerId) -> PendingInitiatorHandshake?,
    val dispatchPeerDisconnected: suspend (PeerId, Map<String, String>) -> Unit,
    val markPeerDisconnected: (PeerId) -> Boolean,
    val emitPeerLost: suspend (PeerId) -> Unit,
)

internal class MeshEnginePeerForgetSupport(private val callbacks: MeshEnginePeerForgetCallbacks) {
    internal suspend fun forgetPeer(peerId: PeerId): ForgetPeerResult {
        val firstSeenAtEpochMillis = callbacks.readFirstSeenAtEpochMillis(peerId)
        if (firstSeenAtEpochMillis == null) {
            return ForgetPeerResult.NotFound
        }

        callbacks.deleteTrust(peerId)
        callbacks
            .clearPeer(peerId)
            ?.sessionDeferred
            ?.complete(SessionEstablishmentOutcome.Unreachable)
        callbacks.dispatchPeerDisconnected(
            peerId,
            mapOf(
                "forgottenPeerId" to peerId.value,
                "firstSeenAtEpochMillis" to firstSeenAtEpochMillis.toString(),
            ),
        )
        if (callbacks.markPeerDisconnected(peerId)) {
            callbacks.emitPeerLost(peerId)
        }
        return ForgetPeerResult.Forgotten
    }
}

internal fun buildMeshEngineRuntimePeerForgetSupport(
    readFirstSeenAtEpochMillis: suspend (PeerId) -> Long?,
    deleteTrust: suspend (PeerId) -> Unit,
    clearPeer: suspend (PeerId) -> PendingInitiatorHandshake?,
    dispatchPeerDisconnected: suspend (PeerId, Map<String, String>) -> Unit,
    markPeerDisconnected: (PeerId) -> Boolean,
    emitPeerLost: suspend (PeerId) -> Unit,
): MeshEnginePeerForgetSupport {
    return MeshEnginePeerForgetSupport(
        callbacks =
            MeshEnginePeerForgetCallbacks(
                readFirstSeenAtEpochMillis = readFirstSeenAtEpochMillis,
                deleteTrust = deleteTrust,
                clearPeer = clearPeer,
                dispatchPeerDisconnected = dispatchPeerDisconnected,
                markPeerDisconnected = markPeerDisconnected,
                emitPeerLost = emitPeerLost,
            )
    )
}
