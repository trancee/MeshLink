package ch.trancee.meshlink.reference.platform

import ch.trancee.meshlink.api.MeshLinkBootstrap
import ch.trancee.meshlink.reference.meshlink.LiveReferenceMeshLinkController
import ch.trancee.meshlink.reference.meshlink.PreviewReferenceMeshLinkController
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController

/** Shared platform bridge consumed by the app shell. */
public interface PlatformServices {
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

/** Mutable options bag used by the shared default platform-services bridge. */
public class DefaultPlatformServicesOptions {
    public var nowProvider: () -> Long = { 0L }
    public var appId: String = DEFAULT_REFERENCE_APP_ID
    public var meshLinkBootstrap: MeshLinkBootstrap? = null
    public var documentStore: Any? = null
    public var readinessBlockers: List<String> = emptyList()
    public var powerMitigationStatus: String? = null
    public var automationTargetPeerId: String? = null
    public var automationLogger: (String) -> Unit = {}
    public var meshLinkControllerFactory: ((String) -> ReferenceMeshLinkController)? = null
    public var stopPowerMitigation: () -> Unit = {}
}

/** Lightweight default implementation used by the reference app entry points. */
public class DefaultPlatformServices(
    override val platformName: String,
    override val defaultAuthorityMode: String,
    override val readinessGuidance: List<String>,
    options: DefaultPlatformServicesOptions = DefaultPlatformServicesOptions(),
) : PlatformServices {
    private val nowProvider: () -> Long = options.nowProvider
    private val appId: String = options.appId
    private val meshLinkBootstrap: MeshLinkBootstrap? = options.meshLinkBootstrap
    override val documentStore: Any? = options.documentStore
    override val readinessBlockers: List<String> = options.readinessBlockers
    override val powerMitigationStatus: String? = options.powerMitigationStatus
    private val automationTargetPeerId: String? = options.automationTargetPeerId
    private val automationLogger: (String) -> Unit = options.automationLogger
    private val stopPowerMitigationAction: () -> Unit = options.stopPowerMitigation
    private val meshLinkControllerFactory: ((String) -> ReferenceMeshLinkController)? =
        options.meshLinkControllerFactory

    override val meshLinkController: ReferenceMeshLinkController by lazy {
        createSupportedMeshLinkController()
    }

    override fun createSupportedMeshLinkController(
        surfaceOfOrigin: String
    ): ReferenceMeshLinkController {
        return meshLinkControllerFactory?.invoke(surfaceOfOrigin)
            ?: runCatching {
                    LiveReferenceMeshLinkController(
                        platformName = platformName,
                        authorityMode = defaultAuthorityMode,
                        appId = appId,
                        nowProvider = nowProvider,
                        surfaceOfOrigin = surfaceOfOrigin,
                        meshLinkBootstrap = meshLinkBootstrap,
                        automationTargetPeerId = automationTargetPeerId,
                        runtimeLogger = automationLogger,
                    )
                }
                .onSuccess {
                    automationLogger(
                        "REFERENCE_AUTOMATION live.controller.created surface=$surfaceOfOrigin platform=$platformName appId=$appId"
                    )
                }
                .getOrElse { error ->
                    automationLogger(
                        "REFERENCE_AUTOMATION live.controller.fallback surface=$surfaceOfOrigin platform=$platformName appId=$appId reason=${error::class.simpleName}: ${error.message.orEmpty()}"
                    )
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

    override fun stopPowerMitigation(): Unit {
        stopPowerMitigationAction()
    }
}

private const val DEFAULT_REFERENCE_APP_ID: String = "demo.meshlink.reference"
