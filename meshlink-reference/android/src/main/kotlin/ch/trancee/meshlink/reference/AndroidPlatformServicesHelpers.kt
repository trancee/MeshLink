package ch.trancee.meshlink.reference

import android.content.Context
import android.util.Log
import ch.trancee.meshlink.api.MeshLinkBootstrap
import ch.trancee.meshlink.api.android.meshLinkBootstrap
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.meshlink.ReferenceMeshLinkController
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity
import ch.trancee.meshlink.reference.model.REFERENCE_AUTHORITY_MODE_LIVE

@Suppress("LongParameterList")
internal class AndroidPlatformServices(
    val context: Context,
    val readinessGuidance: List<String>,
    private val readinessBlockersFactory: (Context) -> List<String>,
    private val meshLinkControllerFactory: () -> ReferenceMeshLinkController,
    val platformName: String = "Android",
    val defaultAuthorityMode: String = REFERENCE_AUTHORITY_MODE_LIVE,
    val powerMitigationStatus: String? = null,
    val documentStore: Any? = null,
    private val currentTimeMillisProvider: () -> Long = { System.currentTimeMillis() },
    private val stopPowerMitigationAction: () -> Unit = {},
    private val emitAutomationLogAction: (String) -> Unit = { message ->
        Log.i("MeshLinkReferenceAutomation", message)
    },
) {
    val readinessBlockers: List<String>
        get() = readinessBlockersFactory(context)

    private val meshLinkControllerLock: Any = Any()
    @Volatile private var meshLinkControllerInstance: ReferenceMeshLinkController? = null
    @Volatile private var meshLinkControllerFactoryInProgress: Boolean = false

    private companion object {
        private const val MESH_LINK_CONTROLLER_FACTORY_WATCHDOG_DELAY_MILLIS: Long = 2_000L
    }

    val meshLinkController: ReferenceMeshLinkController
        get() {
            meshLinkControllerInstance?.let { return it }
            return synchronized(meshLinkControllerLock) {
                meshLinkControllerInstance?.let { return@synchronized it }
                Log.i(
                    "MeshLinkReferenceAutomation",
                    "REFERENCE_AUTOMATION android.meshLinkController.access begin",
                )
                meshLinkControllerFactoryInProgress = true
                Log.i(
                    "MeshLinkReferenceAutomation",
                    "REFERENCE_AUTOMATION android.meshLinkController.factoryInvoke.begin",
                )
                val watchdog =
                    Thread {
                        try {
                            Thread.sleep(MESH_LINK_CONTROLLER_FACTORY_WATCHDOG_DELAY_MILLIS)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                        if (meshLinkControllerFactoryInProgress) {
                            Log.i(
                                "MeshLinkReferenceAutomation",
                                "REFERENCE_AUTOMATION android.meshLinkController.access waiting elapsedSeconds=2.0",
                            )
                        }
                    }
                watchdog.isDaemon = true
                watchdog.start()
                val created = meshLinkControllerFactory()
                Log.i(
                    "MeshLinkReferenceAutomation",
                    "REFERENCE_AUTOMATION android.meshLinkController.factoryInvoke.end",
                )
                meshLinkControllerFactoryInProgress = false
                meshLinkControllerInstance = created
                watchdog.interrupt()
                Log.i(
                    "MeshLinkReferenceAutomation",
                    "REFERENCE_AUTOMATION android.meshLinkController.access end",
                )
                created
            }
        }

    fun stopPowerMitigation(): Unit = stopPowerMitigationAction()

    fun currentTimeMillis(): Long = currentTimeMillisProvider()

    fun emitAutomationLog(message: String): Unit = emitAutomationLogAction(message)
}

internal data class TimelineAppendContext(
    val sessionId: String,
    val currentTimeMillis: () -> Long,
    val timeline: MutableList<TimelineEntry>,
    val updateSnapshot:
        ((ReferenceControllerSnapshot) -> ReferenceControllerSnapshot) -> Unit,
)

internal data class TimelineAppendSpec(
    val family: TimelineFamily,
    val severity: TimelineSeverity,
    val title: String,
    val detail: String,
    val peerSuffix: String? = null,
    val payloadPreview: String? = null,
    val payloadSizeBytes: Int? = null,
)

internal fun appendTimeline(
    context: TimelineAppendContext,
    spec: TimelineAppendSpec,
): TimelineEntry {
    val entry =
        TimelineEntry(
            entryId = "${context.sessionId}-${context.timeline.size + 1}",
            sessionId = context.sessionId,
            occurredAtEpochMillis = context.currentTimeMillis(),
            family = spec.family,
            severity = spec.severity,
            title = spec.title,
            detail = spec.detail,
            peerSuffix = spec.peerSuffix,
            payloadPreview = spec.payloadPreview,
            payloadSizeBytes = spec.payloadSizeBytes,
        )
    context.timeline += entry
    return entry
}

@Suppress("UnusedParameter")
internal fun createMeshLinkBootstrap(context: Context): MeshLinkBootstrap {
    return meshLinkBootstrap(context)
}

@Suppress("UnusedParameter")
internal fun powerManagementBlockers(context: Context): List<String> {
    return emptyList()
}
