package ch.trancee.meshlink.engine.assembly

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.config.MeshLinkConfig
import ch.trancee.meshlink.engine.transfer.INLINE_MESSAGE_PAYLOAD_BYTES
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal data class MeshEngineRuntimePolicies(
    val delivery: MeshEngineRuntimeDeliveryPolicy,
    val powerPolicyNowMillis: () -> Long,
)

/**
 * Delivery-oriented runtime policy module shared by assembly phases.
 *
 * The assembly code should compose support modules, not inline policy choices like TTLs, retry
 * metadata, or the inline-message threshold.
 */
internal data class MeshEngineRuntimeDeliveryPolicy(
    val retryDeadline: Duration,
    val retryBackoffBase: Duration,
    val inlineMessagePayloadBytes: Int,
) {
    fun ttlMillis(priority: DeliveryPriority): Int {
        return when (priority) {
            DeliveryPriority.HIGH -> HIGH_PRIORITY_TTL_MILLIS
            DeliveryPriority.NORMAL -> NORMAL_PRIORITY_TTL_MILLIS
            DeliveryPriority.LOW -> LOW_PRIORITY_TTL_MILLIS
        }
    }

    fun retryDiagnosticMetadata(priority: DeliveryPriority): Map<String, String> {
        return mapOf(
            "priority" to priority.name,
            "retryDeadlineMs" to retryDeadline.inWholeMilliseconds.toString(),
            "retryBackoffBaseMs" to retryBackoffBase.inWholeMilliseconds.toString(),
        )
    }
}

internal fun buildMeshEngineRuntimePolicies(
    config: MeshLinkConfig,
    powerPolicyNowMillis: () -> Long,
): MeshEngineRuntimePolicies {
    return MeshEngineRuntimePolicies(
        delivery =
            MeshEngineRuntimeDeliveryPolicy(
                retryDeadline = config.deliveryRetryDeadline,
                retryBackoffBase = DEFAULT_RETRY_BACKOFF,
                inlineMessagePayloadBytes = INLINE_MESSAGE_PAYLOAD_BYTES,
            ),
        powerPolicyNowMillis = powerPolicyNowMillis,
    )
}

private const val HIGH_PRIORITY_TTL_MILLIS: Int = 45 * 60 * 1_000
private const val NORMAL_PRIORITY_TTL_MILLIS: Int = 15 * 60 * 1_000
private const val LOW_PRIORITY_TTL_MILLIS: Int = 5 * 60 * 1_000
private val DEFAULT_RETRY_BACKOFF = 250.milliseconds
