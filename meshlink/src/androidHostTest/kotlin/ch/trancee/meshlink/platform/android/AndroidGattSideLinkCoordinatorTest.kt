package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import ch.trancee.meshlink.transport.TransportMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GattSideLinkCoordinatorTest {
    @Test
    fun ensureStartedSkipsPeersThatDoNotNeedTheMixedPlatformGattSideLink(): Unit {
        // Arrange
        val fixture = GattSideLinkCoordinatorFixture()
        val peer = discoveredPeer(platformFamily = BleDiscoveryPlatformFamily.ANDROID)

        // Act
        fixture.coordinator.ensureStarted(
            peer = peer,
            localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
        )

        // Assert
        assertEquals(0, fixture.createClientCalls)
        assertNull(fixture.coordinator.currentClient(peer.hintPeerId.value))
    }

    @Test
    fun ensureStartedCreatesAndStartsANewClientForMixedPlatformPeers(): Unit {
        // Arrange
        val fixture = GattSideLinkCoordinatorFixture()
        val peer = discoveredPeer(platformFamily = BleDiscoveryPlatformFamily.IOS)
        val client = FakeAndroidGattSideLinkClient(ready = false)
        fixture.enqueueClient(client)

        // Act
        fixture.coordinator.ensureStarted(
            peer = peer,
            localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
        )

        // Assert
        assertEquals(1, fixture.createClientCalls)
        assertEquals(1, client.startCalls)
        assertEquals(true, fixture.coordinator.currentClient(peer.hintPeerId.value) != null)
    }

    @Test
    fun ensureStartedRestartsAnExistingClientWhenItIsNotReady(): Unit {
        // Arrange
        val fixture = GattSideLinkCoordinatorFixture()
        val peer = discoveredPeer(platformFamily = BleDiscoveryPlatformFamily.IOS)
        val client = FakeAndroidGattSideLinkClient(ready = false)
        fixture.enqueueClient(client)
        fixture.coordinator.ensureStarted(
            peer = peer,
            localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
        )

        // Act
        fixture.coordinator.ensureStarted(
            peer = peer,
            localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
        )

        // Assert
        assertEquals(1, fixture.createClientCalls)
        assertEquals(2, client.startCalls)
    }

    @Test
    fun restartClosesTheExistingClientBeforeStartingAReplacement(): Unit {
        // Arrange
        val fixture = GattSideLinkCoordinatorFixture()
        val peer = discoveredPeer(platformFamily = BleDiscoveryPlatformFamily.IOS)
        val firstClient = FakeAndroidGattSideLinkClient(ready = true)
        val replacementClient = FakeAndroidGattSideLinkClient(ready = false)
        fixture.enqueueClient(firstClient)
        fixture.enqueueClient(replacementClient)
        fixture.coordinator.ensureStarted(
            peer = peer,
            localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
        )

        // Act
        fixture.coordinator.restart(
            peer = peer,
            localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
            reason = "write failed",
        )

        // Assert
        assertEquals(2, fixture.createClientCalls)
        assertEquals(1, firstClient.closeCalls)
        assertEquals(1, replacementClient.startCalls)
    }

    @Test
    fun promoteHintMovesTheExistingClientToTheCanonicalPeerWhenNoReplacementExists(): Unit {
        // Arrange
        val fixture = GattSideLinkCoordinatorFixture()
        val peer = discoveredPeer(platformFamily = BleDiscoveryPlatformFamily.IOS)
        val client = FakeAndroidGattSideLinkClient(ready = true)
        fixture.enqueueClient(client)
        fixture.coordinator.ensureStarted(
            peer = peer,
            localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
        )

        // Act
        fixture.coordinator.promoteHint(
            temporaryHintPeerIdValue = peer.hintPeerId.value,
            canonicalHintPeerIdValue = "canonical-peer",
        )

        // Assert
        assertNull(fixture.coordinator.currentClient(peer.hintPeerId.value))
        assertEquals(true, fixture.coordinator.currentClient("canonical-peer") != null)
        assertEquals(0, client.closeCalls)
    }

    @Test
    fun handleDisconnectedEmitsPeerLostWhenNoActiveL2capLinkRemains(): Unit {
        // Arrange
        val fixture = GattSideLinkCoordinatorFixture(hasActiveL2capLink = false)
        val peer = discoveredPeer(platformFamily = BleDiscoveryPlatformFamily.IOS)
        val client = FakeAndroidGattSideLinkClient(ready = true)
        fixture.enqueueClient(client)
        fixture.coordinator.ensureStarted(
            peer = peer,
            localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
        )

        // Act
        fixture.coordinator.handleDisconnected(peer.hintPeerId)

        // Assert
        assertEquals(listOf(peer.hintPeerId.value to false), fixture.presenceUpdates)
        assertEquals(listOf(peer.hintPeerId.value), fixture.lostPeerIds)
        assertEquals(1, client.closeCalls)
    }

    @Test
    fun handleDisconnectedKeepsThePeerPresentWhenAnL2capLinkIsStillActive(): Unit {
        // Arrange
        val fixture = GattSideLinkCoordinatorFixture(hasActiveL2capLink = true)
        val peer = discoveredPeer(platformFamily = BleDiscoveryPlatformFamily.IOS)
        val client = FakeAndroidGattSideLinkClient(ready = true)
        fixture.enqueueClient(client)
        fixture.coordinator.ensureStarted(
            peer = peer,
            localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
        )

        // Act
        fixture.coordinator.handleDisconnected(peer.hintPeerId)

        // Assert
        assertEquals(emptyList(), fixture.presenceUpdates)
        assertEquals(emptyList(), fixture.lostPeerIds)
        assertEquals(0, client.closeCalls)
    }
}

private class GattSideLinkCoordinatorFixture(hasActiveL2capLink: Boolean = false) {
    private val queuedClients: ArrayDeque<FakeAndroidGattSideLinkClient> = ArrayDeque()

    var createClientCalls: Int = 0
    val presenceUpdates: MutableList<Pair<String, Boolean>> = mutableListOf()
    val lostPeerIds: MutableList<String> = mutableListOf()

    val coordinator =
        GattSideLinkCoordinator(
            dependencies =
                GattSideLinkCoordinatorDependencies(
                    deviceForPeer = { Any() },
                    hasActiveL2capLink = { hasActiveL2capLink },
                    setPresenceAnnounced = { hintPeerIdValue, announced ->
                        presenceUpdates += hintPeerIdValue to announced
                    },
                    onFrameReceived = { _, _ -> true },
                    onPeerLost = { peerId -> lostPeerIds += peerId.value },
                    createClient = { _, _, _, _ ->
                        createClientCalls += 1
                        queuedClients.removeFirst()
                    },
                    log = {},
                )
        )

    fun enqueueClient(client: FakeAndroidGattSideLinkClient): Unit {
        queuedClients += client
    }
}

private class FakeAndroidGattSideLinkClient(private var ready: Boolean) : GattSideLinkClient {
    var startCalls: Int = 0
    var closeCalls: Int = 0
    var writeCalls: Int = 0

    override fun start(): Unit {
        startCalls += 1
    }

    override fun isReady(): Boolean {
        return ready
    }

    override suspend fun write(payload: ByteArray): Boolean {
        writeCalls += 1
        return true
    }

    override fun close(): Unit {
        closeCalls += 1
    }
}

private fun discoveredPeer(platformFamily: BleDiscoveryPlatformFamily): DiscoveredPeer {
    return DiscoveredPeer(
        hintPeerId = PeerId("peer-hint-id"),
        state =
            DiscoveredPeerState(
                keyHash = ByteArray(12) { index -> (index + 1).toByte() },
                deviceAddress = "AA:BB:CC:DD:EE:FF",
                l2capPsm = 192,
                transportMode = TransportMode.L2CAP,
                platformFamily = platformFamily,
            ),
    )
}
