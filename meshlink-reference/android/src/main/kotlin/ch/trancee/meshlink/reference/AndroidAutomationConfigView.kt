package ch.trancee.meshlink.reference

import ch.trancee.meshlink.reference.automation.ReferenceAutomationConfigView

internal data class AndroidAutomationConfigView(
    override val mode: String,
    override val role: String,
    override val appId: String,
    override val storageSubdirectory: String,
    override val requiredPeerCount: Int,
    override val targetPeerIndex: Int,
    override val targetPeerId: String?,
    override val scenario: String,
) : ReferenceAutomationConfigView

internal fun AutomationConfig.toAndroidAutomationConfigView(): AndroidAutomationConfigView? {
    val mode = mode ?: return null
    return AndroidAutomationConfigView(
        mode = mode,
        role = role,
        appId = appId,
        storageSubdirectory = storageSubdirectory,
        requiredPeerCount = requiredPeerCount,
        targetPeerIndex = targetPeerIndex,
        targetPeerId = targetPeerId,
        scenario = scenario,
    )
}
