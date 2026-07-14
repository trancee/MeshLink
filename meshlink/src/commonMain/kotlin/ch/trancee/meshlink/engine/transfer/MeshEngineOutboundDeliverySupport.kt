package ch.trancee.meshlink.engine.transfer

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.engine.assembly.MeshEngineHardRunToken
import kotlin.time.Duration

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

internal class MeshEngineOutboundDeliverySupport(
    private val outboundDeliveryDriver: MeshEngineOutboundDeliveryDriver,
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
        val initialContext =
            MeshEngineOutboundDeliveryAttemptContext(
                peerId = peerId,
                payload = payload,
                priority = priority,
                hardRunToken = hardRunToken,
                remainingBudget = outboundDeliveryDriver.deliveryRetryDeadline,
            )
        return when (mode) {
            MeshEngineOutboundDeliveryMode.INLINE ->
                sendWithAdapter(inlineOutboundDeliveryAdapter, initialContext)
            MeshEngineOutboundDeliveryMode.LARGE_TRANSFER ->
                sendWithAdapter(largeTransferOutboundDeliveryAdapter, initialContext)
        }
    }

    private suspend fun <S> sendWithAdapter(
        adapter: MeshEngineOutboundDeliveryAdapter<S>,
        initialContext: MeshEngineOutboundDeliveryAttemptContext,
    ): SendResult {
        return adapter.withDiscoveryPolicy(initialContext) {
            outboundDeliveryDriver.execute(adapter = adapter, initialContext = initialContext)
        }
    }
}

internal fun buildMeshEngineRuntimeOutboundDeliverySupport(
    deliveryRetryDeadline: Duration,
    deliveryRetrySupport: MeshEngineDeliveryRetrySupport,
    inlineOutboundDeliveryAdapter: MeshEngineInlineOutboundDeliveryAdapter,
    largeTransferOutboundDeliveryAdapter: MeshEngineLargeTransferOutboundDeliveryAdapter,
): MeshEngineOutboundDeliverySupport {
    return MeshEngineOutboundDeliverySupport(
        outboundDeliveryDriver =
            MeshEngineOutboundDeliveryDriver(
                deliveryRetryDeadline = deliveryRetryDeadline,
                deliveryRetrySupport = deliveryRetrySupport,
            ),
        inlineOutboundDeliveryAdapter = inlineOutboundDeliveryAdapter,
        largeTransferOutboundDeliveryAdapter = largeTransferOutboundDeliveryAdapter,
    )
}
