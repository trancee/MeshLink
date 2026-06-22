package ch.trancee.meshlink.reference.navigation

import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import ch.trancee.meshlink.reference.platform.LiveProofPlatformServices
import ch.trancee.meshlink.reference.session.ReferenceSessionController

internal class SessionAwarePlatformServices(
    private val delegate: LiveProofPlatformServices,
    private val sessionController: ReferenceSessionController,
) : LiveProofPlatformServices by delegate {
    override val meshLinkController: ReferenceMeshLinkController
        get() = sessionController

    override fun createSupportedMeshLinkController(
        surfaceOfOrigin: String
    ): ReferenceMeshLinkController {
        return delegate.createSupportedMeshLinkController(surfaceOfOrigin)
    }
}
