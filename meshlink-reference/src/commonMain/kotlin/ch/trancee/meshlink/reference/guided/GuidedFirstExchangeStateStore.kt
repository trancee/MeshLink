package ch.trancee.meshlink.reference.guided

import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class GuidedFirstExchangeStateStore(
    private val platformName: String,
    private val readinessGuidance: List<String>,
    private val readinessBlockers: List<String>,
    initialSnapshot: ReferenceControllerSnapshot,
    private val readinessChecker: ReadinessChecker = ReadinessChecker(),
) {
    private val uiStateFlow: MutableStateFlow<GuidedFirstExchangeUiState> =
        MutableStateFlow(buildUiState(initialSnapshot))

    val uiState: StateFlow<GuidedFirstExchangeUiState> = uiStateFlow.asStateFlow()

    fun applySnapshot(snapshot: ReferenceControllerSnapshot): Unit {
        uiStateFlow.value = buildUiState(snapshot)
    }

    private fun buildUiState(snapshot: ReferenceControllerSnapshot): GuidedFirstExchangeUiState {
        return GuidedFirstExchangeUiState(
            readiness =
                readinessChecker.evaluate(
                    platformName = platformName,
                    guidance = readinessGuidance,
                    blockers = readinessBlockers,
                ),
            snapshot = snapshot,
        )
    }
}
