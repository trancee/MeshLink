package ch.trancee.meshlink.reference.automation

import kotlin.test.Test
import kotlin.test.assertEquals

class ReferenceAutomationScenarioTest {
    @Test
    fun automationScenarioParserRoundTripsLegacyAndResilienceWireValues() {
        // Arrange
        val cases =
            listOf(
                "direct-guided" to ReferenceAutomationScenario.DIRECT_GUIDED,
                "direct-pause-resume" to ReferenceAutomationScenario.DIRECT_PAUSE_RESUME,
                "direct-full-export" to ReferenceAutomationScenario.DIRECT_FULL_EXPORT,
                "direct-trust-reset-recovery" to
                    ReferenceAutomationScenario.DIRECT_TRUST_RESET_RECOVERY,
                "direct-restart-recovery" to ReferenceAutomationScenario.DIRECT_RESTART_RECOVERY,
                "direct-isolation-recovery" to
                    ReferenceAutomationScenario.DIRECT_ISOLATION_RECOVERY,
                "direct-route-break-recovery" to
                    ReferenceAutomationScenario.DIRECT_ROUTE_BREAK_RECOVERY,
                "direct-large-transfer" to ReferenceAutomationScenario.DIRECT_LARGE_TRANSFER,
                "relay-constrained" to ReferenceAutomationScenario.RELAY_CONSTRAINED,
            )

        // Act / Assert
        cases.forEach { (wireValue, scenario) ->
            assertEquals(scenario, wireValue.toReferenceAutomationScenario())
            assertEquals(wireValue, scenario.wireValue())
        }
    }

    @Test
    fun automationScenarioParserDefaultsUnknownValuesToGuided() {
        // Arrange
        val unknownWireValue = "direct-unknown-scenario"

        // Act
        val parsedScenario = unknownWireValue.toReferenceAutomationScenario()

        // Assert
        assertEquals(ReferenceAutomationScenario.DIRECT_GUIDED, parsedScenario)
    }
}
