package io.meshlink.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeshLinkConfigTest {

    @Test
    fun presetsReturnCorrectDefaultsWithOverrides() {
        val chat = MeshLinkConfig.chatOptimized()
        assertEquals(10_000, chat.maxMessageSize)
        assertEquals(524_288, chat.bufferCapacity)

        val file = MeshLinkConfig.fileTransferOptimized()
        assertEquals(100_000, file.maxMessageSize)
        assertEquals(2_097_152, file.bufferCapacity)

        val power = MeshLinkConfig.powerOptimized()
        assertEquals(10_000, power.maxMessageSize)
        assertEquals(262_144, power.bufferCapacity)

        // Individual override after preset
        val custom = MeshLinkConfig.chatOptimized { maxMessageSize = 50_000 }
        assertEquals(50_000, custom.maxMessageSize)
        assertEquals(524_288, custom.bufferCapacity)
    }

    @Test
    fun crossFieldValidationReturnsAllViolations() {
        val violations = MeshLinkConfig(
            mtu = 10,
            maxMessageSize = 2_000_000,
            bufferCapacity = 1_000_000,
        ).validate()

        assertTrue(violations.size >= 2, "Should report at least 2 violations, got: $violations")
        assertTrue(violations.any { "mtu" in it.lowercase() })
        assertTrue(violations.any { "buffer" in it.lowercase() || "maxmessagesize" in it.lowercase() })
    }
}
