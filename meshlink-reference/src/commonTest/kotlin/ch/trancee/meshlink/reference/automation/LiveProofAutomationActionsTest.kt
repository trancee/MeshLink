package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.navigation.SessionTransitionService
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.session.InMemoryReferenceDocumentStore
import ch.trancee.meshlink.reference.session.ReferenceDocumentStore
import ch.trancee.meshlink.reference.timeline.TimelineStoreHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class LiveProofAutomationActionsTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun requestEndCurrentSessionEndsTheSupportedSessionThroughTheTransitionService() = runTest {
        // Arrange
        val timelineStore = TimelineStoreHarness().createStore(scope = this)
        val sessionTransitionService = SessionTransitionService(timelineStore)
        val platformServices =
            object : PlatformServices {
                override val platformName: String = "Test"
                override val defaultAuthorityMode: ReferenceAuthorityMode =
                    ReferenceAuthorityMode.LIVE
                override val readinessGuidance: List<String> = emptyList()
                override val readinessBlockers: List<String> = emptyList()
                override val automationConfig: ReferenceAutomationConfig? = null
                override val documentStore: ReferenceDocumentStore =
                    InMemoryReferenceDocumentStore()
                override val meshLinkController = timelineStore.sessionController

                override fun currentTimeMillis(): Long = 1_000L

                override fun emitAutomationLog(message: String): Unit = Unit
            }
        val actions =
            TimelineStoreLiveProofAutomationActions(
                platformServices = platformServices,
                timelineStore = timelineStore,
                sessionTransitionService = sessionTransitionService,
            )
        advanceUntilIdle()

        try {
            // Act
            actions.requestEndCurrentSession()
            advanceUntilIdle()

            // Assert
            assertEquals(true, timelineStore.uiState.value.isCurrentSessionEnded)
        } finally {
            coroutineContext.cancelChildren()
        }
    }
}
