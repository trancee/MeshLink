package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.BatterySnapshot
import ch.trancee.meshlink.config.PowerMode
import ch.trancee.meshlink.config.RegulatoryRegion
import ch.trancee.meshlink.engine.lifecycle.MeshEnginePowerPolicyCallbacks
import ch.trancee.meshlink.engine.lifecycle.MeshEnginePowerPolicyDiagnostics
import ch.trancee.meshlink.engine.lifecycle.MeshEnginePowerPolicyState
import ch.trancee.meshlink.engine.lifecycle.MeshEnginePowerPolicySupport
import ch.trancee.meshlink.power.PowerPolicy
import ch.trancee.meshlink.power.PowerPolicyController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MeshEnginePowerPolicySupportTest {
    @Test
    fun `updateBattery stores the new power policy and emits a power-mode diagnostic`() {
        // Arrange
        val harness = powerPolicyHarness()

        // Act
        harness.support.updateBattery(BatterySnapshot(level = 1.5f, isCharging = false))

        // Assert
        assertEquals(1, harness.callbacks.launchedTransportPolicies.size)
        assertEquals(
            harness.powerPolicyState.currentPowerPolicy.tier,
            harness.callbacks.launchedTransportPolicies.single().tier,
        )
        assertEquals(1.0f, harness.diagnostics.powerModeLevels.single())
        assertFalse(harness.diagnostics.powerModeCharging.single())
        assertEquals(1, harness.diagnostics.powerModePolicies.size)
    }
}

private data class PowerPolicyHarness(
    val powerPolicyState: MeshEnginePowerPolicyState,
    val callbacks: RecordingBatteryPolicyCallbacks,
    val diagnostics: RecordingBatteryPolicyDiagnostics,
    val support: MeshEnginePowerPolicySupport,
)

private fun powerPolicyHarness(): PowerPolicyHarness {
    val powerPolicyController =
        PowerPolicyController(
            configuredMode = PowerMode.Automatic,
            region = RegulatoryRegion.DEFAULT,
        )
    val powerPolicyState =
        MeshEnginePowerPolicyState(
            currentPowerPolicy = powerPolicyController.currentPolicy(nowMillis = 0L)
        )
    val callbacks = RecordingBatteryPolicyCallbacks()
    val diagnostics = RecordingBatteryPolicyDiagnostics()
    val support =
        MeshEnginePowerPolicySupport(
            powerPolicyController = powerPolicyController,
            powerPolicyNowMillis = { 1_000L },
            state = powerPolicyState,
            callbacks = callbacks.asCallbacks(),
            diagnostics = diagnostics.asDiagnostics(),
        )
    return PowerPolicyHarness(powerPolicyState, callbacks, diagnostics, support)
}

private class RecordingBatteryPolicyCallbacks {
    val updatedTransportPolicies: MutableList<PowerPolicy> = mutableListOf()
    val launchedTransportPolicies: MutableList<PowerPolicy> = mutableListOf()

    fun asCallbacks(): MeshEnginePowerPolicyCallbacks {
        return MeshEnginePowerPolicyCallbacks(
            updateTransportPowerPolicy = { policy -> updatedTransportPolicies += policy },
            launchTransportPowerPolicyUpdate = { policy -> launchedTransportPolicies += policy },
        )
    }
}

private class RecordingBatteryPolicyDiagnostics {
    val powerModePolicies: MutableList<PowerPolicy> = mutableListOf()
    val powerModeLevels: MutableList<Float> = mutableListOf()
    val powerModeCharging: MutableList<Boolean> = mutableListOf()

    fun asDiagnostics(): MeshEnginePowerPolicyDiagnostics {
        return MeshEnginePowerPolicyDiagnostics(
            emitPowerModeChanged = { policy, level, isCharging ->
                powerModePolicies += policy
                powerModeLevels += level
                powerModeCharging += isCharging
            }
        )
    }
}
