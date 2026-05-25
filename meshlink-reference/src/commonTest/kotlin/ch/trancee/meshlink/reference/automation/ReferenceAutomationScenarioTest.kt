package ch.trancee.meshlink.reference.automation

import kotlin.test.Test
import kotlin.test.assertEquals

class ReferenceAutomationScenarioTest {
    @Test
    fun automationScenarioParserMapsWireValues() {
        // Arrange
        val largeTransferWireValue = "direct-large-transfer"
        val relayWireValue = "relay-constrained"

        // Act
        val largeTransferScenario = largeTransferWireValue.toReferenceAutomationScenario()
        val relayScenario = relayWireValue.toReferenceAutomationScenario()

        // Assert
        assertEquals(ReferenceAutomationScenario.DIRECT_LARGE_TRANSFER, largeTransferScenario)
        assertEquals(ReferenceAutomationScenario.RELAY_CONSTRAINED, relayScenario)
    }
}
