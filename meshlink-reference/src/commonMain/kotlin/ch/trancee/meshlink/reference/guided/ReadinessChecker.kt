package ch.trancee.meshlink.reference.guided

/** Builds the explanatory readiness list used by the guided first-exchange surface. */
public class ReadinessChecker {
    public fun evaluate(
        platformName: String,
        guidance: List<String>,
        blockers: List<String> = emptyList(),
    ): ReadinessEvaluation {
        val items = guidance.mapIndexed { index, detail ->
            ReadinessItem(title = "Step ${index + 1}", detail = detail)
        }
        val blockerItems = blockers.mapIndexed { index, detail ->
            ReadinessItem(title = "Blocker ${index + 1}", detail = detail)
        }
        val summary =
            when {
                blockerItems.isNotEmpty() ->
                    "$platformName has startup blockers that must be resolved before the guided exchange can start."
                items.isEmpty() ->
                    "$platformName guidance will appear here once platform setup is wired."
                else ->
                    "$platformName is ready for a guided first exchange when the steps below are satisfied."
            }
        return ReadinessEvaluation(
            platformName = platformName,
            items = items,
            blockers = blockerItems,
            summary = summary,
        )
    }
}
