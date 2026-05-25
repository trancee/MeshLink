package ch.trancee.meshlink.reference.navigation

import kotlinx.serialization.Serializable

@Serializable
public enum class ReferenceSurface(public val route: String) {
    MAIN_GUIDED("main-guided"),
    SOLO_EXPLORATION("solo-exploration"),
    ADVANCED_CONTROLS("advanced-controls"),
    TECHNICAL_TIMELINE("technical-timeline"),
    LAB("lab"),
    RECENT_HISTORY("recent-history"),
}

public data class ReferenceWorkflowDescriptor(
    public val surface: ReferenceSurface,
    public val title: String,
)

public object ReferenceWorkflowCatalog {
    public fun descriptors(): List<ReferenceWorkflowDescriptor> {
        return listOf(
            ReferenceWorkflowDescriptor(ReferenceSurface.MAIN_GUIDED, "Guided first exchange"),
            ReferenceWorkflowDescriptor(ReferenceSurface.SOLO_EXPLORATION, "Solo exploration"),
            ReferenceWorkflowDescriptor(ReferenceSurface.ADVANCED_CONTROLS, "Advanced controls"),
            ReferenceWorkflowDescriptor(ReferenceSurface.TECHNICAL_TIMELINE, "Technical timeline"),
            ReferenceWorkflowDescriptor(ReferenceSurface.LAB, "Lab"),
            ReferenceWorkflowDescriptor(ReferenceSurface.RECENT_HISTORY, "Recent history"),
        )
    }
}
