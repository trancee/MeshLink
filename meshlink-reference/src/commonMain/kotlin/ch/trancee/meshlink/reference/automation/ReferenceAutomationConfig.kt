package ch.trancee.meshlink.reference.automation

/** Optional host-driven automation modes for the reference app. */
public data class ReferenceAutomationConfig(
    public val mode: ReferenceAutomationMode,
    public val role: ReferenceAutomationRole,
    public val appId: String,
    public val storageSubdirectory: String,
    public val requiredPeerCount: Int = 1,
    public val targetPeerIndex: Int = 0,
    public val targetPeerId: String? = null,
    public val scenario: ReferenceAutomationScenario = ReferenceAutomationScenario.DIRECT_GUIDED,
)

public enum class ReferenceAutomationMode {
    SCRIPTED_UI,
    LIVE_PROOF,
}

public enum class ReferenceAutomationRole {
    SENDER,
    PASSIVE,
    RELAY,
}

public enum class ReferenceAutomationScenario {
    DIRECT_GUIDED,
    DIRECT_PAUSE_RESUME,
    DIRECT_FULL_EXPORT,
    DIRECT_TRUST_RESET_RECOVERY,
    DIRECT_LARGE_TRANSFER,
    RELAY_CONSTRAINED,
}

public fun String?.toReferenceAutomationScenario(): ReferenceAutomationScenario {
    return when {
        this.equals("direct-pause-resume", ignoreCase = true) ->
            ReferenceAutomationScenario.DIRECT_PAUSE_RESUME
        this.equals("direct-full-export", ignoreCase = true) ->
            ReferenceAutomationScenario.DIRECT_FULL_EXPORT
        this.equals("direct-trust-reset-recovery", ignoreCase = true) ->
            ReferenceAutomationScenario.DIRECT_TRUST_RESET_RECOVERY
        this.equals("direct-large-transfer", ignoreCase = true) ->
            ReferenceAutomationScenario.DIRECT_LARGE_TRANSFER
        this.equals("relay-constrained", ignoreCase = true) ->
            ReferenceAutomationScenario.RELAY_CONSTRAINED
        else -> ReferenceAutomationScenario.DIRECT_GUIDED
    }
}

public fun ReferenceAutomationScenario.wireValue(): String {
    return when (this) {
        ReferenceAutomationScenario.DIRECT_GUIDED -> "direct-guided"
        ReferenceAutomationScenario.DIRECT_PAUSE_RESUME -> "direct-pause-resume"
        ReferenceAutomationScenario.DIRECT_FULL_EXPORT -> "direct-full-export"
        ReferenceAutomationScenario.DIRECT_TRUST_RESET_RECOVERY -> "direct-trust-reset-recovery"
        ReferenceAutomationScenario.DIRECT_LARGE_TRANSFER -> "direct-large-transfer"
        ReferenceAutomationScenario.RELAY_CONSTRAINED -> "relay-constrained"
    }
}
