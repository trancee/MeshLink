package ch.trancee.meshlink.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BleDiscoveryContractTest {
    @Test
    fun `payload encodes to the expected UUID bytes`() {
        // Arrange
        val payload =
            BleDiscoveryPayload(
                protocolVersion = 5,
                powerMode = BlePowerMode.BALANCED,
                meshHash = 0x1234.toUShort(),
                l2capPsm = 0x80.toUByte(),
                keyHash = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
            )
        val expectedBytes =
            byteArrayOf(
                0xA8.toByte(),
                0x34,
                0x12,
                0x80.toByte(),
                0x00,
                0x01,
                0x02,
                0x03,
                0x04,
                0x05,
                0x06,
                0x07,
                0x08,
                0x09,
                0x0A,
                0x0B,
            )
        val expectedUuid = "a8341280-0001-0203-0405-060708090a0b"

        // Act
        val encodedBytes = payload.encode()
        val encodedUuid = payload.payloadUuidString()

        // Assert
        assertContentEquals(expectedBytes, encodedBytes)
        assertEquals(expectedUuid, encodedUuid)
    }

    @Test
    fun `payload UUID round-trips through decoding`() {
        // Arrange
        val payload =
            BleDiscoveryPayload(
                protocolVersion = 1,
                powerMode = BlePowerMode.POWER_SAVER,
                meshHash = 0x1BE1.toUShort(),
                l2capPsm = 0x00.toUByte(),
                keyHash = byteArrayOf(11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
            )

        // Act
        val decoded = BleDiscoveryPayload.fromUuidString(payload.payloadUuidString())

        // Assert
        assertEquals(payload.protocolVersion, decoded.protocolVersion)
        assertEquals(payload.powerMode, decoded.powerMode)
        assertEquals(payload.meshHash, decoded.meshHash)
        assertEquals(payload.l2capPsm, decoded.l2capPsm)
        assertEquals(payload.platformFamily, decoded.platformFamily)
        assertContentEquals(payload.keyHash, decoded.keyHash)
    }

    @Test
    fun `mesh hash is deterministic and substitutes non-zero value`() {
        // Arrange
        val appId = "demo.meshlink"

        // Act
        val meshHash = BleDiscoveryContract.computeMeshHash(appId)

        // Assert
        assertEquals(0x1BE1.toUShort(), meshHash)
        assertTrue(meshHash != 0.toUShort())
    }

    @Test
    fun `discovery UUID set includes the 32-bit advertisement UUID first`() {
        // Arrange
        val payload =
            BleDiscoveryPayload(
                protocolVersion = 0,
                powerMode = BlePowerMode.PERFORMANCE,
                meshHash = 0x0001.toUShort(),
                l2capPsm = 0x00.toUByte(),
                keyHash = ByteArray(12) { 0x11 },
            )

        // Act
        val uuids = BleDiscoveryContract.advertisedServiceUuids(payload)

        // Assert
        assertEquals(BleDiscoveryContract.ADVERTISEMENT_SERVICE_UUID, uuids.first())
        assertEquals(2, uuids.size)
    }

    @Test
    fun `advertised service uuids contain only the fixed discovery uuid and payload uuid`() {
        // Arrange
        val payload =
            BleDiscoveryPayload(
                protocolVersion = 1,
                powerMode = BlePowerMode.BALANCED,
                meshHash = 0x1BE1.toUShort(),
                l2capPsm = 0x80.toUByte(),
                keyHash = byteArrayOf(11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
            )
        val expected =
            listOf(BleDiscoveryContract.ADVERTISEMENT_SERVICE_UUID, payload.payloadUuidString())

        // Act
        val actual = BleDiscoveryContract.advertisedServiceUuids(payload)

        // Assert
        assertEquals(expected, actual)
    }

    @Test
    fun `reserved proof only uuids are not recognized as normative advertisement uuids`() {
        // Arrange
        val reservedServiceUuid = BleDiscoveryContract.GATT_FALLBACK_SERVICE_UUID
        val reservedCharacteristicUuid = BleDiscoveryContract.GATT_CHARACTERISTIC_UUIDS.first()

        // Act
        val serviceRecognized = BleDiscoveryContract.isAdvertisementServiceUuid(reservedServiceUuid)
        val characteristicRecognized =
            BleDiscoveryContract.isAdvertisementServiceUuid(reservedCharacteristicUuid)

        // Assert
        assertTrue(!serviceRecognized)
        assertTrue(!characteristicRecognized)
    }

    @Test
    fun `payload rejects key hashes that are not exactly 12 bytes`() {
        // Arrange / Act
        val error =
            assertFailsWith<IllegalArgumentException> {
                BleDiscoveryPayload(
                    protocolVersion = 1,
                    powerMode = BlePowerMode.BALANCED,
                    meshHash = 0x1234.toUShort(),
                    l2capPsm = 0x80.toUByte(),
                    keyHash = ByteArray(11),
                )
            }

        // Assert
        assertEquals("keyHash must be exactly 12 bytes", error.message)
    }

    @Test
    fun `payload rejects protocol versions outside the 3 bit header range`() {
        // Arrange / Act
        val error =
            assertFailsWith<IllegalArgumentException> {
                BleDiscoveryPayload(
                    protocolVersion = 8,
                    powerMode = BlePowerMode.BALANCED,
                    meshHash = 0x1234.toUShort(),
                    l2capPsm = 0x80.toUByte(),
                    keyHash = ByteArray(12),
                )
            }

        // Assert
        assertEquals("protocolVersion must be in 0..7", error.message)
    }

    @Test
    fun `payload rejects nonzero l2cap psm values below the dynamic ble range`() {
        // Arrange / Act
        val error =
            assertFailsWith<IllegalArgumentException> {
                BleDiscoveryPayload(
                    protocolVersion = 1,
                    powerMode = BlePowerMode.BALANCED,
                    meshHash = 0x1234.toUShort(),
                    l2capPsm = 0x7F.toUByte(),
                    keyHash = ByteArray(12),
                )
            }

        // Assert
        assertEquals("l2capPsm must be 0 or in 128..255", error.message)
    }

    @Test
    fun `uuid decoder rejects malformed UUID strings`() {
        // Arrange / Act
        val error =
            assertFailsWith<IllegalArgumentException> {
                BleDiscoveryContract.bytesFromUuidString("4d455348")
            }

        // Assert
        assertEquals("UUID string must encode exactly 16 bytes", error.message)
    }

    @Test
    fun `platform family bits round trip through encoding`() {
        // Arrange
        val payload =
            BleDiscoveryPayload(
                protocolVersion = 1,
                powerMode = BlePowerMode.BALANCED,
                meshHash = 0x1BE1.toUShort(),
                l2capPsm = 0x80.toUByte(),
                keyHash = ByteArray(12) { index -> index.toByte() },
                platformFamily = BleDiscoveryPlatformFamily.IOS,
            )

        // Act
        val decoded = BleDiscoveryPayload.decode(payload.encode())

        // Assert
        assertEquals(BleDiscoveryPlatformFamily.IOS, decoded.platformFamily)
    }

    @Test
    fun `android deterministically initiates mixed android ios links`() {
        // Arrange
        val localKeyHash = byteArrayOf(0x20, 0x00)
        val remoteKeyHash = byteArrayOf(0x10, 0x00)

        // Act
        val androidInitiates =
            shouldLocalPeerInitiateL2capConnection(
                localKeyHash = localKeyHash,
                localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                remoteKeyHash = remoteKeyHash,
                remotePlatformFamily = BleDiscoveryPlatformFamily.IOS,
            )
        val iosInitiates =
            shouldLocalPeerInitiateL2capConnection(
                localKeyHash = remoteKeyHash,
                localPlatformFamily = BleDiscoveryPlatformFamily.IOS,
                remoteKeyHash = localKeyHash,
                remotePlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
            )

        // Assert
        assertTrue(androidInitiates)
        assertTrue(!iosInitiates)
    }

    @Test
    fun `same platform peers fall back to legacy key hash ordering`() {
        // Arrange
        val lowerKeyHash = byteArrayOf(0x01, 0x00)
        val higherKeyHash = byteArrayOf(0x02, 0x00)

        // Act
        val lowerInitiates =
            shouldLocalPeerInitiateL2capConnection(
                localKeyHash = lowerKeyHash,
                localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                remoteKeyHash = higherKeyHash,
                remotePlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
            )
        val higherInitiates =
            shouldLocalPeerInitiateL2capConnection(
                localKeyHash = higherKeyHash,
                localPlatformFamily = BleDiscoveryPlatformFamily.IOS,
                remoteKeyHash = lowerKeyHash,
                remotePlatformFamily = BleDiscoveryPlatformFamily.IOS,
            )

        // Assert
        assertTrue(lowerInitiates)
        assertTrue(!higherInitiates)
    }

    @Test
    fun `unknown platform families remain backward compatible with legacy ordering`() {
        // Arrange
        val lowerKeyHash = byteArrayOf(0x00, 0x10)
        val higherKeyHash = byteArrayOf(0x00, 0x20)

        // Act
        val lowerInitiates =
            shouldLocalPeerInitiateL2capConnection(
                localKeyHash = lowerKeyHash,
                localPlatformFamily = BleDiscoveryPlatformFamily.UNKNOWN,
                remoteKeyHash = higherKeyHash,
                remotePlatformFamily = BleDiscoveryPlatformFamily.IOS,
            )
        val higherInitiates =
            shouldLocalPeerInitiateL2capConnection(
                localKeyHash = higherKeyHash,
                localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                remoteKeyHash = lowerKeyHash,
                remotePlatformFamily = BleDiscoveryPlatformFamily.UNKNOWN,
            )

        // Assert
        assertTrue(lowerInitiates)
        assertTrue(!higherInitiates)
    }

    @Test
    fun `only the current protocol version is considered supported`() {
        // Arrange
        val currentVersion = BleDiscoveryContract.CURRENT_PROTOCOL_VERSION
        val futureVersion = currentVersion + 1

        // Act
        val currentSupported = BleDiscoveryContract.isSupportedProtocolVersion(currentVersion)
        val futureSupported = BleDiscoveryContract.isSupportedProtocolVersion(futureVersion)

        // Assert
        assertTrue(currentSupported)
        assertTrue(!futureSupported)
    }

    @Test
    fun `expanded and 32-bit discovery UUID forms are both recognized`() {
        // Arrange
        val shortUuid = BleDiscoveryContract.ADVERTISEMENT_SERVICE_UUID
        val expandedUuid = BleDiscoveryContract.ADVERTISEMENT_SERVICE_UUID_EXPANDED

        // Act
        val shortRecognized = BleDiscoveryContract.isAdvertisementServiceUuid(shortUuid)
        val expandedRecognized = BleDiscoveryContract.isAdvertisementServiceUuid(expandedUuid)

        // Assert
        assertTrue(shortRecognized)
        assertTrue(expandedRecognized)
    }

    @Test
    fun `gatt fallback characteristic UUIDs count upward from service UUID`() {
        // Arrange
        val expected =
            listOf(
                "4d455348-0002-1000-8000-000000000000",
                "4d455348-0003-1000-8000-000000000000",
                "4d455348-0004-1000-8000-000000000000",
                "4d455348-0005-1000-8000-000000000000",
                "4d455348-0006-1000-8000-000000000000",
            )

        // Act
        val actual = BleDiscoveryContract.GATT_CHARACTERISTIC_UUIDS

        // Assert
        assertEquals(expected, actual)
    }

    @Test
    fun `named gatt fallback characteristic roles stay aligned with the fixed uuid set`() {
        // Arrange / Act / Assert
        assertEquals(
            BleDiscoveryContract.GATT_CHARACTERISTIC_UUIDS[0],
            BleDiscoveryContract.GATT_WRITE_CHARACTERISTIC_UUID,
        )
        assertEquals(
            BleDiscoveryContract.GATT_CHARACTERISTIC_UUIDS[1],
            BleDiscoveryContract.GATT_NOTIFY_CHARACTERISTIC_UUID,
        )
        assertEquals(
            BleDiscoveryContract.GATT_CHARACTERISTIC_UUIDS[2],
            BleDiscoveryContract.GATT_CONTROL_CHARACTERISTIC_UUID,
        )
        assertEquals(
            BleDiscoveryContract.GATT_CHARACTERISTIC_UUIDS[3],
            BleDiscoveryContract.GATT_MTU_CHARACTERISTIC_UUID,
        )
        assertEquals(
            BleDiscoveryContract.GATT_CHARACTERISTIC_UUIDS[4],
            BleDiscoveryContract.GATT_SERVICE_ID_CHARACTERISTIC_UUID,
        )
    }

    @Test
    fun `known android and ios pairs are eligible for the gatt notify side bearer`() {
        // Arrange / Act
        val androidToIos =
            shouldUseMixedPlatformGattNotifyBearer(
                localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                remotePlatformFamily = BleDiscoveryPlatformFamily.IOS,
            )
        val iosToAndroid =
            shouldUseMixedPlatformGattNotifyBearer(
                localPlatformFamily = BleDiscoveryPlatformFamily.IOS,
                remotePlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
            )
        val samePlatform =
            shouldUseMixedPlatformGattNotifyBearer(
                localPlatformFamily = BleDiscoveryPlatformFamily.IOS,
                remotePlatformFamily = BleDiscoveryPlatformFamily.IOS,
            )

        // Assert
        assertTrue(androidToIos)
        assertTrue(iosToAndroid)
        assertTrue(samePlatform)
    }

    @Test
    fun `data bearer mode always keeps gatt available as an l2cap fallback`() {
        // Arrange
        val expected = GattDataBearerMode.GATT_OPTIONAL_WITH_L2CAP_FALLBACK

        // Act
        val actual = resolveGattDataBearerMode()

        // Assert
        assertEquals(expected, actual)
    }

    @Test
    fun `mixed platform peers still allow discovery driven l2cap connects while gatt is ready`() {
        // Arrange
        val expected = true

        // Act
        val actual =
            shouldInitiateDiscoveryDrivenL2capConnection(
                localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                remotePlatformFamily = BleDiscoveryPlatformFamily.IOS,
                gattSideLinkReady = true,
            )

        // Assert
        assertEquals(expected, actual)
    }

    @Test
    fun `mixed platform peers still allow discovery driven l2cap connects before gatt is ready`() {
        // Arrange
        val expected = true

        // Act
        val actual =
            shouldInitiateDiscoveryDrivenL2capConnection(
                localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                remotePlatformFamily = BleDiscoveryPlatformFamily.IOS,
                gattSideLinkReady = false,
            )

        // Assert
        assertEquals(expected, actual)
    }

    @Test
    fun `same platform peers keep discovery driven l2cap reconnects even with gatt preference`() {
        // Arrange
        val expected = true

        // Act
        val actual =
            shouldInitiateDiscoveryDrivenL2capConnection(
                localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                remotePlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                gattSideLinkReady = true,
            )

        // Assert
        assertEquals(expected, actual)
    }

    @Test
    fun `same platform peers still keep the gatt notify bearer available`() {
        // Arrange
        val expected = true

        // Act
        val androidPair =
            shouldUseMixedPlatformGattNotifyBearer(
                localPlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
                remotePlatformFamily = BleDiscoveryPlatformFamily.ANDROID,
            )
        val iosPair =
            shouldUseMixedPlatformGattNotifyBearer(
                localPlatformFamily = BleDiscoveryPlatformFamily.IOS,
                remotePlatformFamily = BleDiscoveryPlatformFamily.IOS,
            )

        // Assert
        assertTrue(androidPair)
        assertTrue(iosPair)
        assertEquals(expected, androidPair && iosPair)
    }
}
