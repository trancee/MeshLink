package ch.trancee.meshlink.reference.session

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.model.ReferenceSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ReferenceSessionControllerTest {
    @Test
    fun startSoloSessionUsesSoloExplorationAsTheSurfaceOfOrigin() =
        kotlinx.coroutines.test.runTest {
            // Arrange
            val controller =
                ReferenceSessionController(
                    platformName = "iOS",
                    nowProvider = { 42L },
                    supportedControllerFactory = {
                        FakeReferenceMeshLinkController(referenceSnapshot())
                    },
                )

            // Act
            val actual = controller.startSoloSession()

            // Assert
            assertEquals(ReferenceAuthorityMode.SOLO, actual.session.authorityMode)
            assertEquals(
                "solo-exploration",
                actual.session.configurationSnapshot.getValue("surface"),
            )
            assertEquals(
                "solo-exploration",
                controller.snapshot.value.session.configurationSnapshot.getValue("surface"),
            )
        }
}

private class FakeReferenceMeshLinkController(initialSnapshot: ReferenceControllerSnapshot) :
    ReferenceMeshLinkController {
    private val snapshotFlow = MutableStateFlow(initialSnapshot)

    override val snapshot: StateFlow<ReferenceControllerSnapshot> = snapshotFlow

    override suspend fun start(): Unit = Unit

    override suspend fun pause(): Unit = Unit

    override suspend fun resume(): Unit = Unit

    override suspend fun stop(): Unit = Unit

    override suspend fun sendSamplePayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
    ): Unit = Unit

    override suspend fun forgetPeer(peerId: String): Unit = Unit

    override suspend fun close(): Unit = Unit
}

private fun referenceSnapshot(): ReferenceControllerSnapshot {
    return ReferenceControllerSnapshot(
        session =
            ReferenceSession(
                sessionId = "session-1",
                scenarioId = "guided-first-exchange",
                authorityMode = ReferenceAuthorityMode.LIVE,
                startedAtEpochMillis = 1L,
                configurationSnapshot = mapOf("platform" to "iOS", "surface" to "main-guided"),
            ),
        peers = emptyList(),
        timeline = emptyList(),
        activePowerModeLabel = "Automatic",
    )
}
