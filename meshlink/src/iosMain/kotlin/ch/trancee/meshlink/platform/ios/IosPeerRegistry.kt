package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.identity.hexStartsWith
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportMode
import platform.CoreBluetooth.CBPeripheral

internal const val TEMPORARY_PEER_PREFIX: String = "cb-"

internal class DiscoveredPeerFlags
internal constructor(
    val rediscoveryLoggedWithoutLink: Boolean = false,
    val presenceAnnounced: Boolean = false,
)

internal class DiscoveredPeerState
internal constructor(
    keyHash: ByteArray,
    val peripheralIdentifier: String,
    val l2capPsm: Int,
    val transportMode: TransportMode,
    val platformFamily: BleDiscoveryPlatformFamily,
    val flags: DiscoveredPeerFlags = DiscoveredPeerFlags(),
) {
    val keyHash: ByteArray = keyHash.copyOf()
}

internal class DiscoveredPeerDiscovery
internal constructor(
    internal val identifier: String,
    keyHash: ByteArray,
    internal val l2capPsm: Int,
    internal val transportMode: TransportMode,
    internal val platformFamily: BleDiscoveryPlatformFamily,
) {
    internal val keyHash: ByteArray = keyHash.copyOf()
}

internal class DiscoveredPeer
internal constructor(val hintPeerId: PeerId, state: DiscoveredPeerState) {
    val keyHash: ByteArray = state.keyHash
    var peripheralIdentifier: String = state.peripheralIdentifier
    var l2capPsm: Int = state.l2capPsm
    var transportMode: TransportMode = state.transportMode
    var platformFamily: BleDiscoveryPlatformFamily = state.platformFamily
    var rediscoveryLoggedWithoutLink: Boolean = state.flags.rediscoveryLoggedWithoutLink
    var presenceAnnounced: Boolean = state.flags.presenceAnnounced
}

internal class PeerDiscoveryUpdate
internal constructor(internal val peer: DiscoveredPeer, internal val events: List<TransportEvent>)

internal class IosPeerBindings {
    private val peerHintByIdentifier: MutableMap<String, String> = linkedMapOf()
    private val temporaryHintByIdentifier: MutableMap<String, String> = linkedMapOf()
    private val peripheralsByIdentifier: MutableMap<String, CBPeripheral> = linkedMapOf()

    internal val hintBindings: Map<String, String>
        get() = peerHintByIdentifier

    internal fun retainPeripheral(identifier: String, peripheral: CBPeripheral): Unit {
        peripheralsByIdentifier[identifier] = peripheral
    }

    internal fun peripheralFor(identifier: String): CBPeripheral? {
        return peripheralsByIdentifier[identifier]
    }

    internal fun hintForIdentifier(identifier: String): String? {
        return peerHintByIdentifier[identifier]
    }

    internal fun bindHintToIdentifier(identifier: String, hintPeerIdValue: String): Unit {
        peerHintByIdentifier[identifier] = hintPeerIdValue
    }

    internal fun temporaryHintForIdentifier(identifier: String): String? {
        return temporaryHintByIdentifier[identifier]
    }

    internal fun bindTemporaryHint(identifier: String, hintPeerIdValue: String): Unit {
        temporaryHintByIdentifier[identifier] = hintPeerIdValue
    }

    internal fun temporaryPeerId(identifier: String): PeerId {
        val temporaryHint =
            temporaryHintByIdentifier.getOrPut(identifier) {
                TEMPORARY_PEER_PREFIX + identifier.replace("-", "")
            }
        return PeerId(temporaryHint)
    }

    internal fun clear(): Unit {
        peerHintByIdentifier.clear()
        temporaryHintByIdentifier.clear()
        peripheralsByIdentifier.clear()
    }
}

internal class IosPeerRegistry(private val bindings: IosPeerBindings) {
    private val discoveredPeers: MutableMap<String, DiscoveredPeer> = linkedMapOf()

    internal fun upsertDiscovery(
        hintPeerId: PeerId,
        discovery: DiscoveredPeerDiscovery,
    ): PeerDiscoveryUpdate {
        val existingPeer = discoveredPeers[hintPeerId.value]
        return if (existingPeer == null) {
            createDiscoveredPeer(hintPeerId = hintPeerId, discovery = discovery)
        } else {
            refreshDiscoveredPeer(
                existingPeer = existingPeer,
                hintPeerId = hintPeerId,
                discovery = discovery,
            )
        }
    }

    private fun createDiscoveredPeer(
        hintPeerId: PeerId,
        discovery: DiscoveredPeerDiscovery,
    ): PeerDiscoveryUpdate {
        val peer =
            DiscoveredPeer(
                hintPeerId = hintPeerId,
                state =
                    DiscoveredPeerState(
                        keyHash = discovery.keyHash,
                        peripheralIdentifier = discovery.identifier,
                        l2capPsm = discovery.l2capPsm,
                        transportMode = discovery.transportMode,
                        platformFamily = discovery.platformFamily,
                        flags = DiscoveredPeerFlags(presenceAnnounced = true),
                    ),
            )
        discoveredPeers[hintPeerId.value] = peer
        bindings.bindHintToIdentifier(discovery.identifier, hintPeerId.value)
        return PeerDiscoveryUpdate(
            peer = peer,
            events =
                listOf(
                    TransportEvent.PeerDiscovered(
                        peerId = hintPeerId,
                        transportMode = discovery.transportMode,
                    )
                ),
        )
    }

    private fun refreshDiscoveredPeer(
        existingPeer: DiscoveredPeer,
        hintPeerId: PeerId,
        discovery: DiscoveredPeerDiscovery,
    ): PeerDiscoveryUpdate {
        existingPeer.peripheralIdentifier = discovery.identifier
        existingPeer.l2capPsm = discovery.l2capPsm
        existingPeer.platformFamily = discovery.platformFamily
        bindings.bindHintToIdentifier(discovery.identifier, hintPeerId.value)

        val events = mutableListOf<TransportEvent>()
        if (existingPeer.transportMode != discovery.transportMode) {
            existingPeer.transportMode = discovery.transportMode
            events +=
                TransportEvent.TransportModeChanged(
                    peerId = hintPeerId,
                    transportMode = discovery.transportMode,
                )
        }
        if (!existingPeer.presenceAnnounced) {
            existingPeer.presenceAnnounced = true
            events +=
                TransportEvent.PeerDiscovered(
                    peerId = hintPeerId,
                    transportMode = discovery.transportMode,
                )
        }
        return PeerDiscoveryUpdate(peer = existingPeer, events = events)
    }

    internal fun peer(hintPeerIdValue: String): DiscoveredPeer? {
        return discoveredPeers[hintPeerIdValue]
    }

    internal fun peers(): Collection<DiscoveredPeer> {
        return discoveredPeers.values
    }

    internal fun incomingL2capHintCandidates(): List<IncomingL2capHintCandidate> {
        return discoveredPeers.values.map { peer ->
            IncomingL2capHintCandidate(
                hintPeerIdValue = peer.hintPeerId.value,
                keyHash = peer.keyHash,
                platformFamily = peer.platformFamily,
                transportMode = peer.transportMode,
            )
        }
    }

    internal fun resolve(peerId: PeerId): DiscoveredPeer? {
        discoveredPeers[peerId.value]?.let { peer ->
            return peer
        }
        return discoveredPeers.values.firstOrNull { discoveredPeer ->
            peerId.value.hexStartsWith(discoveredPeer.keyHash)
        }
    }

    internal fun setRediscoveryLoggedWithoutLink(hintPeerIdValue: String, logged: Boolean): Unit {
        discoveredPeers[hintPeerIdValue]?.rediscoveryLoggedWithoutLink = logged
    }

    internal fun setPresenceAnnounced(hintPeerIdValue: String, announced: Boolean): Unit {
        discoveredPeers[hintPeerIdValue]?.presenceAnnounced = announced
    }

    internal fun clear(): Unit {
        discoveredPeers.clear()
    }
}
