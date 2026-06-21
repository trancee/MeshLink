package ch.trancee.meshlink.reference

import android.content.Context
import android.os.Build
import android.os.PowerManager
import ch.trancee.meshlink.reference.meshlink.ReferenceControllerSnapshot
import ch.trancee.meshlink.reference.model.TimelineEntry
import ch.trancee.meshlink.reference.model.TimelineFamily
import ch.trancee.meshlink.reference.model.TimelineSeverity

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
): Unit {
    context.timeline +=
        TimelineEntry(
            entryId = "t${context.timeline.size + 1}",
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
    context.updateSnapshot { current -> current.copy(timeline = context.timeline.toList()) }
}

internal fun powerManagementBlockers(context: Context): List<String> =
    when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M -> emptyList()
        else -> {
            val powerManager =
                context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            when {
                powerManager == null -> emptyList()
                else -> {
                    val packageName = context.packageName
                    val ignoringOptimizations =
                        powerManager.isIgnoringBatteryOptimizations(packageName)
                    val idleMode =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            powerManager.isDeviceIdleMode
                        } else {
                            false
                        }
                    val interactive = powerManager.isInteractive
                    when {
                        !idleMode && (interactive || ignoringOptimizations) -> emptyList()
                        else ->
                            listOf(
                                "Keep the screen awake or disable battery optimization before direct proof; " +
                                    "some devices need this to avoid deep doze discovery stalls.",
                            )
                    }
                }
            }
        }
    }
