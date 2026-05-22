package ch.trancee.meshlink.reference.platform

import ch.trancee.meshlink.reference.automation.ReferenceAutomationConfig
import ch.trancee.meshlink.reference.meshlink.LiveReferenceMeshLinkController
import ch.trancee.meshlink.reference.meshlink.PreviewReferenceMeshLinkController
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.ReferenceAuthorityMode
import ch.trancee.meshlink.reference.session.InMemoryReferenceDocumentStore
import ch.trancee.meshlink.reference.session.ReferenceDocumentStore

/** Shared platform bridge consumed by the app shell. */
public interface PlatformServices {
    public val platformName: String
    public val defaultAuthorityMode: ReferenceAuthorityMode
    public val readinessGuidance: List<String>
    public val readinessBlockers: List<String>
    public val automationConfig: ReferenceAutomationConfig?
    public val documentStore: ReferenceDocumentStore
    public val meshLinkController: ReferenceMeshLinkController

    public fun currentTimeMillis(): Long

    public fun emitAutomationLog(message: String): Unit
}

/** Mutable options bag used by the shared default platform-services bridge. */
public class DefaultPlatformServicesOptions {
    public var nowProvider: () -> Long = { 0L }
    public var appId: String = DEFAULT_REFERENCE_APP_ID
    public var platformContext: Any? = null
    public var documentStore: ReferenceDocumentStore = InMemoryReferenceDocumentStore()
    public var readinessBlockers: List<String> = emptyList()
    public var automationConfig: ReferenceAutomationConfig? = null
    public var automationLogger: (String) -> Unit = {}
    public var meshLinkControllerOverride: ReferenceMeshLinkController? = null
}

/** Lightweight default implementation used by the reference app entry points. */
public class DefaultPlatformServices(
    override val platformName: String,
    override val defaultAuthorityMode: ReferenceAuthorityMode,
    override val readinessGuidance: List<String>,
    options: DefaultPlatformServicesOptions = DefaultPlatformServicesOptions(),
) : PlatformServices {
    private val nowProvider: () -> Long = options.nowProvider
    private val appId: String = options.appId
    private val platformContext: Any? = options.platformContext
    override val documentStore: ReferenceDocumentStore = options.documentStore
    override val readinessBlockers: List<String> = options.readinessBlockers
    override val automationConfig: ReferenceAutomationConfig? = options.automationConfig
    private val automationLogger: (String) -> Unit = options.automationLogger
    private val meshLinkControllerOverride: ReferenceMeshLinkController? =
        options.meshLinkControllerOverride

    override val meshLinkController: ReferenceMeshLinkController by lazy {
        meshLinkControllerOverride
            ?: runCatching {
                    LiveReferenceMeshLinkController(
                        platformName = platformName,
                        authorityMode = defaultAuthorityMode,
                        appId = appId,
                        nowProvider = nowProvider,
                        platformContext = platformContext,
                    )
                }
                .getOrElse {
                    PreviewReferenceMeshLinkController(
                        platformName = platformName,
                        nowEpochMillis = nowProvider(),
                    )
                }
    }

    override fun currentTimeMillis(): Long {
        return nowProvider()
    }

    override fun emitAutomationLog(message: String): Unit {
        automationLogger(message)
    }
}

private const val DEFAULT_REFERENCE_APP_ID: String = "demo.meshlink.reference"
