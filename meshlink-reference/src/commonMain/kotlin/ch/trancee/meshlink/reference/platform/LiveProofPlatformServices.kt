package ch.trancee.meshlink.reference.platform

import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController

/** Shared platform bridge consumed by the app shell. */
public interface LiveProofPlatformServices {
    public val platformName: String
    public val defaultAuthorityMode: String
    public val readinessGuidance: List<String>
    public val readinessBlockers: List<String>
    public val powerMitigationStatus: String?
    public val documentStore: Any?
    public val meshLinkController: ReferenceMeshLinkController

    public fun stopPowerMitigation(): Unit

    public fun createSupportedMeshLinkController(
        surfaceOfOrigin: String = "main-guided"
    ): ReferenceMeshLinkController = meshLinkController

    public fun currentTimeMillis(): Long

    public fun emitAutomationLog(message: String): Unit
}
