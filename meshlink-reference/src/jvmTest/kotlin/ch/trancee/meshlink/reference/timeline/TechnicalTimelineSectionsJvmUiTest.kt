package ch.trancee.meshlink.reference.timeline

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import ch.trancee.meshlink.reference.design.ReferenceTheme
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class TechnicalTimelineSectionsJvmUiTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun headerAndEmptyStateShowTheirOperatorFacingCopy() = runComposeUiTest {
        // Arrange
        setContent {
            ReferenceTheme {
                TechnicalTimelineHeader()
                EmptyTimelineSection()
            }
        }

        // Act
        // No-op; the assertions read the rendered text directly.

        // Assert
        onNodeWithText("Technical timeline").assertIsDisplayed()
        onNodeWithText("No matching events").assertIsDisplayed()
        onNodeWithText("Nothing matches the current search, family, severity, and peer filters.")
            .assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun timelineEntryCardShowsTheEntryMetadataAndPreview() = runComposeUiTest {
        // Arrange
        val entry =
            TimelineEntry(
                entryId = "entry-1",
                sessionId = "session-1",
                occurredAtEpochMillis = 1L,
                family = TimelineFamily.MESSAGE,
                severity = TimelineSeverity.SUCCESS,
                title = "Delivered",
                detail = "Delivered detail",
                peerSuffix = "abc123",
                payloadPreview = "hello world",
            )

        setContent { ReferenceTheme { TimelineEntryCard(entry = entry) } }

        // Act
        // No-op; the assertions read the rendered text directly.

        // Assert
        onNodeWithText("Delivered").assertIsDisplayed()
        onNodeWithText("Delivered detail").assertIsDisplayed()
        onNodeWithText("Message").assertIsDisplayed()
        onNodeWithText("Success").assertIsDisplayed()
        onNodeWithText("Peer abc123").assertIsDisplayed()
        onNodeWithText("Preview: hello world").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun filterSectionUpdatesTheStoreWhenSearchAndChipsAreSelected() = runComposeUiTest {
        // Arrange
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val harness =
            TimelineStoreHarness(initialTimeline = listOf(timelineStoreEntry("1", "Live")))
        val store = harness.createStore(scope = scope)

        setContent {
            ReferenceTheme {
                TimelineFilterSection(
                    uiState = store.uiState.value,
                    availableFamilies = listOf(TimelineFamily.MESSAGE),
                    availablePeerSuffixes = listOf("abc123"),
                    availableSeverities = listOf(TimelineSeverity.INFO),
                    store = store,
                )
            }
        }

        // Act
        onNodeWithText("Search events").assertIsDisplayed()
        onNodeWithText("Message").performClick()
        onNodeWithText("Info").performClick()
        onNodeWithText("Peer abc123").performClick()

        // Assert
        assertEquals(TimelineFamily.MESSAGE, store.uiState.value.filters.family)
        assertEquals(TimelineSeverity.INFO, store.uiState.value.filters.severity)
        assertEquals("abc123", store.uiState.value.filters.peerSuffix)

        scope.cancel()
    }
}
