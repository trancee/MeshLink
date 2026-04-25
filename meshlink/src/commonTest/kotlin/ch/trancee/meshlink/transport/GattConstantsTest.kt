package ch.trancee.meshlink.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that [GattConstants] UUID values match spec §3 exactly.
 *
 * All UUIDs share the `4d455348` ("MESH" in ASCII hex) prefix. The Advertisement UUID uses the
 * Bluetooth Base UUID suffix (`00805f9b34fb`); Service and Characteristic UUIDs use
 * `000000000000`.
 */
class GattConstantsTest {

    // ── Individual UUID string equality ──────────────────────────────────────

    @Test
    fun advertisementUuidMatchesSpec() {
        assertEquals("4d455348-0000-1000-8000-00805f9b34fb", GattConstants.ADVERTISEMENT_UUID)
    }

    @Test
    fun serviceUuidMatchesSpec() {
        assertEquals("4d455348-0001-1000-8000-000000000000", GattConstants.SERVICE_UUID)
    }

    @Test
    fun controlWriteUuidMatchesSpec() {
        assertEquals("4d455348-0002-1000-8000-000000000000", GattConstants.CONTROL_WRITE_UUID)
    }

    @Test
    fun controlNotifyUuidMatchesSpec() {
        assertEquals("4d455348-0003-1000-8000-000000000000", GattConstants.CONTROL_NOTIFY_UUID)
    }

    @Test
    fun dataWriteUuidMatchesSpec() {
        assertEquals("4d455348-0004-1000-8000-000000000000", GattConstants.DATA_WRITE_UUID)
    }

    @Test
    fun dataNotifyUuidMatchesSpec() {
        assertEquals("4d455348-0005-1000-8000-000000000000", GattConstants.DATA_NOTIFY_UUID)
    }

    // ── Structural checks ─────────────────────────────────────────────────────

    @Test
    fun allUuidsShareMeshPrefix() {
        val uuids = listOf(
            GattConstants.ADVERTISEMENT_UUID,
            GattConstants.SERVICE_UUID,
            GattConstants.CONTROL_WRITE_UUID,
            GattConstants.CONTROL_NOTIFY_UUID,
            GattConstants.DATA_WRITE_UUID,
            GattConstants.DATA_NOTIFY_UUID,
        )
        for (uuid in uuids) {
            assertTrue(uuid.startsWith("4d455348"), "Expected MESH prefix: $uuid")
        }
    }

    @Test
    fun advertisementUuidUsesBluetoothBaseUuidSuffix() {
        assertTrue(GattConstants.ADVERTISEMENT_UUID.endsWith("00805f9b34fb"))
    }

    @Test
    fun serviceAndCharacteristicUuidsUseZeroSuffix() {
        val characteristicUuids = listOf(
            GattConstants.SERVICE_UUID,
            GattConstants.CONTROL_WRITE_UUID,
            GattConstants.CONTROL_NOTIFY_UUID,
            GattConstants.DATA_WRITE_UUID,
            GattConstants.DATA_NOTIFY_UUID,
        )
        for (uuid in characteristicUuids) {
            assertTrue(uuid.endsWith("000000000000"), "Expected zero suffix: $uuid")
        }
    }
}
