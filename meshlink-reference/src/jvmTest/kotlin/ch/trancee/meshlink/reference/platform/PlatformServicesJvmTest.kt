package ch.trancee.meshlink.reference.platform

import ch.trancee.meshlink.reference.meshlink.PreviewReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.session.InMemoryReferenceDocumentStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformServicesJvmTest {
    @Test
    fun createSupportedMeshLinkControllerUsesTheConfiguredFactoryWhenPresent() {
        // Arrange
        var capturedSurfaceOfOrigin: String? = null
        val services =
            DefaultPlatformServices(
                platformName = "JVM",
                defaultAuthorityMode = ReferenceAuthorityMode.LIVE,
                readinessGuidance = listOf("Keep two devices nearby"),
                options =
                    DefaultPlatformServicesOptions().apply {
                        nowProvider = { 1_000L }
                        appId = "demo.meshlink.reference.test"
                        documentStore = InMemoryReferenceDocumentStore()
                        meshLinkControllerFactory = { surfaceOfOrigin ->
                            capturedSurfaceOfOrigin = surfaceOfOrigin
                            PreviewReferenceMeshLinkController(
                                platformName = "Factory",
                                nowEpochMillis = 1_000L,
                            )
                        }
                    },
            )

        // Act
        val controller =
            services.createSupportedMeshLinkController(surfaceOfOrigin = "advanced-controls")

        // Assert
        assertEquals("advanced-controls", capturedSurfaceOfOrigin)
        assertTrue(controller.snapshot.value.session.sessionId.startsWith("preview-"))
    }

    @Test
    fun createSupportedMeshLinkControllerBuildsTheLiveControllerByDefault() {
        // Arrange
        val services =
            DefaultPlatformServices(
                platformName = "JVM",
                defaultAuthorityMode = ReferenceAuthorityMode.LIVE,
                readinessGuidance = emptyList(),
                options =
                    DefaultPlatformServicesOptions().apply {
                        nowProvider = { 2_000L }
                        appId = "demo.meshlink.reference.test"
                        documentStore = InMemoryReferenceDocumentStore()
                    },
            )

        // Act
        val controller = services.createSupportedMeshLinkController(surfaceOfOrigin = "main-guided")

        // Assert
        assertEquals("jvm-2000", controller.snapshot.value.session.sessionId)
        assertEquals("Uninitialized", controller.snapshot.value.session.meshStateLabel)
        assertEquals(
            "main-guided",
            controller.snapshot.value.session.configurationSnapshot.getValue("surface"),
        )
    }
}
