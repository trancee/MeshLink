package ch.trancee.meshlink.reference

import android.content.Context
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
    private val emitAutomationLogAction: (String) -> Unit = {},
) {
    val readinessBlockers: List<String>
        get() = readinessBlockersFactory(context)

    val meshLinkController: ReferenceMeshLinkController by lazy(meshLinkControllerFactory)

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
