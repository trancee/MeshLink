package ch.trancee.meshlink.engine.transfer

import ch.trancee.meshlink.api.SendResult
import kotlin.time.Duration
import kotlin.time.TimeSource

internal interface MeshEngineOutboundDeliveryAdapter<S> {
    fun currentTopologyVersion(): Long

    suspend fun <T> withDiscoveryPolicy(
        context: MeshEngineOutboundDeliveryAttemptContext,
        block: suspend () -> T,
    ): T

    fun beginOutboundDelivery(context: MeshEngineOutboundDeliveryAttemptContext): S

    suspend fun attemptOutboundDelivery(
        state: S,
        context: MeshEngineOutboundDeliveryAttemptContext,
    ): MeshEngineOutboundDeliveryAttemptOutcome<S>

    suspend fun onDeadlineExpired(
        state: S,
        context: MeshEngineOutboundDeliveryAttemptContext,
    ): SendResult

    suspend fun onHardRunEnded(
        state: S,
        context: MeshEngineOutboundDeliveryAttemptContext,
    ): SendResult
}

internal class MeshEngineOutboundDeliveryDriver(
    internal val deliveryRetryDeadline: Duration,
    private val deliveryRetrySupport: MeshEngineDeliveryRetrySupport,
) {
    suspend fun <S> execute(
        adapter: MeshEngineOutboundDeliveryAdapter<S>,
        initialContext: MeshEngineOutboundDeliveryAttemptContext,
    ): SendResult {
        val startedAt = TimeSource.Monotonic.markNow()
        var retryState =
            MeshEngineDeliveryRetryState(
                attempt = 0,
                topologyVersion = adapter.currentTopologyVersion(),
            )
        var deliveryState = adapter.beginOutboundDelivery(initialContext)

        while (startedAt.elapsedNow() < deliveryRetryDeadline) {
            val remainingBudget =
                (deliveryRetryDeadline - startedAt.elapsedNow()).coerceAtLeast(Duration.ZERO)
            val attemptContext = initialContext.copy(remainingBudget = remainingBudget)
            when (
                val attemptOutcome = adapter.attemptOutboundDelivery(deliveryState, attemptContext)
            ) {
                is MeshEngineOutboundDeliveryAttemptOutcome.Completed -> {
                    return attemptOutcome.result
                }
                is MeshEngineOutboundDeliveryAttemptOutcome.RetryImmediately -> {
                    deliveryState = attemptOutcome.nextState
                    retryState = retryState.copy(attempt = 0)
                }
                is MeshEngineOutboundDeliveryAttemptOutcome.AwaitRetry -> {
                    deliveryState = attemptOutcome.nextState
                    when (
                        val wakeupResult =
                            deliveryRetrySupport.awaitRetry(
                                peerId = initialContext.peerId,
                                state = retryState,
                                remainingBudget = remainingBudget,
                                hardRunToken = initialContext.hardRunToken,
                                profile = attemptOutcome.retryPolicy.profile,
                                emitDiagnostics = attemptOutcome.retryPolicy.emitDiagnostics,
                            )
                    ) {
                        is MeshEngineDeliveryRetryResult.Woke ->
                            retryState =
                                wakeupResult.state.copy(
                                    topologyVersion =
                                        maxOf(
                                            wakeupResult.state.topologyVersion,
                                            adapter.currentTopologyVersion(),
                                        )
                                )
                        MeshEngineDeliveryRetryResult.DeadlineExpired -> {
                            return adapter.onDeadlineExpired(deliveryState, attemptContext)
                        }
                        MeshEngineDeliveryRetryResult.HardRunEnded -> {
                            return adapter.onHardRunEnded(deliveryState, attemptContext)
                        }
                    }
                }
            }
        }

        return adapter.onDeadlineExpired(
            deliveryState,
            initialContext.copy(remainingBudget = Duration.ZERO),
        )
    }
}
