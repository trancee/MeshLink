package ch.trancee.meshlink.reference.navigation

import kotlinx.serialization.Serializable

@Serializable
public enum class ReferenceSurfaceId(public val route: String) {
    MAIN_GUIDED("main-guided"),
    SOLO_EXPLORATION("solo-exploration"),
    ADVANCED_CONTROLS("advanced-controls"),
    TECHNICAL_TIMELINE("technical-timeline"),
    LAB("lab"),
    RECENT_HISTORY("recent-history"),
}

public data class ReferenceWorkflowDescriptor(
    public val surfaceId: ReferenceSurfaceId,
    public val title: String,
)

public object ReferenceWorkflowCatalog {
    public fun descriptors(): List<ReferenceWorkflowDescriptor> {
        return listOf(
            ReferenceWorkflowDescriptor(ReferenceSurfaceId.MAIN_GUIDED, "Guided first exchange"),
            ReferenceWorkflowDescriptor(ReferenceSurfaceId.SOLO_EXPLORATION, "Solo exploration"),
            ReferenceWorkflowDescriptor(ReferenceSurfaceId.ADVANCED_CONTROLS, "Advanced controls"),
            ReferenceWorkflowDescriptor(ReferenceSurfaceId.TECHNICAL_TIMELINE, "Technical timeline"),
            ReferenceWorkflowDescriptor(ReferenceSurfaceId.LAB, "Lab"),
            ReferenceWorkflowDescriptor(ReferenceSurfaceId.RECENT_HISTORY, "Recent history"),
        )
    }
}
