package ch.trancee.meshlink.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
}
