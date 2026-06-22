package ch.trancee.meshlink.reference

import android.util.Log
import android.view.WindowManager
import androidx.core.content.ContextCompat

internal fun MainActivity.startDirectProofPowerService() {
    ContextCompat.startForegroundService(this, DirectProofPowerService.start(this))
}

internal fun MainActivity.stopDirectProofPowerService() {
    stopService(DirectProofPowerService.start(this))
}

internal fun MainActivity.keepScreenOn() {
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}

internal fun MainActivity.releaseScreenOn() {
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}

internal fun MainActivity.logActivityStage(stage: String, directProofEnabled: Boolean) {
    Log.i(
        "MeshLinkReference",
        "REFERENCE_AUTOMATION activity.stage=$stage directProof=$directProofEnabled " +
            "screenOn=${isDeviceInteractive()}",
    )
}

internal fun MainActivity.emitDirectProofPowerState(
    platformServices: Any?,
    stage: String,
    directProofEnabled: Boolean,
) {
    val androidPlatformServices = platformServices as? AndroidPlatformServices ?: return
    androidPlatformServices.emitAutomationLog(
        "REFERENCE_AUTOMATION power.state stage=$stage interactive=${isDeviceInteractive()} " +
            "powerSaveMode=${isPowerSaveMode()} directProof=$directProofEnabled",
    )
}

internal fun MainActivity.configureDiscoveryCarrier(
    advertisementCarrier: String,
    directProofEnabled: Boolean,
) {
    Log.i(
        "MeshLinkReferenceAutomation",
        "REFERENCE_AUTOMATION carrier.defer directProof=$directProofEnabled carrier=$advertisementCarrier",
    )
}

internal fun MainActivity.createPlatformServicesForAutomation(
    automationConfig: AutomationConfig,
): Any {
    return when {
        automationConfig.enabled && automationConfig.mode == MainActivity.AUTOMATION_MODE_LIVE_PROOF ->
            createLiveAutomationPlatformServices(
                LiveAutomationPlatformServicesArgs(
                    context = applicationContext,
                    storageSubdirectory = automationConfig.storageSubdirectory,
                    appId = automationConfig.appId,
                    role = automationConfig.role,
                    requiredPeerCount = automationConfig.requiredPeerCount,
                    targetPeerIndex = automationConfig.targetPeerIndex,
                    targetPeerId = automationConfig.targetPeerId,
                    scenario = automationConfig.scenario,
                )
            )
        automationConfig.enabled ->
            createAutomationPlatformServices(
                context = applicationContext,
                storageSubdirectory = automationConfig.storageSubdirectory,
                blocked = automationConfig.blocked,
            )
        else -> createPlatformServices(applicationContext)
    }
}
