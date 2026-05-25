package ch.trancee.meshlink.reference.navigation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.automation.ReferenceAutomationConfig
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.session.InMemoryReferenceDocumentStore
import ch.trancee.meshlink.reference.session.ReferenceDocumentStore
import kotlin.test.Test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ReferenceNavHostUiTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun staleBoundaryDialogDismissesAfterSessionEnds() = runComposeUiTest {
        // Arrange
        val supportedController =
            ControllableReferenceMeshLinkController(snapshot = navHostSnapshot())
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
                override val meshLinkController: ReferenceMeshLinkController = supportedController

                override fun createSupportedMeshLinkController(
                    surfaceOfOrigin: String
                ): ReferenceMeshLinkController = supportedController

                override fun currentTimeMillis(): Long = 1_000L

                override fun emitAutomationLog(message: String): Unit = Unit
            }
        setContent { MaterialTheme { ReferenceNavHost(platformServices = platformServices) } }

        // Act
        onNodeWithTag("guided-open-solo").performClick()
        onNodeWithText("Start a new session").assertIsDisplayed()
        supportedController.emitSnapshot(navHostSnapshot(endedAtEpochMillis = 2_000L))
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("Start a new session")
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isEmpty()
        }

        // Assert
        onAllNodesWithText("Start a new session").assertCountEquals(0)
    }
}

private class ControllableReferenceMeshLinkController(snapshot: ReferenceControllerSnapshot) :
    ReferenceMeshLinkController {
    private val snapshotFlow = MutableStateFlow(snapshot)

    override val snapshot: StateFlow<ReferenceControllerSnapshot> = snapshotFlow.asStateFlow()

    fun emitSnapshot(snapshot: ReferenceControllerSnapshot): Unit {
        snapshotFlow.value = snapshot
    }

    override suspend fun start(): Unit = Unit

    override suspend fun pause(): Unit = Unit

    override suspend fun resume(): Unit = Unit

    override suspend fun stop(): Unit = Unit

    override suspend fun sendPayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
    ): Unit = Unit

    override suspend fun forgetPeer(peerId: String): Unit = Unit
}

private fun navHostSnapshot(endedAtEpochMillis: Long? = null): ReferenceControllerSnapshot {
    return ReferenceControllerSnapshot(
        session =
            ReferenceSession(
                sessionId = "session-1",
                scenarioId = "guided-first-exchange",
                authorityMode = ReferenceAuthorityMode.LIVE,
                startedAtEpochMillis = 1_000L,
                endedAtEpochMillis = endedAtEpochMillis,
                configurationSnapshot = mapOf("surface" to ReferenceSurfaceId.MAIN_GUIDED.route),
            ),
        peers = emptyList(),
        timeline = emptyList(),
        activePowerModeLabel = "Automatic",
    )
}
