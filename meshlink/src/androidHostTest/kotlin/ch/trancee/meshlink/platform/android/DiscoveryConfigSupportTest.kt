package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.transport.BleDiscoveryContract
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun buildAdvertisePlanDefaultsToTheUuidPairOnly(): Unit {
        // Arrange
        val payload =
            buildDiscoveryPayload(
                appId = "demo.meshlink.android.transport",
                localKeyHash = ByteArray(12) { index -> (index + 1).toByte() },
                currentPowerProfile = PowerMonitor.defaultProfile(),
                l2capPsm = 192u,
            )

        // Act
        val advertisePlan = buildAdvertisePlan(payload)

        // Assert
        assertEquals(
            listOf(
                BleDiscoveryContract.ADVERTISEMENT_SERVICE_UUID_EXPANDED,
                payload.payloadUuidString(),
            ),
            advertisePlan.serviceUuids,
        )
        assertTrue(advertisePlan.serviceData.isEmpty())
    }

    @Test
    fun buildAdvertisePlanCanCarryThePayloadAsServiceDataForIsolatedFieldTests(): Unit {
        // Arrange
        val payload =
            buildDiscoveryPayload(
                appId = "demo.meshlink.android.transport",
                localKeyHash = ByteArray(12) { index -> (index + 1).toByte() },
                currentPowerProfile = PowerMonitor.defaultProfile(),
                l2capPsm = 192u,
            )

        // Act
        val advertisePlan =
            buildAdvertisePlan(
                payload = payload,
                carrier = DiscoveryAdvertisementCarrier.UUID_PAIR_PLUS_SERVICE_DATA,
            )

        // Assert
        assertContentEquals(
            payload.encode(),
            advertisePlan.serviceData.getValue(payload.payloadUuidString()),
        )
    }
}
