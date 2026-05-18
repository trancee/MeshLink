package ch.trancee.meshlink.reference.platform

import ch.trancee.meshlink.reference.meshlink.PreviewReferenceMeshLinkController
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode

/**
 * Shared platform bridge consumed by the app shell.
 */
public interface PlatformServices {
    public val platformName: String
    public val defaultAuthorityMode: ReferenceAuthorityMode
    public val meshLinkController: ReferenceMeshLinkController

    public fun currentTimeMillis(): Long
}

/**
 * Lightweight default implementation used during foundational scaffolding.
 */
public class DefaultPlatformServices(
    override val platformName: String,
    override val defaultAuthorityMode: ReferenceAuthorityMode,
    private val nowProvider: () -> Long,
) : PlatformServices {
    override val meshLinkController: ReferenceMeshLinkController by lazy {
        PreviewReferenceMeshLinkController(
            platformName = platformName,
            nowEpochMillis = nowProvider(),
        )
    }

    override fun currentTimeMillis(): Long {
        return nowProvider()
    }
}
