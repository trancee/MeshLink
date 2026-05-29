package ch.trancee.meshlink.reference.meshlink

import ch.trancee.meshlink.api.DeliveryPriority
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class ReferenceControllerWrappersJvmTest {
    @Test
    fun previewControllerExposesDeterministicFallbackState() = runTest {
        // Arrange
        val controller =
            PreviewReferenceMeshLinkController(platformName = "JVM", nowEpochMillis = 1_000L)

        // Act
        controller.start()
        controller.pause()
        controller.resume()
        controller.stop()
        controller.sendPayload(
            peerId = "peer-1",
            payloadText = "hello",
            priority = DeliveryPriority.NORMAL,
        )
        controller.forgetPeer(peerId = "peer-1")
        controller.close()

        // Assert
        assertEquals("preview-JVM", controller.snapshot.value.session.sessionId)
        assertEquals("Uninitialized", controller.snapshot.value.session.meshStateLabel)
        assertEquals("JV0001", controller.snapshot.value.peers.single().peerSuffix)
    }

    @Test
    fun liveControllerDelegatesLifecycleCommandsToTheWrappedRuntime() = runTest {
        // Arrange
        val controller =
            LiveReferenceMeshLinkController(
                platformName = "JVM",
                authorityMode = ch.trancee.meshlink.reference.model.ReferenceAuthorityMode.LIVE,
                appId = "demo.meshlink.reference.test",
                nowProvider = { 1_000L },
                scope = backgroundScope,
            )

        // Act
        controller.start()
        controller.pause()
        controller.resume()
        controller.stop()
        controller.close()

        // Assert
        assertEquals("jvm-1000", controller.snapshot.value.session.sessionId)
        assertEquals("Stopped", controller.snapshot.value.session.lastOutcomeSummary)
    }
}
