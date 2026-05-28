package ch.trancee.meshlink.platform.ios

import ch.trancee.meshlink.transport.BleDiscoveryContract
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class BleTransportDiscoveryPayloadTest {
    @Test
    fun discoveryPayloadUsesTheLocalMeshIdentityAndIosPlatformFamily(): Unit {
        // Arrange
        val advertisementKeyHash = ByteArray(12) { index -> (index + 1).toByte() }
        val transport =
            BleTransportAdapter(
                appId = "demo.meshlink.ios.transport",
                advertisementKeyHash = advertisementKeyHash,
            )

        // Act
        val payload = transport.discoveryPayload(l2capPsm = 192u)

        // Assert
        assertEquals(BleDiscoveryContract.CURRENT_PROTOCOL_VERSION, payload.protocolVersion)
        assertEquals(
            BleDiscoveryContract.computeMeshHash("demo.meshlink.ios.transport"),
            payload.meshHash,
        )
        assertEquals(192u, payload.l2capPsm)
        assertEquals(BleDiscoveryPlatformFamily.IOS, payload.platformFamily)
        assertContentEquals(advertisementKeyHash, payload.keyHash)
    }

    @Test
    fun advertisedPsmPreservesValuesInsideTheAdvertisedRange(): Unit {
        // Arrange
        val transport = testTransport()

        // Act
        val lowerBound = transport.advertisedPsm(128u)
        val upperBound = transport.advertisedPsm(255u)

        // Assert
        assertEquals(128u, lowerBound)
        assertEquals(255u, upperBound)
    }

    @Test
    fun advertisedPsmDropsValuesOutsideTheAdvertisedRange(): Unit {
        // Arrange
        val transport = testTransport()

        // Act
        val belowRange = transport.advertisedPsm(127u)
        val aboveRange = transport.advertisedPsm(256u)

        // Assert
        assertEquals(0u, belowRange)
        assertEquals(0u, aboveRange)
    }
}

private fun testTransport(): BleTransportAdapter {
    return BleTransportAdapter(
        appId = "demo.meshlink.ios.transport",
        advertisementKeyHash = ByteArray(12) { index -> (index + 101).toByte() },
    )
}
