package ch.trancee.meshlink.reference.app

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import ch.trancee.meshlink.reference.automation.ReferenceAutomationConfig
import ch.trancee.meshlink.reference.automation.ReferenceAutomationMode
import ch.trancee.meshlink.reference.automation.ReferenceAutomationRole
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import ch.trancee.meshlink.reference.meshlink.ScriptedReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.platform.DefaultPlatformServices
import ch.trancee.meshlink.reference.platform.DefaultPlatformServicesOptions
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.session.InMemoryReferenceDocumentStore
import kotlin.test.Test

class ReferenceAppWorkflowJvmUiTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun guidedWorkflowShowsLiveProofAndSoloFallback() = runComposeUiTest {
        // Arrange
        launchReferenceApp(scriptedPlatformServices())
        waitUntilTextAppears("Guided first exchange")
        val sendHelloButton = onNodeWithTag("guided-send")

        // Act
        onNodeWithTag("guided-start").performClick()
        waitUntilNodeEnabled("guided-send")
        sendHelloButton.performClick()
        onNodeWithTag("guided-open-solo").performClick()
        onNodeWithText("Continue without export").performClick()

        // Assert
        onNodeWithTag("solo-screen").assertIsDisplayed()
        onNodeWithText("Non-authoritative").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun advancedLifecycleTrustResetAndLabFlows() = runComposeUiTest {
        // Arrange
        launchReferenceApp(scriptedPlatformServices())
        onNodeWithTag("guided-start").performClick()
        waitUntilNodeEnabled("guided-send")
        onNodeWithText("Controls").performClick()
        waitUntilTextAppears("Advanced controls")

        // Act
        onNodeWithTag("advanced-pause").performClick()
        onNodeWithTag("advanced-resume").performClick()
        onNodeWithTag("advanced-pause").performClick()
        onNodeWithTag("advanced-resume").performClick()
        onNodeWithTag("advanced-screen")
            .performScrollToNode(hasTestTag("advanced-send-large-transfer"))
        onNodeWithTag("advanced-send-large-transfer", useUnmergedTree = true).performClick()
        onNodeWithTag("advanced-screen").performScrollToNode(hasTestTag("advanced-forget-peer"))
        onNodeWithTag("advanced-forget-peer", useUnmergedTree = true).performClick()
        waitUntilTextAppears("Trust: Forgotten", substring = true)
        onNodeWithText("Lab").performClick()
        onNodeWithText("Continue without export").performClick()

        // Assert
        onNodeWithText("Everything here is explicitly separated", substring = true)
            .assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun timelineHistoryAndRedactedExportSupportRetainedReview() = runComposeUiTest {
        // Arrange
        launchReferenceApp(scriptedPlatformServices())
        onNodeWithTag("guided-start").performClick()
        waitUntilNodeEnabled("guided-send")
        onNodeWithTag("guided-send").performClick()
        onNodeWithText("Evidence").performClick()
        waitUntilTextAppears("Technical timeline")

        // Act
        onNodeWithText("End session").performClick()
        onNodeWithText("End without full export").performClick()
        onNodeWithText("Export session").performScrollTo().performClick()
        onNodeWithText("Redacted export").performClick()
        waitUntilTextAppears("Last export: reference/exports/", substring = true)
        onNodeWithText("Recent history").performClick()
        waitUntilTextAppears("Recent history")
        onAllNodesWithText("Open")[0].performClick()
        waitUntilTextAppears("Return to live")
        onNodeWithText("Return to live").performClick()

        // Assert
        onNodeWithText("Clear all").assertIsDisplayed()
        onNodeWithText("Technical timeline").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun blockedStartupShowsRecoveryGuidance() = runComposeUiTest {
        // Arrange
        launchReferenceApp(scriptedPlatformServices(blocked = true))
        waitUntilTextAppears("Guided first exchange")

        // Act
        val startButton = onNodeWithText("Start MeshLink")

        // Assert
        startButton.assertIsNotEnabled()
        onNodeWithTag("guided-blocker-card").assertIsDisplayed()
        onNodeWithText("Next action: Resolve startup blockers", substring = true)
            .assertIsDisplayed()
        onNodeWithText("Enable Bluetooth", substring = true).assertIsDisplayed()
        onNodeWithText("Grant the required local Bluetooth permissions", substring = true)
            .assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun timelineFiltersCanNarrowAndResetTheVisibleTimeline() = runComposeUiTest {
        // Arrange
        launchReferenceApp(scriptedPlatformServices())
        onNodeWithTag("guided-start").performClick()
        waitUntilNodeEnabled("guided-send")
        onNodeWithTag("guided-send").performClick()
        onNodeWithText("Evidence").performClick()
        waitUntilTextAppears("Technical timeline")

        // Act
        onNodeWithText("Search events").performScrollTo().performTextInput("Received")
        waitUntilTextAppears("Received", substring = true)
        onNodeWithText("All").performScrollTo().performClick()

        // Assert
        onNodeWithText("Filter events").assertIsDisplayed()
        onNodeWithText("All").assertIsDisplayed()
        onNodeWithText("Visible ", substring = true).assertIsDisplayed()
    }
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.launchReferenceApp(platformServices: PlatformServices) {
    setContent { ReferenceApp(platformServices = platformServices) }
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.waitUntilTextAppears(
    text: String,
    substring: Boolean = false,
    timeoutMillis: Long = 10_000,
) {
    waitUntil(timeoutMillis = timeoutMillis) {
        onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
    }
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.waitUntilNodeEnabled(testTag: String, timeoutMillis: Long = 10_000) {
    waitUntil(timeoutMillis = timeoutMillis) {
        runCatching { onNodeWithTag(testTag).assertIsEnabled() }.isSuccess
    }
}

private fun scriptedPlatformServices(blocked: Boolean = false): PlatformServices {
    var nowMillis = 1_000L
    return DefaultPlatformServices(
        platformName = "JVM",
        defaultAuthorityMode = ReferenceAuthorityMode.LIVE,
        readinessGuidance = listOf("Keep two devices nearby", "Stay offline"),
        options =
            DefaultPlatformServicesOptions().apply {
                nowProvider = { nowMillis++ }
                readinessBlockers =
                    if (blocked) {
                        listOf(
                            "Enable Bluetooth on Android before starting the guided exchange.",
                            "Grant the required local Bluetooth permissions for MeshLink.",
                        )
                    } else {
                        emptyList()
                    }
                documentStore = InMemoryReferenceDocumentStore()
                automationConfig =
                    ReferenceAutomationConfig(
                        mode = ReferenceAutomationMode.SCRIPTED_UI,
                        role = ReferenceAutomationRole.PASSIVE,
                        appId = "demo.meshlink.reference.automation",
                        storageSubdirectory = "jvm-ui",
                    )
                meshLinkControllerFactory = { surfaceOfOrigin ->
                    scriptedMeshLinkController(
                        nowProvider = { nowMillis++ },
                        surfaceOfOrigin = surfaceOfOrigin,
                    )
                }
            },
    )
}

private fun scriptedMeshLinkController(
    nowProvider: () -> Long,
    surfaceOfOrigin: String,
): ReferenceMeshLinkController {
    return ScriptedReferenceMeshLinkController(
        platformName = "JVM",
        authorityMode = ReferenceAuthorityMode.LIVE,
        nowProvider = nowProvider,
        surfaceOfOrigin = surfaceOfOrigin,
    )
}
