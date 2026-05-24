package ch.trancee.meshlink.config

import ch.trancee.meshlink.api.MeshLinkException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class MeshLinkConfigTest {
    @Test
    fun `meshLinkConfig applies DSL values and preserves defaults for omitted fields`() {
        // Arrange / Act
        val config = meshLinkConfig {
            appId = "demo.meshlink"
            powerMode = PowerMode.Performance
        }

        // Assert
        assertEquals("demo.meshlink", config.appId)
        assertEquals(RegulatoryRegion.DEFAULT, config.regulatoryRegion)
        assertEquals(PowerMode.Performance, config.powerMode)
        assertEquals(15.seconds, config.deliveryRetryDeadline)
    }

    @Test
    fun `meshLinkConfig rejects blank app ids`() {
        // Arrange / Act
        val failure =
            assertFailsWith<MeshLinkException.InvalidConfiguration> {
                meshLinkConfig { appId = "  " }
            }

        // Assert
        assertEquals("appId must not be blank", failure.message)
    }

    @Test
    fun `meshLinkConfig rejects non positive retry deadlines`() {
        // Arrange / Act
        val failure =
            assertFailsWith<MeshLinkException.InvalidConfiguration> {
                meshLinkConfig {
                    appId = "demo.meshlink"
                    deliveryRetryDeadline = 0.seconds
                }
            }

        // Assert
        assertEquals("deliveryRetryDeadline must be greater than zero", failure.message)
    }
}
