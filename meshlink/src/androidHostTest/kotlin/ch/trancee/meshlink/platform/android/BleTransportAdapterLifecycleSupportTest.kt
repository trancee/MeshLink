package ch.trancee.meshlink.platform.android

import kotlin.test.Test
import kotlin.test.assertEquals

class BleTransportAdapterLifecycleSupportTest {
    @Test
    fun advertisedDiscoveryL2capPsmZeroesValueWhenClientSocketsUnsupported(): Unit {
        // Arrange / Act
        val actual =
            advertisedDiscoveryL2capPsm(
                serverSocketPsm = 167,
                localL2capClientSocketsSupported = false,
            )

        // Assert
        assertEquals(0u, actual)
    }

    @Test
    fun advertisedDiscoveryL2capPsmPreservesValueWhenClientSocketsSupported(): Unit {
        // Arrange / Act
        val actual =
            advertisedDiscoveryL2capPsm(
                serverSocketPsm = 167,
                localL2capClientSocketsSupported = true,
            )

        // Assert
        assertEquals(167u, actual)
    }
}
