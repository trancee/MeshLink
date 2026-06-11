package ch.trancee.meshlink.reference.automation

import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.platform.PlatformServices
import ch.trancee.meshlink.reference.session.InMemoryReferenceDocumentStore
import ch.trancee.meshlink.reference.session.ReferenceDocumentStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest

class PassiveTransportStartupTest {
    @Test
    fun startupMarkerDescribesThePassiveLiveProofLaunchWithoutDownstreamClaims() {
        // Arrange
        val config =
            ReferenceAutomationConfig(
                mode = ReferenceAutomationMode.LIVE_PROOF,
                role = ReferenceAutomationRole.PASSIVE,
                appId = "ch.trancee.meshlink.reference",
                storageSubdirectory = "passive-startup",
                scenario = ReferenceAutomationScenario.RELAY_CONSTRAINED,
            )

        // Act
        val marker = config.startupMarker(stage = "activity.onCreate")

        // Assert
        assertEquals(
            "REFERENCE_AUTOMATION startup stage=activity.onCreate mode=LIVE_PROOF role=PASSIVE scenario=relay-constrained appId=ch.trancee.meshlink.reference storage=passive-startup",
            marker,
        )
        assertFalse(marker.contains("sender", ignoreCase = true))
        assertFalse(marker.contains("peer", ignoreCase = true))
    }

    @Test
    fun startLiveProofIfNeededRequestsStartupOnceAndOnlyEmitsStartupLogs() = runTest {
        // Arrange
        val started = mutableListOf<String>()
        val automationLogs = mutableListOf<String>()
        val controller =
            object : ReferenceMeshLinkController {
                override val snapshot:
                    StateFlow<ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot> =
                    MutableStateFlow(
                        ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot(
                            session =
                                ch.trancee.meshlink.reference.model.ReferenceSession(
                                    sessionId = "session-1",
                                    scenarioId = "direct-guided",
                                    authorityMode = ReferenceAuthorityMode.LIVE,
                                    startedAtEpochMillis = 1_000L,
                                ),
                            peers = emptyList(),
                            timeline = emptyList(),
                            activePowerModeLabel = "unknown",
                        )
                    )

                override suspend fun start() {
                    started += "start"
                }

                override suspend fun pause() = Unit

                override suspend fun resume() = Unit

                override suspend fun stop() = Unit

                override suspend fun sendPayload(
                    peerId: String,
                    payloadText: String,
                    priority: ch.trancee.meshlink.api.DeliveryPriority,
                ) = Unit

                override suspend fun forgetPeer(peerId: String) = Unit
            }
        val services =
            object : PlatformServices {
                override val platformName: String = "JVM"
                override val defaultAuthorityMode: ReferenceAuthorityMode =
                    ReferenceAuthorityMode.LIVE
                override val readinessGuidance: List<String> = listOf("Keep two devices nearby")
                override val readinessBlockers: List<String> = emptyList()
                override val automationConfig: ReferenceAutomationConfig? =
                    ReferenceAutomationConfig(
                        mode = ReferenceAutomationMode.LIVE_PROOF,
                        role = ReferenceAutomationRole.PASSIVE,
                        appId = "ch.trancee.meshlink.reference",
                        storageSubdirectory = "passive-startup",
                        scenario = ReferenceAutomationScenario.RELAY_CONSTRAINED,
                    )
                override val powerMitigationStatus: String? = null
                override val documentStore: ReferenceDocumentStore =
                    InMemoryReferenceDocumentStore()
                override val meshLinkController: ReferenceMeshLinkController = controller

                override fun stopPowerMitigation() = Unit

                override fun currentTimeMillis(): Long = 1_000L

                override fun emitAutomationLog(message: String) {
                    automationLogs += message
                }
            }
        val coordinator =
            ReferenceStartupCoordinator(platformServices = services, scope = backgroundScope)

        // Act
        coordinator.startLiveProofIfNeeded()
        coordinator.startLiveProofIfNeeded()

        // Assert
        assertEquals(listOf("start"), started)
        assertTrue(automationLogs.first().contains("startup.coordinator.requested"))
        assertTrue(automationLogs.last().contains("startup.coordinator.dispatch"))
        assertTrue(automationLogs.all { it.contains("REFERENCE_AUTOMATION startup") })
        assertTrue(automationLogs.none { it.contains("sender", ignoreCase = true) })
        assertTrue(automationLogs.none { it.contains("peer", ignoreCase = true) })
    }
}
