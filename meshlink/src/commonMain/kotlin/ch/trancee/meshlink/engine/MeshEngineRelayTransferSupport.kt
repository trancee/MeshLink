package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transfer.RelayTransferSession
import ch.trancee.meshlink.wire.WireFrame

internal data class MeshEngineRelayTransferCallbacks(
    val sendEncryptedWireFrame:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
    val sendTransferTowardsDestination:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
)

internal class MeshEngineRelayTransferSupport(
    private val relayTransfers: MutableMap<String, RelayTransferSession>,
    private val callbacks: MeshEngineRelayTransferCallbacks,
) {
    suspend fun handleTransferStart(
        peerId: PeerId,
        frame: WireFrame.TransferStart,
        hardRunToken: MeshEngineHardRunToken,
    ): Unit {
        val relaySession = relayTransfers[frame.transferId]
        val activeRelaySession =
            if (relaySession != null) {
                relaySession.upstreamPeerId = peerId
                relaySession
            } else {
                RelayTransferSession(
                        transferId = frame.transferId,
                        messageId = frame.messageId,
                        originPeerId = frame.originPeerId,
                        destinationPeerId = frame.destinationPeerId,
                        upstreamPeerId = peerId,
                        hardRunToken = hardRunToken,
                    )
                    .also { session -> relayTransfers[frame.transferId] = session }
            }
        callbacks.sendTransferTowardsDestination(
            frame.destinationPeerId,
            frame,
            "transfer.forward.start",
            activeRelaySession.hardRunToken,
        )
    }

    suspend fun handleTransferChunk(frame: WireFrame.TransferChunk): Boolean {
        val relaySession = relayTransfers[frame.transferId] ?: return false
        callbacks.sendTransferTowardsDestination(
            relaySession.destinationPeerId,
            frame,
            "transfer.forward.chunk",
            relaySession.hardRunToken,
        )
        return true
    }

    suspend fun handleTransferAck(frame: WireFrame.TransferAck): Boolean {
        val relaySession = relayTransfers[frame.transferId] ?: return false
        callbacks.sendEncryptedWireFrame(
            relaySession.upstreamPeerId,
            frame,
            "transfer.forward.ack",
            relaySession.hardRunToken,
        )
        return true
    }

    suspend fun handleTransferComplete(frame: WireFrame.TransferComplete): Boolean {
        val relaySession = relayTransfers.remove(frame.transferId) ?: return false
        callbacks.sendTransferTowardsDestination(
            relaySession.destinationPeerId,
            frame,
            "transfer.forward.complete",
            relaySession.hardRunToken,
        )
        return true
    }

    suspend fun handleTransferAbort(frame: WireFrame.TransferAbort): Boolean {
        val relaySession = relayTransfers.remove(frame.transferId) ?: return false
        callbacks.sendTransferTowardsDestination(
            relaySession.destinationPeerId,
            frame,
            "transfer.forward.abort",
            relaySession.hardRunToken,
        )
        return true
    }
}

internal fun buildMeshEngineRuntimeRelayTransferSupport(
    relayTransfers: MutableMap<String, RelayTransferSession>,
    sendEncryptedWireFrame: suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
    sendTransferTowardsDestination:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
): MeshEngineRelayTransferSupport {
    return MeshEngineRelayTransferSupport(
        relayTransfers = relayTransfers,
        callbacks =
            MeshEngineRelayTransferCallbacks(
                sendEncryptedWireFrame = sendEncryptedWireFrame,
                sendTransferTowardsDestination = sendTransferTowardsDestination,
            ),
    )
}
