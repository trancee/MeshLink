package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.identity.toHexString
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IosPeerRegistryTest {
    @Test
    fun upsertDiscoveryAnnouncesNewPeerAndBindsIdentifier(): Unit {
        // Arrange
        val bindings = IosPeerBindings()
        val registry = IosPeerRegistry(bindings)
        val hintPeerId = PeerId("peer-123")
        val identifier = "peripheral-123"
        val keyHash = byteArrayOf(0x01, 0x23, 0x45)

        // Act
        val update =
            registry.upsertDiscovery(
                hintPeerId = hintPeerId,
                discovery =
                    DiscoveredPeerDiscovery(
                        identifier = identifier,
                        keyHash = keyHash,
                        l2capPsm = 192,
                        transportMode = TransportMode.L2CAP,
                        platformFamily = BleDiscoveryPlatformFamily.IOS,
                    ),
            )

        // Assert
        assertSame(update.peer, registry.peer(hintPeerId.value))
        assertEquals(identifier, update.peer.peripheralIdentifier)
        assertEquals(hintPeerId.value, bindings.hintForIdentifier(identifier))
        assertTrue(update.peer.presenceAnnounced, "Newly discovered peers should be announced")
        assertEquals(1, update.events.size)
        val event = update.events.single()
        assertTrue(event is TransportEvent.PeerDiscovered)
        assertEquals(hintPeerId.value, event.peerId.value)
        assertEquals(TransportMode.L2CAP, event.transportMode)
    }

    @Test
    fun upsertDiscoveryEmitsTransportModeChangedForExistingPeer(): Unit {
        // Arrange
        val bindings = IosPeerBindings()
        val registry = IosPeerRegistry(bindings)
        val hintPeerId = PeerId("peer-456")
        registry.upsertDiscovery(
            hintPeerId = hintPeerId,
            discovery =
                DiscoveredPeerDiscovery(
                    identifier = "peripheral-456",
                    keyHash = byteArrayOf(0x45, 0x67),
                    l2capPsm = 192,
                    transportMode = TransportMode.L2CAP,
                    platformFamily = BleDiscoveryPlatformFamily.IOS,
                ),
        )

        // Act
        val update =
            registry.upsertDiscovery(
                hintPeerId = hintPeerId,
                discovery =
                    DiscoveredPeerDiscovery(
                        identifier = "peripheral-456",
                        keyHash = byteArrayOf(0x45, 0x67),
                        l2capPsm = 0,
                        transportMode = TransportMode.GATT,
                        platformFamily = BleDiscoveryPlatformFamily.ANDROID,
                    ),
            )

        // Assert
        assertEquals(TransportMode.GATT, update.peer.transportMode)
        assertEquals(BleDiscoveryPlatformFamily.ANDROID, update.peer.platformFamily)
        assertEquals(1, update.events.size)
        val event = update.events.single()
        assertTrue(event is TransportEvent.TransportModeChanged)
        assertEquals(hintPeerId.value, event.peerId.value)
        assertEquals(TransportMode.GATT, event.transportMode)
    }

    @Test
    fun upsertDiscoveryReannouncesPeerAfterPresenceWasCleared(): Unit {
        // Arrange
        val bindings = IosPeerBindings()
        val registry = IosPeerRegistry(bindings)
        val hintPeerId = PeerId("peer-789")
        registry.upsertDiscovery(
            hintPeerId = hintPeerId,
            discovery =
                DiscoveredPeerDiscovery(
                    identifier = "peripheral-789",
                    keyHash = byteArrayOf(0x78, 0x09),
                    l2capPsm = 200,
                    transportMode = TransportMode.L2CAP,
                    platformFamily = BleDiscoveryPlatformFamily.IOS,
                ),
        )
        registry.setPresenceAnnounced(hintPeerId.value, announced = false)

        // Act
        val update =
            registry.upsertDiscovery(
                hintPeerId = hintPeerId,
                discovery =
                    DiscoveredPeerDiscovery(
                        identifier = "peripheral-789",
                        keyHash = byteArrayOf(0x78, 0x09),
                        l2capPsm = 200,
                        transportMode = TransportMode.L2CAP,
                        platformFamily = BleDiscoveryPlatformFamily.IOS,
                    ),
            )

        // Assert
        assertTrue(update.peer.presenceAnnounced, "Rediscovery should mark the peer present again")
        assertEquals(1, update.events.size)
        val event = update.events.single()
        assertTrue(event is TransportEvent.PeerDiscovered)
        assertEquals(hintPeerId.value, event.peerId.value)
        assertEquals(TransportMode.L2CAP, event.transportMode)
    }

    @Test
    fun resolveFallsBackToKeyHashPrefixMatch(): Unit {
        // Arrange
        val bindings = IosPeerBindings()
        val registry = IosPeerRegistry(bindings)
        val keyHash = byteArrayOf(0x0A, 0x0B, 0x0C)
        registry.upsertDiscovery(
            hintPeerId = PeerId("canonical-peer"),
            discovery =
                DiscoveredPeerDiscovery(
                    identifier = "peripheral-prefix",
                    keyHash = keyHash,
                    l2capPsm = 220,
                    transportMode = TransportMode.L2CAP,
                    platformFamily = BleDiscoveryPlatformFamily.IOS,
                ),
        )
        val prefixPeerId = PeerId(keyHash.toHexString())

        // Act
        val resolvedPeer = registry.resolve(prefixPeerId)

        // Assert
        assertNotNull(resolvedPeer)
        assertEquals("canonical-peer", resolvedPeer.hintPeerId.value)
    }

    @Test
    fun temporaryPeerIdIsStablePerIdentifier(): Unit {
        // Arrange
        val bindings = IosPeerBindings()
        val identifier = "123e4567-e89b-12d3-a456-426614174000"

        // Act
        val firstPeerId = bindings.temporaryPeerId(identifier)
        val secondPeerId = bindings.temporaryPeerId(identifier)

        // Assert
        assertEquals(firstPeerId.value, secondPeerId.value)
        assertTrue(
            firstPeerId.value.startsWith(TEMPORARY_PEER_PREFIX),
            "Temporary peer ids should use the expected prefix",
        )
    }
}
