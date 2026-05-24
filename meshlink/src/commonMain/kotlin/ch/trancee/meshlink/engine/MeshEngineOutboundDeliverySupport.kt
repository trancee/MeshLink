package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendResult
import kotlin.time.Duration
import kotlin.time.TimeSource

internal enum class MeshEngineOutboundDeliveryMode {
    INLINE,
    LARGE_TRANSFER,
}

internal data class MeshEngineOutboundDeliveryAttemptContext(
    val peerId: PeerId,
    val payload: ByteArray,
    val priority: DeliveryPriority,
    val hardRunToken: MeshEngineHardRunToken,
    val remainingBudget: Duration,
)

internal data class MeshEngineOutboundDeliveryRetryPolicy(
    val profile: MeshEngineDeliveryRetryProfile,
    val emitDiagnostics: Boolean = true,
)

internal sealed class MeshEngineOutboundDeliveryAttemptOutcome<out S> {
    internal class Completed internal constructor(internal val result: SendResult) :
        MeshEngineOutboundDeliveryAttemptOutcome<Nothing>()

    internal class RetryImmediately<S> internal constructor(internal val nextState: S) :
        MeshEngineOutboundDeliveryAttemptOutcome<S>()

    internal class AwaitRetry<S>
    internal constructor(
        internal val nextState: S,
        internal val retryPolicy: MeshEngineOutboundDeliveryRetryPolicy,
    ) : MeshEngineOutboundDeliveryAttemptOutcome<S>()
}

internal data class MeshEngineOutboundDeliveryConfig(val deliveryRetryDeadline: Duration)

internal data class MeshEngineOutboundDeliveryDependencies(
    val deliveryRetrySupport: MeshEngineDeliveryRetrySupport
)

internal class MeshEngineOutboundDeliverySupport(
    private val config: MeshEngineOutboundDeliveryConfig,
    private val dependencies: MeshEngineOutboundDeliveryDependencies,
    private val inlineOutboundDeliveryAdapter: MeshEngineInlineOutboundDeliveryAdapter,
    private val largeTransferOutboundDeliveryAdapter: MeshEngineLargeTransferOutboundDeliveryAdapter,
) {
    suspend fun sendPayload(
        mode: MeshEngineOutboundDeliveryMode,
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
        hardRunToken: MeshEngineHardRunToken,
    ): SendResult {
        return when (mode) {
            MeshEngineOutboundDeliveryMode.INLINE ->
                sendInlinePayload(
                    peerId = peerId,
                    payload = payload,
                    priority = priority,
                    hardRunToken = hardRunToken,
                )
            MeshEngineOutboundDeliveryMode.LARGE_TRANSFER ->
                sendLargeTransferPayload(
                    peerId = peerId,
                    payload = payload,
                    priority = priority,
                    hardRunToken = hardRunToken,
                )
        }
    }

    private suspend fun sendInlinePayload(
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
        hardRunToken: MeshEngineHardRunToken,
    ): SendResult {
        val initialContext =
            MeshEngineOutboundDeliveryAttemptContext(
                peerId = peerId,
                payload = payload,
                priority = priority,
                hardRunToken = hardRunToken,
                remainingBudget = config.deliveryRetryDeadline,
            )
        return inlineOutboundDeliveryAdapter.withDiscoveryPolicy(initialContext) {
            executeSendLoop(
                peerId = peerId,
                payload = payload,
                priority = priority,
                hardRunToken = hardRunToken,
                currentTopologyVersion = inlineOutboundDeliveryAdapter::currentTopologyVersion,
                createInitialState = inlineOutboundDeliveryAdapter::beginOutboundDelivery,
                attemptDelivery = inlineOutboundDeliveryAdapter::attemptOutboundDelivery,
                onDeadlineExpired = inlineOutboundDeliveryAdapter::onDeadlineExpired,
                onHardRunEnded = inlineOutboundDeliveryAdapter::onHardRunEnded,
            )
        }
    }

    private suspend fun sendLargeTransferPayload(
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
        hardRunToken: MeshEngineHardRunToken,
    ): SendResult {
        val initialContext =
            MeshEngineOutboundDeliveryAttemptContext(
                peerId = peerId,
                payload = payload,
                priority = priority,
                hardRunToken = hardRunToken,
                remainingBudget = config.deliveryRetryDeadline,
            )
        return largeTransferOutboundDeliveryAdapter.withDiscoveryPolicy(initialContext) {
            executeSendLoop(
                peerId = peerId,
                payload = payload,
                priority = priority,
                hardRunToken = hardRunToken,
                currentTopologyVersion =
                    largeTransferOutboundDeliveryAdapter::currentTopologyVersion,
                createInitialState = largeTransferOutboundDeliveryAdapter::beginOutboundDelivery,
                attemptDelivery = largeTransferOutboundDeliveryAdapter::attemptOutboundDelivery,
                onDeadlineExpired = largeTransferOutboundDeliveryAdapter::onDeadlineExpired,
                onHardRunEnded = largeTransferOutboundDeliveryAdapter::onHardRunEnded,
            )
        }
    }

    private suspend fun <S> executeSendLoop(
        peerId: PeerId,
        payload: ByteArray,
        priority: DeliveryPriority,
        hardRunToken: MeshEngineHardRunToken,
        currentTopologyVersion: () -> Long,
        createInitialState: (MeshEngineOutboundDeliveryAttemptContext) -> S,
        attemptDelivery:
            suspend (
                S, MeshEngineOutboundDeliveryAttemptContext,
            ) -> MeshEngineOutboundDeliveryAttemptOutcome<S>,
        onDeadlineExpired: suspend (S, MeshEngineOutboundDeliveryAttemptContext) -> SendResult,
        onHardRunEnded: suspend (S, MeshEngineOutboundDeliveryAttemptContext) -> SendResult,
    ): SendResult {
        val startedAt = TimeSource.Monotonic.markNow()
        var retryState =
            MeshEngineDeliveryRetryState(attempt = 0, topologyVersion = currentTopologyVersion())
        var deliveryState =
            createInitialState(
                MeshEngineOutboundDeliveryAttemptContext(
                    peerId = peerId,
                    payload = payload,
                    priority = priority,
                    hardRunToken = hardRunToken,
                    remainingBudget = config.deliveryRetryDeadline,
                )
            )

        while (startedAt.elapsedNow() < config.deliveryRetryDeadline) {
            val remainingBudget =
                (config.deliveryRetryDeadline - startedAt.elapsedNow()).coerceAtLeast(Duration.ZERO)
            val attemptContext =
                MeshEngineOutboundDeliveryAttemptContext(
                    peerId = peerId,
                    payload = payload,
                    priority = priority,
                    hardRunToken = hardRunToken,
                    remainingBudget = remainingBudget,
                )
            when (val attemptOutcome = attemptDelivery(deliveryState, attemptContext)) {
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
                            dependencies.deliveryRetrySupport.awaitRetry(
                                peerId = peerId,
                                state = retryState,
                                remainingBudget = remainingBudget,
                                hardRunToken = hardRunToken,
                                profile = attemptOutcome.retryPolicy.profile,
                                emitDiagnostics = attemptOutcome.retryPolicy.emitDiagnostics,
                            )
                    ) {
                        is MeshEngineDeliveryRetryResult.Woke -> retryState = wakeupResult.state
                        MeshEngineDeliveryRetryResult.DeadlineExpired -> {
                            return onDeadlineExpired(deliveryState, attemptContext)
                        }
                        MeshEngineDeliveryRetryResult.HardRunEnded -> {
                            return onHardRunEnded(deliveryState, attemptContext)
                        }
                    }
                }
            }
        }

        return onDeadlineExpired(
            deliveryState,
            MeshEngineOutboundDeliveryAttemptContext(
                peerId = peerId,
                payload = payload,
                priority = priority,
                hardRunToken = hardRunToken,
                remainingBudget = Duration.ZERO,
            ),
        )
    }
}

internal fun buildMeshEngineRuntimeOutboundDeliverySupport(
    deliveryRetryDeadline: Duration,
    deliveryRetrySupport: MeshEngineDeliveryRetrySupport,
    inlineOutboundDeliveryAdapter: MeshEngineInlineOutboundDeliveryAdapter,
    largeTransferOutboundDeliveryAdapter: MeshEngineLargeTransferOutboundDeliveryAdapter,
): MeshEngineOutboundDeliverySupport {
    return MeshEngineOutboundDeliverySupport(
        config = MeshEngineOutboundDeliveryConfig(deliveryRetryDeadline = deliveryRetryDeadline),
        dependencies =
            MeshEngineOutboundDeliveryDependencies(deliveryRetrySupport = deliveryRetrySupport),
        inlineOutboundDeliveryAdapter = inlineOutboundDeliveryAdapter,
        largeTransferOutboundDeliveryAdapter = largeTransferOutboundDeliveryAdapter,
    )
}
