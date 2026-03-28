package io.meshlink.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GattConstantsTest {

    @Test
    fun serviceUuidIsRandom128Bit() {
        // Must NOT use Bluetooth SIG Base UUID pattern (unassigned 16-bit UUIDs are silently dropped)
        val sigBaseSuffix = "0000-1000-8000-00805f9b34fb"
        assertTrue(!GattConstants.SERVICE_UUID.endsWith(sigBaseSuffix),
            "Service UUID must not use Bluetooth SIG Base UUID pattern")
        assertTrue(GattConstants.SERVICE_UUID.contains("c64fb997", ignoreCase = true),
            "Service UUID should be the expected MeshLink UUID")
    }

    @Test
    fun fourCharacteristicsDefined() {
        val uuids = listOf(
            GattConstants.CONTROL_WRITE_UUID,
            GattConstants.CONTROL_NOTIFY_UUID,
            GattConstants.DATA_WRITE_UUID,
            GattConstants.DATA_NOTIFY_UUID,
        )
        assertEquals(4, uuids.size, "Should have exactly 4 characteristic UUIDs")
        assertEquals(4, uuids.toSet().size, "All 4 UUIDs should be unique")
    }

    @Test
    fun allUuidsAreValidFormat() {
        val uuidRegex = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", RegexOption.IGNORE_CASE)
        listOf(
            GattConstants.SERVICE_UUID,
            GattConstants.CONTROL_WRITE_UUID,
            GattConstants.CONTROL_NOTIFY_UUID,
            GattConstants.DATA_WRITE_UUID,
            GattConstants.DATA_NOTIFY_UUID,
        ).forEach { uuid ->
            assertTrue(uuidRegex.matches(uuid), "UUID should be valid format: $uuid")
        }
    }

    @Test
    fun characteristicUuidsShareServiceBase() {
        // All characteristic UUIDs share the same base (last 28 chars) as the service
        val serviceBase = GattConstants.SERVICE_UUID.substring(8)
        listOf(
            GattConstants.CONTROL_WRITE_UUID,
            GattConstants.CONTROL_NOTIFY_UUID,
            GattConstants.DATA_WRITE_UUID,
            GattConstants.DATA_NOTIFY_UUID,
        ).forEach { uuid ->
            assertTrue(uuid.substring(8) == serviceBase,
                "Characteristic UUID should share service base: $uuid")
        }
    }
}
