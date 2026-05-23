package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import kotlin.time.Duration

internal data class MeshEngineDeliveryRetryState(val attempt: Int, val topologyVersion: Long)

internal data class MeshEngineDeliveryRetryProfile(
    val scheduledStage: String,
    val retryingStage: String,
)

internal sealed class MeshEngineDeliveryRetryResult {
    internal class Woke internal constructor(internal val state: MeshEngineDeliveryRetryState) :
        MeshEngineDeliveryRetryResult()

    internal data object DeadlineExpired : MeshEngineDeliveryRetryResult()

    internal data object HardRunEnded : MeshEngineDeliveryRetryResult()
}

internal data class MeshEngineDeliveryRetryCallbacks(
    val awaitRetry: suspend (Int, Duration, Long, MeshEngineHardRunToken) -> RetryWakeup,
    val routeMetadata: (PeerId, Map<String, String>) -> Map<String, String>,
    val emitDiagnostic:
        (
            DiagnosticCode,
            DiagnosticSeverity,
            String,
            String?,
            DiagnosticReason?,
            Map<String, String>,
        ) -> Unit,
)

internal class MeshEngineDeliveryRetrySupport(
    private val callbacks: MeshEngineDeliveryRetryCallbacks
) {
    suspend fun awaitRetry(
        peerId: PeerId,
        state: MeshEngineDeliveryRetryState,
        remainingBudget: Duration,
        hardRunToken: MeshEngineHardRunToken,
        profile: MeshEngineDeliveryRetryProfile,
        emitDiagnostics: Boolean = true,
    ): MeshEngineDeliveryRetryResult {
        if (emitDiagnostics) {
            emitRetryDiagnostic(
                code = DiagnosticCode.DELIVERY_RETRY_SCHEDULED,
                stage = profile.scheduledStage,
                peerId = peerId,
                attempt = state.attempt,
            )
        }

        val wakeupResult =
            when (
                val wakeup =
                    callbacks.awaitRetry(
                        state.attempt,
                        remainingBudget,
                        state.topologyVersion,
                        hardRunToken,
                    )
            ) {
                is RetryWakeup.DeadlineExpired -> MeshEngineDeliveryRetryResult.DeadlineExpired
                is RetryWakeup.TimerElapsed ->
                    MeshEngineDeliveryRetryResult.Woke(
                        MeshEngineDeliveryRetryState(
                            attempt = state.attempt + 1,
                            topologyVersion = wakeup.topologyVersion,
                        )
                    )
                is RetryWakeup.TopologyChanged ->
                    MeshEngineDeliveryRetryResult.Woke(
                        MeshEngineDeliveryRetryState(
                            attempt = 0,
                            topologyVersion = wakeup.topologyVersion,
                        )
                    )
                RetryWakeup.HardRunEnded -> MeshEngineDeliveryRetryResult.HardRunEnded
            }

        if (emitDiagnostics && wakeupResult is MeshEngineDeliveryRetryResult.Woke) {
            emitRetryDiagnostic(
                code = DiagnosticCode.DELIVERY_RETRYING,
                stage = profile.retryingStage,
                peerId = peerId,
                attempt = wakeupResult.state.attempt,
            )
        }

        return wakeupResult
    }

    private fun emitRetryDiagnostic(
        code: DiagnosticCode,
        stage: String,
        peerId: PeerId,
        attempt: Int,
    ): Unit {
        callbacks.emitDiagnostic(
            code,
            DiagnosticSeverity.WARN,
            stage,
            peerId.value.takeLast(DIAGNOSTIC_PEER_SUFFIX_LENGTH),
            DiagnosticReason.DELIVERY_RETRY,
            callbacks.routeMetadata(peerId, mapOf("attempt" to attempt.toString())),
        )
    }
}
