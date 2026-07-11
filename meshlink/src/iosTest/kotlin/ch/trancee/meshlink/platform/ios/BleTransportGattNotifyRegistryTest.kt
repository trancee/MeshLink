package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.api.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Mirrors Android's `GattSideLinkCoordinatorTest`'s `activeHintIds()` coverage: iOS's GATT notify
 * side-link registry plays the same admission-control role
 * (`ch.trancee.meshlink.power.hasConnectionBudget`) as Android's `GattSideLinkCoordinator` -- both
 * expose the set of peer hints currently occupying a connection slot so the discovery admission
 * gate can compute an active connection count.
 */
class BleTransportGattNotifyRegistryTest {
    @Test
    fun activeHintIdsIsEmptyBeforeAnyLinkIsRegistered(): Unit {
        // Arrange
        val registry = BleTransportGattNotifyRegistry()

        // Act / Assert
        assertEquals(emptySet(), registry.activeHintIds())
    }

    @Test
    fun activeHintIdsIncludesAPeerAsSoonAsItsLinkIsRegistered(): Unit {
        // Arrange
        val registry = BleTransportGattNotifyRegistry()
        val hintPeerId = PeerId("peer-1")

        // Act
        registry.replaceLink(hintPeerId.value, fakeGattNotifyLink(hintPeerId))

        // Assert
        assertEquals(setOf(hintPeerId.value), registry.activeHintIds())
    }

    @Test
    fun activeHintIdsDropsAPeerOnceItsLinkIsRemoved(): Unit {
        // Arrange
        val registry = BleTransportGattNotifyRegistry()
        val hintPeerId = PeerId("peer-1")
        registry.replaceLink(hintPeerId.value, fakeGattNotifyLink(hintPeerId))

        // Act
        registry.removeLink(hintPeerId.value)

        // Assert
        assertEquals(emptySet(), registry.activeHintIds())
    }

    @Test
    fun activeHintIdsIncludesEveryConcurrentlyRegisteredPeer(): Unit {
        // Arrange
        val registry = BleTransportGattNotifyRegistry()
        val firstHintPeerId = PeerId("peer-1")
        val secondHintPeerId = PeerId("peer-2")

        // Act
        registry.replaceLink(firstHintPeerId.value, fakeGattNotifyLink(firstHintPeerId))
        registry.replaceLink(secondHintPeerId.value, fakeGattNotifyLink(secondHintPeerId))

        // Assert
        assertEquals(setOf(firstHintPeerId.value, secondHintPeerId.value), registry.activeHintIds())
    }

    private fun fakeGattNotifyLink(hintPeerId: PeerId): GattNotifyLink {
        return GattNotifyLink(
            peer =
                GattNotifyPeer(
                    hintPeerId = hintPeerId,
                    centralIdentifier = "central-${hintPeerId.value}",
                    maximumUpdateValueLength = 185,
                ),
            dependencies =
                GattNotifyDependencies(
                    peripheralAdapterProvider = { null },
                    runPump = { false },
                    logger = {},
                    schedulePumpRetry = {},
                ),
        )
    }
}
