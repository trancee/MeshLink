package ch.trancee.meshlink.platform.android

import android.bluetooth.BluetoothDevice
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.identity.hexStartsWith
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportMode

internal const val TEMPORARY_PEER_PREFIX: String = "bt-"

internal class DiscoveredPeerFlags
internal constructor(
    val rediscoveryLoggedWithoutLink: Boolean = false,
    val presenceAnnounced: Boolean = false,
)

internal class DiscoveredPeerState
internal constructor(
    keyHash: ByteArray,
    val deviceAddress: String,
    val l2capPsm: Int,
    val transportMode: TransportMode,
    val platformFamily: BleDiscoveryPlatformFamily,
    val flags: DiscoveredPeerFlags = DiscoveredPeerFlags(),
) {
    val keyHash: ByteArray = keyHash.copyOf()
}

internal class DiscoveredPeerDiscovery
internal constructor(
    internal val address: String,
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
    var deviceAddress: String = state.deviceAddress
    var l2capPsm: Int = state.l2capPsm
    var transportMode: TransportMode = state.transportMode
    var platformFamily: BleDiscoveryPlatformFamily = state.platformFamily
    var rediscoveryLoggedWithoutLink: Boolean = state.flags.rediscoveryLoggedWithoutLink
    var presenceAnnounced: Boolean = state.flags.presenceAnnounced
}

internal class PeerDiscoveryUpdate
internal constructor(internal val peer: DiscoveredPeer, internal val events: List<TransportEvent>)

/**
 * Mutated from multiple independent Android BLE callback sources (scan callback thread, GATT server
 * Binder thread, L2CAP accept coroutine), so every access is guarded by [lock] to avoid torn
 * reads/writes and `ConcurrentModificationException` on the backing maps.
 */
internal class PeerBindings {
    private val lock = Any()
    private val peerHintByAddress: MutableMap<String, String> = linkedMapOf()
    private val temporaryHintByAddress: MutableMap<String, String> = linkedMapOf()
    private val devicesByAddress: MutableMap<String, BluetoothDevice> = linkedMapOf()

    /**
     * Diagnostic hook invoked whenever [bindHintToAddress] silently replaces an address's existing
     * hint with a *different* peer id. Such a rebind is expected when an address is genuinely
     * reused across a disconnect/reconnect for the same peer, but if it happens for a *different*
     * peer id it means an inbound frame (or unauthenticated LinkIdentity claim; see
     * [resolveIncomingGattFrameDisposition]) is being attributed to the wrong logical peer/session
     * -- worth surfacing explicitly rather than debugging blind when co-located devices with a
     * colliding 16-bit meshHash are nearby. No-op by default; wired up via [attachRebindLogger].
     */
    private var onConflictingRebind:
        (address: String, previousHint: String, newHint: String) -> Unit =
        { _, _, _ ->
        }

    internal fun attachRebindLogger(
        onConflictingRebind: (address: String, previousHint: String, newHint: String) -> Unit
    ): Unit {
        this.onConflictingRebind = onConflictingRebind
    }

    internal fun retainDevice(address: String, device: BluetoothDevice): Unit {
        synchronized(lock) { devicesByAddress[address] = device }
    }

    internal fun deviceFor(address: String): BluetoothDevice? {
        return synchronized(lock) { devicesByAddress[address] }
    }

    internal fun hintForAddress(address: String): String? {
        return synchronized(lock) { peerHintByAddress[address] }
    }

    internal fun bindHintToAddress(address: String, hintPeerIdValue: String): Unit {
        val previousHint = synchronized(lock) { peerHintByAddress.put(address, hintPeerIdValue) }
        if (previousHint != null && previousHint != hintPeerIdValue) {
            onConflictingRebind(address, previousHint, hintPeerIdValue)
        }
    }

    internal fun temporaryHintForAddress(address: String): String? {
        return synchronized(lock) { temporaryHintByAddress[address] }
    }

    internal fun removeTemporaryHint(address: String): String? {
        return synchronized(lock) { temporaryHintByAddress.remove(address) }
    }

    internal fun temporaryPeerId(address: String): PeerId {
        val temporaryHint =
            synchronized(lock) {
                temporaryHintByAddress.getOrPut(address) {
                    TEMPORARY_PEER_PREFIX + address.lowercase().replace(":", "")
                }
            }
        return PeerId(temporaryHint)
    }

    internal fun clear(): Unit {
        synchronized(lock) {
            peerHintByAddress.clear()
            temporaryHintByAddress.clear()
            devicesByAddress.clear()
        }
    }
}

/**
 * Mutated from multiple independent Android BLE callback sources (scan callback thread, GATT server
 * Binder thread, L2CAP accept coroutine), so every access is guarded by [lock] to avoid torn
 * reads/writes and `ConcurrentModificationException` on the backing map.
 */
internal class PeerRegistry(private val bindings: PeerBindings) {
    private val lock = Any()
    private val discoveredPeers: MutableMap<String, DiscoveredPeer> = linkedMapOf()

    internal fun upsertDiscovery(
        hintPeerId: PeerId,
        discovery: DiscoveredPeerDiscovery,
        announcePresence: Boolean = true,
    ): PeerDiscoveryUpdate {
        return synchronized(lock) {
            val existingPeer = discoveredPeers[hintPeerId.value]
            if (existingPeer == null) {
                createDiscoveredPeer(
                    hintPeerId = hintPeerId,
                    discovery = discovery,
                    announcePresence = announcePresence,
                )
            } else {
                refreshDiscoveredPeer(
                    existingPeer = existingPeer,
                    hintPeerId = hintPeerId,
                    discovery = discovery,
                    announcePresence = announcePresence,
                )
            }
        }
    }

    /** Callers must hold [lock]. */
    private fun createDiscoveredPeer(
        hintPeerId: PeerId,
        discovery: DiscoveredPeerDiscovery,
        announcePresence: Boolean,
    ): PeerDiscoveryUpdate {
        val peer =
            DiscoveredPeer(
                hintPeerId = hintPeerId,
                state =
                    DiscoveredPeerState(
                        keyHash = discovery.keyHash,
                        deviceAddress = discovery.address,
                        l2capPsm = discovery.l2capPsm,
                        transportMode = discovery.transportMode,
                        platformFamily = discovery.platformFamily,
                        flags = DiscoveredPeerFlags(presenceAnnounced = announcePresence),
                    ),
            )
        discoveredPeers[hintPeerId.value] = peer
        bindings.bindHintToAddress(discovery.address, hintPeerId.value)
        return PeerDiscoveryUpdate(
            peer = peer,
            events =
                if (announcePresence) {
                    listOf(
                        TransportEvent.PeerDiscovered(
                            peerId = hintPeerId,
                            transportMode = discovery.transportMode,
                        )
                    )
                } else {
                    emptyList()
                },
        )
    }

    /** Callers must hold [lock]. */
    private fun refreshDiscoveredPeer(
        existingPeer: DiscoveredPeer,
        hintPeerId: PeerId,
        discovery: DiscoveredPeerDiscovery,
        announcePresence: Boolean,
    ): PeerDiscoveryUpdate {
        existingPeer.deviceAddress = discovery.address
        existingPeer.l2capPsm = discovery.l2capPsm
        existingPeer.platformFamily = discovery.platformFamily
        bindings.bindHintToAddress(discovery.address, hintPeerId.value)

        val events = mutableListOf<TransportEvent>()
        if (existingPeer.transportMode != discovery.transportMode) {
            existingPeer.transportMode = discovery.transportMode
            events +=
                TransportEvent.TransportModeChanged(
                    peerId = hintPeerId,
                    transportMode = discovery.transportMode,
                )
        }
        if (announcePresence && !existingPeer.presenceAnnounced) {
            existingPeer.presenceAnnounced = true
            events +=
                TransportEvent.PeerDiscovered(
                    peerId = hintPeerId,
                    transportMode = discovery.transportMode,
                )
        }
        return PeerDiscoveryUpdate(peer = existingPeer, events = events)
    }

    internal fun discoveredPeerCount(): Int {
        return synchronized(lock) { discoveredPeers.size }
    }

    internal fun peer(hintPeerIdValue: String): DiscoveredPeer? {
        return synchronized(lock) { discoveredPeers[hintPeerIdValue] }
    }

    internal fun removePeer(hintPeerIdValue: String): DiscoveredPeer? {
        return synchronized(lock) { discoveredPeers.remove(hintPeerIdValue) }
    }

    internal fun resolve(peerId: PeerId): DiscoveredPeer? {
        return synchronized(lock) {
            discoveredPeers[peerId.value]
                ?: discoveredPeers.values.firstOrNull { discoveredPeer ->
                    peerId.value.hexStartsWith(discoveredPeer.keyHash)
                }
        }
    }

    internal fun setRediscoveryLoggedWithoutLink(hintPeerIdValue: String, logged: Boolean): Unit {
        synchronized(lock) {
            discoveredPeers[hintPeerIdValue]?.rediscoveryLoggedWithoutLink = logged
        }
    }

    internal fun setPresenceAnnounced(hintPeerIdValue: String, announced: Boolean): Unit {
        synchronized(lock) { discoveredPeers[hintPeerIdValue]?.presenceAnnounced = announced }
    }

    internal fun clear(): Unit {
        synchronized(lock) { discoveredPeers.clear() }
    }
}
