package ch.trancee.meshlink.engine.transfer

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.engine.assembly.MeshEngineHardRunToken
import ch.trancee.meshlink.transfer.RelayTransferSession
import ch.trancee.meshlink.wire.WireFrame

internal data class MeshEngineRelayTransferCallbacks(
    val sendEncryptedWireFrame:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
    val sendTransferTowardsDestination:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
)

internal class MeshEngineRelayTransferSupport(
    private val transferRegistry: MeshEngineTransferRegistry,
    private val callbacks: MeshEngineRelayTransferCallbacks,
) {
    constructor(
        relayTransfers: MutableMap<String, RelayTransferSession>,
        callbacks: MeshEngineRelayTransferCallbacks,
    ) : this(MeshEngineTransferRegistry(relayTransfers = relayTransfers), callbacks)

    suspend fun handleTransferStart(
        peerId: PeerId,
        frame: WireFrame.TransferStart,
        hardRunToken: MeshEngineHardRunToken,
    ): Unit {
        val relaySession = transferRegistry.relaySession(frame.transferId)
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
                    .also { session -> transferRegistry.storeRelaySession(session) }
            }
        callbacks.sendTransferTowardsDestination(
            frame.destinationPeerId,
            frame,
            "transfer.forward.start",
            activeRelaySession.hardRunToken,
        )
    }

    suspend fun handleTransferChunk(frame: WireFrame.TransferChunk): Boolean {
        val relaySession = transferRegistry.relaySession(frame.transferId) ?: return false
        callbacks.sendTransferTowardsDestination(
            relaySession.destinationPeerId,
            frame,
            "transfer.forward.chunk",
            relaySession.hardRunToken,
        )
        return true
    }

    suspend fun handleTransferAck(frame: WireFrame.TransferAck): Boolean {
        val relaySession = transferRegistry.relaySession(frame.transferId) ?: return false
        callbacks.sendEncryptedWireFrame(
            relaySession.upstreamPeerId,
            frame,
            "transfer.forward.ack",
            relaySession.hardRunToken,
        )
        return true
    }

    suspend fun handleTransferComplete(frame: WireFrame.TransferComplete): Boolean {
        val relaySession = transferRegistry.removeRelaySession(frame.transferId) ?: return false
        callbacks.sendTransferTowardsDestination(
            relaySession.destinationPeerId,
            frame,
            "transfer.forward.complete",
            relaySession.hardRunToken,
        )
        return true
    }

    suspend fun handleTransferAbort(frame: WireFrame.TransferAbort): Boolean {
        val relaySession = transferRegistry.removeRelaySession(frame.transferId) ?: return false
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
    transferRegistry: MeshEngineTransferRegistry,
    sendEncryptedWireFrame: suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
    sendTransferTowardsDestination:
        suspend (PeerId, WireFrame, String, MeshEngineHardRunToken?) -> Boolean,
): MeshEngineRelayTransferSupport {
    return MeshEngineRelayTransferSupport(
        transferRegistry = transferRegistry,
        callbacks =
            MeshEngineRelayTransferCallbacks(
                sendEncryptedWireFrame = sendEncryptedWireFrame,
                sendTransferTowardsDestination = sendTransferTowardsDestination,
            ),
    )
}
