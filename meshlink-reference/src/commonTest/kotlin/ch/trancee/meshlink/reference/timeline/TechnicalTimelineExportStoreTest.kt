package ch.trancee.meshlink.reference.timeline

import ch.trancee.meshlink.reference.session.ExportPayloadPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class TechnicalTimelineExportStoreTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun exportVisibleSessionCreatesUniqueArtifactInstancePath() = runTest {
        // Arrange
        val harness = TimelineStoreHarness()
        val store = harness.createStore(scope = this)
        advanceUntilIdle()
        val firstNow = harness.nowMillis

        // Act
        store.exportVisibleSession(ExportPayloadPolicy.REDACTED_PREVIEW)
        advanceUntilIdle()
        harness.nowMillis = firstNow + 100L
        store.exportVisibleSession(ExportPayloadPolicy.REDACTED_PREVIEW)
        advanceUntilIdle()

        // Assert
        assertEquals(
            "reference/exports/timeline-session-2100-redacted.json",
            store.uiState.value.lastExportPath,
        )
        coroutineContext.cancelChildren()
    }
}
