package ch.trancee.meshlink.reference

import android.util.Log
import android.view.WindowManager
import androidx.core.content.ContextCompat
import ch.trancee.meshlink.platform.android.AndroidDiscoveryAdvertisementConfig
import ch.trancee.meshlink.platform.android.DiscoveryAdvertisementCarrier
import ch.trancee.meshlink.reference.automation.ReferenceAutomationRole
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.platform.createAutomationPlatformServices
import ch.trancee.meshlink.reference.platform.createLiveAutomationPlatformServices
import ch.trancee.meshlink.reference.platform.createPlatformServices

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
    platformServices: PlatformServices?,
    stage: String,
    directProofEnabled: Boolean,
) {
    if (platformServices == null) return
    platformServices.emitAutomationLog(
        "REFERENCE_AUTOMATION power.state stage=$stage interactive=${isDeviceInteractive()} " +
            "powerSaveMode=${isPowerSaveMode()} directProof=$directProofEnabled",
    )
}

internal fun MainActivity.configureDiscoveryCarrier(
    advertisementCarrier: DiscoveryAdvertisementCarrier,
    directProofEnabled: Boolean,
) {
    AndroidDiscoveryAdvertisementConfig.carrier =
        if (directProofEnabled) {
            advertisementCarrier
        } else {
            DiscoveryAdvertisementCarrier.UUID_PAIR
        }
}

internal fun MainActivity.createPlatformServicesForAutomation(
    automationConfig: AutomationConfig,
): PlatformServices {
    return when {
        automationConfig.enabled && automationConfig.mode == MainActivity.AUTOMATION_MODE_LIVE_PROOF ->
            createLiveAutomationPlatformServices(
                context = applicationContext,
                storageSubdirectory = automationConfig.storageSubdirectory,
                appId = automationConfig.appId,
                role = automationConfig.role,
                requiredPeerCount = automationConfig.requiredPeerCount,
                targetPeerIndex = automationConfig.targetPeerIndex,
                targetPeerId = automationConfig.targetPeerId,
                benchmarkTransport = automationConfig.benchmarkTransport,
                scenario = automationConfig.scenario,
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
