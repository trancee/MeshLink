package ch.trancee.meshlink.reference.guided

/** Operator-facing readiness item shown before the first live exchange begins. */
public data class ReadinessItem(public val title: String, public val detail: String)

public data class ReadinessEvaluation(
    public val platformName: String,
    public val items: List<ReadinessItem>,
    public val blockers: List<ReadinessItem> = emptyList(),
    public val summary: String,
) {
    public val isBlocked: Boolean
        get() = blockers.isNotEmpty()

    public val isReadyToGuide: Boolean
        get() = items.isNotEmpty() && !isBlocked
}
