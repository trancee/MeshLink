package ch.trancee.meshlink.reference

import android.content.Context
import android.content.Intent
import ch.trancee.meshlink.platform.android.DiscoveryAdvertisementCarrier
import ch.trancee.meshlink.reference.automation.ReferenceAutomationRole
import ch.trancee.meshlink.reference.automation.ReferenceAutomationScenario
import ch.trancee.meshlink.reference.automation.wireValue

public data class AutomationLaunch(
    val storageSubdirectory: String,
    val appId: String,
    val role: ReferenceAutomationRole,
    val scenario: ReferenceAutomationScenario = ReferenceAutomationScenario.DIRECT_GUIDED,
    val targetPeerId: String? = null,
    val advertisementCarrier: DiscoveryAdvertisementCarrier =
        DiscoveryAdvertisementCarrier.UUID_PAIR,
)

public fun automationIntent(
    context: Context,
    storageSubdirectory: String,
    blocked: Boolean = false,
): Intent {
    return Intent(context, MainActivity::class.java).apply {
        putExtra(MainActivity.EXTRA_UI_AUTOMATION, true)
        putExtra(MainActivity.EXTRA_UI_AUTOMATION_STORAGE_SUBDIRECTORY, storageSubdirectory)
        putExtra(MainActivity.EXTRA_UI_AUTOMATION_BLOCKED, blocked)
        putExtra(MainActivity.EXTRA_UI_AUTOMATION_MODE, MainActivity.AUTOMATION_MODE_SCRIPTED)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}

public fun liveAutomationIntent(context: Context, automationLaunch: AutomationLaunch): Intent {
    return Intent(context, MainActivity::class.java).apply {
        putExtra(MainActivity.EXTRA_UI_AUTOMATION, true)
        putExtra(MainActivity.EXTRA_UI_AUTOMATION_MODE, MainActivity.AUTOMATION_MODE_LIVE_PROOF)
        putExtra(
            MainActivity.EXTRA_UI_AUTOMATION_STORAGE_SUBDIRECTORY,
            automationLaunch.storageSubdirectory,
        )
        putExtra(MainActivity.EXTRA_UI_AUTOMATION_APP_ID, automationLaunch.appId)
        putExtra(MainActivity.EXTRA_UI_AUTOMATION_ROLE, automationLaunch.role.name.lowercase())
        putExtra(MainActivity.EXTRA_UI_AUTOMATION_SCENARIO, automationLaunch.scenario.wireValue())
        putExtra(
            MainActivity.EXTRA_UI_AUTOMATION_ADVERTISEMENT_CARRIER,
            automationLaunch.advertisementCarrier.name,
        )
        automationLaunch.targetPeerId?.let {
            putExtra(MainActivity.EXTRA_UI_AUTOMATION_TARGET_PEER_ID, it)
        }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
