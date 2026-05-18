package ch.trancee.meshlink.reference.platform

import ch.trancee.meshlink.reference.meshlink.LiveReferenceMeshLinkController
import ch.trancee.meshlink.reference.meshlink.PreviewReferenceMeshLinkController
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode

/**
 * Shared platform bridge consumed by the app shell.
 */
public interface PlatformServices {
    public val platformName: String
    public val defaultAuthorityMode: ReferenceAuthorityMode
    public val readinessGuidance: List<String>
    public val meshLinkController: ReferenceMeshLinkController

    public fun currentTimeMillis(): Long
}

/**
 * Lightweight default implementation used by the reference app entry points.
 */
public class DefaultPlatformServices(
    override val platformName: String,
    override val defaultAuthorityMode: ReferenceAuthorityMode,
    override val readinessGuidance: List<String>,
    private val nowProvider: () -> Long,
    private val appId: String = "demo.meshlink.reference",
    private val platformContext: Any? = null,
) : PlatformServices {
    override val meshLinkController: ReferenceMeshLinkController by lazy {
        runCatching {
            LiveReferenceMeshLinkController(
                platformName = platformName,
                authorityMode = defaultAuthorityMode,
                appId = appId,
                nowProvider = nowProvider,
                platformContext = platformContext,
            )
        }.getOrElse {
            PreviewReferenceMeshLinkController(
                platformName = platformName,
                nowEpochMillis = nowProvider(),
            )
        }
    }

    override fun currentTimeMillis(): Long {
        return nowProvider()
    }
}
