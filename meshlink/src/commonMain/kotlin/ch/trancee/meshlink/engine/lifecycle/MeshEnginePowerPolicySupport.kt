package ch.trancee.meshlink.engine.lifecycle

import ch.trancee.meshlink.api.BatterySnapshot
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import ch.trancee.meshlink.engine.internal.powerPolicyMetadata
import ch.trancee.meshlink.power.PowerPolicy
import ch.trancee.meshlink.power.PowerPolicyController

internal data class MeshEnginePowerPolicyState(var currentPowerPolicy: PowerPolicy)

internal data class MeshEnginePowerPolicyCallbacks(
    val updateTransportPowerPolicy: suspend (PowerPolicy) -> Unit,
    val launchTransportPowerPolicyUpdate: (PowerPolicy) -> Unit,
)

internal data class MeshEnginePowerPolicyDiagnostics(
    val emitPowerModeChanged: (PowerPolicy, Float, Boolean) -> Unit
)

internal class MeshEnginePowerPolicySupport(
    private val powerPolicyController: PowerPolicyController,
    private val powerPolicyNowMillis: () -> Long,
    private val state: MeshEnginePowerPolicyState,
    private val callbacks: MeshEnginePowerPolicyCallbacks,
    private val diagnostics: MeshEnginePowerPolicyDiagnostics,
) {
    internal suspend fun onMeshStarted(): Unit {
        state.currentPowerPolicy = powerPolicyController.onMeshStarted(powerPolicyNowMillis())
        callbacks.updateTransportPowerPolicy(state.currentPowerPolicy)
    }

    internal suspend fun refreshCurrentPolicy(): Unit {
        state.currentPowerPolicy = powerPolicyController.currentPolicy(powerPolicyNowMillis())
        callbacks.updateTransportPowerPolicy(state.currentPowerPolicy)
    }

    internal fun updateBattery(snapshot: BatterySnapshot): Unit {
        val policy =
            powerPolicyController.onBatterySnapshot(
                level = snapshot.level,
                isCharging = snapshot.isCharging,
                nowMillis = powerPolicyNowMillis(),
            )
        state.currentPowerPolicy = policy
        callbacks.launchTransportPowerPolicyUpdate(policy)
        diagnostics.emitPowerModeChanged(policy, snapshot.level, snapshot.isCharging)
    }
}

internal fun buildMeshEngineRuntimePowerPolicySupport(
    powerPolicyController: PowerPolicyController,
    powerPolicyNowMillis: () -> Long,
    updateTransportPowerPolicy: suspend (PowerPolicy) -> Unit,
    launchTransportPowerPolicyUpdate: (PowerPolicy) -> Unit,
    emitDiagnostic:
        (
            DiagnosticCode,
            DiagnosticSeverity,
            String,
            String?,
            DiagnosticReason?,
            Map<String, String>,
        ) -> Unit,
): MeshEnginePowerPolicySupport {
    val powerPolicyState =
        MeshEnginePowerPolicyState(
            currentPowerPolicy = powerPolicyController.currentPolicy(nowMillis = 0L)
        )
    return MeshEnginePowerPolicySupport(
        powerPolicyController = powerPolicyController,
        powerPolicyNowMillis = powerPolicyNowMillis,
        state = powerPolicyState,
        callbacks =
            MeshEnginePowerPolicyCallbacks(
                updateTransportPowerPolicy = updateTransportPowerPolicy,
                launchTransportPowerPolicyUpdate = launchTransportPowerPolicyUpdate,
            ),
        diagnostics =
            MeshEnginePowerPolicyDiagnostics(
                emitPowerModeChanged = { policy, level, isCharging ->
                    emitDiagnostic(
                        DiagnosticCode.POWER_MODE_CHANGED,
                        DiagnosticSeverity.INFO,
                        "power.updateBattery",
                        null,
                        DiagnosticReason.POWER_CHANGE,
                        powerPolicyMetadata(policy = policy, level = level, isCharging = isCharging),
                    )
                }
            ),
    )
}
