package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.crypto.CryptoProvider
import ch.trancee.meshlink.identity.toHexString
import ch.trancee.meshlink.transport.BleDiscoveryPayload

internal fun PeerId.isTemporaryTransportPeerId(): Boolean {
    return value.startsWith(ANDROID_TEMPORARY_PEER_PREFIX) ||
        value.startsWith(IOS_TEMPORARY_PEER_PREFIX)
}

internal fun canonicalPeerIdForTemporaryTransportPeer(
    peerId: PeerId,
    remoteEd25519PublicKey: ByteArray,
    remoteX25519PublicKey: ByteArray,
    cryptoProvider: CryptoProvider,
): PeerId {
    if (!peerId.isTemporaryTransportPeerId()) {
        return peerId
    }
    val remoteIdentityHash = cryptoProvider.sha256(remoteEd25519PublicKey + remoteX25519PublicKey)
    return PeerId(
        remoteIdentityHash.copyOfRange(0, BleDiscoveryPayload.KEY_HASH_SIZE_BYTES).toHexString()
    )
}

private const val ANDROID_TEMPORARY_PEER_PREFIX: String = "bt-"
private const val IOS_TEMPORARY_PEER_PREFIX: String = "cb-"
