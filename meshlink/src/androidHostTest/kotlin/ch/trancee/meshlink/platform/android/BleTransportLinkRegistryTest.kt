package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import ch.trancee.meshlink.transport.TransportMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.Job

class BleTransportLinkRegistryTest {
    @Test
    fun reservePendingConnectRejectsHintsThatAlreadyHaveAnActiveLink(): Unit {
        // Arrange
        val bindings = PeerBindings()
        val registry = BleTransportLinkRegistry<FakeActiveLink>(bindings)
        registry.registerActiveLink("peer-active", FakeActiveLink(PeerId("peer-active")))

        // Act
        val reserved =
            registry.reservePendingConnect(hintPeerIdValue = "peer-active", connectJob = Job())

        // Assert
        assertFalse(reserved)
        assertFalse(registry.hasPendingConnect("peer-active"))
    }

    @Test
    fun promoteTemporaryLinkMovesTheLinkAndRebindsTheAddress(): Unit {
        // Arrange
        val address = "AA:BB:CC:DD:EE:FF"
        val bindings = PeerBindings()
        val registry = BleTransportLinkRegistry<FakeActiveLink>(bindings)
        val temporaryHintPeerId = bindings.temporaryPeerId(address)
        val temporaryLink = FakeActiveLink(temporaryHintPeerId)
        registry.registerActiveLink(temporaryHintPeerId.value, temporaryLink)

        // Act
        val outcome =
            registry.promoteTemporaryLink(
                address = address,
                resolvedHintPeerIdValue = "peer-canonical",
                temporaryPeerPrefix = TEMPORARY_PEER_PREFIX,
                updateHint = { link, promotedHintPeerId -> link.hintPeerId = promotedHintPeerId },
                closeLink = {},
            )

        // Assert
        assertEquals(TemporaryLinkPromotionOutcome.PROMOTED, outcome)
        assertEquals("peer-canonical", temporaryLink.hintPeerId.value)
        assertSame(temporaryLink, registry.activeLink("peer-canonical"))
        assertNull(registry.activeLink(temporaryHintPeerId.value))
        assertEquals("peer-canonical", bindings.hintForAddress(address))
        assertNull(bindings.temporaryHintForAddress(address))
    }

    @Test
    fun promoteTemporaryLinkClosesTheTemporaryLinkWhenTheCanonicalLinkAlreadyExists(): Unit {
        // Arrange
        val address = "AA:BB:CC:DD:EE:11"
        val bindings = PeerBindings()
        val registry = BleTransportLinkRegistry<FakeActiveLink>(bindings)
        val temporaryHintPeerId = bindings.temporaryPeerId(address)
        val temporaryLink = FakeActiveLink(temporaryHintPeerId)
        val canonicalLink = FakeActiveLink(PeerId("peer-canonical"))
        val closedLinks = mutableListOf<FakeActiveLink>()
        registry.registerActiveLink(temporaryHintPeerId.value, temporaryLink)
        registry.registerActiveLink("peer-canonical", canonicalLink)

        // Act
        val outcome =
            registry.promoteTemporaryLink(
                address = address,
                resolvedHintPeerIdValue = "peer-canonical",
                temporaryPeerPrefix = TEMPORARY_PEER_PREFIX,
                updateHint = { link, promotedHintPeerId -> link.hintPeerId = promotedHintPeerId },
                closeLink = { link -> closedLinks += link },
            )

        // Assert
        assertEquals(TemporaryLinkPromotionOutcome.CLOSED_DUPLICATE, outcome)
        assertEquals(listOf(temporaryLink), closedLinks)
        assertSame(canonicalLink, registry.activeLink("peer-canonical"))
        assertNull(registry.activeLink(temporaryHintPeerId.value))
        assertEquals("peer-canonical", bindings.hintForAddress(address))
    }

    @Test
    fun resolveActiveLinkUsesTheTemporaryHintBoundToThePeerAddress(): Unit {
        // Arrange
        val address = "AA:BB:CC:DD:EE:22"
        val bindings = PeerBindings()
        val registry = BleTransportLinkRegistry<FakeActiveLink>(bindings)
        val temporaryHintPeerId = bindings.temporaryPeerId(address)
        val temporaryLink = FakeActiveLink(temporaryHintPeerId)
        registry.registerActiveLink(temporaryHintPeerId.value, temporaryLink)
        val peer =
            DiscoveredPeer(
                hintPeerId = PeerId("peer-canonical"),
                state =
                    DiscoveredPeerState(
                        keyHash = ByteArray(12) { index -> (index + 1).toByte() },
                        deviceAddress = address,
                        l2capPsm = 192,
                        transportMode = TransportMode.L2CAP,
                        platformFamily = BleDiscoveryPlatformFamily.IOS,
                    ),
            )

        // Act
        val resolvedLink = registry.resolveActiveLink(peer)

        // Assert
        assertSame(temporaryLink, resolvedLink)
    }
}

private data class FakeActiveLink(var hintPeerId: PeerId)
