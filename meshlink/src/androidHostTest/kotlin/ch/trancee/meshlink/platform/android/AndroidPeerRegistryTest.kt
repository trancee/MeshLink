package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.identity.toHexString
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AndroidPeerRegistryTest {
    @Test
    fun upsertDiscoveryCreatesPeerAndEmitsPeerDiscovered(): Unit {
        // Arrange
        val bindings = AndroidPeerBindings()
        val registry = AndroidPeerRegistry(bindings = bindings)
        val keyHash = keyHash(seed = 1)
        val hintPeerId = PeerId(keyHash.toHexString())
        val discovery =
            DiscoveredPeerDiscovery(
                address = DEVICE_ADDRESS_A,
                keyHash = keyHash,
                l2capPsm = 0,
                transportMode = TransportMode.GATT,
                platformFamily = BleDiscoveryPlatformFamily.ANDROID,
            )

        // Act
        val update = registry.upsertDiscovery(hintPeerId = hintPeerId, discovery = discovery)

        // Assert
        assertEquals(hintPeerId, update.peer.hintPeerId)
        assertEquals(DEVICE_ADDRESS_A, update.peer.deviceAddress)
        assertEquals(1, update.events.size)
        val event = update.events.single() as TransportEvent.PeerDiscovered
        assertEquals(hintPeerId, event.peerId)
        assertEquals(TransportMode.GATT, event.transportMode)
        assertEquals(hintPeerId.value, bindings.hintForAddress(DEVICE_ADDRESS_A))
    }

    @Test
    fun upsertDiscoveryEmitsTransportModeChangedWhenTransportChanges(): Unit {
        // Arrange
        val bindings = AndroidPeerBindings()
        val registry = AndroidPeerRegistry(bindings = bindings)
        val keyHash = keyHash(seed = 2)
        val hintPeerId = PeerId(keyHash.toHexString())
        registry.upsertDiscovery(
            hintPeerId = hintPeerId,
            discovery =
                DiscoveredPeerDiscovery(
                    address = DEVICE_ADDRESS_A,
                    keyHash = keyHash,
                    l2capPsm = 0,
                    transportMode = TransportMode.GATT,
                    platformFamily = BleDiscoveryPlatformFamily.ANDROID,
                ),
        )

        // Act
        val update =
            registry.upsertDiscovery(
                hintPeerId = hintPeerId,
                discovery =
                    DiscoveredPeerDiscovery(
                        address = DEVICE_ADDRESS_A,
                        keyHash = keyHash,
                        l2capPsm = 163,
                        transportMode = TransportMode.L2CAP,
                        platformFamily = BleDiscoveryPlatformFamily.ANDROID,
                    ),
            )

        // Assert
        assertEquals(1, update.events.size)
        val event = update.events.single() as TransportEvent.TransportModeChanged
        assertEquals(hintPeerId, event.peerId)
        assertEquals(TransportMode.L2CAP, event.transportMode)
        assertEquals(163, update.peer.l2capPsm)
        assertEquals(TransportMode.L2CAP, update.peer.transportMode)
    }

    @Test
    fun upsertDiscoveryReannouncesPeerWhenPresenceWasCleared(): Unit {
        // Arrange
        val bindings = AndroidPeerBindings()
        val registry = AndroidPeerRegistry(bindings = bindings)
        val keyHash = keyHash(seed = 3)
        val hintPeerId = PeerId(keyHash.toHexString())
        val discovery =
            DiscoveredPeerDiscovery(
                address = DEVICE_ADDRESS_A,
                keyHash = keyHash,
                l2capPsm = 0,
                transportMode = TransportMode.GATT,
                platformFamily = BleDiscoveryPlatformFamily.ANDROID,
            )
        registry.upsertDiscovery(hintPeerId = hintPeerId, discovery = discovery)
        registry.setPresenceAnnounced(hintPeerId.value, announced = false)

        // Act
        val update = registry.upsertDiscovery(hintPeerId = hintPeerId, discovery = discovery)

        // Assert
        assertEquals(1, update.events.size)
        val event = update.events.single() as TransportEvent.PeerDiscovered
        assertEquals(hintPeerId, event.peerId)
        assertEquals(TransportMode.GATT, event.transportMode)
        assertEquals(true, update.peer.presenceAnnounced)
    }

    @Test
    fun resolveFindsPeerWhenCanonicalPeerIdStartsWithTheKeyHash(): Unit {
        // Arrange
        val bindings = AndroidPeerBindings()
        val registry = AndroidPeerRegistry(bindings = bindings)
        val keyHash = keyHash(seed = 4)
        val hintPeerId = PeerId(keyHash.toHexString())
        registry.upsertDiscovery(
            hintPeerId = hintPeerId,
            discovery =
                DiscoveredPeerDiscovery(
                    address = DEVICE_ADDRESS_B,
                    keyHash = keyHash,
                    l2capPsm = 177,
                    transportMode = TransportMode.L2CAP,
                    platformFamily = BleDiscoveryPlatformFamily.IOS,
                ),
        )
        val canonicalPeerId = PeerId(hintPeerId.value + "ff")

        // Act
        val resolvedPeer = registry.resolve(canonicalPeerId)

        // Assert
        assertNotNull(resolvedPeer)
        assertEquals(hintPeerId, resolvedPeer.hintPeerId)
        assertEquals(DEVICE_ADDRESS_B, resolvedPeer.deviceAddress)
    }

    @Test
    fun temporaryPeerIdIsStablePerAddress(): Unit {
        // Arrange
        val bindings = AndroidPeerBindings()

        // Act
        val firstPeerId = bindings.temporaryPeerId(DEVICE_ADDRESS_A)
        val secondPeerId = bindings.temporaryPeerId(DEVICE_ADDRESS_A)

        // Assert
        assertEquals(firstPeerId.value, secondPeerId.value)
        assertEquals("bt-aabbccddeeff", firstPeerId.value)
        assertEquals(firstPeerId.value, bindings.temporaryHintForAddress(DEVICE_ADDRESS_A))
    }
}

private fun keyHash(seed: Int): ByteArray {
    return ByteArray(size = 32) { index -> (seed + index).toByte() }
}

private const val DEVICE_ADDRESS_A: String = "AA:BB:CC:DD:EE:FF"
private const val DEVICE_ADDRESS_B: String = "11:22:33:44:55:66"
