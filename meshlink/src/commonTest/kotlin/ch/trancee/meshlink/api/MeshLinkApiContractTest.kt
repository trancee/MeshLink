package ch.trancee.meshlink.api

import ch.trancee.meshlink.config.meshLinkConfig
import ch.trancee.meshlink.diagnostics.DiagnosticCode
import ch.trancee.meshlink.engine.MeshEngine
import ch.trancee.meshlink.platform.AndroidFactoryTestContext
import ch.trancee.meshlink.test.MeshTestHarness
import ch.trancee.meshlink.test.RecordingDiagnosticSink
import ch.trancee.meshlink.transport.BleTransport
import ch.trancee.meshlink.transport.OutboundFrame
import ch.trancee.meshlink.transport.TransportEvent
import ch.trancee.meshlink.transport.TransportSendResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class MeshLinkApiContractTest {
    private fun installIosFactoryTestBridge(): Unit {
        var counter = 1

        fun nextBytes(size: Int): ByteArray {
            return ByteArray(size) { index -> ((counter + index) and 0xFF).toByte() }
                .also { counter += 1 }
        }

        IosCryptoBridge.install(
            randomBytes = { size -> nextBytes(size) },
            sha256 = { input ->
                ByteArray(32) { index -> input.getOrElse(index) { index.toByte() } }
            },
            hmacSha256 = { _, data ->
                ByteArray(32) { index -> data.getOrElse(index) { index.toByte() } }
            },
            generateX25519KeyPair = {
                IosCryptoRawKeyPair(privateKey = nextBytes(32), publicKey = nextBytes(32))
            },
            generateEd25519KeyPair = {
                IosCryptoRawKeyPair(privateKey = nextBytes(32), publicKey = nextBytes(32))
            },
            x25519 = { _, publicKey -> publicKey.copyOf() },
            ed25519Sign = { _, message ->
                ByteArray(64) { index -> message.getOrElse(index % maxOf(1, message.size)) { 0 } }
            },
            ed25519Verify = { _, _, _ -> true },
            chacha20Poly1305Seal = { _, _, _, plaintext -> plaintext.copyOf() },
            chacha20Poly1305Open = { _, _, _, ciphertext -> ciphertext.copyOf() },
        )
    }

    @Test
    fun `deliveryRetryDeadline must be greater than zero`() {
        // Arrange / Act
        val error =
            assertFailsWith<MeshLinkException.InvalidConfiguration> {
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
        installIosFactoryTestBridge()
        val config = meshLinkConfig { appId = "demo.meshlink.${kotlin.random.Random.nextInt()}" }
        val androidApi =
            MeshLink.createAndroid(context = AndroidFactoryTestContext, config = config)
        val iosApi = MeshLink.createIos(config = config)

        // Act
        val androidResults =
            listOf(androidApi.start(), androidApi.pause(), androidApi.resume(), androidApi.stop())
        val iosResults = listOf(iosApi.start(), iosApi.pause(), iosApi.resume(), iosApi.stop())

        // Assert
        assertEquals(
            androidResults.map { it::class.simpleName },
            iosResults.map { it::class.simpleName },
        )
        assertEquals(MeshLinkState.Stopped, androidApi.state.value)
        assertEquals(MeshLinkState.Stopped, iosApi.state.value)
    }

    @Test
    fun `start wraps transport exceptions as platform failures`() {
        // Arrange
        val api =
            MeshEngine.create(
                config = meshLinkConfig { appId = "failing.meshlink" },
                bleTransport =
                    object : BleTransport {
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
        assertFailsWith<MeshLinkException.PlatformFailure> { runBlocking { api.start() } }
    }

    @Test
    fun `identity change emits trust failure diagnostics`() = runBlocking {
        // Arrange
        val harness = MeshTestHarness()
        val receiver = harness.createNode("peer-receiver")
        val trustedSender = harness.createNode("peer-sender")

        receiver.api.start()
        trustedSender.api.start()
        trustedSender.api.send(receiver.peerId, "hello".encodeToByteArray())

        val replacedSender = harness.createNode("peer-sender", identityLabel = "rotated")
        replacedSender.api.start()
        val trustFailure = async {
            withTimeout(1_000) {
                receiver.api.diagnosticEvents.first { it.code == DiagnosticCode.TRUST_FAILURE }
            }
        }

        // Act
        replacedSender.api.send(receiver.peerId, "hello-again".encodeToByteArray())

        // Assert
        assertEquals(DiagnosticCode.TRUST_FAILURE, trustFailure.await().code)
        assertTrue(receiver.diagnosticSink.events().any { it.code == DiagnosticCode.TRUST_FAILURE })
    }
}
