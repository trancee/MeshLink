package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// A single physical GATT disconnect (radio hiccup, transient supervision-timeout expiry, momentary
// RF contention in a dense environment) does not necessarily mean the peer is actually gone --
// discovery/advertising frequently rediscovers it and a fresh GATT connection succeeds within a
// few seconds. Without a debounce, every such blip was surfaced to callers (and therefore the UI)
// as an immediate PeerLost, then PeerFound again moments later once reconnected -- the visible
// "connection cycling" flakiness. Delaying the PeerLost signal by this window, and cancelling it
// if the peer reconnects first, absorbs those blips silently while still surfacing a real loss
// promptly enough not to feel unresponsive.
private const val PEER_LOST_DEBOUNCE_MILLIS = 4_000L

internal interface GattSideLinkClient : PreferredGattSendClient {
    fun start(): Unit

    fun close(): Unit
}

internal class GattSideLinkCoordinatorDependencies
internal constructor(
    internal val localHintPeerId: PeerId,
    internal val deviceForPeer: (DiscoveredPeer) -> Any?,
    internal val hasActiveL2capLink: (String) -> Boolean,
    internal val setPresenceAnnounced: (String, Boolean) -> Unit,
    internal val onFrameReceived: (PeerId, ByteArray) -> Boolean,
    internal val onPeerLost: (PeerId) -> Unit,
    internal val createClient:
        (
            peerHintId: PeerId,
            localHintPeerId: PeerId,
            device: Any,
            onFrameReceived: (PeerId, ByteArray) -> Boolean,
            onDisconnected: (PeerId) -> Unit,
        ) -> GattSideLinkClient,
    internal val log: (String) -> Unit,
    // Used to schedule the debounced PeerLost signal (see PEER_LOST_DEBOUNCE_MILLIS). Must be a
    // scope whose lifetime spans the transport adapter's lifetime.
    internal val scope: CoroutineScope,
    // Overridable so tests can use 0L with an unconfined/immediate scope to observe the debounced
    // signal deterministically without depending on kotlinx-coroutines-test.
    internal val peerLostDebounceMillis: Long = PEER_LOST_DEBOUNCE_MILLIS,
)

internal class GattSideLinkCoordinator(
    private val dependencies: GattSideLinkCoordinatorDependencies
) {
    private val clientsByHint: MutableMap<String, GattSideLinkClient> = linkedMapOf()
    private val pendingLostSignals: MutableMap<String, Job> = mutableMapOf()

    internal fun ensureStarted(
        peer: DiscoveredPeer,
        localPlatformFamily: BleDiscoveryPlatformFamily,
    ): Unit {
        val device = dependencies.deviceForPeer(peer) ?: return
        // A fresh (re)connection attempt for this peer means any previously-scheduled PeerLost
        // debounce for it is now moot -- cancel it so a stale "actually lost" signal can't fire
        // later even though the link has since recovered.
        cancelPendingLostSignal(peer.hintPeerId.value)
        val existingClient = clientsByHint[peer.hintPeerId.value]
        if (existingClient != null) {
            if (!existingClient.isReady()) {
                dependencies.log(
                    "starting existing GATT side-link for ${peer.hintPeerId.value.takeLast(6)}"
                )
                existingClient.start()
            } else {
                dependencies.log(
                    "GATT side-link already active for ${peer.hintPeerId.value.takeLast(6)}"
                )
            }
            return
        }
        val client =
            dependencies.createClient(
                peer.hintPeerId,
                dependencies.localHintPeerId,
                device,
                dependencies.onFrameReceived,
                ::handleDisconnected,
            )
        clientsByHint[peer.hintPeerId.value] = client
        dependencies.log(
            "initiating GATT notify side link to ${peer.hintPeerId.value.takeLast(6)} local=$localPlatformFamily remote=${peer.platformFamily}"
        )
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
        pendingLostSignals.values.forEach(Job::cancel)
        pendingLostSignals.clear()
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
        scheduleLostSignal(peerHintId)
    }

    private fun cancelPendingLostSignal(hintPeerIdValue: String): Unit {
        pendingLostSignals.remove(hintPeerIdValue)?.cancel()
    }

    private fun scheduleLostSignal(peerHintId: PeerId): Unit {
        cancelPendingLostSignal(peerHintId.value)
        pendingLostSignals[peerHintId.value] =
            dependencies.scope.launch {
                delay(dependencies.peerLostDebounceMillis)
                pendingLostSignals.remove(peerHintId.value)
                dependencies.log(
                    "GATT side-link for ${peerHintId.value.takeLast(6)} did not recover within " +
                        "${dependencies.peerLostDebounceMillis}ms debounce window -- reporting PeerLost"
                )
                dependencies.setPresenceAnnounced(peerHintId.value, false)
                dependencies.onPeerLost(peerHintId)
            }
    }
}

internal fun createGattSideLinkClient(
    context: Any,
    appId: String,
    peerHintId: PeerId,
    localHintPeerId: PeerId,
    device: Any,
    log: (String) -> Unit,
    onFrameReceived: (PeerId, ByteArray) -> Boolean,
    onDisconnected: (PeerId) -> Unit,
    connectionPriorityProvider: () -> Int,
): GattSideLinkClient {
    return GattSideLinkClientAdapter(
        GattNotifyClient(
            context = context,
            appId = appId,
            peerHintId = peerHintId,
            localHintPeerId = localHintPeerId,
            device = device,
            log = log,
            onFrameReceived = onFrameReceived,
            onDisconnected = onDisconnected,
            connectionPriorityProvider = connectionPriorityProvider,
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
