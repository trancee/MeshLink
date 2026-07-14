package ch.trancee.meshlink.platform.android.gatt

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.platform.android.PeerBindings
import ch.trancee.meshlink.wire.decodeLinkIdentityPeerIdOrNull

internal sealed class IncomingGattFrameDisposition {
    internal class Deliver internal constructor(internal val peerId: PeerId) :
        IncomingGattFrameDisposition()

    internal class ConsumedLinkIdentity internal constructor(internal val claimedPeerId: PeerId) :
        IncomingGattFrameDisposition()
}

internal fun resolveIncomingGattFrameDisposition(
    address: String,
    frame: ByteArray,
    peerBindings: PeerBindings,
    onUnknownPeerFrame: (PeerId, String) -> Unit,
    onClaimedPeerIdentity: (PeerId, String) -> Unit,
    log: (String) -> Unit,
): IncomingGattFrameDisposition {
    val knownHintPeerIdValue =
        peerBindings.hintForAddress(address) ?: peerBindings.temporaryHintForAddress(address)
    if (knownHintPeerIdValue != null) {
        return IncomingGattFrameDisposition.Deliver(PeerId(knownHintPeerIdValue))
    }

    val claimedPeerId = decodeLinkIdentityPeerIdOrNull(frame)
    if (claimedPeerId != null) {
        // This claim is intentionally unauthenticated: it only lets the local BLE transport
        // correlate this connection with an existing logical peer/session. Real confidentiality
        // still depends on the hop session keys established by Noise XX.
        peerBindings.bindHintToAddress(address, claimedPeerId.value)
        // A device that only ever accepts inbound GATT connections (never independently
        // scan-discovers the peer) would otherwise have no DiscoveredPeer entry for the claimed
        // peer id, leaving outbound replies dead-ended at resolvePeer() with "peer not
        // discovered" even though the link is bound and writable. Register/refresh a provisional
        // peer entry so a reply route exists; this mirrors the L2CAP path's promoteTemporaryLink()
        // call after bindHintToAddress() in BleTransportAdapterL2capSupport.kt.
        onClaimedPeerIdentity(claimedPeerId, address)
        log(
            "bound GATT server connection addr=$address to claimed peerId=${claimedPeerId.value} via LinkIdentity"
        )
        return IncomingGattFrameDisposition.ConsumedLinkIdentity(claimedPeerId)
    }

    val existingTemporaryHintPeerIdValue = peerBindings.temporaryHintForAddress(address)
    if (existingTemporaryHintPeerIdValue != null) {
        return IncomingGattFrameDisposition.Deliver(PeerId(existingTemporaryHintPeerIdValue))
    }

    return peerBindings
        .temporaryPeerId(address)
        .also { temporaryPeerId -> onUnknownPeerFrame(temporaryPeerId, address) }
        .let(IncomingGattFrameDisposition::Deliver)
}
