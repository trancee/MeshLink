package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.PauseResult
import ch.trancee.meshlink.api.ResumeResult
import ch.trancee.meshlink.api.StartResult
import ch.trancee.meshlink.api.StopResult
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity

internal fun <T : Any> recordProjectedMeshCall(
    stateStore: ReferenceControllerStateStore,
    result: Result<T>,
    successTitle: String,
    successDetail: (T) -> String,
    errorTitle: String,
): Unit {
    result
        .onSuccess { value ->
            stateStore.appendEvent(
                ReferenceTimelineEvent(
                    family = TimelineFamily.LIFECYCLE,
                    severity = TimelineSeverity.SUCCESS,
                    title = successTitle,
                    detail = successDetail(value),
                )
            )
            if (shouldTrackLifecycleOutcome(value)) {
                stateStore.updateSession(lastOutcomeSummary = value.toString())
            }
        }
        .onFailure { error ->
            stateStore.appendEvent(
                ReferenceTimelineEvent(
                    family = TimelineFamily.LIFECYCLE,
                    severity = TimelineSeverity.ERROR,
                    title = errorTitle,
                    detail = error.message ?: error.toString(),
                )
            )
            stateStore.updateSession(lastOutcomeSummary = errorTitle)
        }
}

private fun shouldTrackLifecycleOutcome(value: Any): Boolean {
    return value is StartResult ||
        value is ResumeResult ||
        value is PauseResult ||
        value is StopResult
}
