package ch.trancee.meshlink.reference.automation

/** Optional host-driven automation modes for the reference app. */
public interface ReferenceAutomationConfigView {
    public val mode: String
    public val role: String
    public val appId: String
    public val storageSubdirectory: String
    public val requiredPeerCount: Int
    public val targetPeerIndex: Int
    public val targetPeerId: String?
    public val scenario: String
}

public data class ReferenceAutomationConfig(
    override val mode: String,
    override val role: String,
    override val appId: String,
    override val storageSubdirectory: String,
    override val requiredPeerCount: Int = 1,
    override val targetPeerIndex: Int = 0,
    override val targetPeerId: String? = null,
    override val scenario: String = AUTOMATION_SCENARIO_DIRECT_GUIDED,
) : ReferenceAutomationConfigView

public const val AUTOMATION_MODE_SCRIPTED_UI: String = "scripted-ui"
public const val AUTOMATION_MODE_LIVE_PROOF: String = "live-proof"

public const val AUTOMATION_ROLE_SENDER: String = "sender"
public const val AUTOMATION_ROLE_PASSIVE: String = "passive"
public const val AUTOMATION_ROLE_RELAY: String = "relay"

public const val AUTOMATION_SCENARIO_DIRECT_GUIDED: String = "direct-guided"
public const val AUTOMATION_SCENARIO_DIRECT_PAUSE_RESUME: String = "direct-pause-resume"
public const val AUTOMATION_SCENARIO_DIRECT_FULL_EXPORT: String = "direct-full-export"
public const val AUTOMATION_SCENARIO_DIRECT_TRUST_RESET_RECOVERY: String =
    "direct-trust-reset-recovery"
public const val AUTOMATION_SCENARIO_DIRECT_RESTART_RECOVERY: String = "direct-restart-recovery"
public const val AUTOMATION_SCENARIO_DIRECT_ISOLATION_RECOVERY: String = "direct-isolation-recovery"
public const val AUTOMATION_SCENARIO_DIRECT_ROUTE_BREAK_RECOVERY: String =
    "direct-route-break-recovery"
public const val AUTOMATION_SCENARIO_DIRECT_LARGE_TRANSFER: String = "direct-large-transfer"
public const val AUTOMATION_SCENARIO_RELAY_CONSTRAINED: String = "relay-constrained"

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
    DIRECT_RESTART_RECOVERY,
    DIRECT_ISOLATION_RECOVERY,
    DIRECT_ROUTE_BREAK_RECOVERY,
    DIRECT_LARGE_TRANSFER,
    RELAY_CONSTRAINED,
}

public fun String?.toReferenceAutomationScenario(): ReferenceAutomationScenario {
    return when {
        this.equals(AUTOMATION_SCENARIO_DIRECT_PAUSE_RESUME, ignoreCase = true) ->
            ReferenceAutomationScenario.DIRECT_PAUSE_RESUME
        this.equals(AUTOMATION_SCENARIO_DIRECT_FULL_EXPORT, ignoreCase = true) ->
            ReferenceAutomationScenario.DIRECT_FULL_EXPORT
        this.equals(AUTOMATION_SCENARIO_DIRECT_TRUST_RESET_RECOVERY, ignoreCase = true) ->
            ReferenceAutomationScenario.DIRECT_TRUST_RESET_RECOVERY
        this.equals(AUTOMATION_SCENARIO_DIRECT_RESTART_RECOVERY, ignoreCase = true) ->
            ReferenceAutomationScenario.DIRECT_RESTART_RECOVERY
        this.equals(AUTOMATION_SCENARIO_DIRECT_ISOLATION_RECOVERY, ignoreCase = true) ->
            ReferenceAutomationScenario.DIRECT_ISOLATION_RECOVERY
        this.equals(AUTOMATION_SCENARIO_DIRECT_ROUTE_BREAK_RECOVERY, ignoreCase = true) ->
            ReferenceAutomationScenario.DIRECT_ROUTE_BREAK_RECOVERY
        this.equals(AUTOMATION_SCENARIO_DIRECT_LARGE_TRANSFER, ignoreCase = true) ->
            ReferenceAutomationScenario.DIRECT_LARGE_TRANSFER
        this.equals(AUTOMATION_SCENARIO_RELAY_CONSTRAINED, ignoreCase = true) ->
            ReferenceAutomationScenario.RELAY_CONSTRAINED
        else -> ReferenceAutomationScenario.DIRECT_GUIDED
    }
}

public fun ReferenceAutomationScenario.wireValue(): String {
    return when (this) {
        ReferenceAutomationScenario.DIRECT_GUIDED -> AUTOMATION_SCENARIO_DIRECT_GUIDED
        ReferenceAutomationScenario.DIRECT_PAUSE_RESUME -> AUTOMATION_SCENARIO_DIRECT_PAUSE_RESUME
        ReferenceAutomationScenario.DIRECT_FULL_EXPORT -> AUTOMATION_SCENARIO_DIRECT_FULL_EXPORT
        ReferenceAutomationScenario.DIRECT_TRUST_RESET_RECOVERY ->
            AUTOMATION_SCENARIO_DIRECT_TRUST_RESET_RECOVERY
        ReferenceAutomationScenario.DIRECT_RESTART_RECOVERY ->
            AUTOMATION_SCENARIO_DIRECT_RESTART_RECOVERY
        ReferenceAutomationScenario.DIRECT_ISOLATION_RECOVERY ->
            AUTOMATION_SCENARIO_DIRECT_ISOLATION_RECOVERY
        ReferenceAutomationScenario.DIRECT_ROUTE_BREAK_RECOVERY ->
            AUTOMATION_SCENARIO_DIRECT_ROUTE_BREAK_RECOVERY
        ReferenceAutomationScenario.DIRECT_LARGE_TRANSFER ->
            AUTOMATION_SCENARIO_DIRECT_LARGE_TRANSFER
        ReferenceAutomationScenario.RELAY_CONSTRAINED -> AUTOMATION_SCENARIO_RELAY_CONSTRAINED
    }
}

/**
 * Creates a reference automation config from primitive inputs so platform code does not need to
 * construct the data class inline.
 */
public fun createReferenceAutomationConfig(
    mode: String,
    role: String,
    appId: String,
    storageSubdirectory: String,
    requiredPeerCount: Int = 1,
    targetPeerIndex: Int = 0,
    targetPeerId: String? = null,
    scenario: String = AUTOMATION_SCENARIO_DIRECT_GUIDED,
): ReferenceAutomationConfig {
    return ReferenceAutomationConfig(
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
