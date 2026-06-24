package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import ch.trancee.meshlink.transport.TransportMode
import ch.trancee.meshlink.transport.shouldUseMixedPlatformGattNotifyBearer

internal interface GattSideLinkClient : PreferredGattSendClient {
    fun start(): Unit

    fun close(): Unit
}

internal class GattSideLinkCoordinatorDependencies
internal constructor(
    internal val deviceForPeer: (DiscoveredPeer) -> Any?,
    internal val hasActiveL2capLink: (String) -> Boolean,
    internal val setPresenceAnnounced: (String, Boolean) -> Unit,
    internal val onFrameReceived: (PeerId, ByteArray) -> Boolean,
    internal val onPeerLost: (PeerId) -> Unit,
    internal val createClient:
        (
            peerHintId: PeerId,
            device: Any,
            onFrameReceived: (PeerId, ByteArray) -> Boolean,
            onDisconnected: (PeerId) -> Unit,
        ) -> GattSideLinkClient,
    internal val log: (String) -> Unit,
)

internal class GattSideLinkCoordinator(
    private val dependencies: GattSideLinkCoordinatorDependencies
) {
    private val clientsByHint: MutableMap<String, GattSideLinkClient> = linkedMapOf()

    internal fun ensureStarted(
        peer: DiscoveredPeer,
        localPlatformFamily: BleDiscoveryPlatformFamily,
    ): Unit {
        if (
            peer.transportMode != TransportMode.GATT &&
                !shouldUseMixedPlatformGattNotifyBearer(
                    localPlatformFamily = localPlatformFamily,
                    remotePlatformFamily = peer.platformFamily,
                )
        ) {
            return
        }
        val device = dependencies.deviceForPeer(peer) ?: return
        val existingClient = clientsByHint[peer.hintPeerId.value]
        if (existingClient != null) {
            if (!existingClient.isReady()) {
                existingClient.start()
            }
            return
        }
        val client =
            dependencies.createClient(
                peer.hintPeerId,
                device,
                dependencies.onFrameReceived,
                ::handleDisconnected,
            )
        clientsByHint[peer.hintPeerId.value] = client
        dependencies.log("initiating GATT notify side link to ${peer.hintPeerId.value.takeLast(6)}")
        client.start()
    }

    internal fun restart(
        peer: DiscoveredPeer,
        localPlatformFamily: BleDiscoveryPlatformFamily,
        reason: String,
    ): Unit {
        clientsByHint.remove(peer.hintPeerId.value)?.let { existingClient ->
            dependencies.log(
                "restarting GATT notify side link for ${peer.hintPeerId.value.takeLast(6)}: $reason"
            )
            existingClient.close()
        }
        ensureStarted(peer = peer, localPlatformFamily = localPlatformFamily)
    }

    internal fun currentClient(hintPeerIdValue: String): PreferredGattSendClient? {
        return clientsByHint[hintPeerIdValue]
    }

    internal fun promoteHint(
        temporaryHintPeerIdValue: String,
        canonicalHintPeerIdValue: String,
    ): Unit {
        clientsByHint.remove(temporaryHintPeerIdValue)?.let { client ->
            if (!clientsByHint.containsKey(canonicalHintPeerIdValue)) {
                clientsByHint[canonicalHintPeerIdValue] = client
            } else {
                client.close()
            }
        }
    }

    internal fun hasReadyLink(hintPeerIdValue: String): Boolean {
        return clientsByHint[hintPeerIdValue]?.isReady() == true
    }

    internal fun stopAll(): Unit {
        clientsByHint.values.forEach(GattSideLinkClient::close)
        clientsByHint.clear()
    }

    internal fun handleDisconnected(peerHintId: PeerId): Unit {
        val removedClient = clientsByHint.remove(peerHintId.value) ?: return
        dependencies.log("removed GATT notify side link for ${peerHintId.value.takeLast(6)}")
        if (dependencies.hasActiveL2capLink(peerHintId.value)) {
            return
        }
        removedClient.close()
        dependencies.setPresenceAnnounced(peerHintId.value, false)
        dependencies.onPeerLost(peerHintId)
    }
}

internal fun createGattSideLinkClient(
    context: Any,
    appId: String,
    peerHintId: PeerId,
    device: Any,
    log: (String) -> Unit,
    onFrameReceived: (PeerId, ByteArray) -> Boolean,
    onDisconnected: (PeerId) -> Unit,
): GattSideLinkClient {
    return GattSideLinkClientAdapter(
        GattNotifyClient(
            context = context,
            appId = appId,
            peerHintId = peerHintId,
            device = device,
            log = log,
            onFrameReceived = onFrameReceived,
            onDisconnected = onDisconnected,
        )
    )
}

private class GattSideLinkClientAdapter(private val client: GattNotifyClient) : GattSideLinkClient {
    override fun start(): Unit {
        client.start()
    }

    override fun isReady(): Boolean {
        return client.isReady()
    }

    override suspend fun write(payload: ByteArray): Boolean {
        return client.write(payload)
    }

    override fun close(): Unit {
        client.close()
    }
}
