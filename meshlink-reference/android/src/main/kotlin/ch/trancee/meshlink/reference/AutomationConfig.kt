package ch.trancee.meshlink.reference

internal data class AutomationConfig(
    val enabled: Boolean,
    val mode: String?,
    val storageSubdirectory: String,
    val appId: String,
    val role: String,
    val requiredPeerCount: Int,
    val targetPeerIndex: Int,
    val targetPeerId: String?,
    val scenario: String,
    val blocked: Boolean,
    val advertisementCarrier: String,
)
