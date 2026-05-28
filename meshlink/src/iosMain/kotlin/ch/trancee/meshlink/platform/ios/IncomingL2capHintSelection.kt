package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.shouldLocalPeerInitiateL2capConnection

internal data class IncomingL2capHintCandidate(
    internal val hintPeerIdValue: String,
    internal val keyHash: ByteArray,
    internal val platformFamily: BleDiscoveryPlatformFamily,
    internal val transportMode: TransportMode,
)

internal class IncomingL2capSelectionIgnoredHints(
    val activeHintIds: Collection<String>,
    val pendingHintIds: Collection<String>,
)

internal class IncomingL2capSelectionLocalPeer(
    localKeyHash: ByteArray,
    val platformFamily: BleDiscoveryPlatformFamily,
) {
    val keyHash: ByteArray = localKeyHash.copyOf()
}

internal class IncomingL2capHintSelectionRequest(
    val peripheralIdentifier: String,
    val peerHintByIdentifier: Map<String, String>,
    val discoveredPeers: List<IncomingL2capHintCandidate>,
    val ignoredHints: IncomingL2capSelectionIgnoredHints,
    val localPeer: IncomingL2capSelectionLocalPeer,
)

internal fun selectIncomingL2capHintPeerId(request: IncomingL2capHintSelectionRequest): String? {
    request.peerHintByIdentifier[request.peripheralIdentifier]?.let { mappedHint ->
        return mappedHint
    }
    val waitingCandidates =
        request.discoveredPeers.filter { candidate ->
            candidate.transportMode == TransportMode.L2CAP &&
                candidate.hintPeerIdValue !in request.ignoredHints.activeHintIds &&
                candidate.hintPeerIdValue !in request.ignoredHints.pendingHintIds &&
                !shouldLocalPeerInitiateL2capConnection(
                    localKeyHash = request.localPeer.keyHash,
                    localPlatformFamily = request.localPeer.platformFamily,
                    remoteKeyHash = candidate.keyHash,
                    remotePlatformFamily = candidate.platformFamily,
                )
        }
    return waitingCandidates.singleOrNull()?.hintPeerIdValue
}
