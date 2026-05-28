package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.transport.BleDiscoveryContract
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DiscoveryConfigSupportTest {
    @Test
    fun buildDiscoveryPayloadUsesTheLocalMeshIdentityAndAndroidPlatformFamily(): Unit {
        // Arrange
        val appId = "demo.meshlink.android.transport"
        val advertisementKeyHash = ByteArray(12) { index -> (index + 1).toByte() }
        val currentPowerProfile = PowerMonitor.defaultProfile()

        // Act
        val payload =
            buildDiscoveryPayload(
                appId = appId,
                localKeyHash = advertisementKeyHash,
                currentPowerProfile = currentPowerProfile,
                l2capPsm = 192u,
            )

        // Assert
        assertEquals(BleDiscoveryContract.CURRENT_PROTOCOL_VERSION, payload.protocolVersion)
        assertEquals(BleDiscoveryContract.computeMeshHash(appId), payload.meshHash)
        assertEquals(192u, payload.l2capPsm)
        assertEquals(BleDiscoveryPlatformFamily.ANDROID, payload.platformFamily)
        assertEquals(currentPowerProfile.discoveryPowerMode, payload.powerMode)
        assertContentEquals(advertisementKeyHash, payload.keyHash)
    }
}
