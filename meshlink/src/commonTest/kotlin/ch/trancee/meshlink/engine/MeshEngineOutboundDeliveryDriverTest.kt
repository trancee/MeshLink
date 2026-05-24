package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendFailureReason
import ch.trancee.meshlink.api.SendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking

class MeshEngineOutboundDeliveryDriverTest {
    @Test
    fun `execute returns the adapter deadline result when retry support expires`() = runBlocking {
        // Arrange
        val retryCalls = mutableListOf<RecordedDriverRetryCall>()
        val driver =
            MeshEngineOutboundDeliveryDriver(
                deliveryRetryDeadline = 250.milliseconds,
                deliveryRetrySupport =
                    driverRetrySupport(retryCalls) { _, _, _ -> RetryWakeup.DeadlineExpired(4L) },
            )
        val adapter =
            RecordingOutboundDeliveryAdapter(
                currentTopologyVersion = 3L,
                attemptOutcomes =
                    ArrayDeque(
                        listOf(
                            MeshEngineOutboundDeliveryAttemptOutcome.AwaitRetry(
                                nextState = "awaiting-retry",
                                retryPolicy = DRIVER_RETRY_POLICY,
                            )
                        )
                    ),
                deadlineResult = SendResult.NotSent(SendFailureReason.UNREACHABLE),
                hardRunEndedResult = SendResult.NotSent(SendFailureReason.TRANSFER_ABORTED),
            )
        val initialContext = driverAttemptContext(peerId = PeerId("peer-abcdef"), hardRunEpoch = 7L)

        // Act
        val result = driver.execute(adapter = adapter, initialContext = initialContext)

        // Assert
        val notSent = assertIs<SendResult.NotSent>(result)
        assertEquals(SendFailureReason.UNREACHABLE, notSent.reason)
        assertEquals(
            listOf(RecordedDriverRetryCall(attempt = 0, topologyVersion = 3L, hardRunEpoch = 7L)),
            retryCalls,
        )
        assertEquals(listOf("begin", "attempt:initial", "deadline:awaiting-retry"), adapter.calls)
        assertTrue(adapter.deadlineContexts.single().remainingBudget > Duration.ZERO)
        assertTrue(adapter.deadlineContexts.single().remainingBudget <= 250.milliseconds)
    }

    @Test
    fun `execute resets retry attempts after immediate progress`() = runBlocking {
        // Arrange
        val retryCalls = mutableListOf<RecordedDriverRetryCall>()
        val wakeups =
            ArrayDeque(listOf(RetryWakeup.TimerElapsed(6L), RetryWakeup.DeadlineExpired(6L)))
        val driver =
            MeshEngineOutboundDeliveryDriver(
                deliveryRetryDeadline = 250.milliseconds,
                deliveryRetrySupport =
                    driverRetrySupport(retryCalls) { _, _, _ ->
                        wakeups.removeFirstOrNull() ?: error("Expected a retry wakeup")
                    },
            )
        val adapter =
            RecordingOutboundDeliveryAdapter(
                currentTopologyVersion = 5L,
                attemptOutcomes =
                    ArrayDeque(
                        listOf(
                            MeshEngineOutboundDeliveryAttemptOutcome.AwaitRetry(
                                nextState = "waiting",
                                retryPolicy = DRIVER_RETRY_POLICY,
                            ),
                            MeshEngineOutboundDeliveryAttemptOutcome.RetryImmediately("progressed"),
                            MeshEngineOutboundDeliveryAttemptOutcome.AwaitRetry(
                                nextState = "waiting-again",
                                retryPolicy = DRIVER_RETRY_POLICY,
                            ),
                        )
                    ),
                deadlineResult = SendResult.NotSent(SendFailureReason.UNREACHABLE),
                hardRunEndedResult = SendResult.NotSent(SendFailureReason.TRANSFER_ABORTED),
            )
        val initialContext = driverAttemptContext(peerId = PeerId("peer-abcdef"), hardRunEpoch = 9L)

        // Act
        val result = driver.execute(adapter = adapter, initialContext = initialContext)

        // Assert
        val notSent = assertIs<SendResult.NotSent>(result)
        assertEquals(SendFailureReason.UNREACHABLE, notSent.reason)
        assertEquals(
            listOf(
                RecordedDriverRetryCall(attempt = 0, topologyVersion = 5L, hardRunEpoch = 9L),
                RecordedDriverRetryCall(attempt = 0, topologyVersion = 6L, hardRunEpoch = 9L),
            ),
            retryCalls,
        )
        assertEquals(
            listOf(
                "begin",
                "attempt:initial",
                "attempt:waiting",
                "attempt:progressed",
                "deadline:waiting-again",
            ),
            adapter.calls,
        )
    }

    @Test
    fun `execute returns the adapter hard run ended result when retry support ends the hard run`() =
        runBlocking {
            // Arrange
            val retryCalls = mutableListOf<RecordedDriverRetryCall>()
            val driver =
                MeshEngineOutboundDeliveryDriver(
                    deliveryRetryDeadline = 250.milliseconds,
                    deliveryRetrySupport =
                        driverRetrySupport(retryCalls) { _, _, _ -> RetryWakeup.HardRunEnded },
                )
            val adapter =
                RecordingOutboundDeliveryAdapter(
                    currentTopologyVersion = 8L,
                    attemptOutcomes =
                        ArrayDeque(
                            listOf(
                                MeshEngineOutboundDeliveryAttemptOutcome.AwaitRetry(
                                    nextState = "awaiting-retry",
                                    retryPolicy = DRIVER_RETRY_POLICY,
                                )
                            )
                        ),
                    deadlineResult = SendResult.NotSent(SendFailureReason.UNREACHABLE),
                    hardRunEndedResult = SendResult.NotSent(SendFailureReason.TRANSFER_ABORTED),
                )
            val initialContext =
                driverAttemptContext(peerId = PeerId("peer-abcdef"), hardRunEpoch = 11L)

            // Act
            val result = driver.execute(adapter = adapter, initialContext = initialContext)

            // Assert
            val notSent = assertIs<SendResult.NotSent>(result)
            assertEquals(SendFailureReason.TRANSFER_ABORTED, notSent.reason)
            assertEquals(
                listOf(
                    RecordedDriverRetryCall(attempt = 0, topologyVersion = 8L, hardRunEpoch = 11L)
                ),
                retryCalls,
            )
            assertEquals(
                listOf("begin", "attempt:initial", "hardRunEnded:awaiting-retry"),
                adapter.calls,
            )
            assertTrue(adapter.hardRunEndedContexts.single().remainingBudget <= 250.milliseconds)
        }
}

private fun driverRetrySupport(
    retryCalls: MutableList<RecordedDriverRetryCall>,
    wakeup: suspend (Int, Long, MeshEngineHardRunToken) -> RetryWakeup,
): MeshEngineDeliveryRetrySupport {
    return MeshEngineDeliveryRetrySupport(
        callbacks =
            MeshEngineDeliveryRetryCallbacks(
                awaitRetry = { attempt, _, topologyVersion, hardRunToken ->
                    retryCalls +=
                        RecordedDriverRetryCall(
                            attempt = attempt,
                            topologyVersion = topologyVersion,
                            hardRunEpoch = hardRunToken.epoch,
                        )
                    wakeup(attempt, topologyVersion, hardRunToken)
                },
                routeMetadata = { _, metadata -> metadata },
                emitDiagnostic = { _, _, _, _, _, _ -> },
            )
    )
}

private fun driverAttemptContext(
    peerId: PeerId,
    hardRunEpoch: Long,
): MeshEngineOutboundDeliveryAttemptContext {
    return MeshEngineOutboundDeliveryAttemptContext(
        peerId = peerId,
        payload = byteArrayOf(0x01),
        priority = DeliveryPriority.NORMAL,
        hardRunToken = MeshEngineHardRunToken(epoch = hardRunEpoch),
        remainingBudget = 250.milliseconds,
    )
}

private data class RecordedDriverRetryCall(
    val attempt: Int,
    val topologyVersion: Long,
    val hardRunEpoch: Long,
)

private class RecordingOutboundDeliveryAdapter(
    private val currentTopologyVersion: Long,
    private val attemptOutcomes: ArrayDeque<MeshEngineOutboundDeliveryAttemptOutcome<String>>,
    private val deadlineResult: SendResult,
    private val hardRunEndedResult: SendResult,
) : MeshEngineOutboundDeliveryAdapter<String> {
    val calls: MutableList<String> = mutableListOf()
    val deadlineContexts: MutableList<MeshEngineOutboundDeliveryAttemptContext> = mutableListOf()
    val hardRunEndedContexts: MutableList<MeshEngineOutboundDeliveryAttemptContext> =
        mutableListOf()

    override fun currentTopologyVersion(): Long {
        return currentTopologyVersion
    }

    override suspend fun <T> withDiscoveryPolicy(
        context: MeshEngineOutboundDeliveryAttemptContext,
        block: suspend () -> T,
    ): T {
        return block()
    }

    override fun beginOutboundDelivery(context: MeshEngineOutboundDeliveryAttemptContext): String {
        calls += "begin"
        return "initial"
    }

    override suspend fun attemptOutboundDelivery(
        state: String,
        context: MeshEngineOutboundDeliveryAttemptContext,
    ): MeshEngineOutboundDeliveryAttemptOutcome<String> {
        calls += "attempt:$state"
        return attemptOutcomes.removeFirstOrNull() ?: error("Expected another attempt outcome")
    }

    override suspend fun onDeadlineExpired(
        state: String,
        context: MeshEngineOutboundDeliveryAttemptContext,
    ): SendResult {
        calls += "deadline:$state"
        deadlineContexts += context
        return deadlineResult
    }

    override suspend fun onHardRunEnded(
        state: String,
        context: MeshEngineOutboundDeliveryAttemptContext,
    ): SendResult {
        calls += "hardRunEnded:$state"
        hardRunEndedContexts += context
        return hardRunEndedResult
    }
}

private val DRIVER_RETRY_POLICY =
    MeshEngineOutboundDeliveryRetryPolicy(
        profile =
            MeshEngineDeliveryRetryProfile(
                scheduledStage = "delivery.retryScheduled",
                retryingStage = "delivery.retrying",
            )
    )
