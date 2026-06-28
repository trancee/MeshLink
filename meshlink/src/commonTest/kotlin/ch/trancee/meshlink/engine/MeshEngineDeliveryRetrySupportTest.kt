package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking

class MeshEngineDeliveryRetrySupportTest {
    @Test
    fun `awaitRetry emits scheduled and retrying diagnostics when the retry timer elapses`() =
        runBlocking<Unit> {
            // Arrange
            val callbacks = RecordingDeliveryRetryCallbacks { _, _, _, _ ->
                RetryWakeup.TimerElapsed(9)
            }
            val support = retrySupport(callbacks)
            val peerId = PeerId("peer-abcdef")
            val state = MeshEngineDeliveryRetryState(attempt = 2, topologyVersion = 7)
            val hardRunToken = MeshEngineHardRunToken(epoch = 5)
            val remainingBudget = 750.milliseconds

            // Act
            val result =
                support.awaitRetry(
                    peerId = peerId,
                    state = state,
                    remainingBudget = remainingBudget,
                    hardRunToken = hardRunToken,
                    profile = DELIVERY_RETRY_PROFILE,
                )

            // Assert
            val woke = assertIs<MeshEngineDeliveryRetryResult.Woke>(result)
            assertEquals(MeshEngineDeliveryRetryState(attempt = 3, topologyVersion = 9), woke.state)
            assertEquals(
                listOf(
                    AwaitRetryCall(
                        attempt = 2,
                        remainingBudget = remainingBudget,
                        topologyVersion = 7,
                        hardRunEpoch = 5,
                    )
                ),
                callbacks.awaitRetryCalls,
            )
            assertEquals(
                listOf(
                    RecordingRetryDiagnostic(
                        code = DiagnosticCode.DELIVERY_RETRY_SCHEDULED,
                        severity = DiagnosticSeverity.WARN,
                        stage = "delivery.retryScheduled",
                        peerSuffix = "abcdef",
                        reason = DiagnosticReason.DELIVERY_RETRY,
                        metadata = mapOf("attempt" to "2", "route" to "available"),
                    ),
                    RecordingRetryDiagnostic(
                        code = DiagnosticCode.DELIVERY_RETRYING,
                        severity = DiagnosticSeverity.WARN,
                        stage = "delivery.retrying",
                        peerSuffix = "abcdef",
                        reason = DiagnosticReason.DELIVERY_RETRY,
                        metadata = mapOf("attempt" to "3", "route" to "available"),
                    ),
                ),
                callbacks.diagnostics,
            )
        }

    @Test
    fun `awaitRetry resets the attempt after a topology change`() =
        runBlocking<Unit> {
            // Arrange
            val callbacks = RecordingDeliveryRetryCallbacks { _, _, _, _ ->
                RetryWakeup.TopologyChanged(11)
            }
            val support = retrySupport(callbacks)
            val peerId = PeerId("peer-abcdef")
            val state = MeshEngineDeliveryRetryState(attempt = 4, topologyVersion = 8)

            // Act
            val result =
                support.awaitRetry(
                    peerId = peerId,
                    state = state,
                    remainingBudget = 500.milliseconds,
                    hardRunToken = MeshEngineHardRunToken(epoch = 6),
                    profile = DELIVERY_RETRY_PROFILE,
                )

            // Assert
            val woke = assertIs<MeshEngineDeliveryRetryResult.Woke>(result)
            assertEquals(
                MeshEngineDeliveryRetryState(attempt = 0, topologyVersion = 11),
                woke.state,
            )
            assertTrue(
                callbacks.diagnostics.any { diagnostic ->
                    diagnostic.stage == "delivery.retrying" && diagnostic.metadata["attempt"] == "0"
                }
            )
        }

    @Test
    fun `awaitRetry suppresses retry diagnostics when disabled`() =
        runBlocking<Unit> {
            // Arrange
            val callbacks = RecordingDeliveryRetryCallbacks { _, _, _, _ ->
                RetryWakeup.TimerElapsed(13)
            }
            val support = retrySupport(callbacks)

            // Act
            val result =
                support.awaitRetry(
                    peerId = PeerId("peer-abcdef"),
                    state = MeshEngineDeliveryRetryState(attempt = 1, topologyVersion = 2),
                    remainingBudget = 250.milliseconds,
                    hardRunToken = MeshEngineHardRunToken(epoch = 7),
                    profile = DELIVERY_RETRY_PROFILE,
                    emitDiagnostics = false,
                )

            // Assert
            val woke = assertIs<MeshEngineDeliveryRetryResult.Woke>(result)
            assertEquals(
                MeshEngineDeliveryRetryState(attempt = 2, topologyVersion = 13),
                woke.state,
            )
            assertTrue(callbacks.diagnostics.isEmpty())
        }

    @Test
    fun `awaitRetry returns deadline expired without emitting retrying diagnostics`() =
        runBlocking<Unit> {
            // Arrange
            val callbacks = RecordingDeliveryRetryCallbacks { _, _, _, _ ->
                RetryWakeup.DeadlineExpired(3)
            }
            val support = retrySupport(callbacks)

            // Act
            val result =
                support.awaitRetry(
                    peerId = PeerId("peer-abcdef"),
                    state = MeshEngineDeliveryRetryState(attempt = 1, topologyVersion = 3),
                    remainingBudget = Duration.ZERO,
                    hardRunToken = MeshEngineHardRunToken(epoch = 8),
                    profile = DELIVERY_RETRY_PROFILE,
                )

            // Assert
            assertEquals(MeshEngineDeliveryRetryResult.DeadlineExpired, result)
            assertEquals(1, callbacks.diagnostics.size)
            assertEquals("delivery.retryScheduled", callbacks.diagnostics.single().stage)
        }

    @Test
    fun `awaitRetry returns hard run ended without emitting retrying diagnostics`() =
        runBlocking<Unit> {
            // Arrange
            val callbacks = RecordingDeliveryRetryCallbacks { _, _, _, _ ->
                RetryWakeup.HardRunEnded
            }
            val support = retrySupport(callbacks)

            // Act
            val result =
                support.awaitRetry(
                    peerId = PeerId("peer-abcdef"),
                    state = MeshEngineDeliveryRetryState(attempt = 1, topologyVersion = 3),
                    remainingBudget = 300.milliseconds,
                    hardRunToken = MeshEngineHardRunToken(epoch = 9),
                    profile = DELIVERY_RETRY_PROFILE,
                )

            // Assert
            assertEquals(MeshEngineDeliveryRetryResult.HardRunEnded, result)
            assertEquals(1, callbacks.diagnostics.size)
            assertEquals("delivery.retryScheduled", callbacks.diagnostics.single().stage)
        }
}

private fun retrySupport(
    callbacks: RecordingDeliveryRetryCallbacks
): MeshEngineDeliveryRetrySupport {
    return MeshEngineDeliveryRetrySupport(
        callbacks =
            MeshEngineDeliveryRetryCallbacks(
                awaitRetry = { attempt, remainingBudget, topologyVersion, hardRunToken ->
                    callbacks.awaitRetry(attempt, remainingBudget, topologyVersion, hardRunToken)
                },
                routeMetadata = { _, metadata -> metadata + ("route" to "available") },
                emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
                    callbacks.diagnostics +=
                        RecordingRetryDiagnostic(
                            code = code,
                            severity = severity,
                            stage = stage,
                            peerSuffix = peerSuffix,
                            reason = reason,
                            metadata = metadata,
                        )
                },
            )
    )
}

private data class AwaitRetryCall(
    val attempt: Int,
    val remainingBudget: Duration,
    val topologyVersion: Long,
    val hardRunEpoch: Long,
)

private data class RecordingRetryDiagnostic(
    val code: DiagnosticCode,
    val severity: DiagnosticSeverity,
    val stage: String,
    val peerSuffix: String?,
    val reason: DiagnosticReason?,
    val metadata: Map<String, String>,
)

private class RecordingDeliveryRetryCallbacks(
    private val onAwaitRetry: suspend (Int, Duration, Long, MeshEngineHardRunToken) -> RetryWakeup
) {
    val awaitRetryCalls: MutableList<AwaitRetryCall> = mutableListOf()
    val diagnostics: MutableList<RecordingRetryDiagnostic> = mutableListOf()

    suspend fun awaitRetry(
        attempt: Int,
        remainingBudget: Duration,
        topologyVersion: Long,
        hardRunToken: MeshEngineHardRunToken,
    ): RetryWakeup {
        awaitRetryCalls +=
            AwaitRetryCall(
                attempt = attempt,
                remainingBudget = remainingBudget,
                topologyVersion = topologyVersion,
                hardRunEpoch = hardRunToken.epoch,
            )
        return onAwaitRetry(attempt, remainingBudget, topologyVersion, hardRunToken)
    }
}

private val DELIVERY_RETRY_PROFILE =
    MeshEngineDeliveryRetryProfile(
        scheduledStage = "delivery.retryScheduled",
        retryingStage = "delivery.retrying",
    )
