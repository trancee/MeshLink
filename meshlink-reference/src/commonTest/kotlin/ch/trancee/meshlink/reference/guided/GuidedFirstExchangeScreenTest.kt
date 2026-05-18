package ch.trancee.meshlink.reference.guided

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.MeshLinkState
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceHistoryStatus
import ch.trancee.meshlink.reference.model.ReferenceSession
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.session.InMemoryReferenceDocumentStore
import ch.trancee.meshlink.reference.session.ReferenceDocumentStore
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class GuidedFirstExchangeScreenTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun guidedScreenShowsStartStateAndSoloEntryPoint() = runComposeUiTest {
        var soloOpened = false
        setContent {
            GuidedFirstExchangeScreen(
                viewModel =
                    GuidedFirstExchangeViewModel(platformServices = screenTestPlatformServices()),
                onOpenSolo = { soloOpened = true },
            )
        }

        onNodeWithTag("guided-state").assertTextEquals("Mesh state: Uninitialized")
        onNodeWithTag("guided-next-action").assertTextEquals("Next action: Start MeshLink")
        onNodeWithTag("guided-open-solo").performClick()

        assertTrue(soloOpened)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun guidedScreenShowsStartupBlockerState() = runComposeUiTest {
        setContent {
            GuidedFirstExchangeScreen(
                viewModel =
                    GuidedFirstExchangeViewModel(
                        platformServices =
                            screenTestPlatformServices(startupBlockers = listOf("Enable Bluetooth"))
                    ),
                onOpenSolo = {},
            )
        }

        onNodeWithTag("guided-next-action")
            .assertTextEquals("Next action: Resolve startup blockers")
        onNodeWithTag("guided-blocker-card")
    }
}

private fun screenTestPlatformServices(
    startupBlockers: List<String> = emptyList()
): PlatformServices {
    return object : PlatformServices {
        override val platformName: String = "Test"
        override val defaultAuthorityMode: ReferenceAuthorityMode = ReferenceAuthorityMode.LIVE
        override val readinessGuidance: List<String> =
            listOf("Keep two devices nearby", "Stay offline")
        override val readinessBlockers: List<String> = startupBlockers
        override val documentStore: ReferenceDocumentStore = InMemoryReferenceDocumentStore()
        override val meshLinkController: ReferenceMeshLinkController =
            object : ReferenceMeshLinkController {
                private val flow =
                    MutableStateFlow(
                        ReferenceControllerSnapshot(
                            session =
                                ReferenceSession(
                                    sessionId = "screen-test",
                                    scenarioId = "guided-first-exchange",
                                    authorityMode = ReferenceAuthorityMode.LIVE,
                                    startedAtEpochMillis = 1_000L,
                                    meshStateLabel = MeshLinkState.Uninitialized.toString(),
                                    historyStatus = ReferenceHistoryStatus.LIVE,
                                ),
                            peers = emptyList(),
                            timeline =
                                listOf(
                                    TimelineEntry(
                                        entryId = "screen-test-1",
                                        sessionId = "screen-test",
                                        occurredAtEpochMillis = 1_000L,
                                        family = TimelineFamily.USER,
                                        severity = TimelineSeverity.INFO,
                                        title = "Initialized",
                                        detail = "Test snapshot",
                                    )
                                ),
                            activePowerModeLabel = "Automatic",
                        )
                    )

                override val snapshot: StateFlow<ReferenceControllerSnapshot> = flow.asStateFlow()

                override suspend fun start() = Unit

                override suspend fun pause() = Unit

                override suspend fun resume() = Unit

                override suspend fun stop() = Unit

                override suspend fun sendSamplePayload(
                    peerId: String,
                    payloadText: String,
                    priority: DeliveryPriority,
                ) = Unit

                override suspend fun forgetPeer(peerId: String) = Unit
            }

        override fun currentTimeMillis(): Long = 1_000L
    }
}
