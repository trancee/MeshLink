package ch.trancee.meshlink.engine

import ch.trancee.meshlink.api.DeliveryPriority
import ch.trancee.meshlink.api.PeerId
import ch.trancee.meshlink.api.SendFailureReason
import ch.trancee.meshlink.api.SendResult
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.diagnostics.DiagnosticReason
import ch.trancee.meshlink.diagnostics.DiagnosticSeverity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class MeshEngineSendSupportTest {
    @Test
    fun `send rejects payloads that exceed the configured size limit`() = runBlocking {
        // Arrange
        val callbacks = RecordingSendCallbacks()
        val support = sendSupport(callbacks = callbacks)
        val peerId = PeerId("peer-abcdef")
        val payload = ByteArray(MAX_SUPPORTED_PAYLOAD_BYTES + 1) { 0x01 }

        // Act
        val result =
            support.send(peerId = peerId, payload = payload, priority = DeliveryPriority.NORMAL)

        // Assert
        val notSent = assertIs<SendResult.NotSent>(result)
        assertEquals(SendFailureReason.PAYLOAD_TOO_LARGE, notSent.reason)
        assertEquals(
            listOf(
                RecordingDiagnostic(
                    code = DiagnosticCode.SIZE_LIMIT_REJECTED,
                    severity = DiagnosticSeverity.WARN,
                    stage = "delivery.send",
                    peerSuffix = "abcdef",
                    reason = DiagnosticReason.SIZE_LIMIT,
                    metadata = mapOf("payloadBytes" to (MAX_SUPPORTED_PAYLOAD_BYTES + 1).toString()),
                )
            ),
            callbacks.diagnostics,
        )
        assertEquals(0, callbacks.inlineSendCalls)
        assertEquals(0, callbacks.largeSendCalls)
    }

    @Test
    fun `send schedules a retry when the mesh is not running`() = runBlocking {
        // Arrange
        val callbacks = RecordingSendCallbacks(isMeshRunning = false)
        val support = sendSupport(callbacks = callbacks)
        val peerId = PeerId("peer-abcdef")
        val payload = ByteArray(32) { 0x01 }

        // Act
        val result =
            support.send(peerId = peerId, payload = payload, priority = DeliveryPriority.HIGH)

        // Assert
        val notSent = assertIs<SendResult.NotSent>(result)
        assertEquals(SendFailureReason.UNREACHABLE, notSent.reason)
        assertEquals(listOf(peerId to DeliveryPriority.HIGH), callbacks.retryDiagnostics)
        assertEquals(0, callbacks.inlineSendCalls)
        assertEquals(0, callbacks.largeSendCalls)
    }

    @Test
    fun `send schedules a retry when the transport is unavailable`() = runBlocking {
        // Arrange
        val callbacks = RecordingSendCallbacks(hasTransport = false)
        val support = sendSupport(callbacks = callbacks)
        val peerId = PeerId("peer-abcdef")
        val payload = ByteArray(32) { 0x01 }

        // Act
        val result =
            support.send(peerId = peerId, payload = payload, priority = DeliveryPriority.LOW)

        // Assert
        val notSent = assertIs<SendResult.NotSent>(result)
        assertEquals(SendFailureReason.UNREACHABLE, notSent.reason)
        assertEquals(listOf(peerId to DeliveryPriority.LOW), callbacks.retryDiagnostics)
        assertEquals(0, callbacks.inlineSendCalls)
        assertEquals(0, callbacks.largeSendCalls)
    }

    @Test
    fun `send uses the inline path for small payloads`() = runBlocking {
        // Arrange
        val callbacks = RecordingSendCallbacks()
        val support = sendSupport(callbacks = callbacks)
        val peerId = PeerId("peer-abcdef")
        val payload = ByteArray(INLINE_MESSAGE_PAYLOAD_BYTES) { 0x01 }

        // Act
        val result =
            support.send(peerId = peerId, payload = payload, priority = DeliveryPriority.NORMAL)

        // Assert
        assertEquals(SendResult.Sent, result)
        assertEquals(1, callbacks.inlineSendCalls)
        assertEquals(0, callbacks.largeSendCalls)
    }

    @Test
    fun `send uses the inline path when the peer flow prefers a large inline send`() = runBlocking {
        // Arrange
        val callbacks = RecordingSendCallbacks(shouldAttemptLargeInlineSend = true)
        val support = sendSupport(callbacks = callbacks)
        val peerId = PeerId("peer-abcdef")
        val payload = ByteArray(INLINE_MESSAGE_PAYLOAD_BYTES + 1) { 0x01 }

        // Act
        val result =
            support.send(peerId = peerId, payload = payload, priority = DeliveryPriority.NORMAL)

        // Assert
        assertEquals(SendResult.Sent, result)
        assertEquals(1, callbacks.inlineSendCalls)
        assertEquals(0, callbacks.largeSendCalls)
    }

    @Test
    fun `send uses the large-transfer path when the payload exceeds the inline budget`() =
        runBlocking {
            // Arrange
            val callbacks = RecordingSendCallbacks()
            val support = sendSupport(callbacks = callbacks)
            val peerId = PeerId("peer-abcdef")
            val payload = ByteArray(INLINE_MESSAGE_PAYLOAD_BYTES + 1) { 0x01 }

            // Act
            val result =
                support.send(peerId = peerId, payload = payload, priority = DeliveryPriority.NORMAL)

            // Assert
            assertEquals(SendResult.Sent, result)
            assertEquals(0, callbacks.inlineSendCalls)
            assertEquals(1, callbacks.largeSendCalls)
        }
}

private fun sendSupport(callbacks: RecordingSendCallbacks): MeshEngineSendSupport {
    return MeshEngineSendSupport(
        config =
            MeshEngineSendConfig(
                maxSupportedPayloadBytes = MAX_SUPPORTED_PAYLOAD_BYTES,
                inlineMessagePayloadBytes = INLINE_MESSAGE_PAYLOAD_BYTES,
            ),
        callbacks = callbacks.asCallbacks(),
    )
}

private data class RecordingDiagnostic(
    val code: DiagnosticCode,
    val severity: DiagnosticSeverity,
    val stage: String,
    val peerSuffix: String?,
    val reason: DiagnosticReason?,
    val metadata: Map<String, String>,
)

private class RecordingSendCallbacks(
    private val isMeshRunning: Boolean = true,
    private val hasTransport: Boolean = true,
    private val shouldAttemptLargeInlineSend: Boolean = false,
) {
    var inlineSendCalls: Int = 0
    var largeSendCalls: Int = 0
    val retryDiagnostics: MutableList<Pair<PeerId, DeliveryPriority>> = mutableListOf()
    val diagnostics: MutableList<RecordingDiagnostic> = mutableListOf()

    fun asCallbacks(): MeshEngineSendCallbacks {
        return MeshEngineSendCallbacks(
            isMeshRunning = { isMeshRunning },
            hasTransport = { hasTransport },
            shouldAttemptLargeInlineSend = { shouldAttemptLargeInlineSend },
            sendInlinePayload = { _, _, _ ->
                inlineSendCalls += 1
                SendResult.Sent
            },
            sendLargePayload = { _, _, _ ->
                largeSendCalls += 1
                SendResult.Sent
            },
            scheduleRetryDiagnostic = { peerId, priority ->
                retryDiagnostics += peerId to priority
            },
            emitDiagnostic = { code, severity, stage, peerSuffix, reason, metadata ->
                diagnostics +=
                    RecordingDiagnostic(
                        code = code,
                        severity = severity,
                        stage = stage,
                        peerSuffix = peerSuffix,
                        reason = reason,
                        metadata = metadata,
                    )
            },
        )
    }
}

private const val MAX_SUPPORTED_PAYLOAD_BYTES: Int = 64 * 1024
private const val INLINE_MESSAGE_PAYLOAD_BYTES: Int = 1_024
