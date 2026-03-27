package io.meshlink.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GattConstantsTest {

    @Test
    fun serviceUuidIs7F3A() {
        assertTrue(GattConstants.SERVICE_UUID.contains("7f3a", ignoreCase = true),
            "Service UUID should contain 0x7F3A")
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
        // All characteristic UUIDs should use the same base as the service
        val serviceBase = GattConstants.SERVICE_UUID.takeLast(24)
        listOf(
            GattConstants.CONTROL_WRITE_UUID,
            GattConstants.CONTROL_NOTIFY_UUID,
            GattConstants.DATA_WRITE_UUID,
            GattConstants.DATA_NOTIFY_UUID,
        ).forEach { uuid ->
            assertTrue(uuid.takeLast(24) == serviceBase,
                "Characteristic UUID should share service base: $uuid")
        }
    }
}
