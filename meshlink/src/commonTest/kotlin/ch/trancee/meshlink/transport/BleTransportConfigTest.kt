package ch.trancee.meshlink.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BleTransportConfigTest {

    // ── Default values ────────────────────────────────────────────────────────

    @Test
    fun defaultValues() {
        val config = BleTransportConfig("myapp")
        assertEquals("myapp", config.appId)
        assertEquals(6, config.maxConnections)
        assertFalse(config.forceL2cap)
        assertFalse(config.forceGatt)
    }

    // ── Custom values ─────────────────────────────────────────────────────────

    @Test
    fun customValues() {
        val config =
            BleTransportConfig(
                appId = "app2",
                maxConnections = 3,
                forceL2cap = true,
                forceGatt = false,
            )
        assertEquals("app2", config.appId)
        assertEquals(3, config.maxConnections)
        assertTrue(config.forceL2cap)
        assertFalse(config.forceGatt)
    }

    @Test
    fun forceGattCustom() {
        val config = BleTransportConfig("app", maxConnections = 1, forceGatt = true)
        assertTrue(config.forceGatt)
    }

    // ── PROTOCOL_VERSION constant ─────────────────────────────────────────────

    @Test
    fun protocolVersionConstant() {
        assertEquals(1, BleTransportConfig.PROTOCOL_VERSION)
    }

    // ── Validation: maxConnections ────────────────────────────────────────────

    @Test
    fun maxConnectionsZeroThrows() {
        assertFailsWith<IllegalArgumentException> { BleTransportConfig("app", maxConnections = 0) }
    }

    @Test
    fun maxConnectionsNegativeThrows() {
        assertFailsWith<IllegalArgumentException> { BleTransportConfig("app", maxConnections = -1) }
    }

    // ── equals / hashCode (MEM144 pattern) ────────────────────────────────────

    @Test
    fun equalsSameRef() {
        val config = BleTransportConfig("app")
        assertTrue(config.equals(config))
    }

    @Test
    fun equalsWrongType() {
        val config = BleTransportConfig("app")
        assertFalse(config.equals("not a config"))
        assertFalse(config.equals(null))
    }

    @Test
    fun equalsAllFieldsEqual() {
        val config = BleTransportConfig("app")
        val copy = config.copy()
        assertEquals(config, copy)
        assertEquals(config.hashCode(), copy.hashCode())
    }

    @Test
    fun equalsAppIdDiffers() {
        val config = BleTransportConfig("app")
        assertNotEquals(config, config.copy(appId = "other"))
    }

    @Test
    fun equalsMaxConnectionsDiffers() {
        val config = BleTransportConfig("app")
        assertNotEquals(config, config.copy(maxConnections = 3))
    }

    @Test
    fun equalsForceL2capDiffers() {
        val config = BleTransportConfig("app")
        assertNotEquals(config, config.copy(forceL2cap = true))
    }

    @Test
    fun equalsForceGattDiffers() {
        val config = BleTransportConfig("app")
        assertNotEquals(config, config.copy(forceGatt = true))
    }
}
