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
import kotlinx.coroutines.test.runTest

class ReferenceSessionControllerTest {
    @Test
    fun startSoloSessionUsesSoloExplorationAsTheSurfaceOfOrigin() = runTest {
        // Arrange
        val factory = RecordingControllerFactory()
        val controller =
            ReferenceSessionController(
                platformName = "iOS",
                nowProvider = { 42L },
                supportedControllerFactory = factory::create,
            )

        // Act
        val actual = controller.startSoloSession()

        // Assert
        assertEquals(ReferenceAuthorityMode.SOLO, actual.session.authorityMode)
        assertEquals("solo-exploration", actual.session.configurationSnapshot.getValue("surface"))
        assertEquals(
            "solo-exploration",
            controller.snapshot.value.session.configurationSnapshot.getValue("surface"),
        )
        assertEquals(1, factory.createdControllers.single().closeCalls)
    }

    @Test
    fun startNewSupportedSessionUsesTheRequestedSupportedSurfaceOfOrigin() = runTest {
        // Arrange
        val factory = RecordingControllerFactory()
        val controller =
            ReferenceSessionController(
                platformName = "iOS",
                nowProvider = { 42L },
                supportedControllerFactory = factory::create,
            )

        // Act
        val actual = controller.startNewSupportedSession(surfaceOfOrigin = "advanced-controls")

        // Assert
        assertEquals("advanced-controls", actual.session.configurationSnapshot.getValue("surface"))
        assertEquals(
            "advanced-controls",
            controller.snapshot.value.session.configurationSnapshot.getValue("surface"),
        )
        assertEquals(2, factory.createdControllers.size)
        assertEquals(1, factory.createdControllers.first().closeCalls)
    }

    @Test
    fun endSupportedSessionClosesTheActiveSupportedController() = runTest {
        // Arrange
        val factory = RecordingControllerFactory()
        val controller =
            ReferenceSessionController(
                platformName = "iOS",
                nowProvider = { 42L },
                supportedControllerFactory = factory::create,
            )

        // Act
        val actual = controller.endSupportedSession()

        // Assert
        assertEquals(42L, actual.session.endedAtEpochMillis)
        assertEquals(42L, controller.snapshot.value.session.endedAtEpochMillis)
        assertEquals(1, factory.createdControllers.single().closeCalls)
    }

    @Test
    fun lifecycleCallsStopForwardingAfterTheControllerLeavesSupportedLiveState() = runTest {
        // Arrange
        val factory = RecordingControllerFactory()
        val controller =
            ReferenceSessionController(
                platformName = "iOS",
                nowProvider = { 42L },
                supportedControllerFactory = factory::create,
            )
        controller.startSoloSession()

        // Act
        controller.start()

        // Assert
        assertEquals(0, factory.createdControllers.single().startCalls)
    }

    @Test
    fun endingThenRestartingSupportedSessionDoesNotCloseTheEndedControllerTwice() = runTest {
        // Arrange
        val factory = RecordingControllerFactory()
        val controller =
            ReferenceSessionController(
                platformName = "iOS",
                nowProvider = { 42L },
                supportedControllerFactory = factory::create,
            )

        // Act
        controller.endSupportedSession()
        controller.startNewSupportedSession(surfaceOfOrigin = "advanced-controls")

        // Assert
        assertEquals(2, factory.createdControllers.size)
        assertEquals(1, factory.createdControllers.first().closeCalls)
    }

    @Test
    fun closingAfterLeavingSupportedLiveStateDoesNotCloseTheControllerTwice() = runTest {
        // Arrange
        val factory = RecordingControllerFactory()
        val controller =
            ReferenceSessionController(
                platformName = "iOS",
                nowProvider = { 42L },
                supportedControllerFactory = factory::create,
            )
        controller.startSoloSession()

        // Act
        controller.close()

        // Assert
        assertEquals(1, factory.createdControllers.single().closeCalls)
    }
}

private class RecordingControllerFactory {
    val createdControllers: MutableList<RecordingReferenceMeshLinkController> = mutableListOf()

    fun create(surfaceOfOrigin: String): ReferenceMeshLinkController {
        return RecordingReferenceMeshLinkController(referenceSnapshot(surfaceOfOrigin)).also {
            createdControllers += it
        }
    }
}

private class RecordingReferenceMeshLinkController(initialSnapshot: ReferenceControllerSnapshot) :
    ReferenceMeshLinkController {
    private val snapshotFlow = MutableStateFlow(initialSnapshot)
    var startCalls: Int = 0
    var closeCalls: Int = 0

    override val snapshot: StateFlow<ReferenceControllerSnapshot> = snapshotFlow

    override suspend fun start(): Unit {
        startCalls += 1
    }

    override suspend fun pause(): Unit = Unit

    override suspend fun resume(): Unit = Unit

    override suspend fun stop(): Unit = Unit

    override suspend fun sendSamplePayload(
        peerId: String,
        payloadText: String,
        priority: DeliveryPriority,
    ): Unit = Unit

    override suspend fun forgetPeer(peerId: String): Unit = Unit

    override suspend fun close(): Unit {
        closeCalls += 1
    }
}

private fun referenceSnapshot(
    surfaceOfOrigin: String = "main-guided"
): ReferenceControllerSnapshot {
    return ReferenceControllerSnapshot(
        session =
            ReferenceSession(
                sessionId = "session-1",
                scenarioId = "guided-first-exchange",
                authorityMode = ReferenceAuthorityMode.LIVE,
                startedAtEpochMillis = 1L,
                configurationSnapshot = mapOf("platform" to "iOS", "surface" to surfaceOfOrigin),
            ),
        peers = emptyList(),
        timeline = emptyList(),
        activePowerModeLabel = "Automatic",
    )
}
