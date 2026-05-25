package ch.trancee.meshlink.platform.android

import ch.trancee.meshlink.identity.toHexString
import ch.trancee.meshlink.transport.BleDiscoveryContract
import ch.trancee.meshlink.transport.BleDiscoveryPayload
import ch.trancee.meshlink.transport.BleDiscoveryPlatformFamily
import ch.trancee.meshlink.transport.BlePowerMode
import ch.trancee.meshlink.transport.TransportMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidScanResultSupportTest {
    @Test
    fun parseAndroidDiscoveryScanResultOrNullReturnsNullWhenNoPayloadUuidExists(): Unit {
        // Arrange
        val logMessages = mutableListOf<String>()
        val serviceUuids = listOf(BleDiscoveryContract.ADVERTISEMENT_SERVICE_UUID_EXPANDED)

        // Act
        val discovery =
            parseAndroidDiscoveryScanResultOrNull(
                serviceUuids = serviceUuids,
                deviceAddress = DEVICE_ADDRESS,
                localMeshHash = BleDiscoveryContract.computeMeshHash(APP_ID),
                localKeyHash = byteArrayOf(1, 2, 3),
                log = logMessages::add,
            )

        // Assert
        assertNull(discovery)
        assertEquals(emptyList(), logMessages)
    }

    @Test
    fun parseAndroidDiscoveryScanResultOrNullLogsAndRejectsUnsupportedProtocolVersions(): Unit {
        // Arrange
        val logMessages = mutableListOf<String>()
        val payload =
            BleDiscoveryPayload(
                protocolVersion = 0,
                powerMode = BlePowerMode.BALANCED,
                meshHash = BleDiscoveryContract.computeMeshHash(APP_ID),
                l2capPsm = 192u,
                keyHash = keyHash(seed = 4),
                platformFamily = BleDiscoveryPlatformFamily.IOS,
            )
        val serviceUuids =
            listOf(
                BleDiscoveryContract.ADVERTISEMENT_SERVICE_UUID_EXPANDED,
                payload.payloadUuidString(),
            )

        // Act
        val discovery =
            parseAndroidDiscoveryScanResultOrNull(
                serviceUuids = serviceUuids,
                deviceAddress = DEVICE_ADDRESS,
                localMeshHash = BleDiscoveryContract.computeMeshHash(APP_ID),
                localKeyHash = keyHash(seed = 9),
                log = logMessages::add,
            )

        // Assert
        assertNull(discovery)
        assertEquals(
            listOf(
                "ignoring discovery payload with unsupported protocolVersion=0 addr=$DEVICE_ADDRESS"
            ),
            logMessages,
        )
    }

    @Test
    fun parseAndroidDiscoveryScanResultOrNullReturnsL2capDiscoveryForValidPayloads(): Unit {
        // Arrange
        val keyHash = keyHash(seed = 7)
        val payload =
            BleDiscoveryPayload(
                protocolVersion = BleDiscoveryContract.CURRENT_PROTOCOL_VERSION,
                powerMode = BlePowerMode.BALANCED,
                meshHash = BleDiscoveryContract.computeMeshHash(APP_ID),
                l2capPsm = 193u,
                keyHash = keyHash,
                platformFamily = BleDiscoveryPlatformFamily.IOS,
            )
        val serviceUuids =
            listOf(
                BleDiscoveryContract.ADVERTISEMENT_SERVICE_UUID_EXPANDED,
                payload.payloadUuidString(),
            )

        // Act
        val discovery =
            parseAndroidDiscoveryScanResultOrNull(
                serviceUuids = serviceUuids,
                deviceAddress = DEVICE_ADDRESS,
                localMeshHash = BleDiscoveryContract.computeMeshHash(APP_ID),
                localKeyHash = keyHash(seed = 1),
                log = {},
            )

        // Assert
        requireNotNull(discovery)
        assertEquals(payload.payloadUuidString(), discovery.payload.payloadUuidString())
        assertEquals(keyHash.toHexString(), discovery.hintPeerId.value)
        assertEquals(TransportMode.L2CAP, discovery.transportMode)
    }

    @Test
    fun shouldConnectAfterAndroidDiscoveryOnlyReturnsTrueForEligibleL2capPeers(): Unit {
        // Arrange / Act
        val gattOnlyResult =
            shouldConnectAfterAndroidDiscovery(
                transportMode = TransportMode.GATT,
                localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                remotePlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                shouldInitiateL2cap = true,
                gattSideLinkReady = false,
            )
        val waitingForInboundResult =
            shouldConnectAfterAndroidDiscovery(
                transportMode = TransportMode.L2CAP,
                localPlatformFamily = BleDiscoveryPlatformFamily.IOS,
                remotePlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                shouldInitiateL2cap = false,
                gattSideLinkReady = false,
            )
        val mixedPlatformReadyResult =
            shouldConnectAfterAndroidDiscovery(
                transportMode = TransportMode.L2CAP,
                localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                remotePlatformFamily = BleDiscoveryPlatformFamily.IOS,
                shouldInitiateL2cap = true,
                gattSideLinkReady = true,
            )
        val eligibleResult =
            shouldConnectAfterAndroidDiscovery(
                transportMode = TransportMode.L2CAP,
                localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                remotePlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                shouldInitiateL2cap = true,
                gattSideLinkReady = false,
            )

        // Assert
        assertEquals(false, gattOnlyResult)
        assertEquals(false, waitingForInboundResult)
        assertEquals(false, mixedPlatformReadyResult)
        assertEquals(true, eligibleResult)
    }
}

private fun keyHash(seed: Int): ByteArray {
    return ByteArray(size = BleDiscoveryPayload.KEY_HASH_SIZE_BYTES) { index ->
        (seed + index).toByte()
    }
}

private const val APP_ID: String = "demo.meshlink.android.transport"
private const val DEVICE_ADDRESS: String = "AA:BB:CC:DD:EE:FF"
