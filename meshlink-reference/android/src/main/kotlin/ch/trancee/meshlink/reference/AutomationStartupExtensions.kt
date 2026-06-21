package ch.trancee.meshlink.reference

import android.util.Log

internal fun MainActivity.emitStartupMarker(automationConfig: AutomationConfig) {
    if (!automationConfig.enabled) return
    Log.i(
        "MeshLinkReferenceAutomation",
        "REFERENCE_AUTOMATION startup stage=activity.onCreate " +
            "mode=${automationConfig.mode} role=${automationConfig.role} " +
            "scenario=${automationConfig.scenario} appId=${automationConfig.appId} " +
            "storage=${automationConfig.storageSubdirectory}",
    )
}
