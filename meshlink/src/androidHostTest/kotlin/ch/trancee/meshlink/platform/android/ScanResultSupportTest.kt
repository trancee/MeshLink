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

class ScanResultSupportTest {
    @Test
    fun parseDiscoveryScanResultOrNullReturnsNullWhenNoPayloadUuidExists(): Unit {
        // Arrange
        val logMessages = mutableListOf<String>()
        val serviceUuids = listOf(BleDiscoveryContract.ADVERTISEMENT_SERVICE_UUID_EXPANDED)

        // Act
        val discovery =
            parseDiscoveryScanResultOrNull(
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
    fun parseDiscoveryScanResultOrNullLogsAndRejectsUnsupportedProtocolVersions(): Unit {
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
            parseDiscoveryScanResultOrNull(
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
    fun parseDiscoveryScanResultOrNullReturnsL2capDiscoveryForValidPayloads(): Unit {
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
            parseDiscoveryScanResultOrNull(
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
    fun shouldConnectAfterDiscoveryOnlyReturnsTrueForEligibleL2capPeers(): Unit {
        // Arrange / Act
        val gattOnlyResult =
            shouldConnectAfterDiscovery(
                transportMode = TransportMode.GATT,
                localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                remotePlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                localL2capClientSocketsSupported = true,
                shouldInitiateL2cap = true,
                gattSideLinkReady = false,
            )
        val waitingForInboundResult =
            shouldConnectAfterDiscovery(
                transportMode = TransportMode.L2CAP,
                localPlatformFamily = BleDiscoveryPlatformFamily.IOS,
                remotePlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                localL2capClientSocketsSupported = true,
                shouldInitiateL2cap = false,
                gattSideLinkReady = false,
            )
        val mixedPlatformReadyResult =
            shouldConnectAfterDiscovery(
                transportMode = TransportMode.L2CAP,
                localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                remotePlatformFamily = BleDiscoveryPlatformFamily.IOS,
                localL2capClientSocketsSupported = true,
                shouldInitiateL2cap = true,
                gattSideLinkReady = true,
            )
        val unsupportedSdkResult =
            shouldConnectAfterDiscovery(
                transportMode = TransportMode.L2CAP,
                localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                remotePlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                localL2capClientSocketsSupported = false,
                shouldInitiateL2cap = true,
                gattSideLinkReady = false,
            )
        val eligibleResult =
            shouldConnectAfterDiscovery(
                transportMode = TransportMode.L2CAP,
                localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                remotePlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                localL2capClientSocketsSupported = true,
                shouldInitiateL2cap = true,
                gattSideLinkReady = false,
            )

        // Assert
        assertEquals(false, gattOnlyResult)
        assertEquals(false, waitingForInboundResult)
        assertEquals(false, mixedPlatformReadyResult)
        assertEquals(false, unsupportedSdkResult)
        assertEquals(true, eligibleResult)
    }

    @Test
    fun supportsL2capClientSocketsUsesSdkCondition(): Unit {
        // Arrange / Act
        val unsupportedResult = supportsL2capClientSockets(sdkInt = 33)
        val supportedResult = supportsL2capClientSockets(sdkInt = 34)

        // Assert
        assertEquals(false, unsupportedResult)
        assertEquals(true, supportedResult)
    }

    @Test
    fun supportsL2capServerSocketsUsesSdkCondition(): Unit {
        // Arrange / Act
        val unsupportedResult = supportsL2capServerSockets(sdkInt = 33)
        val supportedResult = supportsL2capServerSockets(sdkInt = 34)

        // Assert
        assertEquals(false, unsupportedResult)
        assertEquals(true, supportedResult)
    }
}

private fun keyHash(seed: Int): ByteArray {
    return ByteArray(size = BleDiscoveryPayload.KEY_HASH_SIZE_BYTES) { index ->
        (seed + index).toByte()
    }
}

private const val APP_ID: String = "demo.meshlink.android.transport"
private const val DEVICE_ADDRESS: String = "AA:BB:CC:DD:EE:FF"
