package ch.trancee.meshlink.reference.automation

/** Optional host-driven automation modes for the reference app. */
public data class ReferenceAutomationConfig(
    public val mode: ReferenceAutomationMode,
    public val role: ReferenceAutomationRole,
    public val appId: String,
    public val storageSubdirectory: String,
)

public enum class ReferenceAutomationMode {
    SCRIPTED_UI,
    LIVE_PROOF,
}

public enum class ReferenceAutomationRole {
    SENDER,
    PASSIVE,
}
