package ch.trancee.meshlink.reference.model

import kotlinx.serialization.Serializable

/** Operator-facing workflow metadata used to build the reference app navigation surfaces. */
@Serializable
public data class ReferenceScenario(
    public val scenarioId: String,
    public val surface: ReferenceSurface,
    public val mode: ReferenceMode,
    public val title: String,
    public val summary: String,
    public val prerequisites: List<String> = emptyList(),
    public val successSignals: List<String> = emptyList(),
    public val blockedGuidance: List<String> = emptyList(),
    public val capabilityTags: Set<String> = emptySet(),
)

@Serializable
public enum class ReferenceSurface {
    MAIN,
    ADVANCED,
    LAB,
}

@Serializable
public enum class ReferenceMode {
    LIVE,
    SOLO,
}
