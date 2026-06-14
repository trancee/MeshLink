package ch.trancee.meshlink.reference

import ch.trancee.meshlink.platform.android.DiscoveryAdvertisementCarrier
import ch.trancee.meshlink.reference.automation.ReferenceAutomationRole
import ch.trancee.meshlink.reference.automation.ReferenceAutomationScenario

internal data class AutomationConfig(
    val enabled: Boolean,
    val mode: String?,
    val storageSubdirectory: String,
    val appId: String,
    val role: ReferenceAutomationRole,
    val requiredPeerCount: Int,
    val targetPeerIndex: Int,
    val targetPeerId: String?,
    val benchmarkTransport: String,
    val scenario: ReferenceAutomationScenario,
    val blocked: Boolean,
    val advertisementCarrier: DiscoveryAdvertisementCarrier,
)
