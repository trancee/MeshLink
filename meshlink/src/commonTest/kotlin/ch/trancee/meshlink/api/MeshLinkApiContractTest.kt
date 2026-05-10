package ch.trancee.meshlink.api

import ch.trancee.meshlink.config.meshLinkConfig
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.test.MeshTestHarness
import ch.trancee.meshlink.test.RecordingDiagnosticSink
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportSendResult
import ch.trancee.meshlink.engine.MeshEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MeshLinkApiContractTest {
    @Test
    fun `deliveryRetryDeadline must be greater than zero`() {
        // Arrange / Act
        val error = assertFailsWith<MeshLinkException.InvalidConfiguration> {
            meshLinkConfig {
                appId = "demo.meshlink"
                deliveryRetryDeadline = kotlin.time.Duration.ZERO
            }
        }

        // Assert
        assertEquals(
            expected = "deliveryRetryDeadline must be greater than zero",
            actual = error.message,
        )
    }

    @Test
    fun `diagnostic catalog exposes 26 stable codes`() {
        // Arrange
        val expectedCount = 26

        // Act
        val entries = DiagnosticCode.entries

        // Assert
        assertEquals(expectedCount, entries.size)
        assertTrue(entries.contains(DiagnosticCode.TRUST_FAILURE))
        assertTrue(entries.contains(DiagnosticCode.DELIVERY_UNREACHABLE))
    }

    @Test
    fun `android and ios factories expose matching lifecycle results`() = runBlocking {
        // Arrange
        val config = meshLinkConfig { appId = "demo.meshlink" }
        val androidApi = MeshLink.createAndroid(context = Any(), config = config)
        val iosApi = MeshLink.createIos(config = config)

        // Act
        val androidResults = listOf(androidApi.start(), androidApi.pause(), androidApi.resume(), androidApi.stop())
        val iosResults = listOf(iosApi.start(), iosApi.pause(), iosApi.resume(), iosApi.stop())

        // Assert
        assertEquals(androidResults.map { it::class.simpleName }, iosResults.map { it::class.simpleName })
        assertEquals(MeshLinkState.Stopped, androidApi.state.value)
        assertEquals(MeshLinkState.Stopped, iosApi.state.value)
    }

    @Test
    fun `start wraps transport exceptions as platform failures`() {
        // Arrange
        val api = MeshEngine.create(
            config = meshLinkConfig { appId = "failing.meshlink" },
            bleTransport = object : BleTransport {
                override val events: Flow<TransportEvent> = emptyFlow()

                override suspend fun start(): Unit = error("boom")

                override suspend fun pause(): Unit = Unit

                override suspend fun resume(): Unit = Unit

                override suspend fun stop(): Unit = Unit

                override suspend fun send(frame: OutboundFrame): TransportSendResult =
                    TransportSendResult.Delivered
            },
            diagnosticSink = RecordingDiagnosticSink(),
        )

        // Act / Assert
        assertFailsWith<MeshLinkException.PlatformFailure> {
            runBlocking { api.start() }
        }
    }

    @Test
    fun `identity change emits trust failure diagnostics`() = runBlocking {
        // Arrange
        val harness = MeshTestHarness()
        val receiver = harness.createNode("peer-receiver")
        val trustedSender = harness.createNode("peer-sender")
        val replacedSender = harness.createNode("peer-sender", identityLabel = "rotated")

        receiver.api.start()
        trustedSender.api.start()
        replacedSender.api.start()

        // Act
        trustedSender.api.send(receiver.peerId, "hello".encodeToByteArray())
        replacedSender.api.send(receiver.peerId, "hello-again".encodeToByteArray())

        // Assert
        assertTrue(
            receiver.diagnosticSink.events().any { it.code == DiagnosticCode.TRUST_FAILURE },
        )
    }
}
