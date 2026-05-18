package ch.trancee.meshlink.reference.guided

/**
 * Builds the explanatory readiness list used by the guided first-exchange surface.
 */
public class ReadinessChecker {
    public fun evaluate(platformName: String, guidance: List<String>): ReadinessEvaluation {
        val items =
            guidance.mapIndexed { index, detail ->
                ReadinessItem(
                    title = "Step ${index + 1}",
                    detail = detail,
                )
            }
        val summary =
            if (items.isEmpty()) {
                "$platformName guidance will appear here once platform setup is wired."
            } else {
                "$platformName is ready for a guided first exchange when the steps below are satisfied."
            }
        return ReadinessEvaluation(
            platformName = platformName,
            items = items,
            summary = summary,
        )
    }
}
